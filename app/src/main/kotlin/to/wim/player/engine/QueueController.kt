package to.wim.player.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import to.wim.player.data.AudioFeatureSource
import to.wim.player.data.PlayerException
import to.wim.player.data.PlayerProblem
import to.wim.player.data.PlayerSnapshot
import to.wim.player.data.SpotifyPlayer
import to.wim.player.data.model.QueueMode
import to.wim.player.data.spotify.SpotifyPausedException
import to.wim.player.data.spotify.SpotifySignedOutException
import to.wim.player.logic.Backoff
import to.wim.player.logic.Candidate
import to.wim.player.logic.RollingQueue
import kotlin.coroutines.cancellation.CancellationException

enum class QueueStatus { IDLE, CONNECTING, RUNNING, FINISHED, STOPPED, FAILED }

/** Why the queue stopped early. */
sealed interface QueueProblem {
    data class Player(val problem: PlayerProblem) : QueueProblem

    data class SpotifyPaused(val untilMillis: Long) : QueueProblem

    data object SignedOut : QueueProblem

    data object Network : QueueProblem
}

data class QueueState(
    val status: QueueStatus = QueueStatus.IDLE,
    val mode: QueueMode = QueueMode.ROLLING,
    val total: Int = 0,
    /** Tracks handed to Spotify so far. */
    val queued: Int = 0,
    /** Tracks looked at (queued or skipped). */
    val processed: Int = 0,
    /** Tracks not found on Spotify, so left out. */
    val skipped: Int = 0,
    /** Index (in the queued list) of the last of our tracks that started playing; -1 before. */
    val playedIndex: Int = -1,
    val nowPlaying: PlayerSnapshot? = null,
    val problem: QueueProblem? = null,
) {
    val active: Boolean get() = status == QueueStatus.CONNECTING || status == QueueStatus.RUNNING

    /** Queued tracks still waiting to play. */
    val ahead: Int get() = (queued - (playedIndex + 1)).coerceAtLeast(0)
}

/**
 * Hands the generated tracks to Spotify. Rolling mode keeps [RollingQueue.AHEAD] tracks queued
 * ahead of the one playing and searches Spotify only as they are needed, spreading the calls
 * over the listening session. "All now" queues everything in batches.
 */
