package to.wim.player.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.wim.player.data.AudioFeatureSource
import to.wim.player.data.PlaylistMemory
import to.wim.player.data.PlaylistTarget
import to.wim.player.data.model.QueueMode
import to.wim.player.data.spotify.SpotifyHttpException
import to.wim.player.data.spotify.SpotifyPausedException
import to.wim.player.data.spotify.SpotifySignedOutException
import to.wim.player.logic.Candidate
import to.wim.player.logic.PlaylistPlan
import kotlin.coroutines.cancellation.CancellationException

enum class QueueStatus { IDLE, CONNECTING, RUNNING, FINISHED, STOPPED, FAILED }

/** Why filling the playlist stopped early. */
sealed interface QueueProblem {
    data class SpotifyPaused(val untilMillis: Long) : QueueProblem

    data object SignedOut : QueueProblem

    /** Spotify refused a playlist change. */
    data object Rejected : QueueProblem

    data object Network : QueueProblem
}

data class QueueState(
    val status: QueueStatus = QueueStatus.IDLE,
    val mode: QueueMode = QueueMode.ROLLING,
    val total: Int = 0,
    /** Tracks added to the playlist so far. */
    val queued: Int = 0,
    /** Tracks looked at (added or skipped). */
    val processed: Int = 0,
    /** Tracks not found on Spotify, so left out. */
    val skipped: Int = 0,
    /** The playlist being filled, once it exists. */
    val playlistId: String? = null,
    /** True when Spotify started playing it; false when the user has to press play in Spotify. */
    val playbackStarted: Boolean? = null,
    /** Estimated music in the playlist not listened to yet (rolling mode), in minutes. */
    val minutesAhead: Int = 0,
    val problem: QueueProblem? = null,
) {
    val active: Boolean get() = status == QueueStatus.CONNECTING || status == QueueStatus.RUNNING
}

/**
 * Puts the generated tracks in one private Spotify playlist ("Player", reused for every set).
 * Rolling mode adds tracks a few at a time, staying about [PlaylistPlan.AHEAD_MILLIS] ahead of
 * the time since the playlist started, so Spotify searches are spread over the listening session.
 * "All now" adds everything at once. Each track is matched on Spotify just before it's added.
 */
