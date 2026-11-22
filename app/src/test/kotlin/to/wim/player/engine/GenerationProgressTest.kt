package to.wim.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationProgressTest {
    @Test
    fun `steps are numbered in order`() {
        val steps =
            listOf(
                GenerationStep.Seeds,
                GenerationStep.Artists(1, 5),
                GenerationStep.Tracks(1, 5),
                GenerationStep.Moods(1, 5),
                GenerationStep.Picking,
            )
        assertEquals((0 until GenerationProgress.STEPS).toList(), steps.map(GenerationProgress::index))
    }

    @Test
    fun `fraction grows through the steps`() {
        assertNull(GenerationProgress.fraction(GenerationStep.Seeds))
        assertEquals(0.05f, GenerationProgress.fraction(GenerationStep.Artists(1, 2))!!, 1e-6f)
        assertEquals(0.6f, GenerationProgress.fraction(GenerationStep.Tracks(4, 4))!!, 1e-6f)
        assertEquals(0.95f, GenerationProgress.fraction(GenerationStep.Moods(3, 3))!!, 1e-6f)
        assertEquals(0.97f, GenerationProgress.fraction(GenerationStep.Picking)!!, 1e-6f)
    }

    @Test
    fun `zero totals don't divide by zero`() {
        assertEquals(0.1f, GenerationProgress.fraction(GenerationStep.Tracks(0, 0))!!, 1e-6f)
    }
}
