package to.wim.player.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class PlaylistNameTest {
    private val english = PlaylistName.Templates(calm = "Calm %1\$s", upbeat = "Upbeat %1\$s", dated = "%1\$s · %2\$s", fallback = "Player")
    private val day = LocalDate.of(2026, 11, 22)

    @Test
    fun `genres come first, at most two, capitalised`() {
        assertEquals("Indie & Trip-hop", PlaylistName.topic(listOf("indie", "trip-hop", "jazz"), listOf("Bonobo")))
    }

    @Test
    fun `artists are used without genres`() {
        assertEquals("Bonobo & Air", PlaylistName.topic(emptyList(), listOf("Bonobo", "Air", "Moby")))
        assertNull(PlaylistName.topic(emptyList(), emptyList()))
    }

    @Test
    fun `mood picks the word`() {
        assertEquals(PlaylistName.Vibe.CALM, PlaylistName.vibe(0.1))
        assertEquals(PlaylistName.Vibe.NEUTRAL, PlaylistName.vibe(0.5))
        assertEquals(PlaylistName.Vibe.UPBEAT, PlaylistName.vibe(0.9))
    }

    @Test
    fun `suggestion has topic, mood and date`() {
        assertEquals("Upbeat Indie · 22 Nov", PlaylistName.suggest(listOf("indie"), emptyList(), 0.8, day, Locale.UK, english))
        assertEquals("Calm Bonobo · 22 Nov", PlaylistName.suggest(emptyList(), listOf("Bonobo"), 0.2, day, Locale.UK, english))
        assertEquals("Player · 22 Nov", PlaylistName.suggest(emptyList(), emptyList(), 0.5, day, Locale.UK, english))
    }

    @Test
    fun `translated templates and dates`() {
        val dutch = english.copy(calm = "Rustige %1\$s", upbeat = "Energieke %1\$s")
        assertEquals("Rustige Jazz · 22 nov", PlaylistName.suggest(listOf("jazz"), emptyList(), 0.1, day, Locale.forLanguageTag("nl"), dutch))
    }

    @Test
    fun `typed names are tidied`() {
        assertEquals("My mix", PlaylistName.clean("  My   mix ", "Player"))
        assertEquals("Player", PlaylistName.clean("   ", "Player"))
        assertEquals(PlaylistName.MAX_LENGTH, PlaylistName.clean("a".repeat(150), "Player").length)
    }
}
