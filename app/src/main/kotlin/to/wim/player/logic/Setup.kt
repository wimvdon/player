package to.wim.player.logic

/** Checks for the keys the user pastes in, and the values they paste into Spotify's dashboard. */
object Setup {
    private val Hex32 = Regex("^[0-9a-fA-F]{32}$")

    /** Spotify Client IDs and Last.fm API keys are both 32 hexadecimal characters. */
    fun isValidKey(value: String): Boolean = Hex32.matches(value.trim())

    /** The redirect URI the app listens on: its package name as the scheme. */
    fun redirectUri(packageName: String): String = "$packageName://callback"

    /** A certificate hash as Spotify's dashboard shows it: "AB:CD:…" in capitals. */
    fun fingerprint(hash: ByteArray): String = hash.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    /** The scopes the app asks Spotify for. */
    val SpotifyScopes =
        arrayOf(
            "playlist-modify-private",
            "playlist-modify-public",
            "user-modify-playback-state",
            "user-top-read",
            "user-library-read",
        )

    /** The scopes as stored with a sign-in, to spot sign-ins from older versions. */
    val scopeString: String = SpotifyScopes.joinToString(" ")

    /** The name of the playlist Player fills. */
    const val PLAYLIST_NAME = "Player"
}
