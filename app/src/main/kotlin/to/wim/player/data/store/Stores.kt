package to.wim.player.data.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import to.wim.player.data.model.AppSettings
import to.wim.player.data.model.QueueMode
import to.wim.player.data.model.Secrets
import to.wim.player.data.model.ThemeMode
import to.wim.player.data.spotify.SpotifyAccount
import to.wim.player.data.spotify.TokenGrant
import to.wim.player.logic.Generator
import to.wim.player.logic.Ordering
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Android backs this one up (see res/xml/data_extraction_rules.xml); keys and sign-in are not in it.
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "backed_up_settings")

// On this phone only, every value encrypted with a key from the Android Keystore.
private val Context.secretStore: DataStore<Preferences> by preferencesDataStore(name = "device_secrets")

/** Settings worth having on a new phone: appearance, queue options and the last generator input. */
class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsStore

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val lastFmUser = stringPreferencesKey("lastfm_username")
        val queueMode = stringPreferencesKey("queue_mode")
        val ordering = stringPreferencesKey("ordering")
        val reccoBeats = booleanPreferencesKey("reccobeats")
        val mood = floatPreferencesKey("mood")
        val familiarity = floatPreferencesKey("familiarity")
        val length = intPreferencesKey("length")
        val genres = stringPreferencesKey("genres")
        val recentGenres = stringPreferencesKey("recent_genres")
        val artists = stringPreferencesKey("artists")
    }

    val settings: Flow<AppSettings> =
        store.data.map { prefs ->
            AppSettings(
                themeMode = enumOr(prefs[Keys.theme], ThemeMode.SYSTEM),
                dynamicColor = prefs[Keys.dynamicColor] ?: false,
                lastFmUsername = prefs[Keys.lastFmUser].orEmpty(),
                queueMode = enumOr(prefs[Keys.queueMode], QueueMode.ROLLING),
                ordering = enumOr(prefs[Keys.ordering], Ordering.MOOD_CURVE),
                useReccoBeats = prefs[Keys.reccoBeats] ?: false,
                mood = prefs[Keys.mood] ?: 0.5f,
                familiarity = prefs[Keys.familiarity] ?: 0.5f,
                length = prefs[Keys.length] ?: Generator.DEFAULT_LENGTH,
                selectedGenres = split(prefs[Keys.genres]),
                recentGenres = split(prefs[Keys.recentGenres]),
                selectedArtists = split(prefs[Keys.artists]),
            )
        }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setTheme(mode: ThemeMode) = store.edit { it[Keys.theme] = mode.name }

    suspend fun setDynamicColor(on: Boolean) = store.edit { it[Keys.dynamicColor] = on }

    suspend fun setLastFmUsername(name: String) = store.edit { it[Keys.lastFmUser] = name.trim() }

    suspend fun setQueueMode(mode: QueueMode) = store.edit { it[Keys.queueMode] = mode.name }

    suspend fun setOrdering(ordering: Ordering) = store.edit { it[Keys.ordering] = ordering.name }

    suspend fun setReccoBeats(on: Boolean) = store.edit { it[Keys.reccoBeats] = on }

    /** Saves the generator's input so it's there next time. */
    suspend fun setGeneratorInput(mood: Float, familiarity: Float, length: Int, genres: List<String>, recentGenres: List<String>, artists: List<String>) =
        store.edit {
            it[Keys.mood] = mood
            it[Keys.familiarity] = familiarity
            it[Keys.length] = length
            it[Keys.genres] = join(genres)
            it[Keys.recentGenres] = join(recentGenres)
            it[Keys.artists] = join(artists)
        }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E = enumValues<E>().firstOrNull { it.name == name } ?: default

    // Names can hold commas, so lists are stored one per line.
    private fun split(value: String?): List<String> = value?.split('\n')?.filter(String::isNotBlank).orEmpty()

    private fun join(list: List<String>): String = list.joinToString("\n") { it.replace('\n', ' ') }
}

