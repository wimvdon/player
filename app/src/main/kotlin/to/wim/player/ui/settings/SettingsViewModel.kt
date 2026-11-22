package to.wim.player.ui.settings

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spotify.sdk.android.auth.AuthorizationResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import to.wim.player.PlayerApp
import to.wim.player.data.brainz.ListenHistoryStatus
import to.wim.player.data.model.AppSettings
import to.wim.player.data.model.Secrets
import to.wim.player.data.model.ThemeMode
import to.wim.player.data.spotify.TokenGrant
import to.wim.player.logic.Ordering
import to.wim.player.logic.Setup
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

private const val TOKEN_TYPING_DELAY_MILLIS = 800L

/** What ListenBrainz says about the token in Settings. */
sealed interface TokenStatus {
    data object None : TokenStatus

    data object Checking : TokenStatus

    data class Valid(val userName: String) : TokenStatus

    data object Invalid : TokenStatus

    /** ListenBrainz couldn't be reached; the token is saved and used anyway. */
    data object Unchecked : TokenStatus
}

/** What happened with the last Spotify sign-in. */
enum class SignInProblem { CANCELLED, REFUSED, FAILED }

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as PlayerApp).graph

    val settings: StateFlow<AppSettings?> = graph.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val secrets: StateFlow<Secrets?> = graph.secrets.secrets.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** How far reading the ListenBrainz play history is. */
    val history: StateFlow<ListenHistoryStatus> = graph.history.status

    private val _signInProblem = MutableStateFlow<SignInProblem?>(null)
    val signInProblem: StateFlow<SignInProblem?> = _signInProblem.asStateFlow()

    val packageName: String = app.packageName
    val redirectUri: String = graph.redirectUri

    /** The SHA-1 fingerprint of this APK's signing key, as Spotify's dashboard asks for it. */
    val fingerprint: String = signingFingerprint(app)

    fun setClientId(id: String) = viewModelScope.launch { graph.secrets.setSpotifyClientId(id) }


    fun setListenBrainzUsername(name: String) = viewModelScope.launch { graph.settings.setListenBrainzUsername(name) }

    private val _tokenStatus = MutableStateFlow<TokenStatus>(TokenStatus.None)
    val tokenStatus: StateFlow<TokenStatus> = _tokenStatus.asStateFlow()
    private var tokenCheck: Job? = null

    /** Saves the ListenBrainz token and asks ListenBrainz whether it works (after typing stops). */
    fun setListenBrainzToken(token: String) {
        val trimmed = token.trim()
        tokenCheck?.cancel()
        tokenCheck =
            viewModelScope.launch {
                graph.secrets.setListenBrainzToken(trimmed)
                if (trimmed.isEmpty()) {
                    _tokenStatus.value = TokenStatus.None
                    return@launch
                }
                _tokenStatus.value = TokenStatus.Checking
                delay(TOKEN_TYPING_DELAY_MILLIS)
                check(trimmed)
            }
    }

    /** Checks the saved token once when Settings opens. */
    fun checkSavedToken() {
        if (tokenCheck != null) return
        tokenCheck =
            viewModelScope.launch {
                val saved = graph.secrets.current().listenBrainzToken
                if (saved.isEmpty()) return@launch
                _tokenStatus.value = TokenStatus.Checking
                check(saved)
            }
    }

    private suspend fun check(token: String) {
        _tokenStatus.value =
            try {
                graph.checkListenBrainzToken(token)?.let { TokenStatus.Valid(it) } ?: TokenStatus.Invalid
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TokenStatus.Unchecked
            }
    }


    fun setOrdering(ordering: Ordering) = viewModelScope.launch { graph.settings.setOrdering(ordering) }

    fun setReccoBeats(on: Boolean) = viewModelScope.launch { graph.settings.setReccoBeats(on) }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { graph.settings.setTheme(mode) }

    fun setDynamicColor(on: Boolean) = viewModelScope.launch { graph.settings.setDynamicColor(on) }

    fun signOut() =
        viewModelScope.launch {
            graph.queue.stop()
            graph.secrets.signOut()
            graph.spotifyDailyCache.clear()
        }

    fun clearSignInProblem() {
        _signInProblem.value = null
    }

    /** Handles the answer of Spotify's sign-in screen. */
    fun onSignInResult(response: AuthorizationResponse) {
        when (response.type) {
            AuthorizationResponse.Type.TOKEN -> {
                val access = response.accessToken
                if (access.isNullOrEmpty()) {
                    _signInProblem.value = SignInProblem.FAILED
                    return
                }
                _signInProblem.value = null
                viewModelScope.launch {
                    graph.secrets.saveGrant(TokenGrant(access, response.refreshToken, response.expiresIn.toLong()), System.currentTimeMillis())
                    graph.secrets.setSpotifyScopes(Setup.scopeString)
                    graph.secrets.clearPause()
                    val name = runCatching { graph.webApi.displayName() }.getOrNull()
                    graph.secrets.setSpotifyUserName(name.orEmpty())
                }
            }
            AuthorizationResponse.Type.ERROR -> _signInProblem.value = SignInProblem.REFUSED
            AuthorizationResponse.Type.CANCELLED -> _signInProblem.value = SignInProblem.CANCELLED
            else -> _signInProblem.value = SignInProblem.FAILED
        }
    }

    /**
     * Empties every cache (Spotify matches included) and forgets the play history read from
     * ListenBrainz; the next generation reads everything again.
     */
    fun clearCaches() =
        viewModelScope.launch(Dispatchers.IO) {
            graph.caches.forEach { (_, cache) -> cache.clear() }
            graph.history.clear()
        }

    private fun signingFingerprint(app: Application): String =
        runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return ""
            Setup.fingerprint(MessageDigest.getInstance("SHA-1").digest(signer.toByteArray()))
        }.getOrDefault("")
}
