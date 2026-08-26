package org.relay.extensions.audius

import dev.relay.music.source.api.BaseRelaySource
import dev.relay.music.source.api.RelaySource
import dev.relay.music.source.api.RelaySourceApi
import dev.relay.music.source.api.RelaySourceFactory
import dev.relay.music.source.api.RelaySourceListing
import dev.relay.music.source.api.RelaySourcePage
import dev.relay.music.source.api.RelaySourceSetting
import dev.relay.music.source.api.RelaySourceTrack
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/**
 * Audius decentralized music platform. Public discovery API requires no user key;
 * the `app_name` parameter attributes requests to Relay as the Audius convention.
 * One bounded request per search or trending page returns a track-shaped list and
 * a lazy CID stream URL is supplied at playback time.
 */
class AudiusSourceFactory : RelaySourceFactory {
    override fun getApiVersion() = RelaySourceApi.VERSION
    override fun createSources(): List<RelaySource> = listOf(AudiusSource())
}

private class AudiusSource : BaseRelaySource() {
    @Volatile private var pageSize = DEFAULT_PAGE_SIZE

    override fun getId() = "audius"
    override fun getName() = "Audius"

    override fun getListings(): List<RelaySourceListing> = AUDIUS_LISTINGS

    override fun getSettings() = listOf(
        RelaySourceSetting(
            "page-size",
            "Results per page",
            RelaySourceSetting.Type.CHOICE,
            DEFAULT_PAGE_SIZE.toString(),
            listOf("10", "20", "30", "50"),
        ),
    )

    override fun applySettings(values: Map<String, String>) {
        values["page-size"]?.toIntOrNull()?.takeIf { it in 1..50 }?.let { pageSize = it }
    }

    override fun search(query: String, page: Int): RelaySourcePage {
        require(page in 1..MAX_PAGE) { "Audius page is out of range." }
        val term = query.removeFieldPrefix().trim()
        if (term.isEmpty()) return RelaySourcePage(emptyList(), false)
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        val encoded = term.encoded()
        val url = "$AUDIUS_HOST/v1/tracks/search?query=$encoded&app_name=$APP_NAME&limit=$pageSize&offset=$offset"
        val tracks = parseAudiusTracks(fetchJson(url))
        return RelaySourcePage(tracks, tracks.size >= pageSize)
    }

    override fun browse(listingId: String, page: Int): RelaySourcePage {
        require(page in 1..MAX_PAGE) { "Audius page is out of range." }
        if (!AUDIUS_GENRE_BY_LISTING.containsKey(listingId)) {
            throw IllegalArgumentException("Unknown Audius listing.")
        }
        val genre = AUDIUS_GENRE_BY_LISTING[listingId]
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        val url = if (genre == null) {
            "$AUDIUS_HOST/v1/tracks/trending?app_name=$APP_NAME&limit=$pageSize&offset=$offset&timeRange=week"
        } else {
            "$AUDIUS_HOST/v1/tracks/trending?genre=${genre.encoded()}&app_name=$APP_NAME&limit=$pageSize&offset=$offset&timeRange=week"
        }
        val tracks = parseAudiusTracks(fetchJson(url))
        return RelaySourcePage(tracks, tracks.size >= pageSize)
    }

    override fun resolveStreamUrl(trackId: String): String {
        require(isValidAudiusId(trackId)) { "Audius track ID must be alphanumeric (3-32 chars)." }
        return audiusStreamUrl(trackId.trim())
    }

    override fun getMediaRequestHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "audio/mpeg",
    )
}

internal fun audiusStreamUrl(trackId: String): String {
    require(isValidAudiusId(trackId)) { "Audius track ID must be alphanumeric (3-32 chars)." }
    val encoded = URLEncoder.encode(trackId.trim(), StandardCharsets.UTF_8.name())
    return "$AUDIUS_HOST/v1/tracks/$encoded/stream?app_name=$APP_NAME"
}

internal fun isValidAudiusId(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.length in 3..32 && trimmed.matches(Regex("[A-Za-z0-9_-]+"))
}

