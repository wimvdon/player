package to.wim.player.ui.running

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.wim.player.R
import to.wim.player.engine.QueueProblem
import to.wim.player.engine.QueueState
import to.wim.player.engine.QueueStatus
import to.wim.player.ui.MainViewModel
import to.wim.player.ui.theme.AppShapes
import to.wim.player.ui.theme.EmptyState
import to.wim.player.ui.theme.readableWidth
import java.util.Date

/** The playlist while it fills: open it in Spotify, how far it is, and Stop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunningScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.queue.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.queue_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        if (state.status == QueueStatus.IDLE) {
            EmptyState(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                title = stringResource(R.string.queue_empty_title),
                body = stringResource(R.string.queue_empty_body),
                modifier = Modifier.padding(padding),
            ) {
                Button(onClick = onBack) { Text(stringResource(R.string.action_back_to_generator)) }
            }
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.readableWidth().fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                PlaylistCard(state)
                Progress(state)
                Status(state = state, onStop = viewModel::stopQueue, onResume = viewModel::resumeQueue, onOpenSettings = onOpenSettings)
            }
        }
    }
}

@Composable
private fun PlaylistCard(state: QueueState) {
    val context = LocalContext.current
    val id = state.playlistId
    // Spotify couldn't start the playlist (no active device): open it once so the user can press play.
    var opened by rememberSaveable(id) { mutableStateOf(false) }
    LaunchedEffect(id, state.playbackStarted) {
        if (id != null && state.playbackStarted == false && !opened) {
            opened = true
            openPlaylist(context, id)
        }
    }
    Card(
        shape = AppShapes.Group,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.playlist_card_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(state.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(
                    when {
                        id == null -> R.string.playlist_waiting
                        state.playbackStarted == true -> R.string.playlist_playing
                        else -> R.string.playlist_press_play
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (id != null) {
                Button(onClick = { openPlaylist(context, id) }) {
                    Text(stringResource(R.string.action_open_in_spotify))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** Opens the playlist in the Spotify app, or on the web when the app isn't there. */
private fun openPlaylist(
    context: Context,
    id: String,
) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:playlist:$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(app)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/playlist/$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun Progress(state: QueueState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val matching = state.playlistId == null
        Text(
            if (matching) {
                stringResource(R.string.queue_progress_matching, state.processed, state.total)
            } else {
                stringResource(R.string.queue_progress_adding, state.queued, state.found)
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        LinearProgressIndicator(
            progress = {
                when {
                    matching -> if (state.total == 0) 0f else state.processed.toFloat() / state.total
                    else -> if (state.found == 0) 0f else state.queued.toFloat() / state.found
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.skipped > 0) {
            Text(
                pluralStringResource(R.plurals.queue_skipped, state.skipped, state.skipped),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Status(
    state: QueueState,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state.status) {
            QueueStatus.MATCHING -> StatusText(stringResource(R.string.queue_matching))
            QueueStatus.SAVING -> StatusText(stringResource(R.string.queue_saving))
            QueueStatus.FINISHED -> StatusText(stringResource(R.string.queue_finished))
            QueueStatus.STOPPED -> StatusText(stringResource(R.string.queue_stopped))
            QueueStatus.FAILED -> StatusText(problemText(state.problem), error = true)
            QueueStatus.IDLE -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.active) {
                OutlinedButton(onClick = onStop) {
                    Icon(Icons.Rounded.Stop, contentDescription = null)
                    Text(stringResource(R.string.action_stop), Modifier.padding(start = 8.dp))
                }
            } else if (state.status == QueueStatus.STOPPED || state.status == QueueStatus.FAILED) {
                val problem = state.problem
                if (problem == QueueProblem.SignedOut) {
                    Button(onClick = onOpenSettings) { Text(stringResource(R.string.action_open_settings)) }
                } else if (problem !is QueueProblem.SpotifyPaused && problem != QueueProblem.NothingFound) {
                    Button(onClick = onResume) { Text(stringResource(if (state.status == QueueStatus.FAILED) R.string.action_retry else R.string.action_continue)) }
                }
            }
        }
        if (state.status == QueueStatus.MATCHING) {
            Text(stringResource(R.string.queue_stop_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusText(
    text: String,
    error: Boolean = false,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun problemText(problem: QueueProblem?): String {
    val context = LocalContext.current
    return when (problem) {
        is QueueProblem.SpotifyPaused -> stringResource(R.string.problem_quota, DateFormat.getTimeFormat(context).format(Date(problem.untilMillis)))
        QueueProblem.SignedOut -> stringResource(R.string.problem_signed_out)
        QueueProblem.Rejected -> stringResource(R.string.problem_playlist_rejected)
        QueueProblem.NothingFound -> stringResource(R.string.problem_nothing_found)
        QueueProblem.Network, null -> stringResource(R.string.problem_network)
    }
}
