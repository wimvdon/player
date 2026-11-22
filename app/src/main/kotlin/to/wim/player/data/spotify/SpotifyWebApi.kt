package to.wim.player.data.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import to.wim.player.data.PlaylistTarget
import to.wim.player.data.SpotifyCatalog
import to.wim.player.data.cache.KeyValueCache
import to.wim.player.data.model.SpotifyTrack
import to.wim.player.logic.ApiCall
import to.wim.player.logic.CallLog
import to.wim.player.logic.Service
import to.wim.player.logic.SpotifyLimit
import to.wim.player.logic.SpotifyLimits
import to.wim.player.logic.TokenBucket
import to.wim.player.logic.TrackText
import java.io.IOException

/** Where the Spotify sign-in and the quota pause are stored (encrypted, on this phone only). */
interface SpotifyAccount {
    suspend fun clientId(): String

    suspend fun accessToken(): String

    suspend fun refreshToken(): String

    suspend fun expiresAtMillis(): Long

    suspend fun saveGrant(grant: TokenGrant, nowMillis: Long)

    suspend fun signOut()

    suspend fun pausedUntilMillis(): Long

    suspend fun pauseUntil(millis: Long)
}

/**
 * The Spotify Web API, used as little as possible: Development Mode apps have a small,
 * unpublished quota. Every call is logged; after QUOTA_EXCEEDED all calls stop until the pause
 * ends. Only endpoints still open to new apps are used (no recommendations, audio features,
 * related artists, artist top tracks, browse or batch track lookups; search limit at most 10).
 */
