package org.relay.extensions.jamendo

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
 * Jamendo: Creative-Commons catalogue with a free developer key and real FLAC streams.
 *
 * Search and browse are track-shaped (one request per page). Stream URLs are built from the
 * track id, which is stable, so they are resolved lazily: the preferred format is probed with a
 * one-byte ranged request and falls back to MP3 when the artist did not publish a FLAC master.
 * The client id is free but personal, so it lives in the source settings — never in the APK.
 */
class JamendoSourceFactory : RelaySourceFactory {
    override fun getApiVersion() = RelaySourceApi.VERSION
    override fun createSources(): List<RelaySource> = listOf(JamendoSource())
}

private class JamendoSource : BaseRelaySource() {
    @Volatile private var clientId = ""
    @Volatile private var pageSize = DEFAULT_PAGE_SIZE
    @Volatile private var preferLossless = true

    override fun getId() = "jamendo"
    override fun getName() = "Jamendo"

    override fun getListings(): List<RelaySourceListing> = JAMENDO_LISTINGS

    override fun getSettings() = listOf(
        RelaySourceSetting(
            "client-id",
            "Free Jamendo client ID",
            RelaySourceSetting.Type.TEXT,
            "",
        ),
        RelaySourceSetting(
            "quality",
            "Preferred quality",
            RelaySourceSetting.Type.CHOICE,
            QUALITY_FLAC,
            listOf(QUALITY_FLAC, QUALITY_MP3),
        ),
        RelaySourceSetting(
            "page-size",
            "Results per page",
            RelaySourceSetting.Type.CHOICE,
            DEFAULT_PAGE_SIZE.toString(),
            listOf("20", "50", "100"),
        ),
    )

    override fun applySettings(values: Map<String, String>) {
        values["client-id"]?.trim()?.let { clientId = it.take(MAX_CLIENT_ID_CHARS) }
        values["page-size"]?.toIntOrNull()?.takeIf { it in 1..MAX_PAGE_SIZE }?.let { pageSize = it }
        values["quality"]?.let { preferLossless = it.trim().equals(QUALITY_FLAC, ignoreCase = true) }
    }

    override fun search(query: String, page: Int): RelaySourcePage {
        require(page in 1..MAX_PAGE) { "Jamendo page is out of range." }
        val term = query.trim()
        if (term.isEmpty()) return browse(DEFAULT_LISTING_ID, page)
        return pageFrom(tracksUrl(searchParams(term), page), retryOnEmpty = false)
    }

    override fun browse(listingId: String, page: Int): RelaySourcePage {
        require(page in 1..MAX_PAGE) { "Jamendo page is out of range." }
        val params = JAMENDO_QUERY_BY_LISTING[listingId]
            ?: throw IllegalArgumentException("Unknown Jamendo listing.")
        return pageFrom(tracksUrl(params, page), retryOnEmpty = true)
    }

    override fun resolveStreamUrl(trackId: String): String = resolveMediaUrl(trackId)

    override fun resolveDownloadUrl(trackId: String): String = resolveMediaUrl(trackId)

    override fun getMediaRequestHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "audio/*",
    )

    /**
     * Jamendo occasionally answers a valid request with `success` and zero rows from one of its
     * backends. Chart pages retry once; a search with no matches returns empty on purpose.
     */
    private fun pageFrom(url: String, retryOnEmpty: Boolean): RelaySourcePage {
        var page = fetchPage(url)
        if (retryOnEmpty && page.tracks.isEmpty()) page = fetchPage(url)
        return RelaySourcePage(page.tracks, page.hasNext)
    }

    private fun fetchPage(url: String): JamendoPage = try {
        parseJamendoPage(fetchJson(url), pageSize)
    } catch (error: JamendoApiException) {
        // Code 1 is Jamendo's transient internal error.
        if (error.code == 1) parseJamendoPage(fetchJson(url), pageSize) else throw error
    }

    private fun searchParams(term: String): Map<String, String> = when {
        term.startsWith("title:", ignoreCase = true) ->
            mapOf("namesearch" to term.substringAfter(':').trim())
        term.startsWith("artist:", ignoreCase = true) ->
            mapOf("artist_name" to term.substringAfter(':').trim())
        term.startsWith("album:", ignoreCase = true) ->
            mapOf("album_name" to term.substringAfter(':').trim())
        else -> mapOf("search" to term)
    }

    private fun tracksUrl(params: Map<String, String>, page: Int): String {
        val id = clientId.trim()
        require(id.isNotEmpty()) { MISSING_CLIENT_ID_MESSAGE }
        require(isUsableClientId(id)) { MISSING_CLIENT_ID_MESSAGE }
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        val query = StringBuilder()
        fun add(name: String, value: String) {
            if (query.isNotEmpty()) query.append('&')
            query.append(name).append('=').append(value.encoded())
        }
        add("client_id", id)
        add("format", "json")
        add("limit", pageSize.toString())
        add("offset", offset.toString())
        add("imagesize", "300")
        // Jamendo returns album tracks only unless singles are requested too.
        add("type", "single albumtrack")
        params.forEach { (name, value) -> if (value.isNotBlank()) add(name, value) }
        return "$JAMENDO_API_HOST/tracks/?$query"
    }

    private fun resolveMediaUrl(trackId: String): String {
        val id = requireJamendoId(trackId)
        if (preferLossless) {
            val flac = jamendoMediaUrl(id, FORMAT_FLAC)
            if (isPlayable(flac)) return flac
        }
        val mp3 = jamendoMediaUrl(id, FORMAT_MP3)
        check(isPlayable(mp3)) { "Jamendo has no playable audio for this track." }
        return mp3
    }
}

