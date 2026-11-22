package to.wim.player.logic

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Suggests a name for a new playlist and tidies the name the user typed. */
object PlaylistName {
    /** Longest name Player sends to Spotify. */
    const val MAX_LENGTH = 100

    /** At most this many genres or artists go in a suggested name. */
    const val MAX_TOPICS = 2

    /** Moods below this are called calm, above [UPBEAT_FROM] upbeat; in between the name has no mood. */
    const val CALM_UNTIL = 0.35
    const val UPBEAT_FROM = 0.65

    enum class Vibe { CALM, NEUTRAL, UPBEAT }

    /**
     * The translated pieces of a name. [calm] and [upbeat] wrap the topic (`%1$s`), [dated] joins
     * the name (`%1$s`) and the date (`%2$s`), [fallback] is the topic when nothing was picked.
     */
    data class Templates(
        val calm: String,
        val upbeat: String,
        val dated: String,
        val fallback: String,
    )

    fun vibe(mood: Double): Vibe =
        when {
            mood < CALM_UNTIL -> Vibe.CALM
            mood > UPBEAT_FROM -> Vibe.UPBEAT
            else -> Vibe.NEUTRAL
        }

    /** The picked genres (or, without genres, artists), for example "Indie & Jazz"; null when nothing was picked. */
    fun topic(genres: List<String>, artists: List<String>): String? {
        val names = genres.map(::capitalize).ifEmpty { artists }.map(String::trim).filter(String::isNotEmpty).distinct()
        if (names.isEmpty()) return null
        return names.take(MAX_TOPICS).joinToString(" & ")
    }

    /** For example "Upbeat Indie & Jazz · 22 Nov". */
    fun suggest(
        genres: List<String>,
        artists: List<String>,
        mood: Double,
        date: LocalDate,
        locale: Locale,
        templates: Templates,
    ): String {
        val topic = topic(genres, artists) ?: templates.fallback
        val named =
            when (vibe(mood)) {
                Vibe.CALM -> templates.calm.format(topic)
                Vibe.UPBEAT -> templates.upbeat.format(topic)
                Vibe.NEUTRAL -> topic
            }
        val day = date.format(DateTimeFormatter.ofPattern("d MMM", locale)).trimEnd('.')
        return clean(templates.dated.format(named, day), templates.fallback)
    }

    /** The name to send: spaces tidied and at most [MAX_LENGTH] characters, or [fallback] when empty. */
    fun clean(input: String, fallback: String): String {
        val tidy = input.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH).trim()
        return tidy.ifEmpty { fallback }
    }

    private fun capitalize(word: String): String = word.trim().replaceFirstChar { it.titlecase(Locale.ROOT) }
}
