package to.wim.player.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import to.wim.player.data.cache.JsonFileCache
import to.wim.player.data.cache.MemoryCache
import to.wim.player.data.brainz.BrainzAuthRequiredException
import to.wim.player.data.brainz.BrainzCaches
import to.wim.player.data.brainz.RadioRecording
import to.wim.player.data.brainz.BrainzListen
import to.wim.player.data.brainz.ListenHistory
import to.wim.player.data.brainz.BrainzMatch
import to.wim.player.data.brainz.BrainzMusic
import to.wim.player.data.brainz.BrainzRecording
import to.wim.player.data.brainz.BrainzTopArtist
import to.wim.player.data.brainz.MbArtist
import to.wim.player.data.brainz.Recommendation
import to.wim.player.data.brainz.SimilarArtist
import to.wim.player.data.brainz.TagRecording
import to.wim.player.data.model.ArtistHit
import to.wim.player.data.model.PoolTrack
import to.wim.player.logic.TrackText
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

/** Counts the calls BrainzMusic makes, so tests can check what is cached. */
private class CountingMusicBrainz : MusicBrainzSource {
    var searches = 0
    var tagLookups = 0
    var searchResults: List<MbArtist> = listOf(MbArtist("mb-bonobo", "Bonobo", score = 100))

    override suspend fun searchArtists(name: String, limit: Int): List<MbArtist> {
        searches++
        return searchResults
    }

    override suspend fun artistsByTag(tag: String, limit: Int): List<MbArtist> = listOf(MbArtist("mb-tycho", "Tycho", score = 100))

    override suspend fun artistTags(mbid: String): List<WeightedTag> {
        tagLookups++
        return listOf(WeightedTag("downtempo", 3))
    }

    override suspend fun artistsForSpotify(spotifyArtistId: String): List<MbArtist> = emptyList()
}

private class CountingListenBrainz : ListenBrainzSource {
    val calls = mutableListOf<String>()

    override suspend fun similarArtists(mbid: String, limit: Int): List<SimilarArtist> {
        calls += "similar:$mbid"
        return listOf(SimilarArtist("mb-emancipator", "Emancipator", 10.0))
    }

    override suspend fun topRecordings(artistMbid: String, limit: Int): List<BrainzRecording> {
        calls += "top:$artistMbid"
        return listOf(BrainzRecording("Song", "Artist $artistMbid", listeners = 10))
    }

    override suspend fun userTopArtists(username: String, limit: Int): List<BrainzTopArtist> = listOf(BrainzTopArtist("Bonobo", "mb-bonobo", 3))

    override suspend fun userTopRecordings(username: String): List<BrainzRecording> {
        calls += "user:$username"
        return listOf(BrainzRecording("Kerala", "Bonobo"), BrainzRecording("Kerala (Live)", "Bonobo"))
    }

    override suspend fun recommendations(username: String, limit: Int): List<Recommendation> = emptyList()

    override suspend fun listens(username: String, minTs: Long?, maxTs: Long?, count: Int): List<BrainzListen> =
        if (maxTs == null && minTs == null) listOf(BrainzListen("Tycho", "Awake", 100)) else emptyList()

    /** Tag radio for "shoegaze" only: two recordings by Slowdive, one by Ride. */
    override suspend fun tagRecordings(tag: String, count: Int): List<TagRecording> {
        calls += "radio:$tag"
        if (tag != "shoegaze") return emptyList()
        return listOf(TagRecording("r1", 2, 100.0), TagRecording("r2", 3, 90.0), TagRecording("r3", 4, 80.0))
    }

    override suspend fun recordingMetadata(recordingMbids: List<String>): List<BrainzRecording> {
        calls += "metadata:${recordingMbids.size}"
        val known =
            mapOf(
                "r1" to BrainzRecording("Alison", "Slowdive", "r1", listOf("mb-slowdive")),
                "r2" to BrainzRecording("When the Sun Hits", "Slowdive", "r2", listOf("mb-slowdive")),
                "r3" to BrainzRecording("Vapour Trail", "Ride", "r3", listOf("mb-ride")),
            )
        return recordingMbids.mapNotNull { known[it] }
    }
}

/** ListenBrainz as it answers since 2026: top recordings need a token, artist radio doesn't. */
private class TokenOnlyTopListenBrainz : ListenBrainzSource by CountingListenBrainz() {
    val calls = mutableListOf<String>()