internal const val QUALITY_FLAC = "FLAC"
internal const val QUALITY_MP3 = "MP3"
internal const val FORMAT_FLAC = "flac"
internal const val FORMAT_MP3 = "mp32"
internal const val MISSING_CLIENT_ID_MESSAGE =
    "Add your free Jamendo client ID in this source's settings (create one at devportal.jamendo.com)."

internal fun isUsableClientId(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.isNotEmpty() && trimmed.length <= 128 && trimmed.none { it.isWhitespace() || it == '\r' || it == '\n' }
}

internal fun isValidJamendoId(value: String): Boolean =
    value.trim().matches(Regex("[0-9]{1,20}"))

internal fun requireJamendoId(value: String): String {
    val trimmed = value.trim()
    require(isValidJamendoId(trimmed)) { "Jamendo track ID must be numeric." }
    return trimmed
}

internal fun jamendoMediaUrl(trackId: String, format: String): String {
    val id = requireJamendoId(trackId)
    val fmt = when (format) {
        FORMAT_FLAC, FORMAT_MP3 -> format
        else -> FORMAT_FLAC
    }
    return "https://prod-1.storage.jamendo.com/?trackid=$id&format=$fmt&from=relay"
}

/** One parsed `/v3.0/tracks/` page plus whether Jamendo advertised a next page. */
internal data class JamendoPage(val tracks: List<RelaySourceTrack>, val hasNext: Boolean)

internal class JamendoApiException(val code: Int, message: String) : IllegalStateException(message)

/** Parses one `/v3.0/tracks/` page, skipping rows Relay cannot render. */
internal fun parseJamendoPage(json: String, expectedPageSize: Int = 0): JamendoPage {
    require(json.length <= MAX_RESPONSE_CHARS) { "Jamendo response was too large." }
    val root = JSONObject(json)
    val headers = root.optJSONObject("headers")
    if (headers?.optString("status").equals("failed", ignoreCase = true)) {
        val code = headers?.optInt("code", -1) ?: -1
        val detail = headers?.optString("error_message").orEmpty()
        throw JamendoApiException(code, jamendoErrorMessage(code, detail))
    }
    val results: JSONArray = root.optJSONArray("results") ?: return JamendoPage(emptyList(), false)
    val tracks = ArrayList<RelaySourceTrack>(results.length().coerceAtMost(MAX_PAGE_SIZE))
    for (index in 0 until results.length()) {
        if (tracks.size == MAX_PAGE_SIZE) break
        val item = results.optJSONObject(index) ?: continue
        val id = item.optString("id").trim().takeIf { isValidJamendoId(it) } ?: continue
        val title = item.optString("name").trim().takeIf(String::isNotEmpty) ?: continue
        val artist = item.optString("artist_name").trim().takeIf(String::isNotEmpty) ?: continue
        val album = item.optString("album_name").trim().takeIf(String::isNotEmpty)
        val seconds = item.optLong("duration", 0L)
        val durationMs = seconds.takeIf { it in 1..86_400L }?.times(1_000L)
        val artwork = jamendoArtwork(item)
        tracks += RelaySourceTrack(
            id,
            null,
            title,
            artist,
            album,
            durationMs,
            artwork,
        )
    }
    val next = headers?.optString("next").orEmpty()
    val hasNext = next.startsWith("https://") ||
        (expectedPageSize > 0 && tracks.size >= expectedPageSize)
    return JamendoPage(tracks, hasNext)
}

private fun jamendoArtwork(item: JSONObject): String? {
    for (key in listOf("image", "album_image")) {
        val url = item.optString(key).trim()
        if (url.startsWith("https://") && url.length <= MAX_URL_CHARS) return url
    }
    return null
}

