package to.wim.player.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import to.wim.player.data.AudioFeatureSource
import to.wim.player.data.LastFmSource
import to.wim.player.data.SpotifyCatalog
import to.wim.player.data.lastfm.LastFmException
import to.wim.player.data.model.LfmTrack
import to.wim.player.logic.Candidate
import to.wim.player.logic.Familiarity
import to.wim.player.logic.Generator
import to.wim.player.logic.GeneratorRequest
import to.wim.player.logic.MoodModel
import to.wim.player.logic.Ordering
import to.wim.player.logic.TrackText
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

data class GenerationInput(
    val seedArtists: List<String>,
    val genres: List<String>,
    val mood: Double,
    val familiarity: Double,
    val length: Int,
    val ordering: Ordering,
    val lastFmUsername: String = "",
)

/** Where a generation is, for the progress bar. */
sealed interface GenerationStep {
    data object Seeds : GenerationStep

    data class Artists(val done: Int, val total: Int) : GenerationStep

    data class Tracks(val done: Int, val total: Int) : GenerationStep

    data class Moods(val done: Int, val total: Int) : GenerationStep

    data object Picking : GenerationStep
}

data class GenerationResult(
    val tracks: List<Candidate>,
    val requested: Int,
    val poolSize: Int,
    val artistCount: Int,
    /** True when no seeds were picked and the user's top artists were used. */
    val usedTopArtists: Boolean,
    /** True when the user's Spotify library couldn't be read, so "known" is less precise. */
    val libraryUnavailable: Boolean,
)

/** Nothing picked and no top artists to start from. */
class NoSeedsException : Exception("No seed artists or genres")

/** Last.fm gave nothing to pick from. */
class EmptyPoolException : Exception("No tracks found")

/**
 * Builds a set of tracks from seeds: similar artists and genre charts from Last.fm, their top
 * tracks, mood from artist tags (or cached audio features) and familiarity from the user's
 * library and listener counts. No Spotify searches happen here; tracks are matched on Spotify
 * while they are queued.
 */
