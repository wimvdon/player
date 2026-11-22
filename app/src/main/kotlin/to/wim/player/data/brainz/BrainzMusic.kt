package to.wim.player.data.brainz

import to.wim.player.data.ListenBrainzSource
import to.wim.player.data.METADATA_BATCH
import to.wim.player.data.MusicBrainzSource
import to.wim.player.data.MusicSource
import to.wim.player.data.cache.KeyValueCache
import to.wim.player.data.model.ArtistHit
import to.wim.player.data.model.PoolTrack
import to.wim.player.logic.Genres
import to.wim.player.logic.TrackText
import to.wim.player.logic.WeightedTag

/** The caches behind [BrainzMusic]. */
class BrainzCaches(
    /** Artist name key to MusicBrainz ID ("" when no artist matched). Kept long: IDs don't change. */
    val artistIds: KeyValueCache<String>,
    /** Similar artists and genre artists (names), 30 days. */
    val artistLists: KeyValueCache<List<String>>,
    /** Artists' top tracks, 30 days. */
    val tracks: KeyValueCache<List<PoolTrack>>,
    /** Artists' tags, 30 days. */
    val tags: KeyValueCache<List<WeightedTag>>,
    /** The user's top tracks (keys) and artists (names), a day. */
    val user: KeyValueCache<List<String>>,
)

/**
 * Music data by artist name, from MusicBrainz (IDs, tags, genre artists) and ListenBrainz
 * (similar artists, top tracks, the user's history). ListenBrainz only knows MusicBrainz IDs, so
 * every name is turned into an ID first; IDs that answers already carry are remembered, so most
 * artists never need a MusicBrainz search. Every answer is cached.
 */
