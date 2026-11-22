package to.wim.player.logic

import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random

/** A track the generator may pick, with its scores already worked out. */
data class Candidate(
    val artist: String,
    val title: String,
    /** 0 = downtempo, 1 = upbeat. */
    val mood: Double,
    /** 0 = known, 1 = discovery. */
    val familiarity: Double,
    /** True when the artist is one of the seed artists the user picked. */
    val fromSeedArtist: Boolean,
    val listeners: Long? = null,
) {
    val key: String = TrackText.key(artist, title)
    val artistKey: String = TrackText.artistKey(artist)
}

/** How the picked tracks are put in order. */
enum class Ordering {
    /** Calm at the start and end, the most upbeat tracks in the middle. */
    MOOD_CURVE,

    /** The closest matches to the sliders first. */
    BEST_MATCH,

    /** Random order. */
    SHUFFLE,
}

data class GeneratorRequest(
    /** Mood slider, 0..1. */
    val mood: Double,
    /** Familiarity slider, 0..1. */
    val familiarity: Double,
    val length: Int,
    val ordering: Ordering = Ordering.MOOD_CURVE,
)

/**
 * Picks and orders tracks for the sliders. Pure functions, so the rules below are unit tested:
 * - closest to both sliders first, with a little randomness so Regenerate gives a different set;
 * - at most [MAX_PER_ARTIST] tracks per artist, never the same artist twice in a row;
 * - at least half from artists the user didn't pick, unless familiarity is fully on "known".
 */
object Generator {
    const val MAX_PER_ARTIST = 3

    /** How far the random factor can move a track's distance (the slider space is 0..1 by 0..1). */
    const val JITTER = 0.08

    const val MIN_LENGTH = 10
    const val MAX_LENGTH = 500
    const val DEFAULT_LENGTH = 250

    fun generate(candidates: List<Candidate>, request: GeneratorRequest, random: Random): List<Candidate> {
        val picked = select(candidates, request, random)
        val ordered =
            when (request.ordering) {
                Ordering.MOOD_CURVE -> moodCurve(picked)
                Ordering.BEST_MATCH -> picked
                Ordering.SHUFFLE -> picked.shuffled(random)
            }
        return separateArtists(ordered)
    }

    /** The distance of a track to the slider position (smaller is better). */
    fun distance(candidate: Candidate, request: GeneratorRequest): Double {
        val dm = candidate.mood - request.mood
        val df = candidate.familiarity - request.familiarity
        return sqrt(dm * dm + df * df)
    }

    /** True when the rule "at least half from other artists" applies. */
    fun requiresDiscovery(request: GeneratorRequest): Boolean = request.familiarity > 0.0

    /** The best tracks for the sliders that obey the diversity rules, best first. */
    fun select(candidates: List<Candidate>, request: GeneratorRequest, random: Random): List<Candidate> {
        val length = request.length.coerceIn(1, MAX_LENGTH)
        val unique = candidates.distinctBy { it.key }
        val scored = unique.map { it to distance(it, request) + random.nextDouble() * JITTER }.sortedBy { it.second }
        val maxFromSeeds = if (requiresDiscovery(request)) length - ceil(length / 2.0).toInt() else length
        val perArtist = HashMap<String, Int>()
        val result = ArrayList<Candidate>(length)
        var fromSeeds = 0
        for ((candidate, _) in scored) {
            if (result.size == length) break
            val count = perArtist[candidate.artistKey] ?: 0
            if (count >= MAX_PER_ARTIST) continue
            if (candidate.fromSeedArtist && fromSeeds >= maxFromSeeds) continue
            result += candidate
            perArtist[candidate.artistKey] = count + 1
            if (candidate.fromSeedArtist) fromSeeds++
        }
        // With few other artists the seed share is capped below the length; trim so the share holds.
        if (requiresDiscovery(request)) {
            val others = result.count { !it.fromSeedArtist }
            if (fromSeeds > others) {
                var toDrop = fromSeeds - others
                val trimmed = ArrayList<Candidate>(result.size)
                for (candidate in result.asReversed()) {
                    if (toDrop > 0 && candidate.fromSeedArtist) {
                        toDrop--
                    } else {
                        trimmed += candidate
                    }
                }
                return trimmed.asReversed()
            }
        }
        return result
    }

    /**
     * A gentle hill: sorted by mood, every other track goes to the front half and the rest to the
     * back half in reverse, so the set starts and ends calmer and peaks in the middle.
     */
    fun moodCurve(tracks: List<Candidate>): List<Candidate> {
        val sorted = tracks.sortedBy { it.mood }
        val rising = sorted.filterIndexed { index, _ -> index % 2 == 0 }
        val falling = sorted.filterIndexed { index, _ -> index % 2 == 1 }.asReversed()
        return rising + falling
    }

    /**
     * Moves tracks so the same artist never plays twice in a row, changing the order as little
     * as possible. When it can't be avoided (one artist left), the order stays as it is.
     */
    fun separateArtists(tracks: List<Candidate>): List<Candidate> {
        val list = tracks.toMutableList()
        for (i in 1 until list.size) {
            if (list[i].artistKey != list[i - 1].artistKey) continue
            val previous = list[i - 1].artistKey
            val ahead = (i + 1 until list.size).firstOrNull { list[it].artistKey != previous }
            if (ahead != null) {
                list.add(i, list.removeAt(ahead))
                continue
            }
            // Nothing different ahead: put this track earlier, between two other artists.
            val moving = list[i]
            val spot =
                (0 until i - 1).firstOrNull { j ->
                    val before = if (j == 0) null else list[j - 1].artistKey
                    before != moving.artistKey && list[j].artistKey != moving.artistKey
                }
            if (spot != null) {
                list.removeAt(i)
                list.add(spot, moving)
            }
        }
        return list
    }

    /** The length the user typed, or null when it isn't a whole number from 10 to 500. */
    fun parseLength(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in MIN_LENGTH..MAX_LENGTH }
}
