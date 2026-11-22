package to.wim.player.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTextTest {
    @Test
    fun `normalize drops case, accents and punctuation`() {
        assertEquals("beyonce", TrackText.normalize("Beyoncé"))
        assertEquals("simon and garfunkel", TrackText.normalize("Simon & Garfunkel"))
        assertEquals("ac dc", TrackText.normalize("AC/DC"))
        assertEquals("dont stop me now", TrackText.normalize("Don't  Stop Me Now!"))
    }

    @Test
    fun `artist key ignores a leading the`() {
        assertEquals(TrackText.artistKey("The xx"), TrackText.artistKey("xx"))
    }

    @Test
    fun `base title strips version notes`() {
        assertEquals("kerala", TrackText.baseTitle("Kerala - Edit"))
        assertEquals("kong", TrackText.baseTitle("Kong - 2009 Remaster"))
        assertEquals("teardrop", TrackText.baseTitle("Teardrop (Remastered 2019)"))
        assertEquals("hey", TrackText.baseTitle("Hey (feat. Someone)"))
        assertEquals("song", TrackText.baseTitle("Song [Radio Edit]"))
        assertEquals("song", TrackText.baseTitle("Song (2011)"))
    }

    @Test
    fun `base title keeps notes that mean another recording`() {
        assertEquals("kerala live", TrackText.baseTitle("Kerala (Live)"))
        assertEquals("kerala someone remix", TrackText.baseTitle("Kerala - Someone Remix"))
        assertEquals("song live at wembley", TrackText.baseTitle("Song - Live at Wembley"))
    }

    @Test
    fun `alternate versions are spotted`() {
        assertTrue(TrackText.isAlternateVersion("Kerala (Live)"))
        assertTrue(TrackText.isAlternateVersion("Song - Karaoke Version"))
        assertTrue(TrackText.isAlternateVersion("Song (Club Remix)"))
        assertFalse(TrackText.isAlternateVersion("Delivery"))
        assertFalse(TrackText.isAlternateVersion("Olive Tree"))
    }

    @Test
    fun `key combines artist and base title`() {
        assertEquals(TrackText.key("Bonobo", "Kerala"), TrackText.key("bonobo", "Kerala - Edit"))
    }
}

class TrackMatcherTest {
    private fun candidate(name: String, vararg artists: String) = SpotifyCandidate("spotify:track:${name.hashCode()}", name, artists.toList())

    @Test
    fun `picks the plain studio version`() {
        val live = candidate("Kerala (Live)", "Bonobo")
        val plain = candidate("Kerala", "Bonobo")
        val edit = candidate("Kerala - Radio Edit", "Bonobo")
        assertEquals(plain, TrackMatcher.bestMatch("Bonobo", "Kerala", listOf(live, edit, plain)))
    }

    @Test
    fun `prefers the shortest matching title`() {
        val edit = candidate("Kerala - Radio Edit", "Bonobo")
        val remaster = candidate("Kerala - 2017 Remaster", "Bonobo")
        assertEquals(edit, TrackMatcher.bestMatch("Bonobo", "Kerala", listOf(remaster, edit)))
    }

    @Test
    fun `never matches another artist`() {
        val cover = candidate("Kerala", "Some Cover Band")
        assertNull(TrackMatcher.bestMatch("Bonobo", "Kerala", listOf(cover)))
    }

    @Test
    fun `skips when only live or karaoke versions exist`() {
        val live = candidate("Kerala - Live", "Bonobo")
        val karaoke = candidate("Kerala (Karaoke Version)", "Bonobo")
        assertNull(TrackMatcher.bestMatch("Bonobo", "Kerala", listOf(live, karaoke)))
    }

    @Test
    fun `a live track on Last fm can match a live track`() {
        val live = candidate("Kerala (Live)", "Bonobo")
        assertEquals(live, TrackMatcher.bestMatch("Bonobo", "Kerala (Live)", listOf(live)))
    }

    @Test
    fun `featured artists still match`() {
        val track = candidate("Hey", "Main Artist", "Guest")
        assertEquals(track, TrackMatcher.bestMatch("Main Artist", "Hey (feat. Guest)", listOf(track)))
    }

    @Test
    fun `different song with similar words is not a match`() {
        val other = candidate("Kerala Dreams", "Bonobo")
        assertNull(TrackMatcher.bestMatch("Bonobo", "Kerala", listOf(other)))
    }
}