class BrainzMusic(
    private val musicBrainz: MusicBrainzSource,
    private val listenBrainz: ListenBrainzSource,
    private val caches: BrainzCaches,
    /** The user's whole play history, when it has been read; adds to the top recordings. */
    private val history: ListenHistory? = null,
) : MusicSource {
    override suspend fun similarArtists(artist: String, limit: Int): List<String> =
        cached(caches.artistLists, "similar:${TrackText.artistKey(artist)}:$limit") {
            val id = artistId(artist) ?: return@cached emptyList()
            listenBrainz.similarArtists(id, limit).onEach { remember(it.name, it.mbid) }.map { it.name }
        }

    /**
     * Artists for a genre, most typical first. ListenBrainz's tag radio comes first: its recordings
     * carry the tag on themselves, their album or their artist, so they are artists people play in
     * that genre. A MusicBrainz tag search tops up the list when that finds too few; on its own it
     * mostly finds little-known artists with one vote, whose tracks ListenBrainz doesn't know.
     */
    override suspend fun genreArtists(genre: String, limit: Int): List<String> =
        cached(caches.artistLists, "genre2:${TrackText.normalize(genre)}:$limit") {
            val fromRadio = radioArtists(genre)
            val result = LinkedHashMap<String, String>()
            for (artist in fromRadio) {
                if (result.size >= limit) break
                remember(artist.name, artist.mbid)
                result.putIfAbsent(TrackText.artistKey(artist.name), artist.name)
            }
            if (result.size < limit) {
                for (artist in musicBrainz.artistsByTag(genre, limit)) {
                    if (result.size >= limit) break
                    remember(artist.name, artist.mbid)
                    result.putIfAbsent(TrackText.artistKey(artist.name), artist.name)
                }
            }
            result.values.toList()
        }

    private suspend fun radioArtists(genre: String): List<MbArtist> {
        val tagged = listenBrainz.tagRecordings(genre.trim().lowercase(), RADIO_RECORDINGS)
        if (tagged.isEmpty()) return emptyList()
        val metadata = tagged.map { it.recordingMbid }.distinct().chunked(METADATA_BATCH).flatMap { listenBrainz.recordingMetadata(it) }
        return rankGenreArtists(tagged, metadata)
    }

    override suspend fun artistTopTracks(artist: String, limit: Int): List<PoolTrack> =
        cached(caches.tracks, "top:${TrackText.artistKey(artist)}:$limit") {
            val id = artistId(artist) ?: return@cached emptyList()
            // Every track carries the asked-for name: ListenBrainz credits vary ("A feat. B",
            // "Smashing Pumpkins" next to "The Smashing Pumpkins"), and one artist should show
            // under one name and count once for the per-artist rule.
            listenBrainz.topRecordings(id, limit).map { recording -> PoolTrack(recording.title, artist, recording.listeners) }
        }

    override suspend fun artistTags(artist: String): List<WeightedTag> =
        cached(caches.tags, TrackText.artistKey(artist)) {
            val id = artistId(artist) ?: return@cached emptyList()
            musicBrainz.artistTags(id).take(MAX_TAGS)
        }

    override suspend fun genres(): List<String> = Genres.Popular

    // Searches follow what the user types, so they aren't kept.
    override suspend fun searchArtists(query: String): List<ArtistHit> =
        musicBrainz.searchArtists(query, SEARCH_LIMIT).map { ArtistHit(it.name, it.mbid, it.disambiguation) }

    override fun pin(artist: ArtistHit) = caches.artistIds.put(TrackText.artistKey(artist.name), artist.mbid)

    override suspend fun userKnownTrackKeys(username: String): Set<String> {
        val top =
            cached(caches.user, "tracks:${username.trim().lowercase()}") {
                listenBrainz.userTopRecordings(username).map { TrackText.key(it.artist, it.title) }.distinct()
            }
        val played = history?.keys(username).orEmpty()
        return if (played.isEmpty()) top.toSet() else played + top
    }

    override suspend fun userTopArtists(username: String, limit: Int): List<String> =
        cached(caches.user, "artists:${username.trim().lowercase()}:$limit") {
            listenBrainz.userTopArtists(username, limit).onEach { top -> top.mbid?.let { remember(top.name, it) } }.map { it.name }
        }

    /** The artist's MusicBrainz ID: remembered, or the best search match. Null when none fits. */
    suspend fun artistId(name: String): String? {
        val key = TrackText.artistKey(name)
        caches.artistIds.get(key)?.let { return it.ifEmpty { null } }
        val found = BrainzMatch.best(name, musicBrainz.searchArtists(name, MATCH_CANDIDATES))
        caches.artistIds.put(key, found?.mbid.orEmpty())
        return found?.mbid
    }

    private fun remember(name: String, mbid: String) {
        val key = TrackText.artistKey(name)
        if (caches.artistIds.get(key).isNullOrEmpty()) caches.artistIds.put(key, mbid)
    }

    private suspend fun <T> cached(cache: KeyValueCache<T>, key: String, load: suspend () -> T): T {
        cache.get(key)?.let { return it }
        val value = load()
        cache.put(key, value)
        return value
    }

    companion object {
        /** Tag radio recordings read per genre: four metadata lookups. */
        const val RADIO_RECORDINGS = 200

        /**
         * Orders the main artists of tag-radio recordings by how strongly they carry the tag: the
         * sum of the tag votes over their recordings. Ties keep the order ListenBrainz gave.
         */
        fun rankGenreArtists(tagged: List<TagRecording>, metadata: List<BrainzRecording>): List<MbArtist> {
            val byRecording = metadata.associateBy { it.recordingMbid }
            val scores = LinkedHashMap<String, Int>()
            val names = HashMap<String, String>()
            for (recording in tagged) {
                val found = byRecording[recording.recordingMbid] ?: continue
                val mbid = found.artistMbids.firstOrNull() ?: continue
                names.putIfAbsent(mbid, found.artist)
                scores[mbid] = (scores[mbid] ?: 0) + maxOf(recording.tagCount, 1)
            }
            return scores.entries.sortedByDescending { it.value }.map { MbArtist(it.key, names.getValue(it.key)) }
        }

        private const val MAX_TAGS = 10
        private const val SEARCH_LIMIT = 8
        private const val MATCH_CANDIDATES = 5
    }
}

/** Picks which MusicBrainz search result an artist name means. */
object BrainzMatch {
    /** Search scores below this are too loose to trust for a name the user didn't pick. */
    const val MIN_SCORE = 90

    /** The first result whose name is the same (ignoring case and accents), else the first close one. */
    fun best(name: String, results: List<MbArtist>): MbArtist? {
        val key = TrackText.artistKey(name)
        val trusted = results.filter { (it.score ?: 0) >= MIN_SCORE }
        return trusted.firstOrNull { TrackText.artistKey(it.name) == key } ?: trusted.firstOrNull()
    }
}
