package to.wim.player.logic

import kotlinx.serialization.Serializable
import kotlin.math.log10

/** A genre or tag with its weight (MusicBrainz: the number of votes). */
@Serializable
data class WeightedTag(
    val name: String,
    val count: Int,
)

/** Clamps [value] from the range [low]..[high] onto 0..1. */
fun normalizeToUnit(value: Double, low: Double, high: Double): Double = ((value - low) / (high - low)).coerceIn(0.0, 1.0)

/**
 * The mood score of a track, from 0 (downtempo) to 1 (upbeat). Audio features win when they are
 * known; otherwise the artist's MusicBrainz genres and tags give an estimate.
 */
object MoodModel {
    /**
     * How upbeat a tag sounds. Edit this map to tune the mood slider; tags that aren't in it are
     * ignored. Spaces and hyphens are the same ("hip hop" matches "hip-hop").
     */
    val TagWeights: Map<String, Double> =
        mapOf(
            // Low
            "ambient" to 0.05,
            "drone" to 0.05,
            "sleep" to 0.05,
            "classical" to 0.15,
            "downtempo" to 0.15,
            "calm" to 0.15,
            "relaxing" to 0.15,
            "sad" to 0.15,
            "chillout" to 0.2,
            "chill" to 0.2,
            "lo-fi" to 0.2,
            "trip-hop" to 0.25,
            "mellow" to 0.25,
            "acoustic" to 0.3,
            "singer-songwriter" to 0.3,
            "soundtrack" to 0.3,
            "folk" to 0.35,
            "dream pop" to 0.35,
            // Mid
            "jazz" to 0.4,
            "post-rock" to 0.4,
            "shoegaze" to 0.4,
            "soul" to 0.45,
            "indie" to 0.5,
            "rnb" to 0.5,
            "alternative" to 0.5,
            "rock" to 0.55,
            "hip-hop" to 0.55,
            "rap" to 0.6,
            "pop" to 0.6,
            "electronic" to 0.6,
            "funk" to 0.7,
            // High
            "disco" to 0.75,
            "dance" to 0.8,
            "house" to 0.8,
            "metal" to 0.8,
            "energetic" to 0.85,
            "upbeat" to 0.85,
            "punk" to 0.85,
            "edm" to 0.85,
            "trance" to 0.85,
            "techno" to 0.9,
            "hardcore" to 0.9,
            "party" to 0.9,
            "drum and bass" to 0.95,
        )

    private val lookup: Map<String, Double> = TagWeights.mapKeys { tagKey(it.key) }

    private fun tagKey(tag: String): String = TrackText.normalize(tag.replace('-', ' ')).replace("r and b", "rnb").replace("randb", "rnb")

    /** The weight of one tag, or null when the map doesn't know it. */
    fun weightOf(tag: String): Double? = lookup[tagKey(tag)]

    /** Average of the known tags, weighted by their votes. No known tags: 0.5. */
    fun fromTags(tags: List<WeightedTag>): Double {
        var sum = 0.0
        var total = 0.0
        for (tag in tags) {
            val weight = weightOf(tag.name) ?: continue
            val count = tag.count.coerceAtLeast(1).toDouble()
            sum += weight * count
            total += count
        }
        return if (total == 0.0) 0.5 else sum / total
    }

    /** From audio features: energy (0..1) and tempo in BPM, normalized over 60..180. */
    fun fromFeatures(energy: Double, tempo: Double): Double =
        (0.6 * energy.coerceIn(0.0, 1.0) + 0.4 * normalizeToUnit(tempo, 60.0, 180.0)).coerceIn(0.0, 1.0)
}

/** How new a track is to the user, from 0 (known) to 1 (discovery). */
object Familiarity {
    /** The score of a track the user saved or has in their top tracks (Spotify or ListenBrainz). */
    const val KNOWN = 0.0

    /** Used when ListenBrainz gives no listener count. */
    const val UNKNOWN_LISTENERS = 0.7

    /** The lowest score a track the user doesn't know can get, so "known" stays distinct. */
    const val MIN_UNKNOWN = 0.1

    /**
     * Listener counts are ListenBrainz users, far fewer than Last.fm had: about 10 (log 1) is
     * niche, 100 000 (log 5) a worldwide hit. Edit these to tune the familiarity slider.
     */
    const val LOG_LISTENERS_NICHE = 1.0
    const val LOG_LISTENERS_HIT = 5.0

    fun score(known: Boolean, listeners: Long?): Double {
        if (known) return KNOWN
        if (listeners == null || listeners <= 0) return UNKNOWN_LISTENERS
        val popularity = normalizeToUnit(log10(listeners.toDouble()), LOG_LISTENERS_NICHE, LOG_LISTENERS_HIT)
        return (1.0 - popularity).coerceIn(MIN_UNKNOWN, 1.0)
    }
}
