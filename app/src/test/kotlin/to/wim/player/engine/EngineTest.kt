package to.wim.player.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.wim.player.data.FakeLastFm
import to.wim.player.data.SpotifyCatalog
import to.wim.player.data.PlaylistMemory
import to.wim.player.data.PlaylistTarget
import to.wim.player.data.cache.MemoryCache
import to.wim.player.data.lastfm.LastFmErrors
import to.wim.player.data.lastfm.LastFmException
import to.wim.player.data.model.LfmArtist
import to.wim.player.data.model.LfmTrack
import to.wim.player.data.model.QueueMode
import to.wim.player.data.model.Resolution
import to.wim.player.data.model.SpotifyTrack
import to.wim.player.data.spotify.SpotifyHttpException
import to.wim.player.data.spotify.SpotifyPausedException
import to.wim.player.data.spotify.SpotifySignedOutException
import to.wim.player.logic.Candidate
import to.wim.player.logic.Ordering
import to.wim.player.logic.TrackText
import to.wim.player.logic.WeightedTag
import kotlin.random.Random

/** A small music world: seeds have similar artists, genres have charts, every artist has tracks and tags. */
private class WorldLastFm : FakeLastFm() {
    val calls = mutableListOf<String>()

    override suspend fun similarArtists(artist: String, limit: Int) =
        (1..30).map { LfmArtist("$artist Similar $it") }.also { calls += "similar:$artist" }

    override suspend fun tagTopArtists(tag: String, limit: Int) = (1..50).map { LfmArtist("$tag Artist $it") }.also { calls += "tagArtists:$tag" }

    override suspend fun tagTopTracks(tag: String, limit: Int) = (1..50).map { LfmTrack("$tag Hit $it", "$tag Artist $it") }

    override suspend fun artistTopTracks(artist: String, limit: Int) =
        (1..limit).map { LfmTrack("$artist Song $it", artist, listeners = 1_000L * it * it) }

    override suspend fun artistTopTags(artist: String): List<WeightedTag> =
        if (artist.hashCode() % 2 == 0) listOf(WeightedTag("ambient", 100)) else listOf(WeightedTag("techno", 100))

    override suspend fun userTopTrackKeys(username: String) = setOf(TrackText.key("Seed A", "Seed A Song 1"))

    override suspend fun userTopArtists(username: String, limit: Int) = listOf(LfmArtist("Top One"), LfmArtist("Top Two"))
}

private open class FakeCatalog : SpotifyCatalog {
    var searches = 0

    override suspend fun searchTrack(artist: String, title: String): List<SpotifyTrack> {
        searches++
        if (title.contains("Missing")) return emptyList()
        return listOf(SpotifyTrack("spotify:track:${TrackText.key(artist, title).hashCode()}", title, listOf(artist)))
    }

    override suspend fun knownTrackKeys(): Set<String> = emptySet()

    override suspend fun topArtists(): List<String> = listOf("Spotify Top")
}

class GenerationEngineTest {
    private fun input(
        artists: List<String> = listOf("Seed A", "Seed B"),
        genres: List<String> = listOf("trip-hop"),
        length: Int = 250,
        mood: Double = 0.5,
        familiarity: Double = 0.5,
    ) = GenerationInput(artists, genres, mood, familiarity, length, Ordering.MOOD_CURVE, lastFmUsername = "someone")

    @Test
    fun `two seeds and a genre give the full length`() =
        runBlocking {
            val lastFm = WorldLastFm()
            val engine = GenerationEngine(lastFm, FakeCatalog(), null, null, Random(1))
            val result = engine.generate(input())
            assertEquals(250, result.tracks.size)
            assertTrue(result.poolSize >= 3 * 250)
            assertTrue(result.tracks.count { !it.fromSeedArtist } >= 125)
            assertTrue(result.tracks.groupingBy { it.artistKey }.eachCount().values.all { it <= 3 })
            assertEquals(listOf("similar:Seed A", "similar:Seed B", "tagArtists:trip-hop").sorted(), lastFm.calls.sorted())
        }

    @Test
    fun `no Spotify searches while generating`() =
        runBlocking {
            val catalog = FakeCatalog()
            GenerationEngine(WorldLastFm(), catalog, TrackResolver(catalog, MemoryCache()), null, Random(2)).generate(input(length = 50))
            assertEquals(0, catalog.searches)
        }

    @Test
    fun `scrobbled tracks count as known`() =
        runBlocking {
            val result = GenerationEngine(WorldLastFm(), null, null, null, Random(3)).generate(input(familiarity = 0.0, length = 100))
            val known = result.tracks.first { it.title == "Seed A Song 1" }
            assertEquals(0.0, known.familiarity, 1e-9)
        }

    @Test
    fun `mood follows the slider`() =
        runBlocking {
            val engine = GenerationEngine(WorldLastFm(), null, null, null, Random(4))
            val calm = engine.generate(input(mood = 0.0, length = 60)).tracks.map { it.mood }.average()
            val upbeat = engine.generate(input(mood = 1.0, length = 60)).tracks.map { it.mood }.average()
            assertTrue(calm < 0.3)
            assertTrue(upbeat > 0.7)
        }

