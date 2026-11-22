package to.wim.player.data.lastfm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import to.wim.player.data.model.LfmArtist
import to.wim.player.data.model.LfmTrack
import to.wim.player.logic.TrackText
import to.wim.player.logic.WeightedTag

/** Last.fm's error codes the app reacts to (https://www.last.fm/api/errorcodes). */
object LastFmErrors {
    const val INVALID_PARAMETERS = 6
    const val INVALID_API_KEY = 10
    const val OPERATION_FAILED = 8
    const val SERVICE_OFFLINE = 11
    const val TEMPORARILY_UNAVAILABLE = 16
    const val SUSPENDED_API_KEY = 26
    const val RATE_LIMIT = 29
}

class LastFmException(val code: Int, message: String) : Exception(message) {
    val isKeyProblem: Boolean get() = code == LastFmErrors.INVALID_API_KEY || code == LastFmErrors.SUSPENDED_API_KEY
    val isRetryable: Boolean
        get() = code == LastFmErrors.RATE_LIMIT || code == LastFmErrors.OPERATION_FAILED ||
            code == LastFmErrors.SERVICE_OFFLINE || code == LastFmErrors.TEMPORARILY_UNAVAILABLE
}

/**
 * Reads Last.fm's JSON. Last.fm turns XML into JSON loosely: numbers are often strings and a list
 * of one item can be a single object, so every reader here is forgiving.
 */
object LastFmJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): JsonObject = json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())

    /** The error in a response, or null when there is none. */
    fun error(root: JsonObject): LastFmException? {
        val code = root["error"].int() ?: return null
        return LastFmException(code, root["message"].string() ?: "Last.fm error $code")
    }

    fun similarArtists(root: JsonObject): List<LfmArtist> =
        root.obj("similarartists").list("artist").mapNotNull { artist(it) }

    fun tagTopArtists(root: JsonObject): List<LfmArtist> = root.obj("topartists").list("artist").mapNotNull { artist(it) }

    fun userTopArtists(root: JsonObject): List<LfmArtist> = root.obj("topartists").list("artist").mapNotNull { artist(it) }

    fun searchArtists(root: JsonObject): List<LfmArtist> =
        root.obj("results").obj("artistmatches").list("artist").mapNotNull { artist(it) }

    fun tagTopTracks(root: JsonObject): List<LfmTrack> = root.obj("tracks").list("track").mapNotNull { track(it) }

    fun artistTopTracks(root: JsonObject): List<LfmTrack> = root.obj("toptracks").list("track").mapNotNull { track(it) }

    fun userTopTrackKeys(root: JsonObject): Set<String> =
        root.obj("toptracks").list("track").mapNotNull { track(it) }.map { TrackText.key(it.artist, it.title) }.toSet()

    /** Total pages of a user chart, for paging. */
    fun totalPages(root: JsonObject, container: String): Int = root.obj(container).obj("@attr")["totalPages"].int() ?: 1

    fun topTags(root: JsonObject): List<String> =
        root.obj("toptags").list("tag").mapNotNull { (it as? JsonObject)?.get("name").string()?.trim()?.takeIf(String::isNotEmpty) }

    fun artistTopTags(root: JsonObject): List<WeightedTag> =
        root.obj("toptags").list("tag").mapNotNull { element ->
            val tag = element as? JsonObject ?: return@mapNotNull null
            val name = tag["name"].string()?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            WeightedTag(name, tag["count"].int() ?: 0)
        }

    private fun artist(element: JsonElement): LfmArtist? {
        val artist = element as? JsonObject ?: return null
        val name = artist["name"].string()?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return LfmArtist(name = name, match = artist["match"].double(), listeners = artist["listeners"].long())
    }

    private fun track(element: JsonElement): LfmTrack? {
        val track = element as? JsonObject ?: return null
        val title = track["name"].string()?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val artistName =
            when (val artist = track["artist"]) {
                is JsonObject -> artist["name"].string() ?: artist["#text"].string()
                else -> artist.string()
            }?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return LfmTrack(title = title, artist = artistName, listeners = track["listeners"].long())
    }

    private fun JsonObject.obj(name: String): JsonObject = this[name] as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.list(name: String): List<JsonElement> =
        when (val value = this[name]) {
            is JsonArray -> value
            is JsonObject -> listOf(value)
            else -> emptyList()
        }

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.long(): Long? = string()?.trim()?.toLongOrNull()

    private fun JsonElement?.int(): Int? = string()?.trim()?.toIntOrNull()

    private fun JsonElement?.double(): Double? = string()?.trim()?.toDoubleOrNull()
}
