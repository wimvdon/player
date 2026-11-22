package to.wim.player

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import to.wim.player.data.cache.JsonFileCache
import to.wim.player.data.lastfm.CachedLastFm
import to.wim.player.data.lastfm.LastFmCaches
import to.wim.player.data.lastfm.LastFmClient
import to.wim.player.data.model.AudioFeatures
import to.wim.player.data.model.LfmArtist
import to.wim.player.data.model.LfmTrack
import to.wim.player.data.model.Resolution
import to.wim.player.data.reccobeats.ReccoBeatsClient
import to.wim.player.data.spotify.CachedSpotify
import to.wim.player.data.spotify.SpotifyWebApi
import to.wim.player.data.store.SecretStore
import to.wim.player.data.store.SettingsStore
import to.wim.player.engine.GenerationEngine
import to.wim.player.engine.GenerationResult
import to.wim.player.engine.QueueController
import to.wim.player.engine.TrackResolver
import to.wim.player.logic.CallLog
import to.wim.player.logic.Setup
import to.wim.player.logic.TokenBucket
import to.wim.player.logic.WeightedTag
import java.io.File
import java.util.concurrent.TimeUnit

/** Creates the app's single instances (no dependency-injection library needed at this size). */
class PlayerApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
            .diskCache { DiskCache.Builder().directory(File(cacheDir, "images").toOkioPath()).maxSizeBytes(50L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
}

class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val callLog = CallLog()
    val settings = SettingsStore(app)
    val secrets = SecretStore(app)
    val redirectUri: String = Setup.redirectUri(app.packageName)

    val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

    private val cacheDir = File(app.noBackupFilesDir, "cache")
    private val now: () -> Long = System::currentTimeMillis

    private fun <T> cache(name: String, serializer: kotlinx.serialization.KSerializer<T>, ttlDays: Long?) =
        JsonFileCache(File(cacheDir, "$name.json"), serializer, ttlDays?.let { TimeUnit.DAYS.toMillis(it) }, now)

    // Tags and similarity change slowly: 30 days. The user's charts: a day. Spotify matches: forever.
    val lastFmArtistCache = cache("lastfm_artists", ListSerializer(LfmArtist.serializer()), 30)
    val lastFmTrackCache = cache("lastfm_tracks", ListSerializer(LfmTrack.serializer()), 30)
    val lastFmTagCache = cache("lastfm_tags", ListSerializer(WeightedTag.serializer()), 30)
    val lastFmNameCache = cache("lastfm_names", ListSerializer(String.serializer()), 30)
    val userChartCache = cache("user_charts", ListSerializer(String.serializer()), 1)
    val spotifyDailyCache = cache("spotify_library", ListSerializer(String.serializer()), 1)
    val resolutionCache = cache("spotify_matches", Resolution.serializer(), null)
    val featureCache = cache("audio_features", AudioFeatures.serializer(), null)
    val featureMissCache = cache("audio_features_missing", Boolean.serializer(), 30)

    /** Every cache, for the debug screen and for saving. */
    val caches: List<Pair<String, JsonFileCache<*>>> =
        listOf(
            "lastfm_artists" to lastFmArtistCache,
            "lastfm_tracks" to lastFmTrackCache,
            "lastfm_tags" to lastFmTagCache,
            "lastfm_names" to lastFmNameCache,
            "user_charts" to userChartCache,
            "spotify_library" to spotifyDailyCache,
            "spotify_matches" to resolutionCache,
            "audio_features" to featureCache,
            "audio_features_missing" to featureMissCache,
        )

    private suspend fun sleep(millis: Long) = delay(millis)

    val lastFm =
        CachedLastFm(
            LastFmClient(
                http = http,
                apiKey = { secrets.current().lastFmApiKey },
                log = callLog,
                bucket = TokenBucket(LastFmClient.CALLS_PER_SECOND, LastFmClient.CALLS_PER_SECOND, now, ::sleep),
            ),
            LastFmCaches(lastFmArtistCache, lastFmTrackCache, lastFmTagCache, lastFmNameCache, userChartCache),
        )

    val webApi = SpotifyWebApi(http, secrets, callLog, TokenBucket(SpotifyWebApi.CALLS_PER_SECOND, 1.0, now, ::sleep))
    val catalog = CachedSpotify(webApi, spotifyDailyCache)
    val resolver = TrackResolver(catalog, resolutionCache)
    val features =
        ReccoBeatsClient(http, featureCache, featureMissCache, enabled = { settings.current().useReccoBeats }, log = callLog)
    val queue = QueueController(webApi, resolver, features, scope)

    /** The last generated set, shown on the preview screen. */
    val preview = MutableStateFlow<GenerationResult?>(null)

    /** A generator that reads Spotify only when the user is signed in. */
    suspend fun engine(): GenerationEngine {
        val signedIn = secrets.current().spotifySignedIn
        return GenerationEngine(lastFm, if (signedIn) catalog else null, resolver, features)
    }

    /** Saves changed caches to disk. */
    fun flushCaches() {
        scope.launch(Dispatchers.IO) { caches.forEach { (_, cache) -> runCatching { cache.flush() } } }
    }

    init {
        // Read the caches from disk now, off the main thread, so the screens never wait for them.
        scope.launch(Dispatchers.IO) { caches.forEach { (_, cache) -> runCatching { cache.size } } }
        // Save caches now and then while the app runs (queueing adds Spotify matches).
        scope.launch {
            while (true) {
                delay(60_000)
                flushCaches()
            }
        }
    }
}
