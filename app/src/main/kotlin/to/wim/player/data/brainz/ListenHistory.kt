package to.wim.player.data.brainz

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import to.wim.player.data.LISTENS_PAGE
import to.wim.player.data.ListenBrainzSource
import to.wim.player.logic.TrackText
import java.io.File

/** What has been read of one user's play history. Times are in seconds since 1970. */
@Serializable
data class ListenHistoryState(
    /** The ListenBrainz user, as typed (compared without case). */
    val username: String,
    /** [TrackText.key] of every track played. */
    val keys: List<String> = emptyList(),
    /** The newest play read; plays after it are fetched next time. */
    val newest: Long = 0,
    /** The oldest play read so far, where reading older plays goes on. */
    val oldest: Long = 0,
    /** True once the oldest play has been reached. */
    val complete: Boolean = false,
    /** When the last read finished (milliseconds). */
    val syncedAtMillis: Long = 0,
)

/** Where the history is kept between runs. */
interface ListenHistoryFile {
    fun read(): ListenHistoryState?

    fun write(state: ListenHistoryState)

    fun delete()
}

/** One JSON file. The app keeps it in `noBackupFilesDir`, so it stays on this phone. */
class JsonListenHistoryFile(private val file: File) : ListenHistoryFile {
    private val format = Json { ignoreUnknownKeys = true }

    override fun read(): ListenHistoryState? =
        runCatching { if (file.exists()) format.decodeFromString(ListenHistoryState.serializer(), file.readText()) else null }.getOrNull()

    override fun write(state: ListenHistoryState) {
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        temporary.writeText(format.encodeToString(ListenHistoryState.serializer(), state))
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }

    override fun delete() {
        file.delete()
    }
}

/** For the settings screen: how far reading the history is. */
data class ListenHistoryStatus(
    val songs: Int = 0,
    val syncing: Boolean = false,
    val complete: Boolean = false,
)

/**
 * The user's whole ListenBrainz play history, as the set of tracks they have played. The
 * statistics ListenBrainz offers stop at the top 1 000 tracks, so every play is read instead:
 * 1 000 a call, newest first, at the shared one-call-a-second pace. The first read of a long
 * history takes a few minutes and goes on where it stopped if it's interrupted; after that only
 * new plays are read.
 */
class ListenHistory(
    private val source: ListenBrainzSource,
    private val file: ListenHistoryFile,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var state: ListenHistoryState? = null
    private var loaded = false
    private var keySet: Set<String> = emptySet()

    private val _status = MutableStateFlow(ListenHistoryStatus())
    val status: StateFlow<ListenHistoryStatus> = _status.asStateFlow()

    /** Keys of the tracks read so far for [username]; empty for another user. Reads no network. */
    fun keys(username: String): Set<String> =
        synchronized(this) {
            load()
            if (state?.username?.equals(username.trim(), ignoreCase = true) == true) keySet else emptySet()
        }

    /** Shows what is stored for [username] without reading anything new. */
    fun show(username: String) =
        synchronized(this) {
            load()
            val current = state?.takeIf { it.username.equals(username.trim(), ignoreCase = true) }
            _status.value = ListenHistoryStatus(current?.keys?.size ?: 0, syncing = false, complete = current?.complete ?: false)
        }

    /**
     * Reads plays that aren't stored yet. Does nothing when the history is complete and was read
     * less than [minIntervalMillis] ago. Network errors are thrown; what was read is kept.
     */
    suspend fun sync(username: String, minIntervalMillis: Long = MIN_INTERVAL_MILLIS) {
        val name = username.trim()
        if (name.isEmpty()) return
        mutex.withLock {
            var current = synchronized(this) {
                load()
                state?.takeIf { it.username.equals(name, ignoreCase = true) } ?: ListenHistoryState(name)
            }
            if (current.complete && nowMillis() - current.syncedAtMillis < minIntervalMillis) {
                publish(current, syncing = false)
                return
            }
            publish(current, syncing = true)
            try {
                val keys = LinkedHashSet(current.keys)
                if (current.newest > 0) current = readNewer(name, current, keys)
                if (!current.complete) current = readOlder(name, current, keys)
                current = current.copy(syncedAtMillis = nowMillis())
                save(current)
            } finally {
                publish(current, syncing = false)
            }
        }
    }

    /** Forgets everything read (for example when the user clears the stored data). */
    fun clear() =
        synchronized(this) {
            file.delete()
            state = null
            keySet = emptySet()
            _status.value = ListenHistoryStatus()
        }

    /** Plays after the newest one stored, oldest first, until none are left. */
    private suspend fun readNewer(name: String, start: ListenHistoryState, keys: MutableSet<String>): ListenHistoryState {
        var newest = start.newest
        repeat(MAX_PAGES) {
            val page = source.listens(name, minTs = newest)
            if (page.isEmpty()) return start.copy(keys = keys.toList(), newest = newest)
            page.forEach { keys += TrackText.key(it.artist, it.title) }
            newest = maxOf(newest, page.maxOf { it.listenedAt })
            if (page.size < LISTENS_PAGE) return start.copy(keys = keys.toList(), newest = newest)
        }
        return start.copy(keys = keys.toList(), newest = newest)
    }

    /** Plays before the oldest one stored (or from now), newest first, saving as it goes. */
    private suspend fun readOlder(name: String, start: ListenHistoryState, keys: MutableSet<String>): ListenHistoryState {
        var current = start
        repeat(MAX_PAGES) { index ->
            val page = source.listens(name, maxTs = current.oldest.takeIf { it > 0 })
            if (page.isNotEmpty()) {
                page.forEach { keys += TrackText.key(it.artist, it.title) }
                current =
                    current.copy(
                        keys = keys.toList(),
                        newest = maxOf(current.newest, page.maxOf { it.listenedAt }),
                        oldest = page.minOf { it.listenedAt },
                    )
            }
            if (page.size < LISTENS_PAGE) return current.copy(keys = keys.toList(), complete = true)
            publish(current, syncing = true)
            if ((index + 1) % SAVE_EVERY_PAGES == 0) save(current)
        }
        return current
    }

    private fun load() {
        if (loaded) return
        loaded = true
        state = file.read()
        keySet = state?.keys?.toHashSet().orEmpty()
    }

    private fun save(value: ListenHistoryState) =
        synchronized(this) {
            file.write(value)
            state = value
            keySet = value.keys.toHashSet()
        }

    private fun publish(value: ListenHistoryState, syncing: Boolean) {
        _status.value = ListenHistoryStatus(value.keys.size, syncing, value.complete)
    }

    companion object {
        /** New plays are read at most this often (6 hours). */
        const val MIN_INTERVAL_MILLIS = 6L * 60 * 60 * 1000

        /** A limit for one read: 1 000 calls, a million plays. */
        const val MAX_PAGES = 1_000

        /** During the first read, progress is saved every this many calls. */
        const val SAVE_EVERY_PAGES = 10
    }
}
