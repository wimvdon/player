package to.wim.player.ui.running

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.wim.player.R
import to.wim.player.data.PlayerProblem
import to.wim.player.data.model.QueueMode
import to.wim.player.engine.QueueProblem
import to.wim.player.engine.QueueState
import to.wim.player.engine.QueueStatus
import to.wim.player.ui.MainViewModel
import to.wim.player.ui.theme.AppShapes
import to.wim.player.ui.theme.EmptyState
import to.wim.player.ui.theme.readableWidth
import java.util.Date

/** The queue while it runs: what plays, how far it is, and Stop. */
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
                NowPlaying(state)
                Progress(state)
                Status(state = state, onStop = viewModel::stopQueue, onResume = viewModel::resumeQueue, onOpenSettings = onOpenSettings)
            }
        }
    }
}

@Composable
private fun NowPlaying(state: QueueState) {
    Card(
        shape = AppShapes.Group,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.queue_now_playing), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            val now = state.nowPlaying
            if (now?.trackName == null) {
                Text(
                    stringResource(if (state.active) R.string.queue_waiting else R.string.queue_nothing_playing),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(now.trackName, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                now.artistName?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (now.isPaused) {
                    Text(stringResource(R.string.queue_paused), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Progress(state: QueueState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.queue_progress, state.processed, state.total),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        LinearProgressIndicator(progress = { if (state.total == 0) 0f else state.processed.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
        if (state.mode == QueueMode.ROLLING && state.active) {
            Text(
                pluralStringResource(R.plurals.queue_ahead, state.ahead, state.ahead),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
            QueueStatus.CONNECTING -> StatusText(stringResource(R.string.queue_connecting))
            QueueStatus.RUNNING -> Unit
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
            } else if (state.processed < state.total) {
                val problem = state.problem
                if (problem == QueueProblem.SignedOut || (problem is QueueProblem.Player && problem.problem == PlayerProblem.NOT_AUTHORIZED)) {
                    Button(onClick = onOpenSettings) { Text(stringResource(R.string.action_open_settings)) }
                } else if (problem !is QueueProblem.SpotifyPaused) {
                    Button(onClick = onResume) { Text(stringResource(if (state.status == QueueStatus.FAILED) R.string.action_retry else R.string.action_continue)) }
                }
            }
        }
        if (state.active) {
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
        is QueueProblem.Player ->
            stringResource(
                when (problem.problem) {
                    PlayerProblem.NOT_INSTALLED -> R.string.problem_not_installed
                    PlayerProblem.NOT_LOGGED_IN -> R.string.problem_not_logged_in
                    PlayerProblem.NOT_AUTHORIZED -> R.string.problem_not_authorized
                    PlayerProblem.OFFLINE -> R.string.problem_offline
                    PlayerProblem.DISCONNECTED -> R.string.problem_disconnected
                    PlayerProblem.QUEUE_FAILED -> R.string.problem_queue_failed
                },
            )
        is QueueProblem.SpotifyPaused -> stringResource(R.string.problem_quota, DateFormat.getTimeFormat(context).format(Date(problem.untilMillis)))
        QueueProblem.SignedOut -> stringResource(R.string.problem_signed_out)
        QueueProblem.Network, null -> stringResource(R.string.problem_network)
    }
}
