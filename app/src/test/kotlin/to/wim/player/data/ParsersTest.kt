package to.wim.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.wim.player.data.lastfm.LastFmErrors
import to.wim.player.data.lastfm.LastFmJson
import to.wim.player.data.model.AudioFeatures
import to.wim.player.data.reccobeats.ReccoBeatsJson
import to.wim.player.data.spotify.SpotifyJson
import to.wim.player.logic.TrackText
import to.wim.player.logic.WeightedTag
import java.io.File

/** Reads real (trimmed) Last.fm answers from src/test/resources/fixtures. */
class LastFmJsonTest {
    private fun fixture(name: String) = LastFmJson.parse(File("src/test/resources/fixtures/lastfm_$name.json").readText())

    @Test
    fun `similar artists with match`() {
        val artists = LastFmJson.similarArtists(fixture("similar"))
        assertEquals(listOf("Hird", "The Funky Lowlives", "Emancipator"), artists.map { it.name })
        assertEquals(1.0, artists.first().match!!, 1e-9)
    }

    @Test
    fun `tag top artists and tracks`() {
        assertEquals(listOf("Massive Attack", "Portishead", "Phantogram"), LastFmJson.tagTopArtists(fixture("tagartists")).map { it.name })
        val tracks = LastFmJson.tagTopTracks(fixture("tagtracks"))
        assertEquals("Angel", tracks.first().title)
        assertEquals("Massive Attack", tracks.first().artist)
        assertNull(tracks.first().listeners)
    }

    @Test
    fun `artist top tracks carry listeners`() {
        val tracks = LastFmJson.artistTopTracks(fixture("artisttracks"))
        assertEquals(listOf("Kong", "Kiara", "Cirrus"), tracks.map { it.title })
        assertEquals(489_980L, tracks.first().listeners)
        assertEquals("Bonobo", tracks.first().artist)
    }

    @Test
    fun `tags`() {
        assertEquals(listOf("rock", "electronic", "seen live", "alternative", "pop"), LastFmJson.topTags(fixture("toptags")))
        assertEquals(WeightedTag("downtempo", 100), LastFmJson.artistTopTags(fixture("artisttags")).first())
    }

    @Test
    fun `artist search`() {
        val found = LastFmJson.searchArtists(fixture("search"))
        assertEquals("Bonobo", found[1].name)
        assertEquals(2_125_616L, found[1].listeners)
    }

    @Test
    fun `user chart becomes track keys`() {
        val root = fixture("usertracks")
        assertEquals(setOf(TrackText.key("Bonobo", "Kerala"), TrackText.key("Massive Attack", "Teardrop")), LastFmJson.userTopTrackKeys(root))
        assertEquals(1, LastFmJson.totalPages(root, "toptracks"))
    }

    @Test
    fun `errors are read`() {
        val error = LastFmJson.error(fixture("err"))!!
        assertEquals(LastFmErrors.INVALID_PARAMETERS, error.code)
        assertNull(LastFmJson.error(fixture("toptags")))
        val badKey = LastFmJson.error(LastFmJson.parse("""{"message":"Invalid API key","error":10}"""))!!
        assertTrue(badKey.isKeyProblem)
        assertTrue(LastFmJson.error(LastFmJson.parse("""{"message":"Rate limit","error":29}"""))!!.isRetryable)
    }

    @Test
    fun `a single item is read as a list`() {
        val root = LastFmJson.parse("""{"similarartists":{"artist":{"name":"Only One","match":"0.5"}}}""")
        assertEquals(listOf("Only One"), LastFmJson.similarArtists(root).map { it.name })
    }

    @Test
    fun `missing parts give empty lists`() {
        assertTrue(LastFmJson.similarArtists(LastFmJson.parse("{}")).isEmpty())
        assertTrue(LastFmJson.artistTopTracks(LastFmJson.parse("""{"toptracks":{"track":[{"name":""}]}}""")).isEmpty())
    }
}

