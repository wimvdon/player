package to.wim.player.logic

/**
 * The genres the picker offers. MusicBrainz has no "most used" chart like Last.fm had, so this is
 * a fixed list of common MusicBrainz genre names; the user can still type any other genre.
 */
object Genres {
    val Popular: List<String> =
        listOf(
            "rock", "pop", "electronic", "hip hop", "jazz", "indie rock", "alternative rock", "folk",
            "soul", "r&b", "metal", "punk", "house", "techno", "ambient", "classical", "blues",
            "country", "reggae", "funk", "disco", "trip hop", "downtempo", "dream pop", "shoegaze",
            "post-rock", "synth-pop", "new wave", "singer-songwriter", "drum and bass", "dubstep",
            "lo-fi", "psychedelic rock", "hard rock", "indie pop", "garage rock", "latin", "afrobeat",
            "k-pop", "soundtrack",
        )
}
