package to.wim.player.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LengthStepTest {
    @Test
    fun `steps by ten up to a hundred`() {
        assertEquals(20, LengthStep.step(10, +1))
        assertEquals(100, LengthStep.step(90, +1))
        assertEquals(90, LengthStep.step(100, -1))
        assertEquals(10, LengthStep.step(20, -1))
    }

    @Test
    fun `steps by fifty above a hundred`() {
        assertEquals(150, LengthStep.step(100, +1))
        assertEquals(300, LengthStep.step(250, +1))
        assertEquals(200, LengthStep.step(250, -1))
        assertEquals(100, LengthStep.step(150, -1))
    }

    @Test
    fun `odd numbers snap to the next round one`() {
        assertEquals(40, LengthStep.step(33, +1))
        assertEquals(30, LengthStep.step(33, -1))
        assertEquals(150, LengthStep.step(123, +1))
        assertEquals(100, LengthStep.step(123, -1))
    }

    @Test
    fun `stays within the generator's range`() {
        assertEquals(Generator.MAX_LENGTH, LengthStep.step(Generator.MAX_LENGTH, +1))
        assertEquals(Generator.MIN_LENGTH, LengthStep.step(Generator.MIN_LENGTH, -1))
        assertFalse(LengthStep.canStep(Generator.MAX_LENGTH, +1))
        assertTrue(LengthStep.canStep(Generator.MAX_LENGTH, -1))
    }

    @Test
    fun `no valid length starts from the default`() {
        assertEquals(Generator.DEFAULT_LENGTH + LengthStep.LARGE, LengthStep.step(null, +1))
    }
}
