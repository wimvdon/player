package to.wim.player.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import to.wim.player.data.cache.JsonFileCache
import to.wim.player.data.cache.MemoryCache
import to.wim.player.data.lastfm.CachedLastFm
import to.wim.player.data.lastfm.LastFmCaches
import to.wim.player.data.model.LfmArtist
import to.wim.player.data.model.LfmTrack
import to.wim.player.logic.WeightedTag
import java.io.File

class JsonFileCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `saves and reads back after flush`() {
        val file = File(folder.root, "c.json")
        var now = 0L
        val cache = JsonFileCache(file, ListSerializer(String.serializer()), ttlMillis = null, now = { now })
        cache.put("a", listOf("x", "y"))
        cache.flush()
        val again = JsonFileCache(file, ListSerializer(String.serializer()), ttlMillis = null, now = { now })
        assertEquals(listOf("x", "y"), again.get("a"))
        assertEquals(1, again.size)
    }

    @Test
    fun `entries expire after the ttl`() {
        val file = File(folder.root, "t.json")
        var now = 0L
        val cache = JsonFileCache(file, String.serializer(), ttlMillis = 1_000, now = { now })
        cache.put("a", "x")
        now = 999
        assertEquals("x", cache.get("a"))
        now = 1_000
        assertNull(cache.get("a"))
    }

    @Test
    fun `a broken file starts empty`() {
        val file = File(folder.root, "b.json").apply { writeText("{not json") }
        val cache = JsonFileCache(file, String.serializer(), ttlMillis = null)
        assertNull(cache.get("a"))
        cache.put("a", "x")
        cache.flush()
        assertEquals("x", JsonFileCache(file, String.serializer(), ttlMillis = null).get("a"))
    }

    @Test
    fun `clear empties the cache and the file`() {
        val file = File(folder.root, "c.json")
        val cache = JsonFileCache(file, String.serializer(), ttlMillis = null)
        cache.put("a", "x")
        cache.flush()
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(false, file.exists())
    }
}

class CachedLastFmTest {
    private class CountingLastFm : FakeLastFm() {
        var calls = 0

        override suspend fun artistTopTracks(artist: String, limit: Int): List<LfmTrack> {
            calls++
            return listOf(LfmTrack("Song", artist, 10))
        }
    }

    @Test
    fun `answers are cached by normalized name`() =
        runBlocking {
            val inner = CountingLastFm()
            val cached =
                CachedLastFm(
                    inner,
                    LastFmCaches(MemoryCache<List<LfmArtist>>(), MemoryCache<List<LfmTrack>>(), MemoryCache<List<WeightedTag>>(), MemoryCache(), MemoryCache()),
                )
            cached.artistTopTracks("The Bonobo", 15)
            cached.artistTopTracks("bonobo", 15)
            assertEquals(1, inner.calls)
            cached.artistTopTracks("bonobo", 20)
            assertEquals(2, inner.calls)
        }
}

/** A Last.fm with nothing in it; tests override what they need. */
open class FakeLastFm : LastFmSource {
    override suspend fun similarArtists(artist: String, limit: Int): List<LfmArtist> = emptyList()

    override suspend fun tagTopArtists(tag: String, limit: Int): List<LfmArtist> = emptyList()

    override suspend fun tagTopTracks(tag: String, limit: Int): List<LfmTrack> = emptyList()

    override suspend fun topTags(): List<String> = emptyList()

    override suspend fun artistTopTracks(artist: String, limit: Int): List<LfmTrack> = emptyList()

    override suspend fun artistTopTags(artist: String): List<WeightedTag> = emptyList()

    override suspend fun searchArtists(query: String): List<LfmArtist> = emptyList()

    override suspend fun userTopTrackKeys(username: String): Set<String> = emptySet()

    override suspend fun userTopArtists(username: String, limit: Int): List<LfmArtist> = emptyList()
}
