package to.wim.player.logic

/** The steps of the track-count stepper on the generator: by 10 up to 100, by 50 above. */
object LengthStep {
    const val SMALL = 10
    const val LARGE = 50
    const val LARGE_FROM = 100

    /** The next length after [current] going up (+1) or down (-1), on a round number, within the generator's range. */
    fun step(
        current: Int?,
        direction: Int,
    ): Int {
        val now = (current ?: Generator.DEFAULT_LENGTH).coerceIn(Generator.MIN_LENGTH, Generator.MAX_LENGTH)
        val next =
            if (direction > 0) {
                val size = if (now >= LARGE_FROM) LARGE else SMALL
                (now / size + 1) * size
            } else {
                val size = if (now > LARGE_FROM) LARGE else SMALL
                ((now + size - 1) / size - 1) * size
            }
        return next.coerceIn(Generator.MIN_LENGTH, Generator.MAX_LENGTH)
    }

    fun canStep(
        current: Int?,
        direction: Int,
    ): Boolean = step(current, direction) != (current ?: Generator.DEFAULT_LENGTH)
}
