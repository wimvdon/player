package to.wim.player.data.model

import kotlinx.serialization.Serializable
import to.wim.player.logic.Ordering

/** An artist from Last.fm. [match] is the similarity (0..1) for similar artists. */
@Serializable
data class LfmArtist(
    val name: String,
    val match: Double? = null,
    val listeners: Long? = null,
)

/** A track from Last.fm. [listeners] is missing for tag charts. */
@Serializable
data class LfmTrack(
    val title: String,
    val artist: String,
    val listeners: Long? = null,
)

/** A Spotify track from a search. */
@Serializable
data class SpotifyTrack(
    val uri: String,
    val name: String,
    val artists: List<String>,
    val imageUrl: String? = null,
    val durationMs: Long = 0,
) {
    val id: String get() = uri.substringAfterLast(':')
}

/** The Spotify track a Last.fm track resolved to, or a miss ([uri] null) so it isn't searched again. */
@Serializable
data class Resolution(
    val uri: String? = null,
    val imageUrl: String? = null,
)

/** ReccoBeats audio features for one Spotify track. */
@Serializable
data class AudioFeatures(
    val energy: Double,
    val tempo: Double,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How the queue is filled. */
enum class QueueMode {
    /** Keeps [to.wim.player.logic.RollingQueue.AHEAD] tracks queued while you listen. */
    ROLLING,

    /** Adds every track now, in batches. */
    ALL_NOW,
}

/** Settings Android backs up (they'd be wanted on a new phone). */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val lastFmUsername: String = "",
    val queueMode: QueueMode = QueueMode.ROLLING,
    val ordering: Ordering = Ordering.MOOD_CURVE,
    val useReccoBeats: Boolean = false,
    val mood: Float = 0.5f,
    val familiarity: Float = 0.5f,
    val length: Int = 250,
    val selectedGenres: List<String> = emptyList(),
    val recentGenres: List<String> = emptyList(),
    val selectedArtists: List<String> = emptyList(),
)

/** Keys and sign-in, kept on this phone only (encrypted). */
data class Secrets(
    val spotifyClientId: String = "",
    val lastFmApiKey: String = "",
    val spotifyAccessToken: String = "",
    val spotifyRefreshToken: String = "",
    val spotifyExpiresAtMillis: Long = 0,
    val spotifyUserName: String = "",
    /** All Spotify Web API calls wait until this time after QUOTA_EXCEEDED (0 = not paused). */
    val spotifyPausedUntilMillis: Long = 0,
) {
    val spotifySignedIn: Boolean get() = spotifyRefreshToken.isNotEmpty() || spotifyAccessToken.isNotEmpty()
}
