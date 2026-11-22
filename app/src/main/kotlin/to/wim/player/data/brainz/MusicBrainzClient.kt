package to.wim.player.data.brainz

import okhttp3.HttpUrl.Companion.toHttpUrl
import to.wim.player.data.MusicBrainzSource
import to.wim.player.logic.WeightedTag

/** MusicBrainz over HTTP (no key). No caching here. */
class MusicBrainzClient(private val http: BrainzHttp) : MusicBrainzSource {
    override suspend fun searchArtists(name: String, limit: Int): List<MbArtist> {
        val query = "artist:\"${name.replace("\"", " ")}\""
        val url = "$BASE/artist".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("limit", "$limit")
            .addQueryParameter("fmt", "json")
            .build()
        val answer = http.get(url, "GET /ws/2/artist?query")
        return if (answer.hasData) MusicBrainzJson.artistSearch(answer.body) else emptyList()
    }

    override suspend fun artistTags(mbid: String): List<WeightedTag> {
        val url = "$BASE/artist/$mbid".toHttpUrl().newBuilder()
            .addQueryParameter("inc", "genres+tags")
            .addQueryParameter("fmt", "json")
            .build()
        val answer = http.get(url, "GET /ws/2/artist/{mbid}")
        return if (answer.hasData) MusicBrainzJson.artistTags(answer.body) else emptyList()
    }

    override suspend fun artistsForSpotify(spotifyArtistId: String): List<MbArtist> {
        val url = "$BASE/url".toHttpUrl().newBuilder()
            .addQueryParameter("resource", "https://open.spotify.com/artist/$spotifyArtistId")
            .addQueryParameter("inc", "artist-rels")
            .addQueryParameter("fmt", "json")
            .build()
        val answer = http.get(url, "GET /ws/2/url")
        return if (answer.hasData) MusicBrainzJson.urlArtists(answer.body) else emptyList()
    }

    companion object {
        private const val BASE = "https://musicbrainz.org/ws/2"
    }
}
