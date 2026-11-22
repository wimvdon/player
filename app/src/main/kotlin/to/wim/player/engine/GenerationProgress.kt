package to.wim.player.engine

/** Where generating is, for the checklist and the progress bar on the generating screen. */
object GenerationProgress {
    /** The checklist's steps, in order: library, artists, tracks, moods, picking. */
    const val STEPS = 5

    /** Position of [step] in the checklist (0 until [STEPS]). */
    fun index(step: GenerationStep): Int =
        when (step) {
            GenerationStep.Seeds -> 0
            is GenerationStep.Artists -> 1
            is GenerationStep.Tracks -> 2
            is GenerationStep.Moods -> 3
            GenerationStep.Picking -> 4
        }

    /** Overall progress: artists 0–10 %, tracks 10–60 %, moods 60–95 %; null while reading the library. */
    fun fraction(step: GenerationStep): Float? =
        when (step) {
            GenerationStep.Seeds -> null
            is GenerationStep.Artists -> 0.1f * step.done / step.total.coerceAtLeast(1)
            is GenerationStep.Tracks -> 0.1f + 0.5f * step.done / step.total.coerceAtLeast(1)
            is GenerationStep.Moods -> 0.6f + 0.35f * step.done / step.total.coerceAtLeast(1)
            GenerationStep.Picking -> 0.97f
        }
}
