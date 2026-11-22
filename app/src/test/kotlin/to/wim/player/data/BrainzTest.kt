package to.wim.player.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.wim.player.data.brainz.BrainzHttp
import to.wim.player.data.brainz.BrainzMusic
import to.wim.player.data.brainz.ListenBrainzJson
import to.wim.player.data.brainz.MusicBrainzJson
import to.wim.player.logic.BrainzLimits
import to.wim.player.logic.CallLog
import to.wim.player.logic.Service
import to.wim.player.logic.TokenBucket
import to.wim.player.logic.WeightedTag
import java.io.File
import java.io.IOException

private fun fixture(name: String) = File("src/test/resources/fixtures/$name.json").readText()

class MusicBrainzJsonTest {
    @Test
    fun `artist search keeps order, score and disambiguation, skips entries without id`() {
        val artists = MusicBrainzJson.artistSearch(fixture("mb_artistsearch"))
        assertEquals(listOf("Radiohead", "Radiohead Tribute"), artists.map { it.name })
        assertEquals(100, artists.first().score)
        assertEquals("cover band", artists[1].disambiguation)
    }

    @Test
    fun `genres and tags merge by lowercase name, keep the higher count, drop zero votes`() {
        val tags = MusicBrainzJson.artistTags(fixture("mb_artisttags"))
        assertEquals(
            listOf(WeightedTag("alternative rock", 30), WeightedTag("art rock", 15), WeightedTag("british", 9)),
            tags,
        )
    }

    @Test
    fun `url lookup gives each linked artist once`() {
        val artists = MusicBrainzJson.urlArtists(fixture("mb_url"))
        assertEquals(listOf("a74b1b7f-71a5-4011-9441-d0b5e4122711"), artists.map { it.mbid })
    }

    @Test
    fun `garbage gives empty results`() {
        assertTrue(MusicBrainzJson.artistSearch("<html>").isEmpty())
        assertTrue(MusicBrainzJson.artistTags("").isEmpty())
        assertTrue(MusicBrainzJson.urlArtists("[]").isEmpty())
        assertTrue(ListenBrainzJson.tagRecordings("{}").isEmpty())
        assertTrue(ListenBrainzJson.recordingMetadata("[]").isEmpty())
    }
}

class ListenBrainzJsonTest {
    @Test
    fun `tag radio keeps votes and popularity, skips entries without an mbid`() {
        val tagged = ListenBrainzJson.tagRecordings(fixture("lb_tagradio"))
        assertEquals(listOf("rec-ride-1", "rec-slowdive-1", "rec-slowdive-2", "rec-pumpkins-1", "rec-unknown"), tagged.map { it.recordingMbid })
        assertEquals(6, tagged.first().tagCount)
        assertEquals(74.9, tagged[1].popularity, 1e-9)
    }

    @Test
    fun `recording metadata names the main artist first and skips broken entries`() {
        val recordings = ListenBrainzJson.recordingMetadata(fixture("lb_recordingmetadata")).associateBy { it.recordingMbid }
        assertEquals(setOf("rec-ride-1", "rec-slowdive-1", "rec-slowdive-2", "rec-pumpkins-1"), recordings.keys)
        val pumpkins = recordings.getValue("rec-pumpkins-1")
        assertEquals("The Smashing Pumpkins", pumpkins.artist)
        assertEquals(listOf("mb-pumpkins", "mb-someone"), pumpkins.artistMbids)
        assertEquals("Without You", pumpkins.title)
        assertEquals(352000L, recordings.getValue("rec-ride-1").lengthMillis)
    }

    @Test
    fun `genre artists are ranked by their tag votes`() {
        val ranked =
            BrainzMusic.rankGenreArtists(
                ListenBrainzJson.tagRecordings(fixture("lb_tagradio")),
                ListenBrainzJson.recordingMetadata(fixture("lb_recordingmetadata")),
            )
        assertEquals(listOf("Slowdive", "Ride", "The Smashing Pumpkins"), ranked.map { it.name })
        assertEquals(listOf("mb-slowdive", "mb-ride", "mb-pumpkins"), ranked.map { it.mbid })
    }

    @Test
    fun `similar artists leave out the artist asked about, dedupe and sort by score`() {
        val similar = ListenBrainzJson.similarArtists(fixture("lb_similar"), of = "a74b1b7f-71a5-4011-9441-d0b5e4122711")
        assertEquals(listOf("Portishead", "Thom Yorke"), similar.map { it.name })
        assertEquals(7000.0, similar.first().score, 1e-9)
    }

    @Test
    fun `similar artists also read the wrapped dataset format`() {
        assertEquals(listOf("b1"), ListenBrainzJson.similarArtists(fixture("lb_similar_wrapped")).map { it.mbid })
    }

    @Test
    fun `top recordings carry counts and length, missing counts stay null`() {
        val recordings = ListenBrainzJson.topRecordings(fixture("lb_toprecordings"))
        assertEquals(listOf("Creep", "No Surprises"), recordings.map { it.title })
        val creep = recordings.first()
        assertEquals("Radiohead", creep.artist)
        assertEquals("r1", creep.recordingMbid)
        assertEquals(812_345L, creep.listens)
        assertEquals(45_678L, creep.listeners)
        assertEquals(238_000L, creep.lengthMillis)
        assertNull(recordings[1].listeners)
    }

