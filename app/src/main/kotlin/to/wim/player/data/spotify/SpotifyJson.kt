package to.wim.player.data.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import to.wim.player.data.model.SpotifyTrack

/** The Spotify Web API calls are paused after QUOTA_EXCEEDED, until [untilMillis]. */
class SpotifyPausedException(val untilMillis: Long) : Exception("Spotify paused until $untilMillis")

/** No Spotify sign-in, or Spotify refused the saved one. */
class SpotifySignedOutException : Exception("Not signed in to Spotify")

class SpotifyHttpException(val status: Int, message: String) : Exception(message)

/** A new access token from Spotify's token endpoint. */
data class TokenGrant(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long,
)

/** Reads the parts of Spotify's JSON the app uses. */
object SpotifyJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): JsonObject = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    /** `tracks.items` of a search. */
    fun searchTracks(root: JsonObject): List<SpotifyTrack> = (root["tracks"] as? JsonObject).items().mapNotNull(::track)

    /** `items` of /me/top/tracks. */
    fun topTracks(root: JsonObject): List<SpotifyTrack> = root.items().mapNotNull(::track)

    /** `items[].track` of /me/tracks (saved tracks). */
    fun savedTracks(root: JsonObject): List<SpotifyTrack> = root.items().mapNotNull { (it as? JsonObject)?.get("track")?.let(::track) }

    /** True when a paged list has more after this page. */
    fun hasNext(root: JsonObject): Boolean = root["next"].string() != null

    /** Names from /me/top/artists. */
    fun artistNames(root: JsonObject): List<String> = root.items().mapNotNull { (it as? JsonObject)?.get("name").string() }

    /** `display_name` (or `id`) from /me. */
    fun displayName(root: JsonObject): String? = root["display_name"].string()?.takeIf(String::isNotBlank) ?: root["id"].string()

    fun tokenGrant(root: JsonObject): TokenGrant? {
        val access = root["access_token"].string() ?: return null
        return TokenGrant(access, root["refresh_token"].string(), root["expires_in"].string()?.toLongOrNull() ?: 3600)
    }

    /** The `reason` or message of an error answer, for the call log. */
    fun errorReason(root: JsonObject): String? {
        (root["reason"].string())?.let { return it }
        val error = root["error"]
        if (error is JsonObject) return error["reason"].string() ?: error["message"].string()
        return error.string() ?: root["error_description"].string()
    }

    private fun track(element: JsonElement): SpotifyTrack? {
        val track = element as? JsonObject ?: return null
        val uri = track["uri"].string()?.takeIf { it.startsWith("spotify:track:") } ?: return null
        val name = track["name"].string() ?: return null
        val artists = (track["artists"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("name").string() }
        val images = ((track["album"] as? JsonObject)?.get("images") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        // Images come largest first; the list rows are small, so pick the one closest to 300 px.
        val image = images.minByOrNull { kotlin.math.abs((it["width"].string()?.toIntOrNull() ?: 640) - 300) }?.get("url").string()
        return SpotifyTrack(uri, name, artists, image, track["duration_ms"].string()?.toLongOrNull() ?: 0)
    }

    private fun JsonObject?.items(): List<JsonElement> = (this?.get("items") as? JsonArray).orEmpty()

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull
}