    @Test
    fun `no seeds uses top artists from Spotify first`() =
        runBlocking {
            val result = GenerationEngine(WorldLastFm(), FakeCatalog(), null, null, Random(5)).generate(input(artists = emptyList(), genres = emptyList(), length = 30))
            assertTrue(result.usedTopArtists)
            assertTrue(result.tracks.any { it.artist.startsWith("Spotify Top") })
        }

    @Test
    fun `no seeds and no Spotify falls back to Last fm top artists`() =
        runBlocking {
            val result = GenerationEngine(WorldLastFm(), null, null, null, Random(6)).generate(input(artists = emptyList(), genres = emptyList(), length = 30))
            assertTrue(result.tracks.any { it.artist.startsWith("Top ") })
        }

    @Test(expected = NoSeedsException::class)
    fun `nothing to start from`() {
        runBlocking {
            GenerationEngine(FakeLastFm(), null, null, null).generate(input(artists = emptyList(), genres = emptyList()).copy(lastFmUsername = ""))
        }
    }

    @Test(expected = LastFmException::class)
    fun `a bad key stops the generation`() {
        val broken =
            object : FakeLastFm() {
                override suspend fun similarArtists(artist: String, limit: Int): List<LfmArtist> =
                    throw LastFmException(LastFmErrors.INVALID_API_KEY, "Invalid API key")
            }
        runBlocking { GenerationEngine(broken, null, null, null).generate(input()) }
    }

    @Test
    fun `one failing artist is left out`() =
        runBlocking {
            val flaky =
                object : FakeLastFm() {
                    override suspend fun similarArtists(artist: String, limit: Int) = listOf(LfmArtist("Good"), LfmArtist("Bad"))

                    override suspend fun artistTopTracks(artist: String, limit: Int): List<LfmTrack> =
                        if (artist == "Bad") throw java.io.IOException("timeout") else (1..10).map { LfmTrack("$artist $it", artist) }
                }
            val result = GenerationEngine(flaky, null, null, null, Random(7)).generate(input(artists = listOf("Seed"), genres = emptyList(), length = 10))
            assertTrue(result.tracks.none { it.artist == "Bad" })
            assertTrue(result.tracks.isNotEmpty())
        }

    @Test
    fun `round robin shares the artist slots`() {
        val lists = listOf(listOf("a1", "a2", "a3"), listOf("b1", "b2"), listOf("a1", "c1"))
        assertEquals(listOf("a1", "b1", "a2", "b2", "c1"), GenerationEngine.roundRobin(lists, 5))
    }
}

class TrackResolverTest {
    @Test
    fun `searches once and remembers misses`() =
        runBlocking {
            val catalog = FakeCatalog()
            val resolver = TrackResolver(catalog, MemoryCache<Resolution>())
            val first = resolver.resolve("Bonobo", "Kerala")
            val again = resolver.resolve("bonobo", "Kerala - Edit")
            assertEquals(first, again)
            assertEquals(1, catalog.searches)
            assertNull(resolver.resolve("Bonobo", "Missing Song").uri)
            assertNull(resolver.resolve("Bonobo", "Missing Song").uri)
            assertEquals(2, catalog.searches)
            assertEquals(first, resolver.cached("Bonobo", "Kerala"))
        }

    @Test
    fun `a wrong artist is a miss`() =
        runBlocking {
            val catalog =
                object : FakeCatalog() {
                    override suspend fun searchTrack(artist: String, title: String) = listOf(SpotifyTrack("spotify:track:x", title, listOf("Cover Band")))
                }
            assertNull(TrackResolver(catalog, MemoryCache()).resolve("Bonobo", "Kerala").uri)
        }
}

private class FakePlaylists : PlaylistTarget, PlaylistMemory {
    val playlists = mutableMapOf<String, MutableList<String>>()
    var saved = ""
    var created = 0
    var canPlay = true
    var failAdd: Exception? = null

    override suspend fun createPlaylist(name: String, description: String): String {
        created++
        val id = "pl$created"
        playlists[id] = mutableListOf()
        return id
    }

    override suspend fun replaceItems(playlistId: String, uris: List<String>) {
        val list = playlists[playlistId] ?: throw SpotifyHttpException(404, "Not found")
        list.clear()
        list += uris
    }

    override suspend fun addItems(playlistId: String, uris: List<String>) {
        failAdd?.let { throw it }
        require(uris.size <= 100)
        playlists.getValue(playlistId) += uris
    }

    override suspend fun play(playlistId: String): Boolean = canPlay

    override suspend fun playlistId(): String = saved

    override suspend fun savePlaylistId(id: String) {
        saved = id
    }
}

class QueueControllerTest {
    private fun tracks(count: Int, missingEvery: Int = 0) =
        (1..count).map { i ->
            val title = if (missingEvery > 0 && i % missingEvery == 0) "Missing $i" else "Song $i"
            Candidate("Artist $i", title, 0.5, 0.5, false)
        }

    private fun uriOf(track: Candidate) = "spotify:track:${TrackText.key(track.artist, track.title).hashCode()}"

