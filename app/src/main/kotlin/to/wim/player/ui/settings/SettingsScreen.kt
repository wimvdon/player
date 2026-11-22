package to.wim.player.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import to.wim.player.R
import to.wim.player.data.model.ThemeMode
import to.wim.player.logic.Ordering
import to.wim.player.logic.Setup
import to.wim.player.ui.theme.AppearanceSettings
import to.wim.player.ui.theme.ExpandableSetting
import to.wim.player.ui.theme.SettingItem
import to.wim.player.ui.theme.SettingsBlock
import to.wim.player.ui.theme.SettingsFooter
import to.wim.player.ui.theme.SettingsScaffold
import to.wim.player.ui.theme.SettingsSection
import to.wim.player.ui.theme.SwitchSetting
import to.wim.player.ui.theme.rememberAppVersion
import java.text.NumberFormat

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()
    val signInProblem by viewModel.signInProblem.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val currentSettings = settings ?: return
    val currentSecrets = secrets ?: return
    val activity = LocalActivity.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    val signIn =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_CANCELED) {
                viewModel.onSignInResult(AuthorizationResponse.Builder().setType(AuthorizationResponse.Type.CANCELLED).build())
            } else {
                viewModel.onSignInResult(AuthorizationClient.getResponse(result.resultCode, result.data))
            }
        }

    var clientId by rememberSaveable { mutableStateOf(currentSecrets.spotifyClientId) }
    var username by rememberSaveable { mutableStateOf(currentSettings.listenBrainzUsername) }
    LaunchedEffect(Unit) { viewModel.clearSignInProblem() }

    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        backLabel = stringResource(R.string.action_back),
        onBack = onBack,
    ) {
        item(key = "spotify") {
            SettingsSection(stringResource(R.string.settings_spotify)) {
                SettingsBlock {
                    KeyField(
                        value = clientId,
                        onChange = {
                            clientId = it
                            if (it.isBlank() || Setup.isValidKey(it)) viewModel.setClientId(it)
                        },
                        label = stringResource(R.string.settings_client_id),
                        invalidText = stringResource(R.string.settings_key_invalid),
                    )
                }
                val validId = Setup.isValidKey(currentSecrets.spotifyClientId)
                if (currentSecrets.spotifySignedIn) {
                    SettingItem(
                        title = stringResource(R.string.settings_spotify_connected),
                        summary =
                            currentSecrets.spotifyUserName.takeIf(String::isNotBlank)?.let { stringResource(R.string.settings_signed_in_as, it) },
                        onClick = {},
                        enabled = false,
                        trailing = { TextButton(onClick = viewModel::signOut) { Text(stringResource(R.string.action_sign_out)) } },
                    )
                } else {
                    SettingsBlock {
                        Button(
                            enabled = validId && activity != null,
                            onClick = {
                                val host = activity ?: return@Button
                                val request =
                                    AuthorizationRequest.Builder(currentSecrets.spotifyClientId, AuthorizationResponse.Type.TOKEN, viewModel.redirectUri)
                                        .setScopes(Setup.SpotifyScopes)
                                        .build()
                                viewModel.clearSignInProblem()
                                signIn.launch(AuthorizationClient.createLoginActivityIntent(host, request))
                            },
                        ) { Text(stringResource(R.string.action_connect_spotify)) }
                        signInProblem?.let { problem ->
                            Text(
                                stringResource(
                                    when (problem) {
                                        SignInProblem.CANCELLED -> R.string.sign_in_cancelled
                                        SignInProblem.REFUSED -> R.string.sign_in_refused
                                        SignInProblem.FAILED -> R.string.sign_in_failed
                                    },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (problem == SignInProblem.CANCELLED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                ExpandableSetting(
                    key = "spotify_steps",
                    title = stringResource(R.string.settings_spotify_steps_title),
                    initiallyExpanded = currentSecrets.spotifyClientId.isBlank(),
                ) {
                    Step(stringResource(R.string.spotify_step_create))
                    Step(stringResource(R.string.spotify_step_redirect))
                    CopyValue(context, viewModel.redirectUri)
                    Step(stringResource(R.string.spotify_step_apis))
                    Step(stringResource(R.string.spotify_step_android))
                    CopyValue(context, viewModel.packageName)
                    if (viewModel.fingerprint.isNotEmpty()) CopyValue(context, viewModel.fingerprint)
                    Step(stringResource(R.string.spotify_step_client_id))
                    OutlinedButton(onClick = { uriHandler.openUri(SPOTIFY_DASHBOARD) }) {
                        Text(stringResource(R.string.action_open_spotify_dashboard))
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        item(key = "listenbrainz") {
            SettingsSection(stringResource(R.string.settings_listenbrainz)) {
                SettingsBlock {
                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            username = it
                            viewModel.setListenBrainzUsername(it)
                        },
                        label = { Text(stringResource(R.string.settings_listenbrainz_username)) },
                        supportingText = { Text(stringResource(R.string.settings_listenbrainz_help)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (username.isNotBlank() && (history.syncing || history.songs > 0)) {
                        val songs = NumberFormat.getIntegerInstance().format(history.songs)
                        Text(
                            when {
                                history.syncing -> pluralStringResource(R.plurals.settings_listenbrainz_history_reading, history.songs, songs)
                                history.complete -> pluralStringResource(R.plurals.settings_listenbrainz_history_done, history.songs, songs)
                                else -> pluralStringResource(R.plurals.settings_listenbrainz_history_partial, history.songs, songs)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Step(stringResource(R.string.settings_listenbrainz_import))
                    OutlinedButton(onClick = { uriHandler.openUri(LISTENBRAINZ) }) {
                        Text(stringResource(R.string.action_open_listenbrainz))
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        item(key = "queue") {
            SettingsSection(stringResource(R.string.settings_queue)) {
                SettingsBlock {
                    Text(stringResource(R.string.settings_order), style = MaterialTheme.typography.bodyLarge)
                    val orders =
                        listOf(Ordering.MOOD_CURVE to R.string.order_curve, Ordering.BEST_MATCH to R.string.order_best, Ordering.SHUFFLE to R.string.order_shuffle)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        orders.forEachIndexed { index, (order, label) ->
                            SegmentedButton(
                                selected = currentSettings.ordering == order,
                                onClick = { viewModel.setOrdering(order) },
                                shape = SegmentedButtonDefaults.itemShape(index, orders.size),
                            ) { Text(stringResource(label)) }
                        }
                    }
                    if (currentSettings.ordering == Ordering.MOOD_CURVE) {
                        Text(
                            stringResource(R.string.order_curve_help),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SwitchSetting(
                    title = stringResource(R.string.settings_reccobeats),
                    summary = stringResource(R.string.settings_reccobeats_help),
                    checked = currentSettings.useReccoBeats,
                    onCheckedChange = viewModel::setReccoBeats,
                )
            }
        }
        item(key = "appearance") {
            SettingsSection(stringResource(R.string.settings_appearance)) {
                AppearanceSettings(
                    themeLabel = stringResource(R.string.settings_theme),
                    themeOptions = listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark)),
                    selectedTheme = currentSettings.themeMode.ordinal,
                    onThemeSelect = { viewModel.setTheme(ThemeMode.entries[it]) },
                    dynamicColorTitle = stringResource(R.string.settings_dynamic_color),
                    dynamicColorSummary = stringResource(R.string.settings_dynamic_color_help),
                    dynamicColor = currentSettings.dynamicColor,
                    onDynamicColorChange = viewModel::setDynamicColor,
                    // Android 12+ (the app's minimum) always has wallpaper colours.
                    dynamicColorSupported = true,
                )
            }
        }
        item(key = "troubleshooting") {
            SettingsSection(stringResource(R.string.settings_troubleshooting)) {
                SettingItem(
                    title = stringResource(R.string.debug_title),
                    summary = stringResource(R.string.settings_debug_help),
                    onClick = onOpenDebug,
                    trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                )
            }
        }
        item(key = "footer") {
            SettingsFooter(stringResource(R.string.settings_footer, stringResource(R.string.app_name), rememberAppVersion()))
        }
    }
}

@Composable
private fun KeyField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    invalidText: String,
) {
    val invalid = value.isNotBlank() && !Setup.isValidKey(value)
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.trim()) },
        label = { Text(label) },
        isError = invalid,
        supportingText = if (invalid) ({ Text(invalidText) }) else null,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Step(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

/** A value to paste into a website, with a copy button. */
@Composable
private fun CopyValue(
    context: Context,
    value: String,
) {
    val copyLabel = stringResource(R.string.action_copy_named, value)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(value, value))
        }) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = copyLabel)
        }
    }
}

private const val SPOTIFY_DASHBOARD = "https://developer.spotify.com/dashboard"
private const val LISTENBRAINZ = "https://listenbrainz.org/"
