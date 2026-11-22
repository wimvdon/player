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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import to.wim.player.data.cache.JsonFileCache
import to.wim.player.data.brainz.BrainzCaches
import to.wim.player.data.brainz.BrainzHttp
import to.wim.player.data.brainz.BrainzMusic
import to.wim.player.data.brainz.JsonListenHistoryFile
import to.wim.player.data.brainz.ListenHistory
import to.wim.player.data.brainz.ListenBrainzClient
import to.wim.player.data.brainz.MusicBrainzClient
import to.wim.player.data.model.AudioFeatures
import to.wim.player.data.model.PoolTrack
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
import to.wim.player.logic.BrainzLimits
import to.wim.player.logic.CallLog
import to.wim.player.logic.Service
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

    // Artist IDs: 180 days. Tags and similarity change slowly: 30 days. The user's charts: a day.
    // Spotify matches: forever.
    val artistIdCache = cache("artist_ids", String.serializer(), 180)
    val artistListCache = cache("artist_lists", ListSerializer(String.serializer()), 30)
    val trackCache = cache("top_tracks", ListSerializer(PoolTrack.serializer()), 30)
    val tagCache = cache("artist_tags", ListSerializer(WeightedTag.serializer()), 30)
    val userChartCache = cache("user_charts", ListSerializer(String.serializer()), 1)
    val spotifyDailyCache = cache("spotify_library", ListSerializer(String.serializer()), 1)
    val resolutionCache = cache("spotify_matches", Resolution.serializer(), null)
    val featureCache = cache("audio_features", AudioFeatures.serializer(), null)
    val featureMissCache = cache("audio_features_missing", Boolean.serializer(), 30)

    /** Every cache, for the debug screen and for saving. */
    val caches: List<Pair<String, JsonFileCache<*>>> =
        listOf(
            "artist_ids" to artistIdCache,
            "artist_lists" to artistListCache,
            "top_tracks" to trackCache,
            "artist_tags" to tagCache,
            "user_charts" to userChartCache,
            "spotify_library" to spotifyDailyCache,
            "spotify_matches" to resolutionCache,
            "audio_features" to featureCache,
            "audio_features_missing" to featureMissCache,
        )

    private suspend fun sleep(millis: Long) = delay(millis)

    private val userAgent = BrainzHttp.userAgent(runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "dev")

    private fun brainzHttp(service: Service, token: suspend () -> String = { "" }) =
        BrainzHttp(http, service, userAgent, TokenBucket(BrainzLimits.callsPerSecond(service), 1.0, now, ::sleep), callLog, token, now = now)

    /** ListenBrainz gets the user's token when there is one; MusicBrainz never does. */
    private val listenBrainz = ListenBrainzClient(brainzHttp(Service.LISTENBRAINZ) { secrets.current().listenBrainzToken })

    /** The ListenBrainz user name [token] belongs to, or null when ListenBrainz refuses it. Throws when offline. */
    suspend fun checkListenBrainzToken(token: String): String? = listenBrainz.validateToken(token)

    /** Every track the user played on ListenBrainz, on this phone only (`noBackupFilesDir`). */
    val history = ListenHistory(listenBrainz, JsonListenHistoryFile(File(app.noBackupFilesDir, "listen_history.json")), now)

    val music =
        BrainzMusic(
            MusicBrainzClient(brainzHttp(Service.MUSICBRAINZ)),
            listenBrainz,
            BrainzCaches(artistIdCache, artistListCache, trackCache, tagCache, userChartCache),
            history,
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
        syncHistory(settings.current().listenBrainzUsername)
        val signedIn = secrets.current().spotifySignedIn
        return GenerationEngine(music, if (signedIn) catalog else null, resolver, features)
    }

    /** Reads new plays in the background (at most every few hours); the set never waits for it. */
    fun syncHistory(username: String) {
        if (username.isBlank()) return
        scope.launch(Dispatchers.IO) { runCatching { history.sync(username) } }
    }

    /** Saves changed caches to disk. */
    fun flushCaches() {
        scope.launch(Dispatchers.IO) { caches.forEach { (_, cache) -> runCatching { cache.flush() } } }
    }

    init {
        // Read the caches from disk now, off the main thread, so the screens never wait for them.
        scope.launch(Dispatchers.IO) { caches.forEach { (_, cache) -> runCatching { cache.size } } }
        // Player no longer uses Last.fm: forget its key, username and cached answers.
        scope.launch(Dispatchers.IO) {
            runCatching { secrets.removeLastFm() }
            runCatching { settings.removeLastFm() }
            OLD_CACHES.forEach { runCatching { File(cacheDir, "$it.json").delete() } }
        }
        // Read the play history for the username in Settings, again when it changes (after typing stops).
        scope.launch {
            settings.settings.map { it.listenBrainzUsername.trim() }.distinctUntilChanged().collectLatest { name ->
                history.show(name)
                if (name.isBlank()) return@collectLatest
                delay(HISTORY_TYPING_DELAY_MILLIS)
                withContext(Dispatchers.IO) { runCatching { history.sync(name) } }
            }
        }
        // A new ListenBrainz token may open endpoints that refused the app before.
        scope.launch {
            secrets.secrets.map { it.listenBrainzToken }.distinctUntilChanged().collect { music.tokenChanged() }
        }
        // Save caches now and then while the app runs (queueing adds Spotify matches).
        scope.launch {
            while (true) {
                delay(60_000)
                flushCaches()
            }
        }
    }

    private companion object {
        const val HISTORY_TYPING_DELAY_MILLIS = 3_000L
        val OLD_CACHES = listOf("lastfm_artists", "lastfm_tracks", "lastfm_tags", "lastfm_names")
    }
}
