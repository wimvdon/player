package to.wim.player.data.brainz

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import to.wim.player.logic.WeightedTag

private val Lenient = Json { ignoreUnknownKeys = true }

internal fun parseJson(text: String): JsonElement? = runCatching { Lenient.parseToJsonElement(text) }.getOrNull()

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

internal fun JsonObject.double(name: String): Double? = (this[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

internal fun JsonObject.strings(name: String): List<String> =
    (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()

internal fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** Reads MusicBrainz answers (`fmt=json`). Anything unexpected gives an empty result. */
object MusicBrainzJson {
    /** `GET /ws/2/artist?query=…`: best match first, as MusicBrainz orders them. */
    fun artistSearch(text: String): List<MbArtist> =
        ((parseJson(text) as? JsonObject)?.get("artists")).objects().mapNotNull { item ->
            val id = item.string("id") ?: return@mapNotNull null
            val name = item.string("name") ?: return@mapNotNull null
            MbArtist(id, name, item.long("score")?.toInt(), item.string("disambiguation"))
        }

    /**
     * `GET /ws/2/artist/{mbid}?inc=genres+tags`: genres (MusicBrainz's curated list) and free
     * tags merged by name, the higher vote count kept, most votes first. Tags with no votes are left out.
     */
    fun artistTags(text: String): List<WeightedTag> {
        val root = parseJson(text) as? JsonObject ?: return emptyList()
        val counts = LinkedHashMap<String, Int>()
        for (list in listOf("genres", "tags")) {
            root[list].objects().forEach { item ->
                val name = item.string("name")?.lowercase() ?: return@forEach
                val count = item.long("count")?.toInt() ?: 0
                if (count > 0) counts[name] = maxOf(counts[name] ?: 0, count)
            }
        }
        return counts.entries.sortedByDescending { it.value }.map { WeightedTag(it.key, it.value) }
    }

    /** `GET /ws/2/url?resource=…&inc=artist-rels`: the artists linked to a URL (a Spotify artist page). */
    fun urlArtists(text: String): List<MbArtist> =
        ((parseJson(text) as? JsonObject)?.get("relations")).objects().mapNotNull { relation ->
            val artist = relation["artist"] as? JsonObject ?: return@mapNotNull null
            val id = artist.string("id") ?: return@mapNotNull null
            MbArtist(id, artist.string("name") ?: return@mapNotNull null)
        }.distinctBy { it.mbid }
}

/** Reads ListenBrainz answers. Anything unexpected gives an empty result. */
object ListenBrainzJson {
    /**
     * The Labs similar-artists dataset. It answers either with the list itself or wrapped as
     * `[{"type": "…", "data": [...]}]`, so both are read. The artist asked about is left out.
     */
    fun similarArtists(text: String, of: String? = null): List<SimilarArtist> {
        val root = parseJson(text)
        val items = root.objects().flatMap { item -> if (item["data"] is JsonArray) item["data"].objects() else listOf(item) }
        return items.mapNotNull { item ->
            val id = item.string("artist_mbid") ?: return@mapNotNull null
            if (id == of) return@mapNotNull null
            SimilarArtist(id, item.string("name") ?: item.string("artist_name") ?: return@mapNotNull null, item.double("score") ?: 0.0)
        }.distinctBy { it.mbid }.sortedByDescending { it.score }
    }

    /** `GET /1/popularity/top-recordings-for-artist/{mbid}`: most listened first. */
    fun topRecordings(text: String): List<BrainzRecording> {
        val root = parseJson(text)
        val items = if (root is JsonArray) root.objects() else (root as? JsonObject)?.get("payload").objects()
        return items.mapNotNull { recording(it, listensKey = "total_listen_count", listenersKey = "total_user_count") }
    }

    /** `GET /1/stats/user/{name}/artists`. Empty when stats aren't calculated yet (HTTP 204). */
    fun userTopArtists(text: String): List<BrainzTopArtist> =
        payload(text)?.get("artists").objects().mapNotNull { item ->
            val name = item.string("artist_name") ?: return@mapNotNull null
            BrainzTopArtist(name, item.string("artist_mbid") ?: item.strings("artist_mbids").firstOrNull(), item.long("listen_count") ?: 0)
        }

    /** `GET /1/stats/user/{name}/recordings`. */
    fun userTopRecordings(text: String): List<BrainzRecording> =
        payload(text)?.get("recordings").objects().mapNotNull { recording(it, listensKey = "listen_count", listenersKey = null) }

    /** Total pages are not given; this tells whether another page may follow. */
    fun totalRecordingCount(text: String): Int = payload(text)?.long("total_recording_count")?.toInt() ?: 0

    /** `GET /1/cf/recommendation/user/{name}/recording`: best first. Empty when there are none yet. */
    fun recommendations(text: String): List<Recommendation> =
        payload(text)?.get("mbids").objects().mapNotNull { item ->
            Recommendation(item.string("recording_mbid") ?: return@mapNotNull null, item.double("score") ?: 0.0)
        }

    private fun payload(text: String): JsonObject? = (parseJson(text) as? JsonObject)?.get("payload") as? JsonObject

    private fun recording(item: JsonObject, listensKey: String, listenersKey: String?): BrainzRecording? {
        val title = item.string("recording_name") ?: item.string("track_name") ?: return null
        val artist = item.string("artist_name") ?: item.string("artist_credit_name") ?: return null
        return BrainzRecording(
            title = title,
            artist = artist,
            recordingMbid = item.string("recording_mbid"),
            artistMbids = item.strings("artist_mbids"),
            listens = item.long(listensKey),
            listeners = listenersKey?.let { item.long(it) },
            lengthMillis = item.long("length"),
        )
    }
}
