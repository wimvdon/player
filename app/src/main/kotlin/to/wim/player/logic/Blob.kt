package to.wim.player.logic

import kotlin.math.cos

/**
 * The math behind Player's soft, wavy shapes (the "cookie" in the wordmark and the loading
 * animation). A shape is a circle whose radius swings [lobes] times around it by [depth].
 */
data class Blob(val lobes: Int, val depth: Float) {
    /** Radius at [angle] (radians), as a fraction of the base radius: between 1 - depth and 1 + depth. */
    fun radius(angle: Float): Float = 1f + depth * cos(lobes * angle)

    companion object {
        /** The nine-lobed cookie of the icon and the wordmark. */
        val Cookie = Blob(lobes = 9, depth = 0.085f)

        /** The shapes the loading animation morphs through, in order. */
        val LoaderShapes = listOf(Cookie, Blob(4, 0.16f), Blob(6, 0.11f), Blob(0, 0f), Blob(5, 0.14f))

        /** Radius at [angle] while morphing from [from] to [to]; [fraction] 0 is [from], 1 is [to]. */
        fun morph(
            from: Blob,
            to: Blob,
            fraction: Float,
            angle: Float,
        ): Float {
            val f = fraction.coerceIn(0f, 1f)
            return from.radius(angle) * (1 - f) + to.radius(angle) * f
        }

        /** Which two of [LoaderShapes] to blend at [phase] (0 until the list size, wrapping) and how far. */
        fun loaderAt(phase: Float): Triple<Blob, Blob, Float> {
            val n = LoaderShapes.size
            val p = ((phase % n) + n) % n
            val i = p.toInt()
            return Triple(LoaderShapes[i], LoaderShapes[(i + 1) % n], p - i)
        }
    }
}
