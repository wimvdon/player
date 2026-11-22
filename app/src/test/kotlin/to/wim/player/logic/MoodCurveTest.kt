package to.wim.player.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoodCurveTest {
    @Test
    fun `start, peak and end of the set`() {
        assertEquals(MoodCurve(20, 3, 10), MoodCurve.of(listOf(0.2, 0.5, 0.9, 0.4, 0.1)))
    }

    @Test
    fun `the first of equal peaks wins and values are clamped`() {
        assertEquals(MoodCurve(100, 1, 0), MoodCurve.of(listOf(1.2, 1.0, -0.1)))
    }

    @Test
    fun `no tracks, no curve`() {
        assertNull(MoodCurve.of(emptyList()))
    }
}