internal fun jamendoErrorMessage(code: Int, detail: String): String {
    val clean = detail.replace('\r', ' ').replace('\n', ' ').trim().take(160)
    return when (code) {
        5 -> "Jamendo rejected the client ID. Create a free app at devportal.jamendo.com and paste its client ID here."
        else -> if (clean.isEmpty()) {
            "Jamendo request failed (code $code)."
        } else {
            "Jamendo request failed (code $code): $clean"
        }
    }
}

private fun fetchJson(url: String): String {
    require(url.startsWith("https://")) { "Jamendo requests must use HTTPS." }
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "application/json")
    }
    return try {
        check(connection.responseCode in 200..299) { "Jamendo returned HTTP ${connection.responseCode}." }
        connection.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(8_192)
            val content = StringBuilder()
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                check(content.length + count <= MAX_RESPONSE_CHARS) { "Jamendo response was too large." }
                content.append(buffer, 0, count)
            }
            content.toString()
        }
    } finally {
        connection.disconnect()
    }
}

/** One-byte ranged request: proves the file exists and is audio without downloading it. */
private fun isPlayable(url: String): Boolean {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = PROBE_TIMEOUT_MS
        readTimeout = PROBE_TIMEOUT_MS
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Range", "bytes=0-0")
    }
    return try {
        val code = connection.responseCode
        val type = connection.contentType.orEmpty().lowercase()
        code in 200..299 && (type.startsWith("audio/") || type.startsWith("application/octet-stream"))
    } catch (_: Exception) {
        false
    } finally {
        connection.disconnect()
    }
}

private fun String.encoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

private const val JAMENDO_API_HOST = "https://api.jamendo.com/v3.0"
private const val DEFAULT_LISTING_ID = "popular"
private const val DEFAULT_PAGE_SIZE = 50
private const val MAX_PAGE = 1_000
private const val MAX_PAGE_SIZE = 100
private const val MAX_CLIENT_ID_CHARS = 128
private const val MAX_RESPONSE_CHARS = 2_000_000
private const val MAX_URL_CHARS = 8_192
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val PROBE_TIMEOUT_MS = 4_000
private const val USER_AGENT = "Relay-JamendoExtension/1.0 (personal music player)"

internal val JAMENDO_LISTINGS: List<RelaySourceListing> = listOf(
    RelaySourceListing("popular", "Popular"),
    RelaySourceListing("featured", "Featured"),
    RelaySourceListing("newest", "New releases"),
    RelaySourceListing("electronic", "Electronic"),
    RelaySourceListing("rock", "Rock"),
    RelaySourceListing("pop", "Pop"),
    RelaySourceListing("hiphop", "Hip-Hop"),
    RelaySourceListing("jazz", "Jazz"),
    RelaySourceListing("classical", "Classical"),
    RelaySourceListing("world", "World"),
    RelaySourceListing("metal", "Metal"),
    RelaySourceListing("soundtrack", "Soundtrack"),
    RelaySourceListing("folk", "Folk"),
    RelaySourceListing("ambient", "Ambient"),
    RelaySourceListing("chillout", "Chillout"),
)

/** Listing id -> extra `/tracks/` parameters. Order is preserved for deterministic URLs. */
internal val JAMENDO_QUERY_BY_LISTING: Map<String, Map<String, String>> = linkedMapOf(
    "popular" to linkedMapOf("order" to "popularity_total"),
    "featured" to linkedMapOf("featured" to "1", "order" to "popularity_total"),
    "newest" to linkedMapOf("order" to "releasedate_desc"),
    "electronic" to linkedMapOf("tags" to "electronic", "order" to "popularity_total"),
    "rock" to linkedMapOf("tags" to "rock", "order" to "popularity_total"),
    "pop" to linkedMapOf("tags" to "pop", "order" to "popularity_total"),
    "hiphop" to linkedMapOf("tags" to "hiphop", "order" to "popularity_total"),
    "jazz" to linkedMapOf("tags" to "jazz", "order" to "popularity_total"),
    "classical" to linkedMapOf("tags" to "classical", "order" to "popularity_total"),
    "world" to linkedMapOf("tags" to "world", "order" to "popularity_total"),
    "metal" to linkedMapOf("tags" to "metal", "order" to "popularity_total"),
    "soundtrack" to linkedMapOf("tags" to "soundtrack", "order" to "popularity_total"),
    "folk" to linkedMapOf("tags" to "folk", "order" to "popularity_total"),
    "ambient" to linkedMapOf("tags" to "ambient", "order" to "popularity_total"),
    "chillout" to linkedMapOf("tags" to "chillout", "order" to "popularity_total"),
)