class SpotifyJsonTest {
    private val search =
        """
        {"tracks":{"items":[
          {"uri":"spotify:track:abc","name":"Kerala","duration_ms":236000,
           "artists":[{"name":"Bonobo"}],
           "album":{"images":[{"url":"big","width":640},{"url":"mid","width":300},{"url":"small","width":64}]}},
          {"uri":"spotify:episode:x","name":"Not a track","artists":[]}
        ]}}
        """.trimIndent()

    @Test
    fun `search results`() {
        val tracks = SpotifyJson.searchTracks(SpotifyJson.parse(search))
        assertEquals(1, tracks.size)
        val track = tracks.first()
        assertEquals("spotify:track:abc", track.uri)
        assertEquals("abc", track.id)
        assertEquals(listOf("Bonobo"), track.artists)
        assertEquals("mid", track.imageUrl)
        assertEquals(236_000L, track.durationMs)
    }

    @Test
    fun `saved tracks and paging`() {
        val root = SpotifyJson.parse("""{"items":[{"track":{"uri":"spotify:track:1","name":"A","artists":[{"name":"B"}]}}],"next":null}""")
        assertEquals(listOf("A"), SpotifyJson.savedTracks(root).map { it.name })
        assertEquals(false, SpotifyJson.hasNext(root))
        assertTrue(SpotifyJson.hasNext(SpotifyJson.parse("""{"items":[],"next":"https://api.spotify.com/v1/me/tracks?offset=50"}""")))
    }

    @Test
    fun `token grant`() {
        val grant = SpotifyJson.tokenGrant(SpotifyJson.parse("""{"access_token":"a","refresh_token":"r","expires_in":3600}"""))!!
        assertEquals("a", grant.accessToken)
        assertEquals("r", grant.refreshToken)
        assertEquals(3600L, grant.expiresInSeconds)
        assertNull(SpotifyJson.tokenGrant(SpotifyJson.parse("""{"error":"invalid_grant"}""")))
    }

    @Test
    fun `error reasons`() {
        assertEquals("QUOTA_EXCEEDED", SpotifyJson.errorReason(SpotifyJson.parse("""{"error":{"status":429,"reason":"QUOTA_EXCEEDED"}}""")))
        assertEquals("NO_ACTIVE_DEVICE", SpotifyJson.errorReason(SpotifyJson.parse("""{"error":{"status":404,"message":"No device","reason":"NO_ACTIVE_DEVICE"}}""")))
        assertEquals("invalid_grant", SpotifyJson.errorReason(SpotifyJson.parse("""{"error":"invalid_grant"}""")))
        assertNull(SpotifyJson.errorReason(SpotifyJson.parse("")))
    }

    @Test
    fun `display name falls back to id`() {
        assertEquals("Wim", SpotifyJson.displayName(SpotifyJson.parse("""{"display_name":"Wim","id":"x"}""")))
        assertEquals("x", SpotifyJson.displayName(SpotifyJson.parse("""{"display_name":null,"id":"x"}""")))
    }
}

class ReccoBeatsJsonTest {
    @Test
    fun `features by spotify id`() {
        val body =
            """{"content":[{"id":"u","href":"https://open.spotify.com/track/3n3Ppam7vgaVa1iaRUc9Lp","energy":0.918,"tempo":148.114},
               {"id":"v","href":"https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b","energy":0.73,"tempo":171.001}]}"""
        val features = ReccoBeatsJson.features(body)
        assertEquals(AudioFeatures(0.918, 148.114), features["3n3Ppam7vgaVa1iaRUc9Lp"])
        assertEquals(2, features.size)
    }

    @Test
    fun `bad answers give nothing`() {
        assertTrue(ReccoBeatsJson.features("""{"content":[]}""").isEmpty())
        assertTrue(ReccoBeatsJson.features("not json").isEmpty())
        assertTrue(ReccoBeatsJson.features("""{"content":[{"href":"x","energy":"a"}]}""").isEmpty())
    }
}
