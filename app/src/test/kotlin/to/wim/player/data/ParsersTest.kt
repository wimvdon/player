package to.wim.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.wim.player.data.model.AudioFeatures
import to.wim.player.data.reccobeats.ReccoBeatsJson
import to.wim.player.data.spotify.SpotifyJson

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