class SpotifyWebApi(
    private val http: OkHttpClient,
    private val account: SpotifyAccount,
    private val log: CallLog,
    private val bucket: TokenBucket,
    private val now: () -> Long = System::currentTimeMillis,
) : SpotifyCatalog, PlaylistTarget {
    private val refreshLock = Mutex()

    override suspend fun searchTrack(artist: String, title: String): List<SpotifyTrack> {
        val query = "track:\"${clean(title)}\" artist:\"${clean(artist)}\""
        return SpotifyJson.searchTracks(get("/search", "q" to query, "type" to "track", "limit" to "$SEARCH_LIMIT"))
    }

    override suspend fun knownTrackKeys(): Set<String> {
        val keys = HashSet<String>()
        for (range in listOf("short_term", "medium_term", "long_term")) {
            SpotifyJson.topTracks(get("/me/top/tracks", "time_range" to range, "limit" to "50")).forEach { keys += it.key() }
        }
        var offset = 0
        while (offset < SAVED_TRACKS_MAX) {
            val page = get("/me/tracks", "limit" to "50", "offset" to "$offset")
            SpotifyJson.savedTracks(page).forEach { keys += it.key() }
            if (!SpotifyJson.hasNext(page)) break
            offset += 50
        }
        return keys
    }

    override suspend fun topArtists(): List<String> = SpotifyJson.artistNames(get("/me/top/artists", "time_range" to "medium_term", "limit" to "20"))

    /** The signed-in user's name, shown in the settings. */
    suspend fun displayName(): String? = SpotifyJson.displayName(get("/me"))

    override suspend fun createPlaylist(name: String, description: String): String {
        val body =
            buildJsonObject {
                put("name", name)
                put("description", description)
                put("public", false)
            }
        val root = playlistCall("POST", "/me/playlists", body)
        return SpotifyJson.playlistId(root) ?: throw SpotifyHttpException(0, "No playlist ID in Spotify's answer")
    }

    override suspend fun addItems(playlistId: String, uris: List<String>) {
        playlistCall("POST", "/playlists/$playlistId/items", uriBody(uris))
    }

    override suspend fun play(playlistId: String): Boolean =
        try {
            send("PUT", "/me/player/play", body = buildJsonObject { put("context_uri", "spotify:playlist:$playlistId") })
            true
        } catch (e: SpotifyHttpException) {
            // 404: no active device; 403: not allowed (for example no Premium). The user presses play instead.
            false
        }

    private fun uriBody(uris: List<String>): JsonObject = buildJsonObject { putJsonArray("uris") { uris.take(MAX_ITEMS).forEach { add(it) } } }

    /**
     * A playlist change. A 403 here means the sign-in lacks the playlist scopes (a sign-in from
     * version 0.1.0), so Player signs out and the settings ask to connect again.
     */
    private suspend fun playlistCall(method: String, path: String, body: JsonObject): JsonObject =
        try {
            send(method, path, body = body)
        } catch (e: SpotifyHttpException) {
            if (e.status == 403) {
                account.signOut()
                throw SpotifySignedOutException()
            }
            throw e
        }

    /** Exchanges a refresh token for a new access token (PKCE: no client secret). */
    suspend fun refresh(): String =
        refreshLock.withLock {
            val current = account.accessToken()
            if (current.isNotEmpty() && account.expiresAtMillis() - EXPIRY_MARGIN_MILLIS > now()) return current
            val refreshToken = account.refreshToken()
            if (refreshToken.isEmpty()) throw SpotifySignedOutException()
            val form =
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", account.clientId())
                    .build()
            val request = Request.Builder().url(TOKEN_URL).post(form).build()
            val (status, body) = execute(request, "POST /api/token")
            val root = SpotifyJson.parse(body)
            log.record(ApiCall(now(), Service.SPOTIFY, "POST /api/token", status, SpotifyJson.errorReason(root)))
            val grant = SpotifyJson.tokenGrant(root)
            when {
                grant != null -> {
                    account.saveGrant(grant, now())
                    grant.accessToken
                }
                status == 400 || status == 401 -> {
                    account.signOut()
                    throw SpotifySignedOutException()
                }
                else -> throw SpotifyHttpException(status, "Token refresh failed ($status)")
            }
        }

    private suspend fun validToken(): String {
        val token = account.accessToken()
        if (token.isNotEmpty() && account.expiresAtMillis() - EXPIRY_MARGIN_MILLIS > now()) return token
        return refresh()
    }

    private suspend fun get(path: String, vararg query: Pair<String, String>): JsonObject = send("GET", path, *query)

    private suspend fun send(
        method: String,
        path: String,
        vararg query: Pair<String, String>,
        body: JsonObject? = null,
    ): JsonObject {
        var retried401 = false
        var retried429 = false
        while (true) {
            val pausedUntil = account.pausedUntilMillis()
            if (pausedUntil > now()) throw SpotifyPausedException(pausedUntil)
            val token = validToken()
            val url = (API + path).toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
            val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
            val requestBody = (body?.toString() ?: "").toRequestBody(if (body != null) JSON else null)
            when (method) {
                "POST" -> builder.post(requestBody)
                "PUT" -> builder.put(requestBody)
                else -> builder.get()
            }
            bucket.acquire()
            val endpoint = "$method $path"
            val response = executeWithHeaders(builder.build(), endpoint)
            val root = SpotifyJson.parse(response.body)
            log.record(ApiCall(now(), Service.SPOTIFY, endpoint, response.status, SpotifyJson.errorReason(root)))
            when {
                response.status in 200..299 -> return root
                response.status == 401 && !retried401 -> {
                    retried401 = true
                    account.saveGrant(TokenGrant("", null, 0), now())
                }
                response.status == 429 -> {
                    when (val limit = SpotifyLimits.classify(response.body, response.retryAfter, now())) {
                        is SpotifyLimit.QuotaExceeded -> {
                            account.pauseUntil(limit.untilMillis)
                            throw SpotifyPausedException(limit.untilMillis)
                        }
                        is SpotifyLimit.RetryAfter -> {
                            if (retried429 || limit.waitMillis > SpotifyLimits.MAX_INLINE_WAIT_MILLIS) {
                                throw SpotifyHttpException(429, "Spotify rate limit")
                            }
                            retried429 = true
                            delay(limit.waitMillis)
                        }
                    }
                }
                response.status == 401 -> {
                    account.signOut()
                    throw SpotifySignedOutException()
                }
                else -> throw SpotifyHttpException(response.status, SpotifyJson.errorReason(root) ?: "Spotify answered ${response.status}")
            }
        }
    }

    private class Answer(val status: Int, val body: String, val retryAfter: String?)

    private suspend fun execute(request: Request, endpoint: String): Pair<Int, String> =
        executeWithHeaders(request, endpoint).let { it.status to it.body }

    private suspend fun executeWithHeaders(request: Request, endpoint: String): Answer =
        try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { Answer(it.code, it.body.string(), it.header("Retry-After")) }
            }
        } catch (e: IOException) {
            log.record(ApiCall(now(), Service.SPOTIFY, endpoint, 0, e.javaClass.simpleName))
            throw e
        }

    private fun SpotifyTrack.key(): String = TrackText.key(artists.firstOrNull().orEmpty(), name)

    private fun clean(text: String): String = text.replace("\"", "").trim()

    companion object {
        private const val API = "https://api.spotify.com/v1"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val EXPIRY_MARGIN_MILLIS = 60_000L

        /** Spotify answers 400 above 10; 5 is plenty to find the right recording. */
        const val SEARCH_LIMIT = 5

        /** Saved tracks read to spot songs the user knows (5 calls, once a day). */
        private const val SAVED_TRACKS_MAX = 250

        /** Spotify takes at most 100 tracks per playlist call. */
        const val MAX_ITEMS = 100

        private val JSON = "application/json".toMediaType()

        /** Spacing for Web API calls, to stay well inside the quota. */
        const val CALLS_PER_SECOND = 3.0
    }
}

/** Caches the user's library lookups for a day, so they cost a few calls a day at most. */
class CachedSpotify(
    private val inner: SpotifyCatalog,
    private val daily: KeyValueCache<List<String>>,
) : SpotifyCatalog {
    override suspend fun searchTrack(artist: String, title: String): List<SpotifyTrack> = inner.searchTrack(artist, title)

    override suspend fun knownTrackKeys(): Set<String> {
        daily.get("known")?.let { return it.toSet() }
        val keys = inner.knownTrackKeys()
        daily.put("known", keys.toList())
        return keys
    }

    override suspend fun topArtists(): List<String> {
        daily.get("topArtists")?.let { return it }
        val names = inner.topArtists()
        daily.put("topArtists", names)
        return names
    }
}
