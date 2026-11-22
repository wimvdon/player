package to.wim.player.logic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Service { SPOTIFY, LAST_FM, RECCOBEATS }

/** One API call, for the debug screen. [status] is the HTTP status, or 0 when no answer came. */
data class ApiCall(
    val atMillis: Long,
    val service: Service,
    val endpoint: String,
    val status: Int,
    val reason: String? = null,
)

/** The most recent API calls (newest first) and a count per service since the app started. */
class CallLog(private val capacity: Int = 300) {
    private val _calls = MutableStateFlow<List<ApiCall>>(emptyList())
    val calls: StateFlow<List<ApiCall>> = _calls.asStateFlow()

    private val _counts = MutableStateFlow<Map<Service, Int>>(emptyMap())
    val counts: StateFlow<Map<Service, Int>> = _counts.asStateFlow()

    fun record(call: ApiCall) {
        _calls.update { (listOf(call) + it).take(capacity) }
        _counts.update { it + (call.service to (it[call.service] ?: 0) + 1) }
    }
}

/**
 * The rolling queue's arithmetic: which of the planned tracks is playing and how many more to
 * add so [ahead] tracks stay queued after it.
 */
object RollingQueue {
    const val AHEAD = 15
    const val BATCH_SIZE = 25

    /**
     * The index in [queuedUris] of the track now playing, searching from [fromIndex] so a song
     * planned twice is found in order. -1 when the playing track isn't one of ours.
     */
    fun playingIndex(queuedUris: List<String>, playingUri: String?, fromIndex: Int): Int {
        if (playingUri == null) return -1
        val start = fromIndex.coerceAtLeast(0)
        for (i in start until queuedUris.size) if (queuedUris[i] == playingUri) return i
        return -1
    }

    /** How many tracks to queue now. [playedIndex] is the last of ours that started (-1: none yet). */
    fun toQueue(queuedCount: Int, playedIndex: Int, remaining: Int, ahead: Int = AHEAD): Int {
        val queuedAhead = queuedCount - (playedIndex + 1)
        return (ahead - queuedAhead).coerceIn(0, remaining.coerceAtLeast(0))
    }
}
