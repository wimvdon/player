package to.wim.player.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class BlobTest {
    @Test
    fun `radius swings by the depth`() {
        val cookie = Blob.Cookie
        assertEquals(1 + cookie.depth, cookie.radius(0f), 1e-5f)
        assertEquals(1 - cookie.depth, cookie.radius((PI / 9).toFloat()), 1e-5f)
        (0 until 360).forEach { deg ->
            val r = cookie.radius((deg * PI / 180).toFloat())
            assertTrue(r in (1 - cookie.depth - 1e-5f)..(1 + cookie.depth + 1e-5f))
        }
    }

    @Test
    fun `a circle has the same radius everywhere`() {
        assertEquals(1f, Blob(0, 0f).radius(1.234f), 0f)
    }

    @Test
    fun `morph blends from one shape to the next`() {
        val a = Blob(4, 0.2f)
        val b = Blob(0, 0f)
        assertEquals(a.radius(0f), Blob.morph(a, b, 0f, 0f), 1e-6f)
        assertEquals(1f, Blob.morph(a, b, 1f, 0f), 1e-6f)
        assertEquals(1.1f, Blob.morph(a, b, 0.5f, 0f), 1e-6f)
    }

    @Test
    fun `loader phase wraps around the shapes`() {
        val n = Blob.LoaderShapes.size
        val (from, to, f) = Blob.loaderAt(1.25f)
        assertEquals(Blob.LoaderShapes[1], from)
        assertEquals(Blob.LoaderShapes[2], to)
        assertEquals(0.25f, f, 1e-6f)
        val (last, first, _) = Blob.loaderAt(n - 0.5f)
        assertEquals(Blob.LoaderShapes[n - 1], last)
        assertEquals(Blob.LoaderShapes[0], first)
        assertEquals(Blob.LoaderShapes[0], Blob.loaderAt(n.toFloat()).first)
    }
}
