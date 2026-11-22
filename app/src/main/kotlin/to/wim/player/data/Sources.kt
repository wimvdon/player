package to.wim.player.data

import kotlinx.coroutines.flow.StateFlow
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

/** What Spotify is playing, from App Remote. */
data class PlayerSnapshot(
    val trackUri: String?,
    val trackName: String?,
    val artistName: String?,
    val isPaused: Boolean,
)

/** Why the player can't be used. Shown to the user, so each has its own message. */
enum class PlayerProblem {
    NOT_INSTALLED,
    NOT_LOGGED_IN,
    NOT_AUTHORIZED,
    OFFLINE,
    DISCONNECTED,
    QUEUE_FAILED,
}

class PlayerException(val problem: PlayerProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/** Plays and queues tracks in the Spotify app. App Remote first, the Web API as fallback. */
interface SpotifyPlayer {
    val state: StateFlow<PlayerSnapshot?>

    /** Connects to the Spotify app, or throws [PlayerException]. */
    suspend fun connect()

    suspend fun queue(uri: String)

    suspend fun play(uri: String)

    fun disconnect()
}