    @Test
    fun `user top artists read single or list mbids`() {
        val artists = ListenBrainzJson.userTopArtists(fixture("lb_userartists"))
        assertEquals(listOf("a74b1b7f-71a5-4011-9441-d0b5e4122711", "b2", null), artists.map { it.mbid })
        assertEquals(420L, artists.first().listens)
    }

    @Test
    fun `user top recordings use track_name and listen_count`() {
        val text = fixture("lb_userrecordings")
        val recordings = ListenBrainzJson.userTopRecordings(text)
        assertEquals(listOf("Creep", "Roads"), recordings.map { it.title })
        assertEquals(40L, recordings.first().listens)
        assertEquals(2, ListenBrainzJson.totalRecordingCount(text))
    }

    @Test
    fun `listens keep artist, title and time, and skip plays without a title`() {
        val listens = ListenBrainzJson.listens(fixture("lb_listens"))
        assertEquals(listOf("Kerala", "Anthem"), listens.map { it.title })
        assertEquals(listOf("Bonobo", "Emancipator"), listens.map { it.artist })
        assertEquals(listOf(1700000300L, 1700000000L), listens.map { it.listenedAt })
        assertTrue(ListenBrainzJson.listens("{}").isEmpty())
    }

    @Test
    fun `recommendations skip entries without an mbid`() {
        val recs = ListenBrainzJson.recommendations(fixture("lb_recommendations"))
        assertEquals(listOf("r9", "r8"), recs.map { it.recordingMbid })
    }

    @Test
    fun `no stats yet gives empty results`() {
        assertTrue(ListenBrainzJson.userTopArtists("").isEmpty())
        assertTrue(ListenBrainzJson.recommendations("{}").isEmpty())
    }
}

class BrainzLimitsTest {
    @Test
    fun `429 and 503 are rate limits`() {
        assertTrue(BrainzLimits.isRateLimited(429))
        assertTrue(BrainzLimits.isRateLimited(503))
        assertFalse(BrainzLimits.isRateLimited(500))
    }

    @Test
    fun `wait prefers reset-in, then retry-after, then backoff, capped at a minute`() {
        assertEquals(3_000L, BrainzLimits.waitMillis("3", "10", attempt = 1))
        assertEquals(10_000L, BrainzLimits.waitMillis(null, "10", attempt = 1))
        assertEquals(1_000L, BrainzLimits.waitMillis("0", null, attempt = 1))
        assertEquals(4_000L, BrainzLimits.waitMillis(null, null, attempt = 2))
        assertEquals(BrainzLimits.MAX_WAIT_MILLIS, BrainzLimits.waitMillis("3600", null, attempt = 1))
    }
}

class BrainzHttpTest {
    private class Scripted(private val answers: MutableList<Pair<Int, String>>) : Interceptor {
        val requests = ArrayList<okhttp3.Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            requests += chain.request()
            val (code, body) = answers.removeAt(0)
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("x")
                .header("X-RateLimit-Reset-In", "2")
                .body(body.toResponseBody())
                .build()
        }
    }

    private fun client(script: Scripted, log: CallLog, sleeps: MutableList<Long>, token: String = ""): BrainzHttp {
        var clock = 0L
        return BrainzHttp(
            http = OkHttpClient.Builder().addInterceptor(script).build(),
            service = Service.LISTENBRAINZ,
            userAgent = BrainzHttp.userAgent("1.2.3"),
            bucket = TokenBucket(1.0, 1.0, { clock }, { clock += it }),
            log = log,
            token = { token },
            now = { clock },
            sleep = { sleeps += it; clock += it },
        )
    }

    private val url = "https://api.listenbrainz.org/1/x".toHttpUrl()

    @Test
    fun `sends user agent, waits on 429 as told, then returns the answer`() =
        runBlocking {
            val script = Scripted(mutableListOf(429 to "", 200 to "{\"ok\":1}"))
            val log = CallLog()
            val sleeps = ArrayList<Long>()
            val answer = client(script, log, sleeps).get(url, "GET x")
            assertTrue(answer.hasData)
            assertEquals("Player/1.2.3 ( https://github.com/wimvdon/player )", script.requests.first().header("User-Agent"))
            assertNull(script.requests.first().header("Authorization"))
            assertTrue(2_000L in sleeps)
            assertEquals(2, log.counts.value[Service.LISTENBRAINZ])
        }

    @Test
    fun `adds the token when there is one`() =
        runBlocking {
            val script = Scripted(mutableListOf(200 to "{}"))
            client(script, CallLog(), ArrayList(), token = "abc").get(url, "GET x")
            assertEquals("Token abc", script.requests.first().header("Authorization"))
        }

    @Test
    fun `404 and 204 are empty answers, not errors`() =
        runBlocking {
            val script = Scripted(mutableListOf(404 to "{\"error\":\"x\"}", 204 to ""))
            val http = client(script, CallLog(), ArrayList())
            assertFalse(http.get(url, "GET x").hasData)
            assertFalse(http.get(url, "GET x").hasData)
        }

    @Test(expected = IOException::class)
    fun `gives up after repeated server errors`() {
        runBlocking {
            val script = Scripted(MutableList(4) { 503 to "" })
            client(script, CallLog(), ArrayList()).get(url, "GET x")
        }
    }

    @Test(expected = IOException::class)
    fun `401 fails at once`() {
        runBlocking {
            client(Scripted(mutableListOf(401 to "")), CallLog(), ArrayList()).get(url, "GET x")
        }
    }
}
