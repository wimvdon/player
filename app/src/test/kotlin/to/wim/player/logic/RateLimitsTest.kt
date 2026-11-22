package to.wim.player.logic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitsTest {
    @Test
    fun `token bucket allows a burst, then spaces calls`() =
        runBlocking {
            var clock = 0L
            val sleeps = mutableListOf<Long>()
            val bucket = TokenBucket(perSecond = 4.0, capacity = 4.0, now = { clock }, sleep = { sleeps += it; clock += it })
            repeat(4) { bucket.acquire() }
            assertTrue(sleeps.isEmpty())
            bucket.acquire()
            assertEquals(listOf(250L), sleeps)
            // 20 calls take about five seconds in total: four a second.
            repeat(15) { bucket.acquire() }
            assertEquals(4000L, sleeps.sum())
        }

    @Test
    fun `token bucket refills while idle`() =
        runBlocking {
            var clock = 0L
            val sleeps = mutableListOf<Long>()
            val bucket = TokenBucket(4.0, 4.0, { clock }, { sleeps += it; clock += it })
            repeat(4) { bucket.acquire() }
            clock += 10_000
            repeat(4) { bucket.acquire() }
            assertTrue(sleeps.isEmpty())
        }

    @Test
    fun `backoff doubles up to the maximum`() {
        assertEquals(1_000, Backoff.delayMillis(0))
        assertEquals(2_000, Backoff.delayMillis(1))
        assertEquals(8_000, Backoff.delayMillis(3))
        assertEquals(60_000, Backoff.delayMillis(10))
        assertEquals(60_000, Backoff.delayMillis(100))
    }

    @Test
    fun `quota exceeded pauses for hours`() {
        val body = """{"error":{"status":429,"message":"Too many requests","reason":"QUOTA_EXCEEDED"}}"""
        val limit = SpotifyLimits.classify(body, "30", nowMillis = 1_000)
        assertEquals(SpotifyLimit.QuotaExceeded(1_000 + SpotifyLimits.QUOTA_PAUSE_MILLIS), limit)
    }

    @Test
    fun `other 429s respect retry after`() {
        assertEquals(SpotifyLimit.RetryAfter(7_000), SpotifyLimits.classify("""{"error":{"status":429}}""", "7", 0))
        assertEquals(SpotifyLimit.RetryAfter(5_000), SpotifyLimits.classify(null, null, 0))
    }
}

class PlaylistPlanTest {
    @Test
    fun `tops up when less than forty minutes are ahead`() {
        assertTrue(PlaylistPlan.needsMore(0))
        assertTrue(PlaylistPlan.needsMore(39L * 60_000))
        assertEquals(false, PlaylistPlan.needsMore(40L * 60_000))
        assertEquals(false, PlaylistPlan.needsMore(2L * 60 * 60_000))
    }

    @Test
    fun `call log keeps the newest first and counts per service`() {
        val log = CallLog(capacity = 2)
        log.record(ApiCall(1, Service.SPOTIFY, "GET /search", 200))
        log.record(ApiCall(2, Service.LAST_FM, "tag.getTopTags", 200))
        log.record(ApiCall(3, Service.SPOTIFY, "GET /me", 429, "QUOTA_EXCEEDED"))
        assertEquals(listOf(3L, 2L), log.calls.value.map { it.atMillis })
        assertEquals(2, log.counts.value[Service.SPOTIFY])
        assertEquals(1, log.counts.value[Service.LAST_FM])
    }
}