internal fun parseAudiusTracks(json: String): List<RelaySourceTrack> {
    require(json.length <= MAX_RESPONSE_CHARS) { "Audius response was too large." }
    val root = JSONObject(json)
    // Audius wraps results in `data`; some error shapes have no data.
    val dataArray: JSONArray = root.optJSONArray("data") ?: return emptyList()
    val result = ArrayList<RelaySourceTrack>(dataArray.length().coerceAtMost(MAX_PAGE_SIZE))
    for (index in 0 until dataArray.length()) {
        if (result.size == MAX_PAGE_SIZE) break
        val item = dataArray.optJSONObject(index) ?: continue
        val id = item.optString("id").trim().takeIf { isValidAudiusId(it) } ?: continue
        val title = item.optString("title").trim().takeIf(String::isNotEmpty) ?: continue
        val userObj = item.optJSONObject("user")
        val artist = userObj?.optString("name")?.trim()?.takeIf(String::isNotEmpty)
            ?: item.optString("user_id").trim().takeIf(String::isNotEmpty)
            ?: continue
        if (item.optBoolean("is_delete", false)) continue
        if (!item.optBoolean("is_available", true)) continue
        if (item.optBoolean("is_unlisted", false)) continue
        if (!item.optBoolean("is_streamable", false)) continue
        if (item.optBoolean("is_stream_gated", false)) continue
        val trackCid = item.optString("track_cid").trim()
        if (trackCid.isEmpty()) continue
        val durationSec = item.optLong("duration", 0L)
        val durationMs = durationSec.takeIf { it in 1..86_400L }?.times(1_000L)
        val artwork = selectAudiusArtwork(item.optJSONObject("artwork"))
        result += RelaySourceTrack(
            id,
            null,
            title,
            artist,
            null,
            durationMs,
            artwork,
        )
    }
    return result
}

internal fun selectAudiusArtwork(artwork: JSONObject?): String? {
    if (artwork == null) return null
    // Prefer 480x480 for row efficiency, fall back to larger/smaller.
    val preference = listOf("480x480", "1000x1000", "150x150")
    for (key in preference) {
        val url = artwork.optString(key).trim().takeIf(String::isNotEmpty) ?: continue
        if (url.startsWith("https://") && url.length <= MAX_URL_CHARS) return url
    }
    return null
}

private fun String.encoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

private fun String.removeFieldPrefix(): String = when {
    startsWith("title:", ignoreCase = true) ||
        startsWith("artist:", ignoreCase = true) ||
        startsWith("album:", ignoreCase = true) -> substringAfter(':')
    else -> this
}

private fun fetchJson(url: String): String {
    require(url.startsWith("https://")) { "Audius requests must use HTTPS." }
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "application/json")
    }
    return try {
        check(connection.responseCode in 200..299) { "Audius returned HTTP ${connection.responseCode}" }
        connection.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(8_192)
            val content = StringBuilder()
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                check(content.length + count <= MAX_RESPONSE_CHARS) { "Audius response was too large" }
                content.append(buffer, 0, count)
            }
            content.toString()
        }
    } finally {
        connection.disconnect()
    }
}

private const val AUDIUS_HOST = "https://discoveryprovider.audius.co"
private const val APP_NAME = "Relay"
private const val DEFAULT_PAGE_SIZE = 20
private const val MAX_PAGE = 1_000
private const val MAX_PAGE_SIZE = 100
private const val MAX_RESPONSE_CHARS = 2_000_000
private const val MAX_URL_CHARS = 8_192
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val USER_AGENT = "Relay-AudiusExtension/1.0 (personal music player)"

internal val AUDIUS_LISTINGS: List<RelaySourceListing> = listOf(
    RelaySourceListing("trending", "Trending"),
    RelaySourceListing("trending-electronic", "Electronic"),
    RelaySourceListing("trending-hiphop-rap", "Hip-Hop/Rap"),
    RelaySourceListing("trending-pop", "Pop"),
    RelaySourceListing("trending-rock", "Rock"),
    RelaySourceListing("trending-house", "House"),
    RelaySourceListing("trending-techno", "Techno"),
    RelaySourceListing("trending-drum-bass", "Drum & Bass"),
    RelaySourceListing("trending-trap", "Trap"),
    RelaySourceListing("trending-ambient", "Ambient"),
    RelaySourceListing("trending-jazz", "Jazz"),
    RelaySourceListing("trending-classical", "Classical"),
    RelaySourceListing("trending-folk", "Folk"),
    RelaySourceListing("trending-rnb-soul", "R&B/Soul"),
    RelaySourceListing("trending-alternative", "Alternative"),
    RelaySourceListing("trending-world", "World"),
    RelaySourceListing("trending-soundtrack", "Soundtrack"),
    RelaySourceListing("trending-country", "Country"),
)

/** Maps listing id -> Audius genre string (null = overall trending, no genre filter). */
internal val AUDIUS_GENRE_BY_LISTING: Map<String, String?> = mapOf(
    "trending" to null,
    "trending-electronic" to "Electronic",
    "trending-hiphop-rap" to "Hip-Hop/Rap",
    "trending-pop" to "Pop",
    "trending-rock" to "Rock",
    "trending-house" to "House",
    "trending-techno" to "Techno",
    "trending-drum-bass" to "Drum & Bass",
    "trending-trap" to "Trap",
    "trending-ambient" to "Ambient",
    "trending-jazz" to "Jazz",
    "trending-classical" to "Classical",
    "trending-folk" to "Folk",
    "trending-rnb-soul" to "R&B/Soul",
    "trending-alternative" to "Alternative",
    "trending-world" to "World",
    "trending-soundtrack" to "Soundtrack",
    "trending-country" to "Country",
)
