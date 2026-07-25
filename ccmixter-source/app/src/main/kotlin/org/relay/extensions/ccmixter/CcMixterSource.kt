package org.relay.extensions.ccmixter

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
 * ccMixter is a Creative Commons remix community. Its public query API returns playable tracks
 * with direct file URLs in one request, so this source needs no per-track lookups and no account.
 */
class CcMixterSourceFactory : RelaySourceFactory {
    override fun getApiVersion() = RelaySourceApi.VERSION

    override fun createSources(): List<RelaySource> = listOf(CcMixterSource())
}

private class CcMixterSource : BaseRelaySource() {
    @Volatile private var pageSize = 20

    override fun getId() = "ccmixter"
    override fun getName() = "ccMixter"

    override fun getListings() = listOf(
        RelaySourceListing("recent", "Recent"),
        RelaySourceListing("instrumental", "Instrumental"),
        RelaySourceListing("electronic", "Electronic"),
        RelaySourceListing("jazz", "Jazz"),
        RelaySourceListing("rock", "Rock"),
        RelaySourceListing("ambient", "Ambient"),
    )

    override fun getSettings() = listOf(
        RelaySourceSetting("page-size", "Results per page", RelaySourceSetting.Type.CHOICE, "20", listOf("10", "20", "40")),
    )

    override fun applySettings(values: Map<String, String>) {
        values["page-size"]?.toIntOrNull()?.takeIf { it in 1..50 }?.let { pageSize = it }
    }

    override fun browse(listingId: String, page: Int): RelaySourcePage = when (listingId) {
        "recent" -> query("sort=date", page)
        else -> query("tags=${listingId.encoded()}", page)
    }

    override fun search(query: String, page: Int): RelaySourcePage {
        val term = query.removeFieldPrefix().trim()
        // An empty search would return everything; the recent listing is the better default.
        return if (term.isEmpty()) query("sort=date", page) else query("search=${term.encoded()}", page)
    }

    // ccMixter's file host answers 403 to requests without a Referer from its own site.
    // Relay attaches these to every stream, artwork, and download fetch for this source.
    override fun getMediaRequestHeaders() = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "https://ccmixter.org/",
    )

    private fun query(parameters: String, page: Int): RelaySourcePage {
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        val url = "https://ccmixter.org/api/query?f=json&limit=$pageSize&offset=$offset&$parameters"
        val results = JSONArray(fetchJson(url))
        val tracks = (0 until results.length()).mapNotNull { index ->
            results.optJSONObject(index)?.toTrack()
        }
        // A full page implies more behind it; ccMixter reports no total.
        return RelaySourcePage(tracks, results.length() == pageSize)
    }
}

private fun JSONObject.toTrack(): RelaySourceTrack? {
    val id = optInt("upload_id").takeIf { it > 0 }?.toString() ?: return null
    val title = optString("upload_name").trim().ifEmpty { return null }
    val artist = optString("user_real_name").trim()
        .ifEmpty { optString("user_name").trim() }
        .ifEmpty { return null }
    val audio = optJSONArray("files")?.firstAudioFile() ?: return null
    return RelaySourceTrack(
        id,
        audio.url,
        title,
        artist,
        null,
        audio.durationMs,
        null,
    )
}

private class AudioFile(val url: String, val durationMs: Long?)

/** ccMixter uploads carry stems and alternative formats; take the first playable audio file. */
private fun JSONArray.firstAudioFile(): AudioFile? {
    for (index in 0 until length()) {
        val file = optJSONObject(index) ?: continue
        val url = file.optString("download_url")
        val info = file.optJSONObject("file_format_info")
        val mimeType = info?.optString("mime_type").orEmpty()
        if (url.startsWith("https://") && mimeType.startsWith("audio/")) {
            return AudioFile(url, info?.optString("ps")?.asDurationMs())
        }
    }
    return null
}

/** ccMixter reports playing time as `m:ss` or `h:mm:ss`. */
private fun String.asDurationMs(): Long? {
    val parts = trim().split(':').map { it.toLongOrNull() ?: return null }
    val seconds = when (parts.size) {
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3_600 + parts[1] * 60 + parts[2]
        else -> return null
    }
    return (seconds * 1_000).takeIf { it > 0 }
}

private fun String.encoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

private fun String.removeFieldPrefix(): String = when {
    startsWith("title:", ignoreCase = true) ||
        startsWith("artist:", ignoreCase = true) ||
        startsWith("album:", ignoreCase = true) -> substringAfter(':')
    else -> this
}

private fun fetchJson(url: String): String {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "application/json")
    }
    return try {
        check(connection.responseCode in 200..299) { "ccMixter returned HTTP ${connection.responseCode}" }
        connection.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(8_192)
            val content = StringBuilder()
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                check(content.length + count <= MAX_RESPONSE_CHARS) { "ccMixter response was too large" }
                content.append(buffer, 0, count)
            }
            content.toString()
        }
    } finally {
        connection.disconnect()
    }
}

private const val USER_AGENT = "RelaySource/0.1 (personal music player)"
private const val MAX_RESPONSE_CHARS = 1_250_000
