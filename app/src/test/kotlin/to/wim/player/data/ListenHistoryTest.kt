package to.wim.player.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import to.wim.player.data.brainz.BrainzListen
import to.wim.player.data.brainz.BrainzRecording
import to.wim.player.data.brainz.BrainzTopArtist
import to.wim.player.data.brainz.JsonListenHistoryFile
import to.wim.player.data.brainz.ListenHistory
import to.wim.player.data.brainz.ListenHistoryFile
import to.wim.player.data.brainz.ListenHistoryState
import to.wim.player.data.brainz.Recommendation
import to.wim.player.data.brainz.SimilarArtist
import to.wim.player.logic.TrackText
import java.io.File
import java.io.IOException

/** A history file kept in memory. */
class MemoryHistoryFile : ListenHistoryFile {
    var state: ListenHistoryState? = null
    var writes = 0

    override fun read(): ListenHistoryState? = state

    override fun write(state: ListenHistoryState) {
        this.state = state
        writes++
    }

    override fun delete() {
        state = null
    }
}

/**
 * A made-up user's plays, one a second from [from], answered like ListenBrainz does: newest first,
 * `max_ts` and `min_ts` exclusive, with `min_ts` giving the oldest plays after it.
 */
private class PlaysServer(count: Int, from: Long = 1_000) : ListenBrainzSource {
    val plays = (0 until count).map { BrainzListen("Artist ${it % 7}", "Song $it", from + it) }.toMutableList()
    val calls = mutableListOf<String>()
    var failAfterCalls = Int.MAX_VALUE

    override suspend fun listens(username: String, minTs: Long?, maxTs: Long?, count: Int): List<BrainzListen> {
        if (calls.size >= failAfterCalls) throw IOException("offline")
        calls += "min=$minTs max=$maxTs"
        val page =
            when {
                minTs != null -> plays.filter { it.listenedAt > minTs }.sortedBy { it.listenedAt }.take(count)
                else -> plays.filter { maxTs == null || it.listenedAt < maxTs }.sortedByDescending { it.listenedAt }.take(count)
            }
        return page.sortedByDescending { it.listenedAt }
    }

    override suspend fun similarArtists(mbid: String, limit: Int): List<SimilarArtist> = emptyList()

    override suspend fun topRecordings(artistMbid: String, limit: Int): List<BrainzRecording> = emptyList()

    override suspend fun userTopArtists(username: String, limit: Int): List<BrainzTopArtist> = emptyList()

    override suspend fun userTopRecordings(username: String): List<BrainzRecording> = emptyList()

    override suspend fun recommendations(username: String, limit: Int): List<Recommendation> = emptyList()
}

class ListenHistoryTest {
    @get:Rule val folder = TemporaryFolder()

    private var now = 10_000_000L

    @Test
    fun `the first read pages back to the oldest play`() =
        runBlocking {
            val server = PlaysServer(2_500)
            val history = ListenHistory(server, MemoryHistoryFile()) { now }
            history.sync("someone")
            assertEquals(3, server.calls.size)
            assertEquals(2_500, history.keys("someone").size)
            assertTrue(TrackText.key("Artist 0", "Song 0") in history.keys("Someone"))
            assertTrue(history.status.value.complete)
            assertFalse(history.status.value.syncing)
        }

    @Test
    fun `afterwards only new plays are read, and not again within the interval`() =
        runBlocking {
            val server = PlaysServer(1_500)
            val history = ListenHistory(server, MemoryHistoryFile()) { now }
            history.sync("someone")
            server.calls.clear()
            server.plays += BrainzListen("New Artist", "New Song", 5_000)
            history.sync("someone")
            assertTrue(server.calls.isEmpty())
            now += ListenHistory.MIN_INTERVAL_MILLIS
            history.sync("someone")
            assertEquals(listOf("min=2499 max=null"), server.calls)
            assertTrue(TrackText.key("New Artist", "New Song") in history.keys("someone"))
        }

    @Test
    fun `more new plays than one page are all read`() =
        runBlocking {
            val server = PlaysServer(10)
            val history = ListenHistory(server, MemoryHistoryFile()) { now }
            history.sync("someone")
            server.plays += (0 until 2_100).map { BrainzListen("Later", "Track $it", 10_000L + it) }
            history.sync("someone", minIntervalMillis = 0)
            assertEquals(2_110, history.keys("someone").size)
        }

    @Test
    fun `an interrupted first read goes on where it stopped`() =
        runBlocking {
            val server = PlaysServer(25_000)
            val file = MemoryHistoryFile()
            server.failAfterCalls = 12
            try {
                ListenHistory(server, file) { now }.sync("someone")
                fail("expected the network error")
            } catch (expected: IOException) {
            }
            // Saved after 10 calls: 10 000 plays, the newest 10 000.
            assertEquals(10_000, file.state?.keys?.size)
            assertFalse(file.state!!.complete)
            server.failAfterCalls = Int.MAX_VALUE
            server.calls.clear()
            val history = ListenHistory(server, file) { now }
            history.sync("someone")
            assertEquals(25_000, history.keys("someone").size)
            assertEquals("min=25999 max=null", server.calls.first())
            assertEquals("min=null max=16000", server.calls[1])
        }

    @Test
    fun `another username starts over and the old history is not used`() =
        runBlocking {
            val server = PlaysServer(5)
            val history = ListenHistory(server, MemoryHistoryFile()) { now }
            history.sync("someone")
            assertEquals(5, history.keys("someone").size)
            assertTrue(history.keys("someone else").isEmpty())
            history.sync("someone else")
            assertTrue(history.keys("someone").isEmpty())
            assertEquals(5, history.keys("someone else").size)
        }

    @Test
    fun `the history is kept in a file between runs`() =
        runBlocking {
            val file = File(folder.root, "listen_history.json")
            ListenHistory(PlaysServer(3), JsonListenHistoryFile(file)) { now }.sync("someone")
            val again = ListenHistory(PlaysServer(0), JsonListenHistoryFile(file)) { now }
            assertEquals(3, again.keys("someone").size)
            again.show("someone")
            assertEquals(3, again.status.value.songs)
            again.clear()
            assertFalse(file.exists())
        }

    @Test
    fun `a blank username reads nothing`() =
        runBlocking {
            val server = PlaysServer(3)
            ListenHistory(server, MemoryHistoryFile()) { now }.sync("  ")
            assertTrue(server.calls.isEmpty())
        }
}
