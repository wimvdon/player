package to.wim.player.logic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Service { SPOTIFY, LAST_FM, RECCOBEATS, MUSICBRAINZ, LISTENBRAINZ }

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

/** How the playlist is filled while you listen. */
object PlaylistPlan {
    /** Tracks in the playlist when it starts. */
    const val FIRST_BATCH = 15

    /** Tracks added each time the rolling fill tops up. */
    const val ROLLING_BATCH = 10

    /** Spotify takes at most 100 tracks per call. */
    const val MAX_PER_CALL = 100

    /** Rolling mode keeps about this much unheard music in the playlist. */
    const val AHEAD_MILLIS = 40L * 60 * 1000

    /** Used for a track whose length Spotify didn't give. */
    const val TYPICAL_TRACK_MILLIS = 3L * 60 * 1000 + 30 * 1000

    /**
     * True when more tracks should go in: [aheadMillis] is the playlist's total length minus the
     * time since it started, so what's left to hear if the user has been listening all along.
     */
    fun needsMore(aheadMillis: Long): Boolean = aheadMillis < AHEAD_MILLIS
}
