package to.wim.player.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.wim.player.PlayerApp
import to.wim.player.R
import to.wim.player.data.lastfm.LastFmException
import to.wim.player.data.model.AppSettings
import to.wim.player.data.model.LfmArtist
import to.wim.player.data.model.Secrets
import to.wim.player.engine.EmptyPoolException
import to.wim.player.engine.GenerationInput
import to.wim.player.engine.GenerationResult
import to.wim.player.engine.GenerationStep
import to.wim.player.engine.NoSeedsException
import to.wim.player.logic.Candidate
import to.wim.player.logic.Generator
import to.wim.player.logic.PlaylistName
import to.wim.player.logic.Setup
import to.wim.player.logic.TrackText
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/** Why a generation failed, for the message. */
enum class GenerateError { LASTFM_KEY, NO_SEEDS, NOTHING_FOUND, NETWORK }

/** A list that loads over the network. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    data object Failed : Loadable<Nothing>

    data class Ready<T>(val value: T) : Loadable<T>
}

data class GeneratorUi(
    val loaded: Boolean = false,
    val mood: Float = 0.5f,
    val familiarity: Float = 0.5f,
    val lengthText: String = Generator.DEFAULT_LENGTH.toString(),
    val genres: List<String> = emptyList(),
    val recentGenres: List<String> = emptyList(),
    val genreQuery: String = "",
    val topTags: Loadable<List<String>> = Loadable.Loading,
    val artists: List<String> = emptyList(),
    val artistQuery: String = "",
    val artistResults: Loadable<List<LfmArtist>>? = null,
    val suggestions: List<String> = emptyList(),
    val step: GenerationStep? = null,
    val error: GenerateError? = null,
) {
    val length: Int? get() = Generator.parseLength(lengthText)
    val generating: Boolean get() = step != null
}

/** The generator, preview and queue screens share this. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as PlayerApp).graph

    val settings: StateFlow<AppSettings?> = graph.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val secrets: StateFlow<Secrets?> = graph.secrets.secrets.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val queue = graph.queue.state
    val preview: StateFlow<GenerationResult?> = graph.preview.asStateFlow()

    private val _ui = MutableStateFlow(GeneratorUi())
    val ui: StateFlow<GeneratorUi> = _ui.asStateFlow()

    private var generateJob: Job? = null
    private var searchJob: Job? = null
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = graph.settings.current()
            _ui.update {
                it.copy(
                    loaded = true,
                    mood = saved.mood,
                    familiarity = saved.familiarity,
                    lengthText = saved.length.toString(),
                    genres = saved.selectedGenres,
                    recentGenres = saved.recentGenres,
                    artists = saved.selectedArtists,
                )
            }
            loadTopTags()
            loadSuggestions()
        }
    }

    fun loadTopTags() {
        viewModelScope.launch {
            if (graph.secrets.current().lastFmApiKey.isBlank()) return@launch
            _ui.update { it.copy(topTags = Loadable.Loading) }
            val tags = runCatching { graph.lastFm.topTags() }.getOrNull()
            _ui.update { it.copy(topTags = if (tags == null) Loadable.Failed else Loadable.Ready(tags)) }
            graph.flushCaches()
        }
    }

    private fun loadSuggestions() {
        viewModelScope.launch {
            if (!graph.secrets.current().spotifySignedIn) return@launch
            val names = runCatching { graph.catalog.topArtists() }.getOrNull().orEmpty()
            _ui.update { it.copy(suggestions = names) }
        }
    }

    /** Called when the keys change (back from the settings). */
    fun refreshAfterSetup() {
        if (_ui.value.topTags !is Loadable.Ready) loadTopTags()
        if (_ui.value.suggestions.isEmpty()) loadSuggestions()
    }

    fun setMood(value: Float) = edit { it.copy(mood = value) }

    fun setFamiliarity(value: Float) = edit { it.copy(familiarity = value) }

    fun setLength(text: String) = edit { it.copy(lengthText = text.filter(Char::isDigit).take(3)) }

    fun setGenreQuery(text: String) = _ui.update { it.copy(genreQuery = text) }

    fun toggleGenre(genre: String) =
        edit { state ->
            val name = genre.trim().lowercase()
            if (name.isEmpty()) return@edit state
            val selected = state.genres.any { it.equals(name, ignoreCase = true) }
            if (selected) {
                state.copy(genres = state.genres.filterNot { it.equals(name, ignoreCase = true) })
            } else {
                state.copy(
                    genres = state.genres + name,
                    recentGenres = (listOf(name) + state.recentGenres.filterNot { it.equals(name, ignoreCase = true) }).take(RECENT_GENRES),
                    genreQuery = "",
                )
            }
        }

    fun setArtistQuery(text: String) {
        _ui.update { it.copy(artistQuery = text) }
        searchJob?.cancel()
        val query = text.trim()
        if (query.length < 2) {
            _ui.update { it.copy(artistResults = null) }
            return
        }
        searchJob =
            viewModelScope.launch {
                delay(SEARCH_DELAY_MILLIS)
                _ui.update { it.copy(artistResults = Loadable.Loading) }
                val results = runCatching { graph.lastFm.searchArtists(query) }.getOrNull()
                _ui.update { it.copy(artistResults = if (results == null) Loadable.Failed else Loadable.Ready(results)) }
            }
    }

    fun addArtist(name: String) =
        edit { state ->
            if (state.artists.any { TrackText.artistKey(it) == TrackText.artistKey(name) }) {
                state.copy(artistQuery = "", artistResults = null)
            } else {
                state.copy(artists = state.artists + name, artistQuery = "", artistResults = null)
            }
        }

    fun removeArtist(name: String) = edit { state -> state.copy(artists = state.artists - name) }

    fun dismissError() = _ui.update { it.copy(error = null) }

    /** Builds a new set; [onDone] runs when the preview is ready. */
    fun generate(onDone: () -> Unit) {
        val state = _ui.value
        val length = state.length ?: return
        if (state.generating) return
        generateJob =
            viewModelScope.launch {
                _ui.update { it.copy(step = GenerationStep.Seeds, error = null) }
                try {
                    val settings = graph.settings.current()
                    val input =
                        GenerationInput(
                            seedArtists = state.artists,
                            genres = state.genres,
                            mood = state.mood.toDouble(),
                            familiarity = state.familiarity.toDouble(),
                            length = length,
                            ordering = settings.ordering,
                            lastFmUsername = settings.lastFmUsername,
                        )
                    val engine = graph.engine()
                    val result = withContext(Dispatchers.Default) { engine.generate(input) { step -> _ui.update { it.copy(step = step) } } }
                    graph.preview.value = result
                    _ui.update { it.copy(step = null) }
                    onDone()
                } catch (e: CancellationException) {
                    _ui.update { it.copy(step = null) }
                    throw e
                } catch (e: Exception) {
                    val error =
                        when (e) {
                            is LastFmException -> if (e.isKeyProblem) GenerateError.LASTFM_KEY else GenerateError.NETWORK
                            is NoSeedsException -> GenerateError.NO_SEEDS
                            is EmptyPoolException -> GenerateError.NOTHING_FOUND
                            else -> GenerateError.NETWORK
                        }
                    _ui.update { it.copy(step = null, error = error) }
                } finally {
                    graph.flushCaches()
                }
            }
    }

    fun cancelGenerate() {
        generateJob?.cancel()
        _ui.update { it.copy(step = null) }
    }

    /** Removes a track from the preview; returns its position for Undo. */
    fun removeFromPreview(track: Candidate): Int {
        val current = graph.preview.value ?: return -1
        val index = current.tracks.indexOf(track)
        if (index < 0) return -1
        graph.preview.value = current.copy(tracks = current.tracks.toMutableList().apply { removeAt(index) })
        return index
    }

    fun restoreToPreview(track: Candidate, index: Int) {
        val current = graph.preview.value ?: return
        val list = current.tracks.toMutableList()
        list.add(index.coerceIn(0, list.size), track)
        graph.preview.value = current.copy(tracks = list)
    }

    /** The cover for a track, when it was matched on Spotify before (no search is made for it). */
    fun coverOf(track: Candidate): String? = graph.resolver.cached(track.artist, track.title)?.imageUrl

    /** A name for the new playlist, from the picked genres or artists, the mood and today's date. */
    fun suggestPlaylistName(): String {
        val app = getApplication<Application>()
        val state = _ui.value
        val templates =
            PlaylistName.Templates(
                calm = app.getString(R.string.playlist_name_calm),
                upbeat = app.getString(R.string.playlist_name_upbeat),
                dated = app.getString(R.string.playlist_name_dated),
                fallback = Setup.PLAYLIST_NAME,
            )
        val locale = app.resources.configuration.locales[0]
        return PlaylistName.suggest(state.genres, state.artists, state.mood.toDouble(), LocalDate.now(), locale, templates)
    }

    /** Makes a new playlist called [name] with every track of the preview. */
    fun startQueue(name: String): Boolean {
        val tracks = graph.preview.value?.tracks.orEmpty()
        if (tracks.isEmpty()) return false
        val description = getApplication<Application>().getString(R.string.playlist_description)
        graph.queue.start(tracks, PlaylistName.clean(name, Setup.PLAYLIST_NAME), description)
        return true
    }

    fun stopQueue() = graph.queue.stop()

    fun resumeQueue() = graph.queue.resume()

    private fun edit(change: (GeneratorUi) -> GeneratorUi) {
        _ui.update(change)
        saveJob?.cancel()
        saveJob =
            viewModelScope.launch {
                delay(SAVE_DELAY_MILLIS)
                val state = _ui.value
                graph.settings.setGeneratorInput(
                    mood = state.mood,
                    familiarity = state.familiarity,
                    length = state.length ?: Generator.DEFAULT_LENGTH,
                    genres = state.genres,
                    recentGenres = state.recentGenres,
                    artists = state.artists,
                )
            }
    }

    companion object {
        private const val RECENT_GENRES = 12
        private const val SEARCH_DELAY_MILLIS = 350L
        private const val SAVE_DELAY_MILLIS = 600L
    }
}
