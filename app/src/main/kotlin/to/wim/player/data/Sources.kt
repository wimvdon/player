package to.wim.player.data

import to.wim.player.data.brainz.BrainzRecording
import to.wim.player.data.brainz.BrainzTopArtist
import to.wim.player.data.brainz.MbArtist
import to.wim.player.data.brainz.Recommendation
import to.wim.player.data.brainz.SimilarArtist
import to.wim.player.data.model.AudioFeatures
import to.wim.player.data.model.LfmArtist
import to.wim.player.data.model.LfmTrack
import to.wim.player.data.model.SpotifyTrack
import to.wim.player.logic.WeightedTag

/** Everything the app reads from Last.fm. Kept behind an interface so tests use a fake. */
interface LastFmSource {
    suspend fun similarArtists(artist: String, limit: Int = 30): List<LfmArtist>

    suspend fun tagTopArtists(tag: String, limit: Int = 50): List<LfmArtist>

    suspend fun tagTopTracks(tag: String, limit: Int = 50): List<LfmTrack>

    suspend fun topTags(): List<String>

    suspend fun artistTopTracks(artist: String, limit: Int = 15): List<LfmTrack>

    suspend fun artistTopTags(artist: String): List<WeightedTag>

    suspend fun searchArtists(query: String): List<LfmArtist>

    /** Keys ([to.wim.player.logic.TrackText.key]) of the user's most scrobbled tracks. */
    suspend fun userTopTrackKeys(username: String): Set<String>

    suspend fun userTopArtists(username: String, limit: Int = 20): List<LfmArtist>
}

/** What the app reads from MusicBrainz: artist IDs, genres and tags. */
interface MusicBrainzSource {
    /** Artists matching a name, best match first. */
    suspend fun searchArtists(name: String, limit: Int = 5): List<MbArtist>

    /** An artist's genres and tags with their vote counts, most votes first. */
    suspend fun artistTags(mbid: String): List<WeightedTag>

    /** The MusicBrainz artists linked to a Spotify artist ID (usually one). */
    suspend fun artistsForSpotify(spotifyArtistId: String): List<MbArtist>
}

/** What the app reads from ListenBrainz. Everything here is public: only a username is needed. */
interface ListenBrainzSource {
    /** Artists listened to in the same sessions as [mbid], most similar first. */
    suspend fun similarArtists(mbid: String, limit: Int = 30): List<SimilarArtist>

    /** An artist's most listened recordings, with listen and listener counts. */
    suspend fun topRecordings(artistMbid: String, limit: Int = 20): List<BrainzRecording>

    /** The user's top artists over the past year. Empty while stats aren't calculated. */
    suspend fun userTopArtists(username: String, limit: Int = 20): List<BrainzTopArtist>

    /** The user's most listened recordings of all time (up to 1 000). */
    suspend fun userTopRecordings(username: String): List<BrainzRecording>

    /** Collaborative-filtering recommendations for the user. Empty until ListenBrainz has made some. */
    suspend fun recommendations(username: String, limit: Int = 100): List<Recommendation>
}

/** What the app reads from the Spotify Web API (searches and the user's library). */
interface SpotifyCatalog {
    /** Up to five search results for a track (Spotify allows at most 10). */
    suspend fun searchTrack(artist: String, title: String): List<SpotifyTrack>

    /** Keys of the user's top tracks (all three time ranges) and saved tracks. */
    suspend fun knownTrackKeys(): Set<String>

    /** Names of the user's top artists. */
    suspend fun topArtists(): List<String>
}

/** Optional tempo and energy from ReccoBeats. Failures give empty results, never errors. */
interface AudioFeatureSource {
    /** Features already stored on the phone (no network). */
    fun cached(spotifyId: String): AudioFeatures?

    /** Looks up features for tracks that aren't stored yet. */
    suspend fun fetch(spotifyIds: List<String>)
}

/** The Spotify playlist Player fills (Web API). */
interface PlaylistTarget {
    /** Creates a private playlist in the user's account and returns its ID. */
    suspend fun createPlaylist(name: String, description: String): String

    /** Replaces everything in the playlist with [uris] (at most 100). */
    suspend fun replaceItems(playlistId: String, uris: List<String>)

    /** Adds [uris] (at most 100) at the end of the playlist. */
    suspend fun addItems(playlistId: String, uris: List<String>)

    /** Starts the playlist on the active Spotify device; false when there is none. */
    suspend fun play(playlistId: String): Boolean
}

/** Remembers which playlist Player reuses, so each set doesn't make a new one. */
interface PlaylistMemory {
    suspend fun playlistId(): String

    suspend fun savePlaylistId(id: String)
}