    private suspend fun waitFor(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(5)
        }
    }

    private fun controller(
        playlists: FakePlaylists,
        catalog: FakeCatalog,
        scope: CoroutineScope,
        clock: () -> Long = { 0L },
    ) = QueueController(playlists, playlists, TrackResolver(catalog, MemoryCache()), null, scope, now = clock, checkEveryMillis = 10)

    @Test
    fun `all at once makes a playlist with every track found`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val playlists = FakePlaylists()
            val c = controller(playlists, FakeCatalog(), scope)
            c.start(tracks(250, missingEvery = 5), QueueMode.ALL_NOW, "Player", "Made by Player")
            waitFor { c.state.value.status == QueueStatus.FINISHED }
            assertEquals(1, playlists.created)
            assertEquals("pl1", playlists.saved)
            assertEquals(200, playlists.playlists.getValue("pl1").size)
            assertEquals(50, c.state.value.skipped)
            assertEquals(250, c.state.value.processed)
            assertEquals(true, c.state.value.playbackStarted)
            scope.cancel()
        }

    @Test
    fun `the saved playlist is reused and emptied`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val playlists =
                FakePlaylists().apply {
                    playlists["old"] = mutableListOf("spotify:track:stale")
                    saved = "old"
                }
            val c = controller(playlists, FakeCatalog(), scope)
            c.start(tracks(20), QueueMode.ALL_NOW, "Player", "")
            waitFor { c.state.value.status == QueueStatus.FINISHED }
            assertEquals(0, playlists.created)
            assertEquals(tracks(20).map(::uriOf), playlists.playlists.getValue("old"))
            scope.cancel()
        }

    @Test
    fun `a deleted playlist is made again`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val playlists = FakePlaylists().apply { saved = "gone" }
            val c = controller(playlists, FakeCatalog(), scope)
            c.start(tracks(5), QueueMode.ALL_NOW, "Player", "")
            waitFor { c.state.value.status == QueueStatus.FINISHED }
            assertEquals("pl1", playlists.saved)
            assertEquals(5, playlists.playlists.getValue("pl1").size)
            scope.cancel()
        }

    @Test
    fun `rolling adds as listening time passes`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val playlists = FakePlaylists()
            var clock = 0L
            val catalog = FakeCatalog()
            val c = controller(playlists, catalog, scope) { clock }
            c.start(tracks(100), QueueMode.ROLLING, "Player", "")
            // 3.5 minutes a track (search gave no length): the first 15 are 52 minutes, past the 40 ahead.
            waitFor { c.state.value.queued == 15 && c.state.value.status == QueueStatus.RUNNING }
            delay(50)
            assertEquals(15, playlists.playlists.getValue("pl1").size)
            assertEquals(15, catalog.searches)
            // Half an hour later the playlist is topped up.
            clock += 30 * 60_000
            waitFor { c.state.value.queued == 25 }
            assertEquals(tracks(25).map(::uriOf), playlists.playlists.getValue("pl1"))
            c.stop()
            assertEquals(QueueStatus.STOPPED, c.state.value.status)
            scope.cancel()
        }

    @Test
    fun `no active device means the user presses play`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val playlists = FakePlaylists().apply { canPlay = false }
            val c = controller(playlists, FakeCatalog(), scope)
            c.start(tracks(3), QueueMode.ALL_NOW, "Player", "")
            waitFor { c.state.value.status == QueueStatus.FINISHED }
            assertEquals(false, c.state.value.playbackStarted)
            scope.cancel()
        }

    @Test
    fun `quota pause stops and resume loses nothing`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var paused = false
            val catalog =
                object : FakeCatalog() {
                    override suspend fun searchTrack(artist: String, title: String): List<SpotifyTrack> {
                        if (paused) throw SpotifyPausedException(123)
                        return super.searchTrack(artist, title)
                    }
                }
            val playlists = FakePlaylists()
            var clock = 0L
            val c = controller(playlists, catalog, scope) { clock }
            c.start(tracks(40), QueueMode.ROLLING, "Player", "")
            waitFor { c.state.value.queued == 15 && c.state.value.status == QueueStatus.RUNNING }
            paused = true
            clock += 60 * 60_000
            waitFor { c.state.value.status == QueueStatus.FAILED }
            assertEquals(QueueProblem.SpotifyPaused(123), c.state.value.problem)
            paused = false
            c.resume()
            waitFor { c.state.value.queued > 15 }
            val list = playlists.playlists.getValue("pl1")
            assertEquals(tracks(40).take(list.size).map(::uriOf), list)
            c.stop()
            scope.cancel()
        }

    @Test
    fun `signed out is reported`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val playlists = FakePlaylists().apply { failAdd = SpotifySignedOutException() }
            val c = controller(playlists, FakeCatalog(), scope)
            c.start(tracks(3), QueueMode.ALL_NOW, "Player", "")
            waitFor { c.state.value.status == QueueStatus.FAILED }
            assertEquals(QueueProblem.SignedOut, c.state.value.problem)
            scope.cancel()
        }
}
