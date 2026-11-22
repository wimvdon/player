package to.wim.player.data.spotify

import android.content.Context
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.AuthenticationFailedException
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.OfflineModeException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.types.Empty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import to.wim.player.data.PlayerException
import to.wim.player.data.PlayerProblem
import to.wim.player.data.PlayerSnapshot
import to.wim.player.data.SpotifyPlayer
import to.wim.player.logic.ApiCall
import to.wim.player.logic.CallLog
import to.wim.player.logic.Service
import java.lang.ref.WeakReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Plays and queues through the Spotify app (App Remote SDK). App Remote talks to the app on the
 * phone, not the Web API, so it doesn't use the Development Mode quota. When App Remote can't
 * queue, the Web API's queue endpoint is the fallback.
 */
class AppRemotePlayer(
    private val appContext: Context,
    private val clientId: suspend () -> String,
    private val redirectUri: String,
    private val webApi: SpotifyWebApi,
    private val log: CallLog,
    private val now: () -> Long = System::currentTimeMillis,
) : SpotifyPlayer {
    private val _state = MutableStateFlow<PlayerSnapshot?>(null)
    override val state: StateFlow<PlayerSnapshot?> = _state.asStateFlow()

    private var remote: SpotifyAppRemote? = null

    /** The screen that started the queue: App Remote shows its permission screen from it the first time. */
    private var activity: WeakReference<Context>? = null

    fun useActivity(context: Context) {
        activity = WeakReference(context)
    }

    override suspend fun connect() {
        if (remote?.isConnected == true) return
        val id = clientId()
        if (id.isBlank()) throw PlayerException(PlayerProblem.NOT_AUTHORIZED)
        val context = activity?.get() ?: appContext
        val params = ConnectionParams.Builder(id).setRedirectUri(redirectUri).showAuthView(true).build()
        val connected =
            withContext(Dispatchers.Main) {
                withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
                    suspendCancellableCoroutine<SpotifyAppRemote> { continuation ->
                        SpotifyAppRemote.connect(
                            context,
                            params,
                            object : Connector.ConnectionListener {
                                override fun onConnected(appRemote: SpotifyAppRemote) {
                                    if (continuation.isActive) continuation.resume(appRemote) else SpotifyAppRemote.disconnect(appRemote)
                                }

                                override fun onFailure(error: Throwable) {
                                    log.record(ApiCall(now(), Service.SPOTIFY, "App Remote connect", 0, error.javaClass.simpleName))
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(PlayerException(problemOf(error), error))
                                    } else {
                                        // Lost an earlier connection.
                                        remote = null
                                        _state.value = null
                                    }
                                }
                            },
                        )
                    }
                }
            } ?: throw PlayerException(PlayerProblem.DISCONNECTED)
        remote = connected
        log.record(ApiCall(now(), Service.SPOTIFY, "App Remote connect", 200))
        connected.playerApi.subscribeToPlayerState()
            .setEventCallback { player ->
                val track = player.track
                _state.value =
                    PlayerSnapshot(
                        trackUri = track?.uri,
                        trackName = track?.name,
                        artistName = track?.artist?.name,
                        isPaused = player.isPaused,
                    )
            }
            .setErrorCallback { _state.value = null }
    }

    override suspend fun queue(uri: String) {
        val app = remote?.takeIf { it.isConnected }
        if (app != null) {
            val ok = attempt { app.playerApi.queue(uri) }
            log.record(ApiCall(now(), Service.SPOTIFY, "App Remote queue", if (ok) 200 else 0))
            if (ok) return
        }
        // Fallback: the Web API (needs an active Spotify device, and uses the quota).
        try {
            webApi.queue(uri)
        } catch (e: SpotifyPausedException) {
            throw e
        } catch (e: SpotifySignedOutException) {
            throw e
        } catch (e: Exception) {
            throw PlayerException(if (app == null) PlayerProblem.DISCONNECTED else PlayerProblem.QUEUE_FAILED, e)
        }
    }

    override suspend fun play(uri: String) {
        val app = remote?.takeIf { it.isConnected } ?: throw PlayerException(PlayerProblem.DISCONNECTED)
        val ok = attempt { app.playerApi.play(uri) }
        log.record(ApiCall(now(), Service.SPOTIFY, "App Remote play", if (ok) 200 else 0))
        if (!ok) throw PlayerException(PlayerProblem.QUEUE_FAILED)
    }

    override fun disconnect() {
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        _state.value = null
    }

    /** True when App Remote did the call; false when it failed or timed out. Cancelling still cancels. */
    private suspend fun attempt(makeCall: () -> CallResult<Empty>): Boolean =
        try {
            await(makeCall)
            true
        } catch (e: TimeoutCancellationException) {
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

    /** Makes an App Remote call on the main thread and waits for its answer. */
    private suspend fun await(makeCall: () -> CallResult<Empty>) {
        withContext(Dispatchers.Main) {
            withTimeout(CALL_TIMEOUT_MILLIS) {
                val call = makeCall()
                suspendCancellableCoroutine { continuation ->
                    call.setResultCallback { if (continuation.isActive) continuation.resume(Unit) }
                    call.setErrorCallback { if (continuation.isActive) continuation.resumeWithException(it) }
                }
            }
        }
    }

    private fun problemOf(error: Throwable): PlayerProblem =
        when (error) {
            is CouldNotFindSpotifyApp -> PlayerProblem.NOT_INSTALLED
            is NotLoggedInException -> PlayerProblem.NOT_LOGGED_IN
            is UserNotAuthorizedException, is AuthenticationFailedException -> PlayerProblem.NOT_AUTHORIZED
            is OfflineModeException -> PlayerProblem.OFFLINE
            else -> PlayerProblem.DISCONNECTED
        }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 30_000L
        private const val CALL_TIMEOUT_MILLIS = 10_000L
    }
}
