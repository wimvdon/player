package to.wim.player.logic

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

/**
 * A token bucket: at most [perSecond] calls a second on average, with short bursts up to
 * [capacity]. [now] and [sleep] are passed in so tests don't wait.
 */
class TokenBucket(
    private val perSecond: Double,
    private val capacity: Double,
    private val now: () -> Long,
    private val sleep: suspend (Long) -> Unit,
) {
    private val mutex = Mutex()
    private var tokens = capacity
    private var last = now()

    /** Waits until a call may be made, then takes one token. */
    suspend fun acquire() {
        mutex.withLock {
            refill()
            if (tokens < 1.0) {
                val waitMillis = ((1.0 - tokens) / perSecond * 1000.0).toLong().coerceAtLeast(1)
                sleep(waitMillis)
                refill()
            }
            tokens = (tokens - 1.0).coerceAtLeast(0.0)
        }
    }

    private fun refill() {
        val current = now()
        val elapsed = (current - last).coerceAtLeast(0)
        last = current
        tokens = min(capacity, tokens + elapsed / 1000.0 * perSecond)
    }
}

/** Exponential backoff: 1 s, 2 s, 4 s … up to [maxMillis]. */
object Backoff {
    fun delayMillis(attempt: Int, baseMillis: Long = 1_000, maxMillis: Long = 60_000): Long {
        val shift = attempt.coerceIn(0, 20)
        return min(maxMillis, baseMillis shl shift)
    }
}

/** What to do after Spotify answered 429 Too Many Requests. */
sealed interface SpotifyLimit {
    /** The app's daily quota is used up: no Spotify Web API calls until [untilMillis]. */
    data class QuotaExceeded(val untilMillis: Long) : SpotifyLimit

    /** Short rate limit: wait [waitMillis], then try again. */
    data class RetryAfter(val waitMillis: Long) : SpotifyLimit
}

object SpotifyLimits {
    /** How long all Spotify Web API calls pause after QUOTA_EXCEEDED. Spotify doesn't say. */
    const val QUOTA_PAUSE_MILLIS = 6L * 60 * 60 * 1000

    /** The longest Retry-After the app waits for inside one call; longer limits fail the call. */
    const val MAX_INLINE_WAIT_MILLIS = 30_000L

    /** Reads a 429 response: its body (for "reason": "QUOTA_EXCEEDED") and Retry-After header (seconds). */
    fun classify(body: String?, retryAfter: String?, nowMillis: Long): SpotifyLimit {
        if (body != null && body.contains("QUOTA_EXCEEDED")) return SpotifyLimit.QuotaExceeded(nowMillis + QUOTA_PAUSE_MILLIS)
        val seconds = retryAfter?.trim()?.toLongOrNull()?.coerceAtLeast(1) ?: 5
        return SpotifyLimit.RetryAfter(seconds * 1000)
    }
}
