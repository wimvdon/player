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
import to.wim.player.data.FakeMusic
import to.wim.player.data.SpotifyCatalog
import to.wim.player.data.PlaylistTarget
import to.wim.player.data.cache.MemoryCache
import to.wim.player.data.model.PoolTrack
import to.wim.player.data.model.Resolution
import to.wim.player.data.model.SpotifyTrack
import to.wim.player.data.spotify.SpotifyPausedException
import to.wim.player.data.spotify.SpotifySignedOutException
import to.wim.player.logic.Candidate
import to.wim.player.logic.Ordering
import to.wim.player.logic.TrackText
import to.wim.player.logic.WeightedTag
import kotlin.random.Random

/** A small music world: seeds have similar artists, genres have artists, every artist has tracks and tags. */
private class World : FakeMusic() {
    val calls = mutableListOf<String>()

    override suspend fun similarArtists(artist: String, limit: Int) = (1..30).map { "$artist Similar $it" }.also { calls += "similar:$artist" }

    override suspend fun genreArtists(genre: String, limit: Int) = (1..50).map { "$genre Artist $it" }.also { calls += "genreArtists:$genre" }

    override suspend fun artistTopTracks(artist: String, limit: Int) = (1..limit).map { PoolTrack("$artist Song $it", artist, listeners = 10L * it * it) }

    override suspend fun artistTags(artist: String): List<WeightedTag> =
        if (artist.hashCode() % 2 == 0) listOf(WeightedTag("ambient", 100)) else listOf(WeightedTag("techno", 100))

    override suspend fun userTopTrackKeys(username: String) = setOf(TrackText.key("Seed A", "Seed A Song 1"))

