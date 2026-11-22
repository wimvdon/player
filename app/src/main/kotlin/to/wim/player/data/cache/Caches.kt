package to.wim.player.data.cache

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** A small key-value cache. [ttlMillis] null keeps entries forever. */
interface KeyValueCache<T> {
    fun get(key: String): T?

    fun put(key: String, value: T)

    val size: Int

    fun clear()
}

@Serializable
data class Stamped<T>(
    val savedAt: Long,
    val value: T,
)

/**
 * A cache kept in memory and saved as one JSON file. Writes only mark it changed; [flush] saves
 * (the app flushes after each generation and when it goes to the background).
 */
class JsonFileCache<T>(
    private val file: File,
    valueSerializer: KSerializer<T>,
    private val ttlMillis: Long?,
    private val now: () -> Long = System::currentTimeMillis,
) : KeyValueCache<T> {
    private val serializer = MapSerializer(String.serializer(), Stamped.serializer(valueSerializer))
    private val lock = Any()
    private var entries: MutableMap<String, Stamped<T>>? = null
    private var dirty = false

    private fun loaded(): MutableMap<String, Stamped<T>> {
        entries?.let { return it }
        val read =
            runCatching { if (file.exists()) Format.decodeFromString(serializer, file.readText()) else emptyMap() }
                .getOrDefault(emptyMap())
        val current = now()
        val fresh = read.filterValues { isFresh(it, current) }.toMutableMap()
        if (fresh.size != read.size) dirty = true
        entries = fresh
        return fresh
    }

    private fun isFresh(entry: Stamped<T>, current: Long): Boolean = ttlMillis == null || current - entry.savedAt < ttlMillis

    override fun get(key: String): T? =
        synchronized(lock) {
            val entry = loaded()[key] ?: return null
            if (!isFresh(entry, now())) {
                loaded().remove(key)
                dirty = true
                return null
            }
            entry.value
        }

    override fun put(key: String, value: T) {
        synchronized(lock) {
            loaded()[key] = Stamped(now(), value)
            dirty = true
        }
    }

    override val size: Int get() = synchronized(lock) { loaded().size }

    override fun clear() {
        synchronized(lock) {
            entries = mutableMapOf()
            dirty = false
            file.delete()
        }
    }

    /** Size of the saved file in bytes. */
    val fileBytes: Long get() = if (file.exists()) file.length() else 0

    /** Saves the cache when it changed. Call off the main thread. */
    fun flush() {
        val snapshot =
            synchronized(lock) {
                if (!dirty) return
                dirty = false
                HashMap(entries ?: return)
            }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(Format.encodeToString(serializer, snapshot))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        private val Format = Json { ignoreUnknownKeys = true }
    }
}

/** A cache that only lives in memory, for tests. */
class MemoryCache<T> : KeyValueCache<T> {
    private val map = HashMap<String, T>()

    override fun get(key: String): T? = synchronized(map) { map[key] }

    override fun put(key: String, value: T) {
        synchronized(map) { map[key] = value }
    }

    override val size: Int get() = synchronized(map) { map.size }

    override fun clear() {
        synchronized(map) { map.clear() }
    }
}
