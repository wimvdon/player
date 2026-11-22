package to.wim.player.ui.preview

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import to.wim.player.R
import to.wim.player.logic.Candidate
import to.wim.player.logic.MoodCurve
import to.wim.player.logic.PlaylistName
import to.wim.player.ui.MainViewModel
import to.wim.player.ui.common.CoverArt
import to.wim.player.ui.common.MoodLine
import to.wim.player.ui.common.ScoreMeter
import to.wim.player.ui.generator.generateErrorText
import to.wim.player.ui.theme.AppShapes
import to.wim.player.ui.theme.EmptyState
import to.wim.player.ui.theme.readableWidth
import to.wim.player.ui.theme.rememberReducedMotion

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
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmStart by remember { mutableStateOf(false) }
    var playlistName by rememberSaveable { mutableStateOf("") }
    var nameOptions by remember { mutableStateOf(emptyList<String>()) }

    val notifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The playlist is made either way; without the permission the notification is just hidden.
            if (viewModel.startQueue(playlistName)) onQueueStarted()
        }

    fun start() {
        val needsAsk =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (viewModel.startQueue(playlistName)) {
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
                actions = {
                    if (tracks.isNotEmpty()) {
                        IconButton(onClick = { viewModel.generate {} }, enabled = !ui.generating) {
                            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.action_regenerate))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (tracks.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Button(
                        onClick = {
                            if (secrets?.spotifySignedIn != true) {
                                scope.launch {
                                    if (snackbar.showSnackbar(connectText, openSettingsText, withDismissAction = true) == SnackbarResult.ActionPerformed) {
                                        onOpenSettings()
                                    }
                                }
                            } else {
                                nameOptions = viewModel.playlistNameOptions()
                                playlistName = nameOptions.first()
                                confirmStart = true
                            }
                        },
                        enabled = !ui.generating,
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .heightIn(min = 56.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                        Text(stringResource(R.string.action_start_queue), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
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
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        val artistCount = tracks.map { it.artistKey }.distinct().size
                        val title = remember(current) { viewModel.suggestPlaylistName() }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                            CoverCollage(tracks.take(12).map { viewModel.coverOf(it) to it.artist })
                            Column(Modifier.weight(1f)) {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.headlineLarge,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Text(
                                    pluralStringResource(R.plurals.preview_summary, tracks.size, tracks.size, artistCount),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                        val notes =
                            buildList {
                                if (current.tracks.size < current.requested) add(stringResource(R.string.preview_short, current.requested))
                                if (current.usedTopArtists) add(stringResource(R.string.preview_top_artists))
                                if (current.libraryUnavailable) add(stringResource(R.string.preview_library_unavailable))
                            }
                        notes.forEach { note ->
                            Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MoodCard(tracks.map { it.mood })
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
        SendSheet(
            trackCount = tracks.size,
            name = playlistName,
            onNameChange = { playlistName = it.take(PlaylistName.MAX_LENGTH) },
            options = nameOptions,
            onDismiss = { confirmStart = false },
            onSend = {
                confirmStart = false
                start()
            },
        )
    }
}

/** Up to four covers (or first letters) of different artists in a rounded square. */
@Composable
private fun CoverCollage(covers: List<Pair<String?, String>>) {
    val picked = covers.distinctBy { it.second.lowercase() }.take(4)
    val tile = 56.dp
    Column(
        Modifier.size(tile * 2 + 3.dp).clip(RoundedCornerShape(24.dp)),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        picked.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { (url, artist) -> CoverArt(url = url, name = artist, size = tile, shape = RoundedCornerShape(0.dp)) }
            }
        }
    }
}

/** The set's mood from first to last track, so the ordering is visible at a glance. */
@Composable
private fun MoodCard(moods: List<Double>) {
    val curve = MoodCurve.of(moods) ?: return
    val description = stringResource(R.string.preview_curve_description, curve.startPercent, curve.peakTrack, curve.endPercent)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AppShapes.Group)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.preview_curve_title), style = MaterialTheme.typography.titleSmall)
        MoodLine(moods = moods.map { it.toFloat() }, description = description)
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.preview_curve_peak, curve.peakTrack),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("${moods.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Send to Spotify: a bottom sheet with the suggested name, a dice for another one, and Send. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SendSheet(
    trackCount: Int,
    name: String,
    onNameChange: (String) -> Unit,
    options: List<String>,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val nameOk = name.isNotBlank()
    var roll by remember { mutableIntStateOf(0) }
    val reduced = rememberReducedMotion()
    val diceTurn by animateFloatAsState(if (reduced) 0f else roll * 200f, label = "dice")
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.start_dialog_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            Text(
                pluralStringResource(R.plurals.start_dialog_new_playlist, trackCount, trackCount),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.start_dialog_name_label)) },
                supportingText = { if (!nameOk) Text(stringResource(R.string.start_dialog_name_empty)) },
                isError = !nameOk,
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                trailingIcon =
                    if (options.size > 1) {
                        {
                            IconButton(onClick = {
                                roll++
                                val at = options.indexOf(name)
                                onNameChange(options[(at + 1).mod(options.size)])
                            }) {
                                Icon(
                                    Icons.Rounded.Casino,
                                    contentDescription = stringResource(R.string.start_dialog_dice),
                                    modifier = Modifier.graphicsLayer { rotationZ = diceTurn },
                                )
                            }
                        }
                    } else {
                        null
                    },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            val chips = options.filter { it != name }.take(2)
            if (chips.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { option -> SuggestionChip(onClick = { onNameChange(option) }, label = { Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = onSend, enabled = nameOk, modifier = Modifier.heightIn(min = 56.dp), contentPadding = PaddingValues(horizontal = 24.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                    Text(stringResource(R.string.action_send), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
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
                    CoverArt(url = cover, name = track.artist, shape = RoundedCornerShape(12.dp))
                }
            },
            headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MeterRow(label = stringResource(R.string.meter_mood), value = track.mood.toFloat(), color = MaterialTheme.colorScheme.tertiary)
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
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ScoreMeter(value = value, color = color)
    }
}
