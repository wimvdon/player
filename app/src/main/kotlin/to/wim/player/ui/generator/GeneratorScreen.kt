package to.wim.player.ui.generator

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Pending
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import to.wim.player.R
import to.wim.player.data.model.ArtistHit
import to.wim.player.engine.GenerationProgress
import to.wim.player.engine.GenerationStep
import to.wim.player.engine.QueueState
import to.wim.player.logic.Generator
import to.wim.player.logic.LengthStep
import to.wim.player.ui.GenerateError
import to.wim.player.ui.GeneratorUi
import to.wim.player.ui.Loadable
import to.wim.player.ui.MainViewModel
import to.wim.player.ui.common.ExpressiveSlider
import to.wim.player.ui.common.MorphingLoader
import to.wim.player.ui.common.NumberStepper
import to.wim.player.ui.common.SectionTitle
import to.wim.player.ui.common.WavyProgress
import to.wim.player.ui.common.Wordmark
import to.wim.player.ui.theme.AppShapes
import to.wim.player.ui.theme.EmptyState
import to.wim.player.ui.theme.SkeletonBlock
import to.wim.player.ui.theme.readableWidth

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
        snackbar.showSnackbar(message = errorText.orEmpty(), withDismissAction = true)
        viewModel.dismissError()
    }

    val current = secrets ?: return
    val setupNeeded = !current.spotifySignedIn

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Wordmark(stringResource(R.string.app_name)) },
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
                GenerateButton(onClick = { if (ui.length != null) viewModel.generate(onOpenPreview) })
            }
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
            val step = ui.step
            if (step != null) {
                GeneratingPanel(step = step, onCancel = viewModel::cancelGenerate)
            } else {
                GeneratorForm(ui = ui, queue = queue, viewModel = viewModel, onOpenQueue = onOpenQueue)
            }
        }
    }
}

@Composable
fun generateErrorText(error: GenerateError): String =
    stringResource(
        when (error) {
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
        item(key = "headline") { Headline() }
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
                TextField(
                    value = ui.genreQuery,
                    onValueChange = viewModel::setGenreQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = SearchFieldShape,
                    colors = searchFieldColors(),
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
                                avatar = { Initial(name) },
                                shape = CircleShape,
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
                TextField(
                    value = ui.artistQuery,
                    onValueChange = viewModel::setArtistQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = SearchFieldShape,
                    colors = searchFieldColors(),
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
                ArtistResults(ui = ui, onAdd = { viewModel.addArtist(it) }, onPick = { viewModel.addArtist(it) })
            }
        }
        item(key = "length") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle(stringResource(R.string.length_title))
                val length = ui.length ?: Generator.DEFAULT_LENGTH
                NumberStepper(
                    value = length,
                    unit = stringResource(R.string.length_unit),
                    description = pluralStringResource(R.plurals.length_tracks, length, length),
                    onMinus = { viewModel.setLength(LengthStep.step(ui.length, -1).toString()) },
                    onPlus = { viewModel.setLength(LengthStep.step(ui.length, +1).toString()) },
                    minusLabel = stringResource(R.string.action_fewer_tracks),
                    plusLabel = stringResource(R.string.action_more_tracks),
                    minusIcon = Icons.Rounded.Remove,
                    plusIcon = Icons.Rounded.Add,
                    canMinus = LengthStep.canStep(ui.length, -1),
                    canPlus = LengthStep.canStep(ui.length, +1),
                )
            }
        }
    }
}

/** The big question on top, with one word in the accent colour. */
@Composable
private fun Headline() {
    val word = stringResource(R.string.generator_headline_word)
    val full = stringResource(R.string.generator_headline, word)
    val accent = MaterialTheme.colorScheme.primary
    val text =
        remember(full, word, accent) {
            buildAnnotatedString {
                append(full)
                val at = full.indexOf(word)
                if (at >= 0) addStyle(SpanStyle(color = accent), at, at + word.length)
            }
        }
    Text(
        text,
        style = MaterialTheme.typography.displaySmall,
        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
    )
}

/** Generate: a large FAB that turns into a pill while pressed. */
@Composable
private fun GenerateButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 40.dp else 28.dp, label = "fab")
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "fabScale")
    ExtendedFloatingActionButton(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        interactionSource = interaction,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        icon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null, Modifier.size(28.dp)) },
        text = { Text(stringResource(R.string.action_generate), style = MaterialTheme.typography.titleLarge) },
        modifier = Modifier.heightIn(min = 80.dp).graphicsLayer { scaleX = scale; scaleY = scale },
    )
}

/** While generating: the morphing loader, a playful title, a wavy progress bar and the steps so far. */
@Composable
private fun GeneratingPanel(
    step: GenerationStep,
    onCancel: () -> Unit,
) {
    val current = GenerationProgress.index(step)
    Column(
        Modifier.readableWidth().fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
    ) {
        MorphingLoader(size = 132.dp)
        Text(
            stringResource(R.string.generating_title),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        WavyProgress(progress = GenerationProgress.fraction(step))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val labels =
                listOf(
                    stringResource(R.string.step_seeds),
                    if (step is GenerationStep.Artists) stepText(step) else stringResource(R.string.step_artists_short),
                    if (step is GenerationStep.Tracks) stepText(step) else stringResource(R.string.step_tracks_short),
                    if (step is GenerationStep.Moods) stepText(step) else stringResource(R.string.step_moods_short),
                    stringResource(R.string.step_picking),
                )
            labels.forEachIndexed { index, label ->
                val done = index < current
                val now = index == current
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = if (now) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                ) {
                    Icon(
                        when {
                            done -> Icons.Rounded.CheckCircle
                            now -> Icons.Rounded.Pending
                            else -> Icons.Rounded.RadioButtonUnchecked
                        },
                        contentDescription = null,
                        tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (now) FontWeight.Medium else FontWeight.Normal,
                        color = if (done || now) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
    }
}

/** The first letter of an artist's name in a small accent circle, for the artist chips. */
@Composable
private fun Initial(name: String) {
    Box(
        Modifier.size(InputChipDefaults.AvatarSize).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

private val SearchFieldShape = RoundedCornerShape(16.dp)

@Composable
private fun searchFieldColors() =
    TextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
    )

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
        ExpressiveSlider(value = value, onValueChange = onChange, state = state)
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
                val corner by animateDpAsState(if (isSelected) 18.dp else 10.dp, label = "chip")
                FilterChip(
                    selected = isSelected,
                    onClick = { onToggle(genre) },
                    label = { Text(genre) },
                    leadingIcon = if (isSelected) ({ Icon(Icons.Rounded.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                    shape = RoundedCornerShape(corner),
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                )
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
    onPick: (ArtistHit) -> Unit,
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
                                artist.disambiguation?.let { note ->
                                    { Text(note, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                },
                            trailingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier.clickable { onPick(artist) },
                        )
                    }
                }
            }
    }
}

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
                    if (queue.playlistId == null) {
                        stringResource(R.string.queue_progress_matching, queue.processed, queue.total)
                    } else {
                        stringResource(R.string.queue_progress_adding, queue.queued, queue.found)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpen) { Text(stringResource(R.string.action_open)) }
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