    override suspend fun topRecordings(artistMbid: String, limit: Int): List<BrainzRecording> {
        calls += "top:$artistMbid"
        throw BrainzAuthRequiredException("LISTENBRAINZ")
    }

    override suspend fun artistRadioRecordings(artistMbid: String, count: Int): List<RadioRecording> {
        calls += "radio:$artistMbid"
        return listOf(RadioRecording("r-live", 900_000), RadioRecording("r-hit", 80_000), RadioRecording("r-b", 8_000), RadioRecording("r-hit2", 50))
    }

    override suspend fun recordingMetadata(recordingMbids: List<String>): List<BrainzRecording> {
        calls += "metadata:${recordingMbids.size}"
        val titles = mapOf("r-live" to "Kerala (Live)", "r-hit" to "Kerala", "r-b" to "Cirrus", "r-hit2" to "Kerala - Remastered 2017")
        return recordingMbids.mapNotNull { id -> titles[id]?.let { BrainzRecording(it, "Somebody Else", id, listOf("x")) } }
    }
}

class BrainzMusicTokenTest {
    @Test
    fun `without a token, top tracks come from the artist radio and the refused call isn't repeated`() =
        runBlocking {
            val listenBrainz = TokenOnlyTopListenBrainz()
            val music =
                BrainzMusic(
                    CountingMusicBrainz(),
                    listenBrainz,
                    BrainzCaches(MemoryCache(), MemoryCache(), MemoryCache<List<PoolTrack>>(), MemoryCache<List<WeightedTag>>(), MemoryCache()),
                )
            val tracks = music.artistTopTracks("Bonobo", 15)
            // Most listened first, live version and a second "Kerala" left out, the asked-for name kept.
            assertEquals(listOf("Kerala", "Cirrus"), tracks.map { it.title })
            assertEquals(listOf("Bonobo"), tracks.map { it.artist }.distinct())
            assertEquals(10_000L, tracks.first().listeners)
            music.artistTopTracks("Tycho", 15)
            assertEquals(1, listenBrainz.calls.count { it.startsWith("top:") })
            assertEquals(2, listenBrainz.calls.count { it.startsWith("radio:") })
        }

    @Test
    fun `after a token is added, the top-recordings call is tried again`() =
        runBlocking {
            val listenBrainz = TokenOnlyTopListenBrainz()
            val music =
                BrainzMusic(
                    CountingMusicBrainz(),
                    listenBrainz,
                    BrainzCaches(MemoryCache(), MemoryCache(), MemoryCache<List<PoolTrack>>(), MemoryCache<List<WeightedTag>>(), MemoryCache()),
                )
            music.artistTopTracks("Bonobo", 15)
            music.tokenChanged()
            music.artistTopTracks("Tycho", 15)
            assertEquals(2, listenBrainz.calls.count { it.startsWith("top:") })
        }
}

class BrainzMusicTest {
    private val musicBrainz = CountingMusicBrainz()
    private val listenBrainz = CountingListenBrainz()
    private val music =
        BrainzMusic(
            musicBrainz,
            listenBrainz,
            BrainzCaches(MemoryCache(), MemoryCache(), MemoryCache<List<PoolTrack>>(), MemoryCache<List<WeightedTag>>(), MemoryCache()),
        )

    @Test
    fun `answers are cached by normalized name and the ID is looked up once`() =
        runBlocking {
            music.artistTopTracks("The Bonobo", 15)
            music.artistTopTracks("bonobo", 15)
            assertEquals(listOf("top:mb-bonobo"), listenBrainz.calls)
            music.artistTopTracks("bonobo", 20)
            assertEquals(2, listenBrainz.calls.size)
            music.artistTags("Bonobo")
            assertEquals(1, musicBrainz.searches)
        }

    @Test
    fun `IDs from similar artists are remembered, so they need no search`() =
        runBlocking {
            assertEquals(listOf("Emancipator"), music.similarArtists("Bonobo", 30))
            music.artistTopTracks("Emancipator", 15)
            assertTrue("top:mb-emancipator" in listenBrainz.calls)
            assertEquals(1, musicBrainz.searches)
        }

    @Test
    fun `an unknown artist gives nothing and is not searched again`() =
        runBlocking {
            musicBrainz.searchResults = listOf(MbArtist("x", "Someone Else", score = 40))
            assertTrue(music.artistTopTracks("Nobody", 15).isEmpty())
            assertTrue(music.artistTags("Nobody").isEmpty())
            assertEquals(1, musicBrainz.searches)
            assertTrue(listenBrainz.calls.isEmpty())
        }

