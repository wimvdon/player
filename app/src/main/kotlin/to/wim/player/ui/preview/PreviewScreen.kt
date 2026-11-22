package to.wim.player.ui.preview

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import to.wim.player.R
import to.wim.player.data.model.QueueMode
import to.wim.player.logic.Candidate
import to.wim.player.ui.MainViewModel
import to.wim.player.ui.common.CoverArt
import to.wim.player.ui.common.ScoreMeter
import to.wim.player.ui.generator.generateErrorText
import to.wim.player.ui.theme.EmptyState
import to.wim.player.ui.theme.readableWidth
import kotlin.math.roundToInt

/** The generated set: check it, swipe tracks away, then start the queue. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onQueueStarted: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val result by viewModel.preview.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmStart by remember { mutableStateOf(false) }

    val notifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The queue runs either way; without the permission the notification is just hidden.
            if (viewModel.startQueue()) onQueueStarted()
        }

    fun start() {
        val needsAsk =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (viewModel.startQueue()) {
            onQueueStarted()
        }
    }

    val removedText = stringResource(R.string.preview_removed)
    val undoText = stringResource(R.string.action_undo)
    val connectText = stringResource(R.string.preview_connect_spotify)
    val openSettingsText = stringResource(R.string.action_open_settings)

    fun remove(track: Candidate) {
        val index = viewModel.removeFromPreview(track)
        if (index < 0) return
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(removedText, undoText, withDismissAction = false) == SnackbarResult.ActionPerformed) {
                viewModel.restoreToPreview(track, index)
            }
        }
    }

    val tracks = result?.tracks.orEmpty()

    val errorText = ui.error?.let { generateErrorText(it) }
    LaunchedEffect(ui.error) {
        if (ui.error == null) return@LaunchedEffect
        snackbar.showSnackbar(errorText.orEmpty(), withDismissAction = true)
        viewModel.dismissError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.preview_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (tracks.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    ) {
                        OutlinedButton(onClick = { viewModel.generate {} }, enabled = !ui.generating) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Text(stringResource(R.string.action_regenerate), Modifier.padding(start = 8.dp))
                        }
                        Button(
                            onClick = {
                                if (secrets?.spotifySignedIn != true) {
                                    scope.launch {
                                        if (snackbar.showSnackbar(connectText, openSettingsText, withDismissAction = true) == SnackbarResult.ActionPerformed) {
                                            onOpenSettings()
                                        }
                                    }
                                } else {
                                    confirmStart = true
                                }
                            },
                            enabled = !ui.generating,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Text(stringResource(R.string.action_start_queue), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
    ) { padding ->
        val current = result
        if (current == null || tracks.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                title = stringResource(R.string.preview_empty_title),
                body = stringResource(R.string.preview_empty_body),
                modifier = Modifier.padding(padding),
            ) {
                Button(onClick = onBack) { Text(stringResource(R.string.action_back_to_generator)) }
            }
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.readableWidth().fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item(key = "summary") {
                    if (ui.generating) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        val artistCount = tracks.map { it.artistKey }.distinct().size
                        Text(
                            pluralStringResource(R.plurals.preview_summary, tracks.size, tracks.size, artistCount),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val notes =
                            buildList {
                                if (current.tracks.size < current.requested) add(stringResource(R.string.preview_short, current.requested))
                                if (current.usedTopArtists) add(stringResource(R.string.preview_top_artists))
                                if (current.libraryUnavailable) add(stringResource(R.string.preview_library_unavailable))
                            }
                        notes.forEach { note ->
                            Text(
                                note,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                itemsIndexed(tracks, key = { _, track -> track.key }) { index, track ->
                    TrackRow(
                        rank = index + 1,
                        track = track,
                        cover = viewModel.coverOf(track),
                        onRemove = { remove(track) },
                    )
                }
            }
        }
    }

    if (confirmStart) {
        val mode = settings?.queueMode ?: QueueMode.ROLLING
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            title = { Text(stringResource(R.string.start_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.start_dialog_queue_warning))
                    Text(
                        if (mode == QueueMode.ROLLING) {
                            stringResource(R.string.start_dialog_rolling)
                        } else {
                            pluralStringResource(R.plurals.start_dialog_all, tracks.size, tracks.size)
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStart = false
                    start()
                }) { Text(stringResource(R.string.action_start)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStart = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackRow(
    rank: Int,
    track: Candidate,
    cover: String?,
    onRemove: () -> Unit,
) {
    val dismiss =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) onRemove()
                false
            },
        )
    val removeLabel = stringResource(R.string.action_remove)
    val mood = (track.mood * 100).roundToInt()
    val discovery = (track.familiarity * 100).roundToInt()
    val description = stringResource(R.string.track_description, rank, track.title, track.artist, mood, discovery)
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        ListItem(
            leadingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$rank",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(32.dp),
                    )
                    CoverArt(url = cover, name = track.artist)
                }
            },
            headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MeterRow(label = stringResource(R.string.meter_mood), value = track.mood.toFloat())
                    MeterRow(label = stringResource(R.string.meter_new), value = track.familiarity.toFloat())
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier =
                Modifier.clearAndSetSemantics {
                    contentDescription = description
                    customActions = listOf(CustomAccessibilityAction(removeLabel) { onRemove(); true })
                },
        )
    }
}

@Composable
private fun MeterRow(
    label: String,
    value: Float,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ScoreMeter(value = value)
    }
}
