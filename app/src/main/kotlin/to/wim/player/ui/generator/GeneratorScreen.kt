package to.wim.player.ui.generator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.wim.player.R
import to.wim.player.engine.GenerationStep
import to.wim.player.engine.QueueState
import to.wim.player.logic.Generator
import to.wim.player.ui.GenerateError
import to.wim.player.ui.GeneratorUi
import to.wim.player.ui.Loadable
import to.wim.player.ui.MainViewModel
import to.wim.player.ui.common.SectionTitle
import to.wim.player.ui.theme.AppShapes
import to.wim.player.ui.theme.EmptyState
import to.wim.player.ui.theme.SkeletonBlock
import to.wim.player.ui.theme.readableWidth
import kotlin.math.roundToInt

/** The start screen: sliders, genres, artists and length, then Generate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenPreview: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val errorText = ui.error?.let { generateErrorText(it) }
    val openSettingsLabel = stringResource(R.string.action_open_settings)
    LaunchedEffect(ui.error) {
        val error = ui.error ?: return@LaunchedEffect
        val result =
            snackbar.showSnackbar(
                message = errorText.orEmpty(),
                actionLabel = if (error == GenerateError.LASTFM_KEY) openSettingsLabel else null,
                withDismissAction = error != GenerateError.LASTFM_KEY,
            )
        viewModel.dismissError()
        if (result == SnackbarResult.ActionPerformed) onOpenSettings()
    }

    val current = secrets ?: return
    val setupNeeded = current.lastFmApiKey.isBlank() || !current.spotifySignedIn

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!setupNeeded && !ui.generating && ui.loaded) {
                ExtendedFloatingActionButton(
                    onClick = { if (ui.length != null) viewModel.generate(onOpenPreview) },
                    icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_generate)) },
                )
            }
        },
        bottomBar = {
            ui.step?.let { step -> GeneratingBar(step = step, onCancel = viewModel::cancelGenerate) }
        },
    ) { padding ->
        if (setupNeeded) {
            EmptyState(
                icon = Icons.Rounded.Key,
                title = stringResource(R.string.setup_title),
                body = stringResource(R.string.setup_body),
                modifier = Modifier.padding(padding),
            ) {
                Button(onClick = onOpenSettings) { Text(openSettingsLabel) }
            }
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            GeneratorForm(ui = ui, queue = queue, viewModel = viewModel, onOpenQueue = onOpenQueue)
        }
    }
}

@Composable
fun generateErrorText(error: GenerateError): String =
    stringResource(
        when (error) {
            GenerateError.LASTFM_KEY -> R.string.error_lastfm_key
            GenerateError.NO_SEEDS -> R.string.error_no_seeds
            GenerateError.NOTHING_FOUND -> R.string.error_nothing_found
            GenerateError.NETWORK -> R.string.error_network
        },
    )

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GeneratorForm(
    ui: GeneratorUi,
    queue: QueueState,
    viewModel: MainViewModel,
    onOpenQueue: () -> Unit,
) {
    val focus = LocalFocusManager.current
    LazyColumn(
        modifier = Modifier.readableWidth().fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (queue.active) {
            item(key = "queue") { QueueBanner(queue = queue, onOpen = onOpenQueue) }
        }
        item(key = "mood") {
            SliderSection(
                title = stringResource(R.string.mood_title),
                value = ui.mood,
                onChange = viewModel::setMood,
                low = stringResource(R.string.mood_low),
                high = stringResource(R.string.mood_high),
            )
        }
        item(key = "familiarity") {
            SliderSection(
                title = stringResource(R.string.familiarity_title),
                value = ui.familiarity,
                onChange = viewModel::setFamiliarity,
                low = stringResource(R.string.familiarity_low),
                high = stringResource(R.string.familiarity_high),
            )
        }
        item(key = "genres") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle(stringResource(R.string.genres_title))
                OutlinedTextField(
                    value = ui.genreQuery,
                    onValueChange = viewModel::setGenreQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.genres_search)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (ui.genreQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setGenreQuery("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear))
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions =
                        KeyboardActions(onDone = {
                            if (ui.genreQuery.isNotBlank()) viewModel.toggleGenre(ui.genreQuery)
                            focus.clearFocus()
                        }),
                )
                GenreChips(ui = ui, onToggle = viewModel::toggleGenre, onRetry = viewModel::loadTopTags)
            }
        }
        item(key = "artists") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle(stringResource(R.string.artists_title))
                if (ui.artists.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ui.artists.forEach { name ->
                            val removeLabel = stringResource(R.string.action_remove_named, name)
                            InputChip(
                                selected = true,
                                onClick = { viewModel.removeArtist(name) },
                                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = null, Modifier.size(InputChipDefaults.IconSize)) },
                                modifier = Modifier.semantics { contentDescription = removeLabel },
                            )
                        }
                    }
                } else {
                    Text(
                        stringResource(if (ui.genres.isEmpty()) R.string.artists_none_help else R.string.artists_optional_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = ui.artistQuery,
                    onValueChange = viewModel::setArtistQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.artists_search)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (ui.artistQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setArtistQuery("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear))
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                )
                ArtistResults(ui = ui, onAdd = viewModel::addArtist)
            }
        }
        item(key = "length") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle(stringResource(R.string.length_title))
                val invalid = ui.length == null
                OutlinedTextField(
                    value = ui.lengthText,
                    onValueChange = viewModel::setLength,
                    modifier = Modifier.width(200.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.length_label)) },
                    isError = invalid,
                    supportingText = {
                        Text(stringResource(R.string.length_range, Generator.MIN_LENGTH, Generator.MAX_LENGTH))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                )
            }
        }
    }
}

@Composable
private fun SliderSection(
    title: String,
    value: Float,
    onChange: (Float) -> Unit,
    low: String,
    high: String,
) {
    val percent = (value * 100).roundToInt()
    val state = stringResource(R.string.slider_state, low, 100 - percent, high, percent)
    Column {
        SectionTitle(title)
        Slider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = state },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(low, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(high, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GenreChips(
    ui: GeneratorUi,
    onToggle: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val query = ui.genreQuery.trim().lowercase()
    val top = (ui.topTags as? Loadable.Ready)?.value.orEmpty().map { it.lowercase() }
    val selected = ui.genres
    val others = (ui.recentGenres + top).distinct().filterNot { name -> selected.any { it.equals(name, ignoreCase = true) } }
    val shown = (selected + others).filter { query.isEmpty() || it.contains(query) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { genre ->
                val isSelected = selected.any { it.equals(genre, ignoreCase = true) }
                FilterChip(selected = isSelected, onClick = { onToggle(genre) }, label = { Text(genre) })
            }
            if (query.isNotEmpty() && shown.none { it == query }) {
                SuggestionChip(
                    onClick = { onToggle(query) },
                    label = { Text(stringResource(R.string.genres_use, query)) },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
        }
        when (ui.topTags) {
            Loadable.Loading ->
                if (others.isEmpty()) {
                    val loading = stringResource(R.string.loading)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = loading },
                    ) {
                        repeat(4) { SkeletonBlock(height = 32.dp, modifier = Modifier.width(72.dp), shape = MaterialTheme.shapes.small) }
                    }
                }
            Loadable.Failed ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.genres_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }
            is Loadable.Ready -> Unit
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ArtistResults(
    ui: GeneratorUi,
    onAdd: (String) -> Unit,
) {
    when (val results = ui.artistResults) {
        null ->
            if (ui.artistQuery.isBlank()) {
                val suggestions = ui.suggestions.filterNot { name -> ui.artists.any { it.equals(name, ignoreCase = true) } }
                if (suggestions.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.artists_suggestions),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            suggestions.take(10).forEach { name -> SuggestionChip(onClick = { onAdd(name) }, label = { Text(name) }) }
                        }
                    }
                }
            }
        Loadable.Loading ->
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        Loadable.Failed ->
            Text(stringResource(R.string.artists_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        is Loadable.Ready ->
            if (results.value.isEmpty()) {
                Text(
                    stringResource(R.string.artists_no_results, ui.artistQuery.trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Card(shape = AppShapes.Item, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    results.value.take(6).forEach { artist ->
                        ListItem(
                            headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent =
                                artist.listeners?.let { count ->
                                    { Text(pluralStringResource(R.plurals.listeners, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), formatCount(count))) }
                                },
                            trailingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier.clickable { onAdd(artist.name) },
                        )
                    }
                }
            }
    }
}

/** A short, locale-aware count, like Last.fm's own ("2.1M", "2,1 mln."). */
fun formatCount(count: Long): String =
    android.icu.text.CompactDecimalFormat
        .getInstance(java.util.Locale.getDefault(), android.icu.text.CompactDecimalFormat.CompactStyle.SHORT)
        .format(count)

