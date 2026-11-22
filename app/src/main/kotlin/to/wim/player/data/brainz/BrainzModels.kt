package to.wim.player.data.brainz

import kotlinx.serialization.Serializable

/** An artist in MusicBrainz. [score] is the search match (0..100). */
@Serializable
data class MbArtist(
    val mbid: String,
    val name: String,
    val score: Int? = null,
    val disambiguation: String? = null,
)

/** An artist ListenBrainz finds similar to another one; a higher [score] is more similar. */
@Serializable
data class SimilarArtist(
    val mbid: String,
    val name: String,
    val score: Double,
)

/** A recording (a track) with ListenBrainz's listen and listener counts when known. */
@Serializable
data class BrainzRecording(
    val title: String,
    val artist: String,
    val recordingMbid: String? = null,
    val artistMbids: List<String> = emptyList(),
    val listens: Long? = null,
    val listeners: Long? = null,
    val lengthMillis: Long? = null,
)

/** One play from the user's ListenBrainz history. [listenedAt] is in seconds since 1970. */
data class BrainzListen(
    val artist: String,
    val title: String,
    val listenedAt: Long,
)

/** One of the user's top artists on ListenBrainz. */
@Serializable
data class BrainzTopArtist(
    val name: String,
    val mbid: String? = null,
    val listens: Long = 0,
)

/** A collaborative-filtering recommendation: a recording MBID with ListenBrainz's score. */
@Serializable
data class Recommendation(
    val recordingMbid: String,
    val score: Double,
)

/**
 * A recording ListenBrainz's tag radio finds for a tag. [tagCount] is how many votes the tag has
 * on the recording, its release group or its artist; [popularity] is ListenBrainz's percent.
 */
data class TagRecording(
    val recordingMbid: String,
    val tagCount: Int,
    val popularity: Double,
)
