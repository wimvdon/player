package to.wim.player.logic

import kotlin.math.roundToInt

/** A short description of the mood line drawn above the preview, for screen readers and the label under it. */
data class MoodCurve(
    /** Mood of the first track, 0..100. */
    val startPercent: Int,
    /** Position (1-based) of the most upbeat track. */
    val peakTrack: Int,
    /** Mood of the last track, 0..100. */
    val endPercent: Int,
) {
    companion object {
        /** Null for an empty set. */
        fun of(moods: List<Double>): MoodCurve? {
            if (moods.isEmpty()) return null
            val peak = moods.indices.maxBy { moods[it] }
            return MoodCurve(
                startPercent = (moods.first().coerceIn(0.0, 1.0) * 100).roundToInt(),
                peakTrack = peak + 1,
                endPercent = (moods.last().coerceIn(0.0, 1.0) * 100).roundToInt(),
            )
        }
    }
}
