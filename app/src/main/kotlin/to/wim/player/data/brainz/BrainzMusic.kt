package to.wim.player.data.brainz

import to.wim.player.data.ListenBrainzSource
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

    override suspend fun genreArtists(genre: String, limit: Int): List<String> =
        cached(caches.artistLists, "genre:${TrackText.normalize(genre)}:$limit") {
            musicBrainz.artistsByTag(genre, limit).onEach { remember(it.name, it.mbid) }.map { it.name }
        }

    override suspend fun artistTopTracks(artist: String, limit: Int): List<PoolTrack> =
        cached(caches.tracks, "top:${TrackText.artistKey(artist)}:$limit") {
            val id = artistId(artist) ?: return@cached emptyList()
            // Credits like "A feat. B" would count as another artist (more lookups, and the
            // three-per-artist rule wouldn't see them), so those tracks carry the asked-for name.
            val key = TrackText.artistKey(artist)
            listenBrainz.topRecordings(id, limit).map { recording ->
                val name = if (TrackText.artistKey(recording.artist) == key) recording.artist else artist
                PoolTrack(recording.title, name, recording.listeners)
            }
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
