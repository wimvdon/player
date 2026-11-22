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

class RollingQueueTest {
    @Test
    fun `queues enough to keep fifteen ahead`() {
        // Nothing played yet, 16 queued (one to play plus 15 ahead): nothing more needed.
        assertEquals(0, RollingQueue.toQueue(queuedCount = 16, playedIndex = 0, remaining = 100))
        // The second track started: one more.
        assertEquals(1, RollingQueue.toQueue(queuedCount = 16, playedIndex = 1, remaining = 100))
        // Fewer left than needed.
        assertEquals(2, RollingQueue.toQueue(queuedCount = 16, playedIndex = 10, remaining = 2))
        assertEquals(0, RollingQueue.toQueue(queuedCount = 16, playedIndex = 10, remaining = 0))
    }

    @Test
    fun `finds the playing track from where it was`() {
        val uris = listOf("a", "b", "a", "c")
        assertEquals(0, RollingQueue.playingIndex(uris, "a", 0))
        assertEquals(2, RollingQueue.playingIndex(uris, "a", 1))
        assertEquals(-1, RollingQueue.playingIndex(uris, "x", 0))
        assertEquals(-1, RollingQueue.playingIndex(uris, null, 0))
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
