package to.wim.player.logic

import java.text.Normalizer

/**
 * Text rules for comparing artist names and track titles from Last.fm and Spotify, which spell
 * the same song differently ("Kerala", "Kerala - Edit", "Kéràla (Remastered 2017)").
 */
object TrackText {
    private val Diacritics = Regex("\\p{M}+")
    private val NonWord = Regex("[^a-z0-9 ]+")
    private val Spaces = Regex("\\s+")

    /** Words in a bracket or after " - " that only describe the recording, not the song. */
    private val VersionWords =
        listOf(
            "remaster", "remastered", "mono", "stereo", "single version", "album version",
            "radio edit", "edit", "deluxe", "bonus track", "explicit", "clean", "original mix",
            "feat", "ft", "with", "from", "version", "mix", "anniversary",
        )

    /** Words that mark a different recording than the one Last.fm means (unless its title has them too). */
    private val UnwantedWords = listOf("live", "remix", "karaoke", "instrumental", "cover", "tribute", "acapella", "a cappella")

    private val Brackets = Regex("\\s*[(\\[]([^)\\]]*)[)\\]]")
    private val DashSuffix = Regex("\\s+-\\s+(.*)$")

    /** Lower case, no accents, apostrophes or punctuation, "&" as "and", single spaces. */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Diacritics, "")
        return decomposed
            .replace("&", " and ")
            // "Don't" and "Don’t" both become "dont".
            .replace("'", "")
            .replace("’", "")
            .replace(NonWord, " ")
            .replace(Spaces, " ")
            .trim()
    }

    /** An artist name for comparing: normalized and without a leading "the". */
    fun artistKey(artist: String): String = normalize(artist).removePrefix("the ")

    /**
     * The title without brackets or " - " suffixes that only name a version ("Remastered 2011",
     * "feat. X", "Radio Edit"). Brackets naming a different recording (live, remix) are kept so
     * they still count against a match.
     */
    fun baseTitle(title: String): String {
        var result = title
        result = Brackets.replace(result) { match -> if (isVersionNote(match.groupValues[1])) "" else match.value }
        DashSuffix.find(result)?.let { match -> if (isVersionNote(match.groupValues[1])) result = result.substring(0, match.range.first) }
        return normalize(result)
    }

    private fun isVersionNote(note: String): Boolean {
        val words = normalize(note)
        if (words.isEmpty()) return true
        if (UnwantedWords.any { containsWord(words, it) }) return false
        return VersionWords.any { containsWord(words, it) } || words.all { it.isDigit() || it == ' ' }
    }

    /** True when the title marks a live, remix, karaoke or similar recording. */
    fun isAlternateVersion(title: String): Boolean {
        val words = normalize(title)
        return UnwantedWords.any { containsWord(words, it) }
    }

    /** The key that identifies a song across services: artist and base title. */
    fun key(artist: String, title: String): String = artistKey(artist) + "|" + baseTitle(title)

    private fun containsWord(words: String, word: String): Boolean = " $words ".contains(" $word ")
}
