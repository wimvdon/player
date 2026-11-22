package to.wim.player.data

import to.wim.player.data.brainz.BrainzListen
import to.wim.player.data.brainz.BrainzRecording
import to.wim.player.data.brainz.BrainzTopArtist
import to.wim.player.data.brainz.MbArtist
import to.wim.player.data.brainz.Recommendation
import to.wim.player.data.brainz.SimilarArtist
import to.wim.player.data.model.ArtistHit
import to.wim.player.data.model.AudioFeatures
import to.wim.player.data.model.PoolTrack
import to.wim.player.data.model.SpotifyTrack
import to.wim.player.logic.WeightedTag

/**
 * The music data a set is made from, by artist name: similar artists, genre artists, top tracks,
 * tags and the user's history. [to.wim.player.data.brainz.BrainzMusic] answers from MusicBrainz
 * and ListenBrainz; tests use a fake.
 */
interface MusicSource {
    /** Names of artists similar to [artist], most similar first. */
    suspend fun similarArtists(artist: String, limit: Int = 30): List<String>

    /** Names of artists tagged with [genre]. */
    suspend fun genreArtists(genre: String, limit: Int = 50): List<String>

    /** The artist's most listened tracks, with listener counts when known. */
    suspend fun artistTopTracks(artist: String, limit: Int = 15): List<PoolTrack>

    /** The artist's genres and tags with their votes. */
    suspend fun artistTags(artist: String): List<WeightedTag>

    /** Genres to offer in the picker. */
    suspend fun genres(): List<String>

    /** Artists matching what the user typed. */
    suspend fun searchArtists(query: String): List<ArtistHit>

    /** Remembers which artist the user picked, so a name shared by several artists means this one. */
    fun pin(artist: ArtistHit) {}

    /** Keys ([to.wim.player.logic.TrackText.key]) of tracks the user has played. */
    suspend fun userKnownTrackKeys(username: String): Set<String>

    /** Names of the user's top artists. */
    suspend fun userTopArtists(username: String, limit: Int = 20): List<String>
}

/** What the app reads from MusicBrainz: artist IDs, genres and tags. */
interface MusicBrainzSource {
    /** Artists matching a name, best match first. */
    suspend fun searchArtists(name: String, limit: Int = 5): List<MbArtist>

    /** An artist's genres and tags with their vote counts, most votes first. */
    suspend fun artistTags(mbid: String): List<WeightedTag>

    /** Artists tagged with [tag], best match first. */
    suspend fun artistsByTag(tag: String, limit: Int = 50): List<MbArtist>

    /** The MusicBrainz artists linked to a Spotify artist ID (usually one). */
    suspend fun artistsForSpotify(spotifyArtistId: String): List<MbArtist>
}

/** The most plays ListenBrainz returns in one answer. */
const val LISTENS_PAGE = 1_000

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

    /**
     * Up to [count] (at most 1 000) of the user's plays, newest first. With [maxTs], only plays
     * before it; with [minTs], the oldest plays after it. Both in seconds; ListenBrainz takes one.
     */
    suspend fun listens(username: String, minTs: Long? = null, maxTs: Long? = null, count: Int = LISTENS_PAGE): List<BrainzListen>

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

/** The Spotify playlists Player makes (Web API). */
interface PlaylistTarget {
    /** Creates a private playlist in the user's account and returns its ID. */
    suspend fun createPlaylist(name: String, description: String): String

    /** Adds [uris] (at most 100) at the end of the playlist. */
    suspend fun addItems(playlistId: String, uris: List<String>)

    /** Starts the playlist on the active Spotify device; false when there is none. */
    suspend fun play(playlistId: String): Boolean
}
