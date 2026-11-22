package to.wim.player.data.brainz

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import to.wim.player.logic.ApiCall
import to.wim.player.logic.BrainzLimits
import to.wim.player.logic.CallLog
import to.wim.player.logic.Service
import to.wim.player.logic.TokenBucket
import java.io.IOException

/** An answer: HTTP status and body. 204, 400 and 404 count as "nothing there", not as errors. */
data class BrainzAnswer(val status: Int, val body: String) {
    val hasData: Boolean get() = status in 200..299 && status != 204 && body.isNotBlank()
}

/**
 * The service wants a user token for this endpoint. ListenBrainz asks this for some endpoints
 * (artist top recordings) since 2026, except for answers it still has cached.
 */
class BrainzAuthRequiredException(service: String) : IOException("$service wants a user token for this call")

/**
 * HTTP for MusicBrainz and ListenBrainz: one call a second ([bucket]), the User-Agent both ask
 * for, and waiting and retrying when they say to slow down. Every call goes into the [log].
 */
class BrainzHttp(
    private val http: OkHttpClient,
    private val service: Service,
    private val userAgent: String,
    private val bucket: TokenBucket,
    private val log: CallLog,
    private val token: suspend () -> String = { "" },
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun get(url: HttpUrl, endpoint: String): BrainzAnswer {
        var attempt = 0
        while (true) {
            bucket.acquire()
            val request =
                Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").apply {
                    val key = runCatching { token() }.getOrDefault("").trim()
                    if (key.isNotEmpty()) header("Authorization", "Token $key")
                }.build()
            val result =
                try {
                    withContext(Dispatchers.IO) {
                        http.newCall(request).execute().use {
                            Triple(it.code, it.body.string(), it.header("X-RateLimit-Reset-In") to it.header("Retry-After"))
                        }
                    }
                } catch (e: IOException) {
                    log.record(ApiCall(now(), service, endpoint, 0, e.javaClass.simpleName))
                    if (attempt++ < MAX_RETRIES) {
                        sleep(BrainzLimits.waitMillis(null, null, attempt))
                        continue
                    }
                    throw e
                }
            val (status, body, headers) = result
            log.record(ApiCall(now(), service, endpoint, status))
            when {
                status in 200..299 || status == 400 || status == 404 -> return BrainzAnswer(status, body)
                (BrainzLimits.isRateLimited(status) || status >= 500) && attempt < MAX_RETRIES -> {
                    attempt++
                    sleep(BrainzLimits.waitMillis(headers.first, headers.second, attempt))
                }
                status == 401 -> throw BrainzAuthRequiredException(service.name)
                else -> throw IOException("${service.name} answered $status")
            }
        }
    }

    companion object {
        private const val MAX_RETRIES = 3

        /** What both services ask apps to send: name, version and a way to reach the maker. */
        fun userAgent(version: String): String = "Player/$version ( https://github.com/wimvdon/player )"
    }
}
