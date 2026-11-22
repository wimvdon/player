package to.wim.player.data.reccobeats

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import to.wim.player.data.AudioFeatureSource
import to.wim.player.data.cache.KeyValueCache
import to.wim.player.data.model.AudioFeatures
import to.wim.player.logic.ApiCall
import to.wim.player.logic.CallLog
import to.wim.player.logic.Service

/** Reads ReccoBeats' audio-features answer: Spotify ID (from `href`) to energy and tempo. */
object ReccoBeatsJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun features(text: String): Map<String, AudioFeatures> {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyMap()
        val content = root["content"] as? JsonArray ?: return emptyMap()
        return content.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val href = (item["href"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val id = href.substringAfter("/track/", "").substringBefore('?').takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val energy = (item["energy"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val tempo = (item["tempo"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            id to AudioFeatures(energy, tempo)
        }.toMap()
    }
}

/**
 * Optional audio features from ReccoBeats (free, no key). Anything that goes wrong is ignored:
 * the mood then comes from MusicBrainz tags. Tracks it doesn't know are remembered as misses.
 */
class ReccoBeatsClient(
    private val http: OkHttpClient,
    private val cache: KeyValueCache<AudioFeatures>,
    private val misses: KeyValueCache<Boolean>,
    private val enabled: suspend () -> Boolean,
    private val log: CallLog,
    private val now: () -> Long = System::currentTimeMillis,
) : AudioFeatureSource {
    override fun cached(spotifyId: String): AudioFeatures? = cache.get(spotifyId)

    override suspend fun fetch(spotifyIds: List<String>) {
        if (!runCatching { enabled() }.getOrDefault(false)) return
        val wanted = spotifyIds.distinct().filter { cache.get(it) == null && misses.get(it) == null }
        for (batch in wanted.chunked(BATCH)) {
            val url = BASE.toHttpUrl().newBuilder().addQueryParameter("ids", batch.joinToString(",")).build()
            val result =
                runCatching {
                    withContext(Dispatchers.IO) {
                        http.newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body.string() }
                    }
                }
            val (status, body) = result.getOrElse {
                log.record(ApiCall(now(), Service.RECCOBEATS, "GET /v1/audio-features", 0, it.javaClass.simpleName))
                return
            }
            log.record(ApiCall(now(), Service.RECCOBEATS, "GET /v1/audio-features", status))
            if (status !in 200..299) return
            val found = ReccoBeatsJson.features(body)
            found.forEach { (id, features) -> cache.put(id, features) }
            batch.filter { it !in found }.forEach { misses.put(it, true) }
        }
    }

    companion object {
        private const val BASE = "https://api.reccobeats.com/v1/audio-features"
        private const val BATCH = 40
    }
}
