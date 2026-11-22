package to.wim.player.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoresTest {
    @Test
    fun `no known tags give the middle`() {
        assertEquals(0.5, MoodModel.fromTags(emptyList()), 1e-9)
        assertEquals(0.5, MoodModel.fromTags(listOf(WeightedTag("seen live", 100))), 1e-9)
    }

    @Test
    fun `tags are averaged by their counts`() {
        val mood = MoodModel.fromTags(listOf(WeightedTag("ambient", 100), WeightedTag("techno", 100)))
        assertEquals((0.05 + 0.9) / 2, mood, 1e-9)
        val weighted = MoodModel.fromTags(listOf(WeightedTag("ambient", 300), WeightedTag("techno", 100)))
        assertEquals((0.05 * 3 + 0.9) / 4, weighted, 1e-9)
    }

    @Test
    fun `tag spelling variants match`() {
        assertEquals(0.55, MoodModel.weightOf("Hip Hop")!!, 1e-9)
        assertEquals(0.25, MoodModel.weightOf("trip hop")!!, 1e-9)
        assertEquals(0.95, MoodModel.weightOf("Drum & Bass")!!, 1e-9)
        assertEquals(0.5, MoodModel.weightOf("R&B")!!, 1e-9)
        assertNull(MoodModel.weightOf("ninja tune"))
    }

    @Test
    fun `a calm artist scores low and a club artist high`() {
        val calm = MoodModel.fromTags(listOf(WeightedTag("downtempo", 100), WeightedTag("chillout", 43), WeightedTag("ninja tune", 11)))
        val club = MoodModel.fromTags(listOf(WeightedTag("house", 100), WeightedTag("dance", 60)))
        assertTrue(calm < 0.25)
        assertTrue(club > 0.75)
    }

    @Test
    fun `features mix energy and tempo`() {
        assertEquals(0.6 * 0.5 + 0.4 * 0.5, MoodModel.fromFeatures(0.5, 120.0), 1e-9)
        assertEquals(0.0, MoodModel.fromFeatures(0.0, 40.0), 1e-9)
        assertEquals(1.0, MoodModel.fromFeatures(1.0, 200.0), 1e-9)
    }

    @Test
    fun `known tracks are fully known`() {
        assertEquals(Familiarity.KNOWN, Familiarity.score(known = true, listeners = 10), 1e-9)
    }

    @Test
    fun `listener counts map to discovery on a log scale`() {
        assertEquals(1.0, Familiarity.score(false, 500), 1e-9)
        assertEquals(0.5, Familiarity.score(false, 100_000), 1e-9)
        assertEquals(Familiarity.MIN_UNKNOWN, Familiarity.score(false, 50_000_000), 1e-9)
        assertEquals(Familiarity.UNKNOWN_LISTENERS, Familiarity.score(false, null), 1e-9)
    }
}

class SetupTest {
    @Test
    fun `keys are 32 hex characters`() {
        assertTrue(Setup.isValidKey("0123456789abcdef0123456789ABCDEF"))
        assertTrue(Setup.isValidKey(" 0123456789abcdef0123456789abcdef "))
        assertEquals(false, Setup.isValidKey("0123456789abcdef"))
        assertEquals(false, Setup.isValidKey("0123456789abcdef0123456789abcdeg"))
    }

    @Test
    fun `redirect uri uses the package name`() {
        assertEquals("to.wim.player.debug://callback", Setup.redirectUri("to.wim.player.debug"))
    }

    @Test
    fun `fingerprint is colon separated capitals`() {
        assertEquals("0A:FF:10", Setup.fingerprint(byteArrayOf(0x0A, 0xFF.toByte(), 0x10)))
    }
}