/** Keys, Spotify sign-in and the quota pause: encrypted, never backed up. */
class SecretStore(context: Context) : SpotifyAccount {
    private val store = context.applicationContext.secretStore
    private val crypto = SecretCrypto()

    private object Keys {
        val clientId = stringPreferencesKey("spotify_client_id")
        val lastFmKey = stringPreferencesKey("lastfm_api_key")
        val access = stringPreferencesKey("spotify_access")
        val refresh = stringPreferencesKey("spotify_refresh")
        val expiresAt = stringPreferencesKey("spotify_expires_at")
        val userName = stringPreferencesKey("spotify_user")
        val pausedUntil = stringPreferencesKey("spotify_paused_until")
    }

    val secrets: Flow<Secrets> =
        store.data.map { prefs ->
            Secrets(
                spotifyClientId = read(prefs, Keys.clientId),
                lastFmApiKey = read(prefs, Keys.lastFmKey),
                spotifyAccessToken = read(prefs, Keys.access),
                spotifyRefreshToken = read(prefs, Keys.refresh),
                spotifyExpiresAtMillis = read(prefs, Keys.expiresAt).toLongOrNull() ?: 0,
                spotifyUserName = read(prefs, Keys.userName),
                spotifyPausedUntilMillis = read(prefs, Keys.pausedUntil).toLongOrNull() ?: 0,
            )
        }

    suspend fun current(): Secrets = secrets.first()

    private fun read(prefs: Preferences, key: Preferences.Key<String>): String = prefs[key]?.let { crypto.decrypt(it) }.orEmpty()

    private suspend fun write(vararg values: Pair<Preferences.Key<String>, String>) {
        store.edit { prefs -> values.forEach { (key, value) -> if (value.isEmpty()) prefs.remove(key) else prefs[key] = crypto.encrypt(value) } }
    }

    suspend fun setSpotifyClientId(id: String) {
        val trimmed = id.trim()
        if (trimmed == current().spotifyClientId) return
        // Tokens belong to the old Client ID.
        write(Keys.clientId gets trimmed, Keys.access gets "", Keys.refresh gets "", Keys.expiresAt gets "", Keys.userName gets "")
    }

    suspend fun setLastFmKey(key: String) = write(Keys.lastFmKey gets key.trim())

    suspend fun setSpotifyUserName(name: String) = write(Keys.userName gets name)

    suspend fun clearPause() = write(Keys.pausedUntil gets "")

    override suspend fun clientId(): String = current().spotifyClientId

    override suspend fun accessToken(): String = current().spotifyAccessToken

    override suspend fun refreshToken(): String = current().spotifyRefreshToken

    override suspend fun expiresAtMillis(): Long = current().spotifyExpiresAtMillis

    override suspend fun saveGrant(grant: TokenGrant, nowMillis: Long) {
        val expiresAt = if (grant.accessToken.isEmpty()) 0 else nowMillis + grant.expiresInSeconds * 1000
        val values = mutableListOf(Keys.access gets grant.accessToken, Keys.expiresAt gets expiresAt.toString())
        grant.refreshToken?.takeIf(String::isNotEmpty)?.let { values += Keys.refresh gets it }
        write(*values.toTypedArray())
    }

    override suspend fun signOut() = write(Keys.access gets "", Keys.refresh gets "", Keys.expiresAt gets "", Keys.userName gets "")

    override suspend fun pausedUntilMillis(): Long = current().spotifyPausedUntilMillis

    override suspend fun pauseUntil(millis: Long) = write(Keys.pausedUntil gets millis.toString())
}

private infix fun Preferences.Key<String>.gets(value: String): Pair<Preferences.Key<String>, String> = Pair(this, value)

/** AES-GCM with a key that never leaves the Android Keystore. */
private class SecretCrypto {
    private val alias = "player_secrets"

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /** Empty when the value can't be read (for example after the key was reset). */
    fun decrypt(stored: String): String =
        runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrDefault("")
}
