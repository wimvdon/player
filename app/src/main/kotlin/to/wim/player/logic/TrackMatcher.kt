package to.wim.player.logic

/** One Spotify search result, as far as matching needs it. */
data class SpotifyCandidate(
    val uri: String,
    val name: String,
    val artists: List<String>,
)

/**
 * Picks the Spotify track that is the song Last.fm named, or none. A wrong song in the queue is
 * worse than a missing one, so anything uncertain is skipped.
 */
object TrackMatcher {
    fun bestMatch(artist: String, title: String, candidates: List<SpotifyCandidate>): SpotifyCandidate? {
        val wantedArtist = TrackText.artistKey(artist)
        val wantedTitle = TrackText.baseTitle(title)
        if (wantedArtist.isEmpty() || wantedTitle.isEmpty()) return null
        val wantsAlternate = TrackText.isAlternateVersion(title)
        return candidates
            .filter { candidate -> candidate.artists.any { TrackText.artistKey(it) == wantedArtist } }
            .filter { candidate -> TrackText.baseTitle(candidate.name) == wantedTitle }
            .filter { candidate -> wantsAlternate || !TrackText.isAlternateVersion(candidate.name) }
            .minByOrNull { it.name.length }
    }
}