    @Test
    fun `a picked artist wins over the search`() =
        runBlocking {
            music.pin(ArtistHit("Bonobo", "mb-picked"))
            music.artistTopTracks("Bonobo", 15)
            assertEquals(listOf("top:mb-picked"), listenBrainz.calls)
            assertEquals(0, musicBrainz.searches)
        }

    @Test
    fun `user history gives track keys`() =
        runBlocking {
            assertEquals(setOf(TrackText.key("Bonobo", "Kerala"), TrackText.key("Bonobo", "Kerala (Live)")), music.userKnownTrackKeys("someone"))
            music.userKnownTrackKeys("Someone")
            assertEquals(1, listenBrainz.calls.count { it.startsWith("user:") })
        }

    @Test
    fun `the read play history adds to the top recordings`() =
        runBlocking {
            val history = ListenHistory(listenBrainz, MemoryHistoryFile())
            val withHistory =
                BrainzMusic(
                    musicBrainz,
                    listenBrainz,
                    BrainzCaches(MemoryCache(), MemoryCache(), MemoryCache<List<PoolTrack>>(), MemoryCache<List<WeightedTag>>(), MemoryCache()),
                    history,
                )
            assertEquals(2, withHistory.userKnownTrackKeys("someone").size)
            history.sync("someone")
            assertEquals(
                setOf(TrackText.key("Bonobo", "Kerala"), TrackText.key("Bonobo", "Kerala (Live)"), TrackText.key("Tycho", "Awake")),
                withHistory.userKnownTrackKeys("someone"),
            )
            assertEquals(2, withHistory.userKnownTrackKeys("someone else").size)
        }

    @Test
    fun `genre artists come from MusicBrainz tags when tag radio has none`() =
        runBlocking {
            assertEquals(listOf("Tycho"), music.genreArtists("downtempo", 50))
            music.artistTopTracks("Tycho", 15)
            assertEquals(0, musicBrainz.searches)
        }

    @Test
    fun `genre artists come from tag radio first, ranked by votes, topped up from MusicBrainz`() =
        runBlocking {
            // Ride's recording has 4 votes, Slowdive's best 3.
            assertEquals(listOf("Ride", "Slowdive", "Tycho"), music.genreArtists("Shoegaze", 50))
            assertEquals(listOf("Ride"), music.genreArtists("shoegaze", 1))
            music.artistTopTracks("Ride", 15)
            assertTrue("top:mb-ride" in listenBrainz.calls)
            assertEquals(0, musicBrainz.searches)
            // Cached: the second "Shoegaze" lookup with the same limit asks nothing.
            val before = listenBrainz.calls.size
            music.genreArtists("shoegaze", 50)
            assertEquals(before, listenBrainz.calls.size)
        }

    @Test
    fun `every top track carries the asked-for artist name`() =
        runBlocking {
            val tracks = music.artistTopTracks("Bonobo", 15)
            assertEquals(listOf("Bonobo"), tracks.map { it.artist }.distinct())
        }
}

class BrainzMatchTest {
    @Test
    fun `the same name wins over a higher-ranked other name`() {
        val results = listOf(MbArtist("1", "Air Supply", 100), MbArtist("2", "AIR", 95))
        assertEquals("2", BrainzMatch.best("Air", results)?.mbid)
    }

    @Test
    fun `loose matches are not trusted`() {
        assertNull(BrainzMatch.best("Air", listOf(MbArtist("1", "Air", 60))))
        assertEquals("1", BrainzMatch.best("Air", listOf(MbArtist("1", "Airiel", 92)))?.mbid)
    }
}

/** A music source with nothing in it; tests override what they need. */
open class FakeMusic : MusicSource {
    override suspend fun similarArtists(artist: String, limit: Int): List<String> = emptyList()

    override suspend fun genreArtists(genre: String, limit: Int): List<String> = emptyList()

    override suspend fun artistTopTracks(artist: String, limit: Int): List<PoolTrack> = emptyList()

    override suspend fun artistTags(artist: String): List<WeightedTag> = emptyList()

    override suspend fun genres(): List<String> = emptyList()

    override suspend fun searchArtists(query: String): List<ArtistHit> = emptyList()

    override suspend fun userKnownTrackKeys(username: String): Set<String> = emptySet()

    override suspend fun userTopArtists(username: String, limit: Int): List<String> = emptyList()
}