    override suspend fun userTopArtists(username: String, limit: Int) = listOf("Top One", "Top Two")
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
    ) = GenerationInput(artists, genres, mood, familiarity, length, Ordering.MOOD_CURVE, listenBrainzUsername = "someone")

    @Test
    fun `two seeds and a genre give the full length`() =
        runBlocking {
            val world = World()
            val engine = GenerationEngine(world, FakeCatalog(), null, null, Random(1))
            val result = engine.generate(input())
            assertEquals(250, result.tracks.size)
            assertTrue(result.poolSize >= 3 * 250)
            assertTrue(result.tracks.count { !it.fromSeedArtist } >= 125)
            assertTrue(result.tracks.groupingBy { it.artistKey }.eachCount().values.all { it <= 3 })
            assertEquals(listOf("similar:Seed A", "similar:Seed B", "genreArtists:trip-hop").sorted(), world.calls.sorted())
        }

    @Test
    fun `no Spotify searches while generating`() =
        runBlocking {
            val catalog = FakeCatalog()
            GenerationEngine(World(), catalog, TrackResolver(catalog, MemoryCache()), null, Random(2)).generate(input(length = 50))
            assertEquals(0, catalog.searches)
        }

    @Test
    fun `tracks from the listening history count as known`() =
        runBlocking {
            val result = GenerationEngine(World(), null, null, null, Random(3)).generate(input(familiarity = 0.0, length = 100))
            val known = result.tracks.first { it.title == "Seed A Song 1" }
            assertEquals(0.0, known.familiarity, 1e-9)
        }

    @Test
    fun `mood follows the slider`() =
        runBlocking {
            val engine = GenerationEngine(World(), null, null, null, Random(4))
            val calm = engine.generate(input(mood = 0.0, length = 60)).tracks.map { it.mood }.average()
            val upbeat = engine.generate(input(mood = 1.0, length = 60)).tracks.map { it.mood }.average()
            assertTrue(calm < 0.3)
            assertTrue(upbeat > 0.7)
        }

    @Test
    fun `no seeds uses top artists from Spotify first`() =
        runBlocking {
            val result = GenerationEngine(World(), FakeCatalog(), null, null, Random(5)).generate(input(artists = emptyList(), genres = emptyList(), length = 30))
            assertTrue(result.usedTopArtists)
            assertTrue(result.tracks.any { it.artist.startsWith("Spotify Top") })
        }

    @Test
    fun `no seeds and no Spotify falls back to ListenBrainz top artists`() =
        runBlocking {
            val result = GenerationEngine(World(), null, null, null, Random(6)).generate(input(artists = emptyList(), genres = emptyList(), length = 30))
            assertTrue(result.tracks.any { it.artist.startsWith("Top ") })
        }

    @Test(expected = NoSeedsException::class)
    fun `nothing to start from`() {
        runBlocking {
            GenerationEngine(FakeMusic(), null, null, null).generate(input(artists = emptyList(), genres = emptyList()).copy(listenBrainzUsername = ""))
        }
    }

    @Test(expected = java.io.IOException::class)
    fun `when every track lookup fails the generation fails`() {
        val broken =
            object : FakeMusic() {
                override suspend fun similarArtists(artist: String, limit: Int) = listOf("Other")

                override suspend fun artistTopTracks(artist: String, limit: Int): List<PoolTrack> = throw java.io.IOException("offline")
            }
        runBlocking { GenerationEngine(broken, null, null, null).generate(input()) }
    }

    @Test
    fun `one failing artist is left out`() =
        runBlocking {
            val flaky =
                object : FakeMusic() {
                    override suspend fun similarArtists(artist: String, limit: Int) = listOf("Good", "Bad")

                    override suspend fun artistTopTracks(artist: String, limit: Int): List<PoolTrack> =
                        if (artist == "Bad") throw java.io.IOException("timeout") else (1..10).map { PoolTrack("$artist $it", artist) }
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

private class FakePlaylists(private val catalog: FakeCatalog? = null) : PlaylistTarget {
    val playlists = mutableMapOf<String, MutableList<String>>()
    val names = mutableListOf<String>()
    val addCalls = mutableListOf<Int>()

    /** Spotify searches made before the playlist was created. */
    var searchesBeforeCreate = -1
    var canPlay = true
    var failAdd: Exception? = null

    /** Fails only this add call (counted from 1), then works again. */
    var failOnAdd = 0

    override suspend fun createPlaylist(name: String, description: String): String {
        searchesBeforeCreate = catalog?.searches ?: -1
        names += name
        val id = "pl${names.size}"
        playlists[id] = mutableListOf()
        return id
    }

    override suspend fun addItems(playlistId: String, uris: List<String>) {
        failAdd?.let { throw it }
        addCalls += uris.size
        if (addCalls.size == failOnAdd) throw java.io.IOException("Connection reset")
        require(uris.size <= 100)
        playlists.getValue(playlistId) += uris
    }

    override suspend fun play(playlistId: String): Boolean = canPlay
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
    ) = QueueController(playlists, TrackResolver(catalog, MemoryCache()), null, scope)

    @Test
    fun `every track found goes into the new playlist in one go`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val catalog = FakeCatalog()
            val playlists = FakePlaylists(catalog)
            val c = controller(playlists, catalog, scope)
            c.start(tracks(250, missingEvery = 5), "Upbeat Indie · 22 Nov", "Made by Player")
            waitFor { c.state.value.status == QueueStatus.FINISHED }
            assertEquals(listOf("Upbeat Indie · 22 Nov"), playlists.names)
            // The playlist is made only after every track was looked up, then filled straight away.
            assertEquals(250, playlists.searchesBeforeCreate)
            assertEquals(listOf(100, 100), playlists.addCalls)
            val expected = tracks(250, missingEvery = 5).filterNot { it.title.startsWith("Missing") }.map(::uriOf)
            assertEquals(expected, playlists.playlists.getValue("pl1"))
            assertEquals(50, c.state.value.skipped)
            assertEquals(250, c.state.value.processed)
            assertEquals(200, c.state.value.queued)
            assertEquals(true, c.state.value.playbackStarted)
            scope.cancel()
        }

    @Test
    fun `each set makes a new playlist`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val playlists = FakePlaylists()
            val c = controller(playlists, FakeCatalog(), scope)
            c.start(tracks(20), "First", "")
            waitFor { c.state.value.status == QueueStatus.FINISHED }
            c.start(tracks(5), "Second", "")
            waitFor { c.state.value.status == QueueStatus.FINISHED && c.state.value.name == "Second" }
            assertEquals(listOf("First", "Second"), playlists.names)
            assertEquals(tracks(20).map(::uriOf), playlists.playlists.getValue("pl1"))
            assertEquals(tracks(5).map(::uriOf), playlists.playlists.getValue("pl2"))
            assertEquals("pl2", c.state.value.playlistId)
            scope.cancel()
        }

    @Test
    fun `no active device means the user presses play`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val playlists = FakePlaylists().apply { canPlay = false }
            val c = controller(playlists, FakeCatalog(), scope)
            c.start(tracks(3), "Player", "")
            waitFor { c.state.value.status == QueueStatus.FINISHED }
            assertEquals(false, c.state.value.playbackStarted)
            scope.cancel()
        }

    @Test
    fun `quota pause while looking up makes no playlist and resume loses nothing`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var paused = false
            var pauseAt = 20
            val catalog =
                object : FakeCatalog() {
                    override suspend fun searchTrack(artist: String, title: String): List<SpotifyTrack> {
                        if (searches == pauseAt) {
                            paused = true
                            pauseAt = -1
                        }
                        if (paused) throw SpotifyPausedException(123)
                        return super.searchTrack(artist, title)
                    }
                }
            val playlists = FakePlaylists()
            val c = controller(playlists, catalog, scope)
            c.start(tracks(40), "Player", "")
            waitFor { c.state.value.status == QueueStatus.FAILED }
            assertEquals(QueueProblem.SpotifyPaused(123), c.state.value.problem)
            assertEquals(20, c.state.value.processed)
            assertTrue(playlists.names.isEmpty())
            paused = false
            c.resume()
            waitFor { c.state.value.status == QueueStatus.FINISHED }
            assertEquals(1, playlists.names.size)
            assertEquals(tracks(40).map(::uriOf), playlists.playlists.getValue("pl1"))
            scope.cancel()
        }

    @Test
    fun `a failed add is retried without doubles or a second playlist`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val playlists = FakePlaylists().apply { failOnAdd = 2 }
            val c = controller(playlists, FakeCatalog(), scope)
            c.start(tracks(150), "Player", "")
            waitFor { c.state.value.status == QueueStatus.FAILED }
            assertEquals(QueueProblem.Network, c.state.value.problem)
            assertEquals(100, c.state.value.queued)
            c.resume()
            waitFor { c.state.value.status == QueueStatus.FINISHED }
            assertEquals(1, playlists.names.size)
            assertEquals(tracks(150).map(::uriOf), playlists.playlists.getValue("pl1"))
            scope.cancel()
        }

    @Test
    fun `nothing on Spotify makes no playlist`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val playlists = FakePlaylists()
            val c = controller(playlists, FakeCatalog(), scope)
            c.start(tracks(4, missingEvery = 1), "Player", "")
            waitFor { c.state.value.status == QueueStatus.FAILED }
            assertEquals(QueueProblem.NothingFound, c.state.value.problem)
            assertTrue(playlists.names.isEmpty())
            scope.cancel()
        }

    @Test
    fun `signed out is reported`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val playlists = FakePlaylists().apply { failAdd = SpotifySignedOutException() }
            val c = controller(playlists, FakeCatalog(), scope)
            c.start(tracks(3), "Player", "")
            waitFor { c.state.value.status == QueueStatus.FAILED }
            assertEquals(QueueProblem.SignedOut, c.state.value.problem)
            scope.cancel()
        }
}
