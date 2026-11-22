package to.wim.player.data.lastfm

import to.wim.player.data.LastFmSource
import to.wim.player.data.cache.KeyValueCache
import to.wim.player.data.model.LfmArtist
import to.wim.player.data.model.LfmTrack
import to.wim.player.logic.TrackText
import to.wim.player.logic.WeightedTag

/** The caches behind [CachedLastFm]. Tags and similarity change slowly, so most keep 30 days. */
class LastFmCaches(
    val artistLists: KeyValueCache<List<LfmArtist>>,
    val trackLists: KeyValueCache<List<LfmTrack>>,
    val tags: KeyValueCache<List<WeightedTag>>,
    val names: KeyValueCache<List<String>>,
    /** The user's charts (track keys, artist names): refreshed daily. */
    val userKeys: KeyValueCache<List<String>>,
)

/** Wraps a [LastFmSource] so every answer is cached; a repeated generation makes no calls. */
class CachedLastFm(
    private val inner: LastFmSource,
    private val caches: LastFmCaches,
) : LastFmSource {
    override suspend fun similarArtists(artist: String, limit: Int): List<LfmArtist> =
        cached(caches.artistLists, "similar:${TrackText.artistKey(artist)}:$limit") { inner.similarArtists(artist, limit) }

    override suspend fun tagTopArtists(tag: String, limit: Int): List<LfmArtist> =
        cached(caches.artistLists, "tag:${tagKey(tag)}:$limit") { inner.tagTopArtists(tag, limit) }

    override suspend fun tagTopTracks(tag: String, limit: Int): List<LfmTrack> =
        cached(caches.trackLists, "tag:${tagKey(tag)}:$limit") { inner.tagTopTracks(tag, limit) }

    override suspend fun topTags(): List<String> = cached(caches.names, "toptags") { inner.topTags() }

    override suspend fun artistTopTracks(artist: String, limit: Int): List<LfmTrack> =
        cached(caches.trackLists, "artist:${TrackText.artistKey(artist)}:$limit") { inner.artistTopTracks(artist, limit) }

    override suspend fun artistTopTags(artist: String): List<WeightedTag> =
        cached(caches.tags, TrackText.artistKey(artist)) { inner.artistTopTags(artist) }

    // Searches follow what the user types, so they aren't worth keeping.
    override suspend fun searchArtists(query: String): List<LfmArtist> = inner.searchArtists(query)

    override suspend fun userTopTrackKeys(username: String): Set<String> =
        cached(caches.userKeys, "tracks:${username.lowercase()}") { inner.userTopTrackKeys(username).toList() }.toSet()

    override suspend fun userTopArtists(username: String, limit: Int): List<LfmArtist> =
        cached(caches.userKeys, "artists:${username.lowercase()}:$limit") { inner.userTopArtists(username, limit).map { it.name } }
            .map { LfmArtist(it) }

    private fun tagKey(tag: String): String = TrackText.normalize(tag)

    private suspend fun <T> cached(cache: KeyValueCache<T>, key: String, load: suspend () -> T): T {
        cache.get(key)?.let { return it }
        val value = load()
        cache.put(key, value)
        return value
    }
}
