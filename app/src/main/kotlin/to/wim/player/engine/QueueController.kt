package to.wim.player.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.wim.player.data.AudioFeatureSource
import to.wim.player.data.PlaylistTarget
import to.wim.player.data.spotify.SpotifyHttpException
import to.wim.player.data.spotify.SpotifyPausedException
import to.wim.player.data.spotify.SpotifySignedOutException
import to.wim.player.logic.Candidate
import to.wim.player.logic.PlaylistPlan
import kotlin.coroutines.cancellation.CancellationException

enum class QueueStatus {
    IDLE,

    /** Looking up each track on Spotify. No playlist exists yet. */
    MATCHING,

    /** Making the playlist and putting every matched track in it. */
    SAVING,
    FINISHED,
    STOPPED,
    FAILED,
}

/** Why making the playlist stopped early. */
sealed interface QueueProblem {
    data class SpotifyPaused(val untilMillis: Long) : QueueProblem

    data object SignedOut : QueueProblem

    /** Spotify refused a playlist change. */
    data object Rejected : QueueProblem

    data object Network : QueueProblem

    /** None of the tracks is on Spotify, so no playlist was made. */
    data object NothingFound : QueueProblem
}

data class QueueState(
    val status: QueueStatus = QueueStatus.IDLE,
    /** The playlist's name in Spotify. */
    val name: String = "",
    val total: Int = 0,
    /** Tracks looked up on Spotify so far (found or not). */
    val processed: Int = 0,
    /** Tracks not found on Spotify, so left out. */
    val skipped: Int = 0,
    /** Tracks in the playlist so far. */
    val queued: Int = 0,
    /** The new playlist, once it exists. */
    val playlistId: String? = null,
    /** True when Spotify started playing it; false when the user has to press play in Spotify. */
    val playbackStarted: Boolean? = null,
    val problem: QueueProblem? = null,
) {
    val active: Boolean get() = status == QueueStatus.MATCHING || status == QueueStatus.SAVING

    /** Tracks found on Spotify, so the size of the finished playlist. */
    val found: Int get() = processed - skipped
}

/**
 * Makes a new private Spotify playlist for each set. Every track is first looked up on Spotify;
 * only then is the playlist made and filled with all of them, in calls of
 * [PlaylistPlan.MAX_PER_CALL] (Spotify's limit), so it appears in Spotify complete. Then it's started.
 */
class QueueController(
    private val target: PlaylistTarget,
    private val resolver: TrackResolver,
    private val features: AudioFeatureSource?,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private var job: Job? = null
    private var tracks: List<Candidate> = emptyList()

    /** The first track not looked up yet. */
    private var next = 0

    /** Spotify URIs of the tracks found, in playlist order. */
    private val uris = ArrayList<String>()
    private var description = ""

    /** Starts a new set in a new playlist called [name]. */
    fun start(list: List<Candidate>, name: String, description: String) {
        job?.cancel()
        tracks = list
        next = 0
        uris.clear()
        this.description = description
        _state.value = QueueState(status = QueueStatus.MATCHING, name = name, total = list.size)
        launchRun()
    }

    /** Carries on after a problem, from where it stopped. */
    fun resume() {
        val current = _state.value
        if (current.active || current.status == QueueStatus.IDLE || current.status == QueueStatus.FINISHED) return
        if (current.problem == QueueProblem.NothingFound) return
        _state.update { it.copy(status = if (next < tracks.size) QueueStatus.MATCHING else QueueStatus.SAVING, problem = null) }
        launchRun()
    }

    /** Stops. A playlist that was already made keeps what was added. */
    fun stop() {
        job?.cancel()
        job = null
        if (_state.value.active) _state.update { it.copy(status = QueueStatus.STOPPED) }
    }

    private fun launchRun() {
        job =
            scope.launch {
                try {
                    match()
                    if (uris.isEmpty()) {
                        _state.update { it.copy(status = QueueStatus.FAILED, problem = QueueProblem.NothingFound) }
                        return@launch
                    }
                    _state.update { it.copy(status = QueueStatus.SAVING) }
                    save()
                    _state.update { it.copy(status = QueueStatus.FINISHED) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(status = QueueStatus.FAILED, problem = problemOf(e)) }
                }
            }
    }

    /** Looks up every remaining track on Spotify. Tracks not on Spotify are skipped. */
    private suspend fun match() {
        while (next < tracks.size) {
            val track = tracks[next]
            val uri = resolver.resolve(track.artist, track.title).uri
            if (uri != null) uris += uri
            next++
            _state.update { it.copy(processed = next, skipped = if (uri == null) it.skipped + 1 else it.skipped) }
        }
    }

    /** Makes the playlist (once), adds what isn't in it yet, then starts it. */
    private suspend fun save() {
        var id = _state.value.playlistId
        if (id == null) {
            id = target.createPlaylist(_state.value.name, description)
            _state.update { it.copy(playlistId = id) }
        }
        while (_state.value.queued < uris.size) {
            val from = _state.value.queued
            val chunk = uris.subList(from, minOf(from + PlaylistPlan.MAX_PER_CALL, uris.size)).toList()
            target.addItems(id, chunk)
            _state.update { it.copy(queued = from + chunk.size) }
        }
        if (_state.value.playbackStarted == null) {
            val playing = target.play(id)
            _state.update { it.copy(playbackStarted = playing) }
        }
        fetchFeatures()
    }

    /** Looks up tempo and energy for the new tracks in the background (optional). */
    private fun fetchFeatures() {
        val source = features ?: return
        val ids = uris.map { it.substringAfterLast(':') }
        scope.launch { runCatching { source.fetch(ids) } }
    }

    private fun problemOf(e: Exception): QueueProblem =
        when (e) {
            is SpotifyPausedException -> QueueProblem.SpotifyPaused(e.untilMillis)
            is SpotifySignedOutException -> QueueProblem.SignedOut
            is SpotifyHttpException -> QueueProblem.Rejected
            else -> QueueProblem.Network
        }
}
