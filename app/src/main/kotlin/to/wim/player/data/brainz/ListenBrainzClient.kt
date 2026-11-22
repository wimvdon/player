package to.wim.player.data.brainz

import okhttp3.HttpUrl.Companion.toHttpUrl
import to.wim.player.data.LISTENS_PAGE
import to.wim.player.data.ListenBrainzSource
import to.wim.player.data.METADATA_BATCH

/** ListenBrainz and its Labs datasets over HTTP. Reading needs only a username. No caching here. */
class ListenBrainzClient(private val http: BrainzHttp) : ListenBrainzSource {
    override suspend fun similarArtists(mbid: String, limit: Int): List<SimilarArtist> {
        val url = "$LABS/similar-artists/json".toHttpUrl().newBuilder()
            .addQueryParameter("artist_mbids", mbid)
            .addQueryParameter("algorithm", SIMILAR_ALGORITHM)
            .build()
        val answer = http.get(url, "GET labs/similar-artists")
        return if (answer.hasData) ListenBrainzJson.similarArtists(answer.body, of = mbid).take(limit) else emptyList()
    }

    override suspend fun topRecordings(artistMbid: String, limit: Int): List<BrainzRecording> {
        val url = "$API/popularity/top-recordings-for-artist/$artistMbid".toHttpUrl()
        val answer = http.get(url, "GET /1/popularity/top-recordings-for-artist")
        return if (answer.hasData) ListenBrainzJson.topRecordings(answer.body).take(limit) else emptyList()
    }

    override suspend fun userTopArtists(username: String, limit: Int): List<BrainzTopArtist> {
        val url = "$API/stats/user/${username.trim()}/artists".toHttpUrl().newBuilder()
            .addQueryParameter("count", "$limit")
            .addQueryParameter("range", "year")
            .build()
        val answer = http.get(url, "GET /1/stats/user/{name}/artists")
        return if (answer.hasData) ListenBrainzJson.userTopArtists(answer.body) else emptyList()
    }

    override suspend fun userTopRecordings(username: String): List<BrainzRecording> {
        val result = ArrayList<BrainzRecording>()
        var offset = 0
        while (offset < MAX_USER_RECORDINGS) {
            val url = "$API/stats/user/${username.trim()}/recordings".toHttpUrl().newBuilder()
                .addQueryParameter("count", "$PAGE")
                .addQueryParameter("offset", "$offset")
                .addQueryParameter("range", "all_time")
                .build()
            val answer = http.get(url, "GET /1/stats/user/{name}/recordings")
            if (!answer.hasData) break
            val page = ListenBrainzJson.userTopRecordings(answer.body)
            result += page
            offset += PAGE
            if (page.size < PAGE || offset >= ListenBrainzJson.totalRecordingCount(answer.body)) break
        }
        return result
    }

    override suspend fun listens(username: String, minTs: Long?, maxTs: Long?, count: Int): List<BrainzListen> {
        val url = "$API/user/${username.trim()}/listens".toHttpUrl().newBuilder()
            .addQueryParameter("count", "${count.coerceIn(1, LISTENS_PAGE)}")
            .apply {
                if (minTs != null) addQueryParameter("min_ts", "$minTs") else if (maxTs != null) addQueryParameter("max_ts", "$maxTs")
            }
            .build()
        val answer = http.get(url, "GET /1/user/{name}/listens")
        return if (answer.hasData) ListenBrainzJson.listens(answer.body) else emptyList()
    }

    override suspend fun recommendations(username: String, limit: Int): List<Recommendation> {
        val url = "$API/cf/recommendation/user/${username.trim()}/recording".toHttpUrl().newBuilder()
            .addQueryParameter("count", "$limit")
            .build()
        val answer = http.get(url, "GET /1/cf/recommendation/user/{name}/recording")
        return if (answer.hasData) ListenBrainzJson.recommendations(answer.body) else emptyList()
    }

    override suspend fun tagRecordings(tag: String, count: Int): List<TagRecording> {
        val url = "$API/lb-radio/tags".toHttpUrl().newBuilder()
            .addQueryParameter("tag", tag.trim().lowercase())
            .addQueryParameter("operator", "or")
            .addQueryParameter("pop_begin", "0")
            .addQueryParameter("pop_end", "100")
            .addQueryParameter("count", "$count")
            .build()
        val answer = http.get(url, "GET /1/lb-radio/tags")
        return if (answer.hasData) ListenBrainzJson.tagRecordings(answer.body) else emptyList()
    }

    override suspend fun recordingMetadata(recordingMbids: List<String>): List<BrainzRecording> {
        if (recordingMbids.isEmpty()) return emptyList()
        val url = "$API/metadata/recording/".toHttpUrl().newBuilder()
            .addQueryParameter("recording_mbids", recordingMbids.take(METADATA_BATCH).joinToString(","))
            .addQueryParameter("inc", "artist")
            .build()
        val answer = http.get(url, "GET /1/metadata/recording")
        return if (answer.hasData) ListenBrainzJson.recordingMetadata(answer.body) else emptyList()
    }

    companion object {
        private const val API = "https://api.listenbrainz.org/1"
        private const val LABS = "https://labs.api.listenbrainz.org"

        /** The dataset ListenBrainz's own pages use: listening sessions over about 20 years. */
        const val SIMILAR_ALGORITHM = "session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30"

        /** ListenBrainz returns at most 100 items a page and keeps a user's top 1 000 recordings. */
        private const val PAGE = 100
        private const val MAX_USER_RECORDINGS = 1_000
    }
}