@Composable
private fun QueueBanner(
    queue: QueueState,
    onOpen: () -> Unit,
) {
    Card(
        shape = AppShapes.Group,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.queue_banner_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.queue_progress, queue.processed, queue.total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpen) { Text(stringResource(R.string.action_open)) }
        }
    }
}

@Composable
private fun GeneratingBar(
    step: GenerationStep,
    onCancel: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stepText(step), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
            Spacer(Modifier.height(4.dp))
            val fraction = stepFraction(step)
            if (fraction == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 8.dp))
            } else {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 8.dp))
            }
        }
    }
}

@Composable
private fun stepText(step: GenerationStep): String =
    when (step) {
        GenerationStep.Seeds -> stringResource(R.string.step_seeds)
        is GenerationStep.Artists -> stringResource(R.string.step_artists, step.done, step.total)
        is GenerationStep.Tracks -> stringResource(R.string.step_tracks, step.done, step.total)
        is GenerationStep.Moods -> stringResource(R.string.step_moods, step.done, step.total)
        GenerationStep.Picking -> stringResource(R.string.step_picking)
    }

/** Overall progress: artists 0–10 %, tracks 10–60 %, moods 60–95 %. */
private fun stepFraction(step: GenerationStep): Float? =
    when (step) {
        GenerationStep.Seeds -> null
        is GenerationStep.Artists -> 0.1f * step.done / step.total.coerceAtLeast(1)
        is GenerationStep.Tracks -> 0.1f + 0.5f * step.done / step.total.coerceAtLeast(1)
        is GenerationStep.Moods -> 0.6f + 0.35f * step.done / step.total.coerceAtLeast(1)
        GenerationStep.Picking -> 0.97f
    }
