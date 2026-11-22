package to.wim.player.ui.debug

import android.app.Application
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import to.wim.player.PlayerApp
import to.wim.player.R
import to.wim.player.logic.ApiCall
import to.wim.player.logic.Service
import to.wim.player.ui.theme.SettingsBlock
import to.wim.player.ui.theme.SettingsSection
import to.wim.player.ui.theme.readableWidth
import java.util.Date

/** One cache for the list: its file name, entries and size on disk. */
data class CacheInfo(
    val name: String,
    val entries: Int,
    val bytes: Long,
)

class DebugViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as PlayerApp).graph

    val calls = graph.callLog.calls
    val counts = graph.callLog.counts
    val secrets = graph.secrets.secrets.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun cacheInfo(): List<CacheInfo> = graph.caches.map { (name, cache) -> CacheInfo(name, cache.size, cache.fileBytes) }

    fun resumeSpotify() = viewModelScope.launch { graph.secrets.clearPause() }

    fun clearCaches() = viewModelScope.launch(Dispatchers.IO) { graph.caches.forEach { (_, cache) -> cache.clear() } }
}

/** API calls (so the Spotify quota use is visible), the caches and the quota pause. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    viewModel: DebugViewModel,
    onBack: () -> Unit,
) {
    val calls by viewModel.calls.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val caches = remember(refresh, calls.size) { viewModel.cacheInfo() }
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            refresh++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.readableWidth().fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "quota") {
                    SettingsSection(stringResource(R.string.debug_quota)) {
                        SettingsBlock {
                            val pausedUntil = secrets?.spotifyPausedUntilMillis ?: 0
                            val paused = pausedUntil > System.currentTimeMillis()
                            Text(
                                if (paused) {
                                    stringResource(R.string.debug_quota_paused, timeFormat.format(Date(pausedUntil)))
                                } else {
                                    stringResource(R.string.debug_quota_ok)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (paused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                stringResource(
                                    R.string.debug_counts,
                                    counts[Service.SPOTIFY] ?: 0,
                                    counts[Service.MUSICBRAINZ] ?: 0,
                                    counts[Service.LISTENBRAINZ] ?: 0,
                                    counts[Service.RECCOBEATS] ?: 0,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (paused) {
                                OutlinedButton(onClick = { viewModel.resumeSpotify() }) { Text(stringResource(R.string.action_resume_spotify)) }
                            }
                        }
                    }
                }
                item(key = "cache") {
                    SettingsSection(stringResource(R.string.debug_cache)) {
                        caches.forEach { cache ->
                            ListItem(
                                headlineContent = { Text(cacheLabel(cache.name)) },
                                trailingContent = {
                                    Text(
                                        stringResource(R.string.debug_cache_entry, cache.entries, (cache.bytes + 1023) / 1024),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                },
                            )
                        }
                        SettingsBlock {
                            OutlinedButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.action_clear_cache)) }
                        }
                    }
                }
                item(key = "calls_title") {
                    SettingsSection(stringResource(R.string.debug_calls)) {
                        if (calls.isEmpty()) {
                            SettingsBlock {
                                Text(
                                    stringResource(R.string.debug_no_calls),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                items(calls) { call ->
                    CallRow(call, timeFormat.format(Date(call.atMillis)))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_cache_title)) },
            text = { Text(stringResource(R.string.clear_cache_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearCaches()
                    refresh++
                }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun CallRow(
    call: ApiCall,
    time: String,
) {
    val service =
        when (call.service) {
            Service.SPOTIFY -> stringResource(R.string.service_spotify)
            Service.RECCOBEATS -> stringResource(R.string.service_reccobeats)
            Service.MUSICBRAINZ -> stringResource(R.string.service_musicbrainz)
            Service.LISTENBRAINZ -> stringResource(R.string.service_listenbrainz)
        }
    ListItem(
        overlineContent = { Text(stringResource(R.string.debug_call_overline, time, service)) },
        headlineContent = { Text(call.endpoint, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)) },
        supportingContent = call.reason?.let { reason -> { Text(reason) } },
        trailingContent = {
            Text(
                if (call.status == 0) stringResource(R.string.debug_no_answer) else call.status.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = if (call.status in 200..299) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        },
    )
}

@Composable
private fun cacheLabel(name: String): String =
    stringResource(
        when (name) {
            "artist_ids" -> R.string.cache_artist_ids
            "artist_lists" -> R.string.cache_artist_lists
            "top_tracks" -> R.string.cache_top_tracks
            "artist_tags" -> R.string.cache_artist_tags
            "user_charts" -> R.string.cache_user_charts
            "spotify_library" -> R.string.cache_spotify_library
            "spotify_matches" -> R.string.cache_spotify_matches
            "audio_features" -> R.string.cache_audio_features
            else -> R.string.cache_audio_features_missing
        },
    )
