package to.wim.player.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.wim.player.data.FakeLastFm
import to.wim.player.data.PlayerException
import to.wim.player.data.PlayerProblem
import to.wim.player.data.PlayerSnapshot
import to.wim.player.data.SpotifyCatalog
import to.wim.player.data.SpotifyPlayer
import to.wim.player.data.cache.MemoryCache
import to.wim.player.data.lastfm.LastFmErrors
import to.wim.player.data.lastfm.LastFmException
import to.wim.player.data.model.LfmArtist
import to.wim.player.data.model.LfmTrack
import to.wim.player.data.model.QueueMode
import to.wim.player.data.model.Resolution
import to.wim.player.data.model.SpotifyTrack
import to.wim.player.data.spotify.SpotifyPausedException
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

private class FakePlayer : SpotifyPlayer {
    val actions = mutableListOf<String>()
    val flow = MutableStateFlow<PlayerSnapshot?>(null)
    var failConnect: PlayerProblem? = null

    override val state: StateFlow<PlayerSnapshot?> = flow

    override suspend fun connect() {
        failConnect?.let { throw PlayerException(it) }
        if (flow.value == null) flow.value = PlayerSnapshot(null, null, null, isPaused = true)
    }

    override suspend fun queue(uri: String) {
        actions += "queue $uri"
    }

    override suspend fun play(uri: String) {
        actions += "play $uri"
        flow.value = PlayerSnapshot(uri, "t", "a", isPaused = false)
    }

    override fun disconnect() {}

    fun playing(uri: String) {
        flow.value = PlayerSnapshot(uri, "t", "a", isPaused = false)
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

    @Test
    fun `rolling keeps fifteen ahead and plays the first when idle`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val player = FakePlayer()
            val catalog = FakeCatalog()
            val controller = QueueController(player, TrackResolver(catalog, MemoryCache()), null, scope, spacingMillis = 0, batchPauseMillis = 0)
            val list = tracks(40)
            controller.start(list, QueueMode.ROLLING)
            waitFor { controller.state.value.queued == 16 }
            assertEquals("play ${uriOf(list[0])}", player.actions.first())
            assertEquals(15, controller.state.value.ahead)
            // Only the queued tracks were searched: the rest waits for listening.
            assertEquals(16, catalog.searches)
            // Track 3 starts: two more are queued.
            player.playing(uriOf(list[2]))
            waitFor { controller.state.value.queued == 18 }
            assertEquals(15, controller.state.value.ahead)
            controller.stop()
            scope.cancel()
        }

    @Test
    fun `tracks not on Spotify are skipped`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val player = FakePlayer()
            val controller = QueueController(player, TrackResolver(FakeCatalog(), MemoryCache()), null, scope, 0, 0)
            controller.start(tracks(30, missingEvery = 3), QueueMode.ALL_NOW)
            waitFor { controller.state.value.status == QueueStatus.FINISHED }
            assertEquals(10, controller.state.value.skipped)
            assertEquals(20, controller.state.value.queued)
            assertEquals(30, controller.state.value.processed)
            scope.cancel()
        }

    @Test
    fun `missing Spotify app is reported`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val player = FakePlayer().apply { failConnect = PlayerProblem.NOT_INSTALLED }
            val controller = QueueController(player, TrackResolver(FakeCatalog(), MemoryCache()), null, scope, 0, 0)
            controller.start(tracks(5), QueueMode.ROLLING)
            waitFor { controller.state.value.status == QueueStatus.FAILED }
            assertEquals(QueueProblem.Player(PlayerProblem.NOT_INSTALLED), controller.state.value.problem)
            scope.cancel()
        }

    @Test
    fun `quota pause stops the queue and resume carries on`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var paused = true
            val catalog =
                object : FakeCatalog() {
                    override suspend fun searchTrack(artist: String, title: String): List<SpotifyTrack> {
                        if (paused && title == "Song 3") throw SpotifyPausedException(123)
                        return super.searchTrack(artist, title)
                    }
                }
            val player = FakePlayer()
            val controller = QueueController(player, TrackResolver(catalog, MemoryCache()), null, scope, 0, 0)
            controller.start(tracks(5), QueueMode.ALL_NOW)
            waitFor { controller.state.value.status == QueueStatus.FAILED }
            assertEquals(QueueProblem.SpotifyPaused(123), controller.state.value.problem)
            assertEquals(2, controller.state.value.queued)
            paused = false
            controller.resume()
            waitFor { controller.state.value.status == QueueStatus.FINISHED }
            // Song 3 is tried again after the pause, so nothing is lost.
            assertEquals(5, controller.state.value.queued)
            scope.cancel()
        }

    @Test
    fun `stop ends the queue`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val player = FakePlayer()
            val controller = QueueController(player, TrackResolver(FakeCatalog(), MemoryCache()), null, scope, 0, 0)
            controller.start(tracks(100), QueueMode.ROLLING)
            waitFor { controller.state.value.queued == 16 }
            controller.stop()
            assertEquals(QueueStatus.STOPPED, controller.state.value.status)
            scope.cancel()
        }
}
