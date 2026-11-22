package to.wim.player.engine

import to.wim.player.data.SpotifyCatalog
import to.wim.player.data.cache.KeyValueCache
import to.wim.player.data.model.Resolution
import to.wim.player.logic.SpotifyCandidate
import to.wim.player.logic.TrackMatcher
import to.wim.player.logic.TrackText

/**
 * Finds the Spotify track for a Last.fm track with one search, and remembers the answer forever,
 * misses included, so no song is searched twice.
 */
class TrackResolver(
    private val catalog: SpotifyCatalog,
    private val cache: KeyValueCache<Resolution>,
) {
    /** The stored answer, without searching. */
    fun cached(artist: String, title: String): Resolution? = cache.get(TrackText.key(artist, title))

    /** The Spotify track, searching when needed. A [Resolution] with a null uri means "not on Spotify". */
    suspend fun resolve(artist: String, title: String): Resolution {
        val key = TrackText.key(artist, title)
        cache.get(key)?.let { return it }
        val results = catalog.searchTrack(artist, title)
        val match =
            TrackMatcher.bestMatch(
                artist,
                title,
                results.map { SpotifyCandidate(it.uri, it.name, it.artists) },
            )
        val track = match?.let { found -> results.first { it.uri == found.uri } }
        val resolution = Resolution(uri = track?.uri, imageUrl = track?.imageUrl, durationMs = track?.durationMs ?: 0)
        cache.put(key, resolution)
        return resolution
    }
}