class QueueController(
    private val target: PlaylistTarget,
    private val memory: PlaylistMemory,
    private val resolver: TrackResolver,
    private val features: AudioFeatureSource?,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val checkEveryMillis: Long = 60_000,
) {
    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private var job: Job? = null
    private var tracks: List<Candidate> = emptyList()

    /** The first track not in the playlist yet. Moves on only once Spotify took a batch. */
    private var next = 0
    private var startedAt = 0L
    private var addedMillis = 0L
    private var playlistName = ""
    private var playlistDescription = ""

    /** Starts a new set: the playlist is emptied and filled with [list]. */
    fun start(list: List<Candidate>, mode: QueueMode, name: String, description: String) {
        job?.cancel()
        tracks = list
        next = 0
        addedMillis = 0
        startedAt = now()
        playlistName = name
        playlistDescription = description
        _state.value = QueueState(status = QueueStatus.CONNECTING, mode = mode, total = list.size)
        launchRun(mode)
    }

    /** Carries on after a problem, from the first track that wasn't added yet. */
    fun resume() {
        val current = _state.value
        if (current.active || next >= tracks.size) return
        _state.update { it.copy(status = if (it.playlistId == null) QueueStatus.CONNECTING else QueueStatus.RUNNING, problem = null) }
        launchRun(current.mode)
    }

    /** Stops adding tracks. The playlist keeps what was added. */
    fun stop() {
        job?.cancel()
        job = null
        if (_state.value.active) _state.update { it.copy(status = QueueStatus.STOPPED) }
    }

    private fun launchRun(mode: QueueMode) {
        job =
            scope.launch {
                try {
                    if (_state.value.playlistId == null) startPlaylist()
                    _state.update { it.copy(status = QueueStatus.RUNNING) }
                    when (mode) {
                        QueueMode.ROLLING -> rolling()
                        QueueMode.ALL_NOW -> allNow()
                    }
                    _state.update { it.copy(status = QueueStatus.FINISHED) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(status = QueueStatus.FAILED, problem = problemOf(e)) }
                }
            }
    }

    /** Empties the reused playlist (or makes one) with the first tracks, then tries to play it. */
    private suspend fun startPlaylist() {
        val first = resolveBatch(PlaylistPlan.FIRST_BATCH)
        var id = memory.playlistId()
        if (id.isNotEmpty()) {
            try {
                target.replaceItems(id, first.uris)
            } catch (e: SpotifyHttpException) {
                // The playlist is gone or no longer editable: make a new one.
                id = ""
            }
        }
        if (id.isEmpty()) {
            id = target.createPlaylist(playlistName, playlistDescription)
            memory.savePlaylistId(id)
            if (first.uris.isNotEmpty()) target.addItems(id, first.uris)
        }
        commit(first)
        startedAt = now()
        val playing = target.play(id)
        _state.update { it.copy(playlistId = id, playbackStarted = playing) }
    }

    private suspend fun rolling() {
        while (next < tracks.size) {
            val ahead = addedMillis - (now() - startedAt)
            _state.update { it.copy(minutesAhead = (ahead / 60_000).toInt().coerceAtLeast(0)) }
            if (PlaylistPlan.needsMore(ahead)) add(PlaylistPlan.ROLLING_BATCH) else delay(checkEveryMillis)
        }
    }

    private suspend fun allNow() {
        while (next < tracks.size) add(PlaylistPlan.MAX_PER_CALL)
    }

    private suspend fun add(count: Int) {
        val id = _state.value.playlistId ?: return
        val batch = resolveBatch(count)
        if (batch.uris.isNotEmpty()) target.addItems(id, batch.uris)
        commit(batch)
    }

    /** Tracks matched on Spotify, ready to go into the playlist. */
    private class Batch(
        val uris: List<String>,
        val added: List<Candidate>,
        val skipped: Int,
        val millis: Long,
        val nextIndex: Int,
    )

    /** Matches up to [count] tracks on Spotify, from [next] on. Tracks not on Spotify are skipped. */
    private suspend fun resolveBatch(count: Int): Batch {
        val uris = ArrayList<String>()
        val added = ArrayList<Candidate>()
        var index = next
        var skipped = 0
        var millis = 0L
        while (uris.size < count && index < tracks.size) {
            val track = tracks[index]
            val resolution = resolver.resolve(track.artist, track.title)
            val uri = resolution.uri
            if (uri == null) {
                skipped++
            } else {
                uris += uri
                added += track
                millis += resolution.durationMs.takeIf { it > 0 } ?: PlaylistPlan.TYPICAL_TRACK_MILLIS
            }
            index++
        }
        return Batch(uris, added, skipped, millis, index)
    }

    /** Counts a batch as done once Spotify took it. */
    private fun commit(batch: Batch) {
        next = batch.nextIndex
        addedMillis += batch.millis
        _state.update {
            it.copy(
                queued = it.queued + batch.uris.size,
                skipped = it.skipped + batch.skipped,
                processed = it.processed + batch.uris.size + batch.skipped,
            )
        }
        val source = features ?: return
        val ids = batch.uris.map { it.substringAfterLast(':') }
        if (ids.isNotEmpty()) scope.launch { runCatching { source.fetch(ids) } }
    }

    private fun problemOf(e: Exception): QueueProblem =
        when (e) {
            is SpotifyPausedException -> QueueProblem.SpotifyPaused(e.untilMillis)
            is SpotifySignedOutException -> QueueProblem.SignedOut
            is SpotifyHttpException -> QueueProblem.Rejected
            else -> QueueProblem.Network
        }
}