class QueueController(
    private val player: SpotifyPlayer,
    private val resolver: TrackResolver,
    private val features: AudioFeatureSource?,
    private val scope: CoroutineScope,
    private val spacingMillis: Long = 350,
    private val batchPauseMillis: Long = 3_000,
) {
    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        // What Spotify plays, for the queue screen and the notification, in both modes.
        scope.launch { player.state.collect { now -> _state.update { it.copy(nowPlaying = now) } } }
    }

    private var tracks: List<Candidate> = emptyList()
    private val queuedUris = ArrayList<String>()
    private var next = 0

    fun start(list: List<Candidate>, mode: QueueMode) {
        job?.cancel()
        tracks = list
        queuedUris.clear()
        next = 0
        _state.value = QueueState(status = QueueStatus.CONNECTING, mode = mode, total = list.size, nowPlaying = player.state.value)
        launchRun(mode)
    }

    /** Carries on after a problem, from the first track that wasn't handled yet. */
    fun resume() {
        val current = _state.value
        if (current.active || next >= tracks.size) return
        _state.update { it.copy(status = QueueStatus.CONNECTING, problem = null) }
        launchRun(current.mode)
    }

    private fun launchRun(mode: QueueMode) {
        job =
            scope.launch {
                try {
                    connect()
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

    /** True when [resume] has tracks left to hand over. */
    val canResume: Boolean get() = !_state.value.active && next < tracks.size

    /** Stops adding tracks. What's already in Spotify's queue stays there (apps can't clear it). */
    fun stop() {
        job?.cancel()
        job = null
        if (_state.value.active) _state.update { it.copy(status = QueueStatus.STOPPED) }
        player.disconnect()
    }

    private suspend fun connect() {
        var attempt = 0
        while (true) {
            try {
                player.connect()
                // The first player state arrives just after connecting; wait for it briefly.
                withTimeoutOrNull(STATE_WAIT_MILLIS) { player.state.first { it != null } }
                return
            } catch (e: PlayerException) {
                val retryable = e.problem == PlayerProblem.DISCONNECTED || e.problem == PlayerProblem.OFFLINE
                if (!retryable || attempt >= MAX_CONNECT_ATTEMPTS) throw e
                delay(Backoff.delayMillis(attempt++, baseMillis = 2_000))
            }
        }
    }

    private suspend fun rolling() {
        // First fill: the playing track plus the ones ahead of it.
        val snapshot = player.state.value
        val idle = snapshot == null || snapshot.trackUri == null || snapshot.isPaused
        if (queuedUris.isEmpty()) addTracks(RollingQueue.AHEAD + 1, playFirst = idle)
        while (next < tracks.size) {
            val current = player.state.value
            if (current == null) {
                // App Remote dropped the connection: connect again, then carry on.
                connect()
                if (player.state.value == null) delay(STATE_WAIT_MILLIS)
                continue
            }
            val index = RollingQueue.playingIndex(queuedUris, current.trackUri, _state.value.playedIndex.coerceAtLeast(0))
            if (index > _state.value.playedIndex) _state.update { it.copy(playedIndex = index) }
            val wanted = RollingQueue.toQueue(queuedUris.size, _state.value.playedIndex, tracks.size - next)
            if (wanted > 0) {
                addTracks(wanted, playFirst = false)
            } else {
                // Wait for the next change in what Spotify plays.
                player.state.first { it != current }
            }
        }
    }

    private suspend fun allNow() {
        var first = queuedUris.isEmpty()
        while (next < tracks.size) {
            val snapshot = player.state.value
            val idle = snapshot == null || snapshot.trackUri == null || snapshot.isPaused
            addTracks(RollingQueue.BATCH_SIZE, playFirst = first && idle)
            first = false
            if (next < tracks.size) delay(batchPauseMillis)
        }
    }

    /** Resolves and queues up to [count] tracks; tracks not on Spotify are skipped. */
    private suspend fun addTracks(count: Int, playFirst: Boolean) {
        var added = 0
        var play = playFirst
        val newIds = ArrayList<String>()
        while (added < count && next < tracks.size) {
            // The index only moves on once a track is handled, so after a problem it's tried again.
            val track = tracks[next]
            val uri = resolver.resolve(track.artist, track.title).uri
            if (uri == null) {
                next++
                _state.update { it.copy(skipped = it.skipped + 1, processed = it.processed + 1) }
                continue
            }
            hand(uri, play)
            next++
            if (play) _state.update { it.copy(playedIndex = 0) }
            play = false
            queuedUris += uri
            newIds += uri.substringAfterLast(':')
            added++
            _state.update { it.copy(queued = queuedUris.size, processed = it.processed + 1) }
            if (spacingMillis > 0) delay(spacingMillis)
        }
        if (newIds.isNotEmpty()) features?.let { source -> scope.launch { runCatching { source.fetch(newIds) } } }
    }

    private suspend fun hand(uri: String, play: Boolean) {
        try {
            if (play) player.play(uri) else player.queue(uri)
        } catch (e: PlayerException) {
            if (e.problem != PlayerProblem.DISCONNECTED) throw e
            connect()
            if (play) player.play(uri) else player.queue(uri)
        }
    }

    private fun problemOf(e: Exception): QueueProblem =
        when (e) {
            is PlayerException -> QueueProblem.Player(e.problem)
            is SpotifyPausedException -> QueueProblem.SpotifyPaused(e.untilMillis)
            is SpotifySignedOutException -> QueueProblem.SignedOut
            else -> QueueProblem.Network
        }

    companion object {
        private const val MAX_CONNECT_ATTEMPTS = 5
        private const val STATE_WAIT_MILLIS = 3_000L
    }
}
