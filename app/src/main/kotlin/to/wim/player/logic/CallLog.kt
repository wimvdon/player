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

/** How the playlist is filled. */
object PlaylistPlan {
    /** Spotify takes at most 100 tracks per call. */
    const val MAX_PER_CALL = 100
}
