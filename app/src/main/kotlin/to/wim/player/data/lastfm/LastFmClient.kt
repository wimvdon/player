package to.wim.player.data.lastfm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import to.wim.player.data.LastFmSource
import to.wim.player.data.model.LfmArtist
import to.wim.player.data.model.LfmTrack
import to.wim.player.logic.ApiCall
import to.wim.player.logic.Backoff
import to.wim.player.logic.CallLog
import to.wim.player.logic.Service
import to.wim.player.logic.TokenBucket
import to.wim.player.logic.WeightedTag
import java.io.IOException

/** Last.fm over HTTP, at most 4 calls a second, with backoff on rate limits. No caching here. */
class LastFmClient(
    private val http: OkHttpClient,
    private val apiKey: suspend () -> String,
    private val log: CallLog,
    private val bucket: TokenBucket,
    private val now: () -> Long = System::currentTimeMillis,
) : LastFmSource {
    override suspend fun similarArtists(artist: String, limit: Int): List<LfmArtist> =
        LastFmJson.similarArtists(call("artist.getSimilar", "artist" to artist, "limit" to "$limit", "autocorrect" to "1"))

    override suspend fun tagTopArtists(tag: String, limit: Int): List<LfmArtist> =
        LastFmJson.tagTopArtists(call("tag.getTopArtists", "tag" to tag, "limit" to "$limit"))

    override suspend fun tagTopTracks(tag: String, limit: Int): List<LfmTrack> =
        LastFmJson.tagTopTracks(call("tag.getTopTracks", "tag" to tag, "limit" to "$limit"))

    override suspend fun topTags(): List<String> = LastFmJson.topTags(call("tag.getTopTags"))

    override suspend fun artistTopTracks(artist: String, limit: Int): List<LfmTrack> =
        LastFmJson.artistTopTracks(call("artist.getTopTracks", "artist" to artist, "limit" to "$limit", "autocorrect" to "1"))

    override suspend fun artistTopTags(artist: String): List<WeightedTag> =
        LastFmJson.artistTopTags(call("artist.getTopTags", "artist" to artist, "autocorrect" to "1")).take(10)

    override suspend fun searchArtists(query: String): List<LfmArtist> =
        LastFmJson.searchArtists(call("artist.search", "artist" to query, "limit" to "10"))

    override suspend fun userTopTrackKeys(username: String): Set<String> {
        val keys = HashSet<String>()
        var page = 1
        do {
            val root = call("user.getTopTracks", "user" to username, "period" to "overall", "limit" to "1000", "page" to "$page")
            keys += LastFmJson.userTopTrackKeys(root)
            val pages = LastFmJson.totalPages(root, "toptracks")
            page++
        } while (page <= minOf(pages, USER_TRACK_PAGES))
        return keys
    }

    override suspend fun userTopArtists(username: String, limit: Int): List<LfmArtist> =
        LastFmJson.userTopArtists(call("user.getTopArtists", "user" to username, "period" to "12month", "limit" to "$limit"))

    private suspend fun call(method: String, vararg params: Pair<String, String>): JsonObject {
        val key = apiKey()
        if (key.isBlank()) throw LastFmException(LastFmErrors.INVALID_API_KEY, "No Last.fm API key")
        val url =
            BASE.toHttpUrl().newBuilder().apply {
                addQueryParameter("method", method)
                params.forEach { (name, value) -> addQueryParameter(name, value) }
                addQueryParameter("api_key", key)
                addQueryParameter("format", "json")
            }.build()
        var attempt = 0
        while (true) {
            bucket.acquire()
            val (status, body) =
                try {
                    withContext(Dispatchers.IO) {
                        http.newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body.string() }
                    }
                } catch (e: IOException) {
                    log.record(ApiCall(now(), Service.LAST_FM, method, 0, e.javaClass.simpleName))
                    if (attempt++ < MAX_RETRIES) {
                        delay(Backoff.delayMillis(attempt))
                        continue
                    }
                    throw e
                }
            val root = runCatching { LastFmJson.parse(body) }.getOrElse { JsonObject(emptyMap()) }
            val error = LastFmJson.error(root)
            log.record(ApiCall(now(), Service.LAST_FM, method, status, error?.let { "${it.code} ${it.message}" }))
            when {
                error == null && status in 200..299 -> return root
                error?.code == LastFmErrors.INVALID_PARAMETERS -> return JsonObject(emptyMap())
                error != null && error.isRetryable && attempt < MAX_RETRIES -> {
                    attempt++
                    delay(Backoff.delayMillis(attempt))
                }
                error != null -> throw error
                status >= 500 && attempt < MAX_RETRIES -> {
                    attempt++
                    delay(Backoff.delayMillis(attempt))
                }
                else -> throw IOException("Last.fm answered $status")
            }
        }
    }

    companion object {
        private const val BASE = "https://ws.audioscrobbler.com/2.0/"
        private const val MAX_RETRIES = 4

        /** At most 3 000 scrobbled tracks are read to spot songs the user knows. */
        private const val USER_TRACK_PAGES = 3

        /** Last.fm asks for no more than about five calls a second; the app stays at four. */
        const val CALLS_PER_SECOND = 4.0
    }
}