class GenerationEngine(
    private val lastFm: LastFmSource,
    private val spotify: SpotifyCatalog?,
    private val resolver: TrackResolver?,
    private val features: AudioFeatureSource?,
    private val random: Random = Random.Default,
) {
    suspend fun generate(input: GenerationInput, progress: (GenerationStep) -> Unit = {}): GenerationResult =
        coroutineScope {
            progress(GenerationStep.Seeds)
            val knownJob = async { knownKeys(input.lastFmUsername) }
            var seeds = input.seedArtists.filter(String::isNotBlank).distinctBy(TrackText::artistKey)
            val genres = input.genres.filter(String::isNotBlank).distinctBy(TrackText::normalize)
            val usedTopArtists = seeds.isEmpty() && genres.isEmpty()
            if (usedTopArtists) seeds = topArtists(input.lastFmUsername)
            if (seeds.isEmpty() && genres.isEmpty()) throw NoSeedsException()
            val seedKeys = seeds.map(TrackText::artistKey).toSet()

            // Candidate artists: each seed with its similar artists, plus each genre's chart.
            val sources = seeds.size + genres.size
            val done = AtomicInteger(0)
            progress(GenerationStep.Artists(0, sources))
            val gate = Semaphore(PARALLEL)
            val artistLists =
                (
                    seeds.map { seed ->
                        async {
                            gate.withPermit { listOf(seed) + tolerant { lastFm.similarArtists(seed, SIMILAR_LIMIT) }.orEmpty().map { it.name } }
                                .also { progress(GenerationStep.Artists(done.incrementAndGet(), sources)) }
                        }
                    } +
                        genres.map { genre ->
                            async {
                                gate.withPermit { tolerant { lastFm.tagTopArtists(genre, TAG_ARTISTS_LIMIT) }.orEmpty().map { it.name } }
                                    .also { progress(GenerationStep.Artists(done.incrementAndGet(), sources)) }
                            }
                        }
                ).awaitAll()
            val artists = roundRobin(artistLists, MAX_ARTISTS)

            // Tracks: each artist's top tracks plus each genre's top tracks.
            val perArtist = if (artists.size < 50) 20 else 15
            val trackJobs = artists.size + genres.size
            done.set(0)
            progress(GenerationStep.Tracks(0, trackJobs))
            val failures = AtomicInteger(0)
            val trackLists =
                (
                    artists.map { artist ->
                        async {
                            gate.withPermit { tolerant(failures) { lastFm.artistTopTracks(artist, perArtist) }.orEmpty() }
                                .also { progress(GenerationStep.Tracks(done.incrementAndGet(), trackJobs)) }
                        }
                    } +
                        genres.map { genre ->
                            async {
                                gate.withPermit { tolerant(failures) { lastFm.tagTopTracks(genre, TAG_TRACKS_LIMIT) }.orEmpty() }
                                    .also { progress(GenerationStep.Tracks(done.incrementAndGet(), trackJobs)) }
                            }
                        }
                ).awaitAll()
            val pool: List<LfmTrack> = trackLists.flatten().distinctBy { TrackText.key(it.artist, it.title) }
            if (pool.isEmpty()) throw if (failures.get() > 0) java.io.IOException("Last.fm unavailable") else EmptyPoolException()

            // Mood: one tag lookup per artist (cheaper than per track).
            val poolArtists = pool.map { it.artist }.distinctBy(TrackText::artistKey)
            done.set(0)
            progress(GenerationStep.Moods(0, poolArtists.size))
            val artistMood =
                poolArtists.map { artist ->
                    async {
                        val tags = gate.withPermit { tolerant { lastFm.artistTopTags(artist) }.orEmpty() }
                        progress(GenerationStep.Moods(done.incrementAndGet(), poolArtists.size))
                        TrackText.artistKey(artist) to MoodModel.fromTags(tags)
                    }
                }.awaitAll().toMap()

            progress(GenerationStep.Picking)
            val known = knownJob.await()
            val candidates =
                pool.map { track ->
                    val key = TrackText.key(track.artist, track.title)
                    val artistKey = TrackText.artistKey(track.artist)
                    Candidate(
                        artist = track.artist,
                        title = track.title,
                        mood = featureMood(track) ?: artistMood[artistKey] ?: 0.5,
                        familiarity = Familiarity.score(key in known.keys, track.listeners),
                        fromSeedArtist = artistKey in seedKeys,
                        listeners = track.listeners,
                    )
                }
            val request = GeneratorRequest(input.mood, input.familiarity, input.length, input.ordering)
            GenerationResult(
                tracks = Generator.generate(candidates, request, random),
                requested = input.length,
                poolSize = candidates.size,
                artistCount = poolArtists.size,
                usedTopArtists = usedTopArtists,
                libraryUnavailable = known.spotifyFailed,
            )
        }

    private fun featureMood(track: LfmTrack): Double? {
        val uri = resolver?.cached(track.artist, track.title)?.uri ?: return null
        val found = features?.cached(uri.substringAfterLast(':')) ?: return null
        return MoodModel.fromFeatures(found.energy, found.tempo)
    }

    private class Known(val keys: Set<String>, val spotifyFailed: Boolean)

    private suspend fun knownKeys(username: String): Known {
        var spotifyFailed = false
        val keys = HashSet<String>()
        if (spotify != null) {
            val fromSpotify = tolerant { spotify.knownTrackKeys() }
            if (fromSpotify == null) spotifyFailed = true else keys += fromSpotify
        }
        if (username.isNotBlank()) tolerant { lastFm.userTopTrackKeys(username) }?.let { keys += it }
        return Known(keys, spotifyFailed)
    }

    private suspend fun topArtists(username: String): List<String> {
        spotify?.let { catalog -> tolerant { catalog.topArtists() }?.takeIf { it.isNotEmpty() }?.let { return it.take(TOP_ARTIST_SEEDS) } }
        if (username.isNotBlank()) {
            tolerant { lastFm.userTopArtists(username, TOP_ARTIST_SEEDS) }?.takeIf { it.isNotEmpty() }?.let { list ->
                return list.map { it.name }
            }
        }
        return emptyList()
    }

    /**
     * Runs one lookup; a failure leaves that source out instead of failing the whole set. A bad
     * Last.fm key still stops everything, since nothing would work.
     */
    private suspend fun <T> tolerant(failures: AtomicInteger? = null, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: LastFmException) {
            if (e.isKeyProblem) throw e
            failures?.incrementAndGet()
            null
        } catch (e: Exception) {
            failures?.incrementAndGet()
            null
        }

    companion object {
        const val SIMILAR_LIMIT = 30
        const val TAG_ARTISTS_LIMIT = 50
        const val TAG_TRACKS_LIMIT = 50
        const val MAX_ARTISTS = 100
        const val TOP_ARTIST_SEEDS = 5
        private const val PARALLEL = 6

        /** Takes from each list in turn, so every seed and genre gets a share, without duplicates. */
        fun roundRobin(lists: List<List<String>>, max: Int): List<String> {
            val seen = HashSet<String>()
            val result = ArrayList<String>()
            var index = 0
            while (result.size < max && lists.any { index < it.size }) {
                for (list in lists) {
                    if (result.size >= max) break
                    val name = list.getOrNull(index) ?: continue
                    if (seen.add(TrackText.artistKey(name))) result += name
                }
                index++
            }
            return result
        }
    }
}
