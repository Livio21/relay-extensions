package org.relay.extensions.fma

import dev.relay.music.source.api.RelaySource
import dev.relay.music.source.api.RelaySourceApi
import dev.relay.music.source.api.RelaySourceFactory
import dev.relay.music.source.api.RelaySourcePage
import dev.relay.music.source.api.RelaySourceTrack
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * Public Free Music Archive search source. It deliberately makes one bounded request per browse
 * or search and hands Relay FMA's own public stream redirect URL; it has no account support.
 */
class FreeMusicArchiveSourceFactory : RelaySourceFactory {
    override fun getApiVersion() = RelaySourceApi.VERSION

    override fun createSources(): List<RelaySource> = listOf(FreeMusicArchiveSource())
}

private class FreeMusicArchiveSource : RelaySource {
    override fun getId() = "free-music-archive"
    override fun getName() = "Free Music Archive"

    override fun search(query: String): RelaySourcePage {
        val term = query.removeFieldPrefix().trim()
        val encoded = URLEncoder.encode(term, StandardCharsets.UTF_8.name())
        val url = "https://freemusicarchive.org/search?adv=1&quicksearch=$encoded&pageSize=20&sort=track&d=1"
        val html = fetchPublicPage(url)
        return RelaySourcePage(parseTracks(html), html.contains("page=2"))
    }
}

private fun parseTracks(html: String): List<RelaySourceTrack> = buildList {
    var cursor = 0
    while (size < 20) {
        val start = html.indexOf("data-track-info='", cursor).takeIf { it >= 0 } ?: return@buildList
        val jsonStart = start + "data-track-info='".length
        val jsonEnd = html.indexOf("}'>", jsonStart).takeIf { it >= 0 } ?: return@buildList
        cursor = jsonEnd + 3
        val track = JSONObject(html.substring(jsonStart, jsonEnd + 1))
        val streamUrl = track.optString("playbackUrl")
        if (!streamUrl.startsWith("https://freemusicarchive.org/track/")) continue
        add(
            RelaySourceTrack(
                track.optString("id", track.optString("handle")),
                streamUrl,
                track.optString("title").ifBlank { "Untitled" },
                track.optString("artistName").ifBlank { "Unknown artist" },
                null,
                null,
                null,
            ),
        )
    }
}

private fun fetchPublicPage(url: String): String {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "RelaySource/0.1 (personal music player)")
        setRequestProperty("Accept", "text/html,application/xhtml+xml")
    }
    return try {
        check(connection.responseCode in 200..299) { "Free Music Archive returned HTTP ${connection.responseCode}" }
        connection.inputStream.bufferedReader().use { reader ->
            reader.readText(limit = 1_250_000)
        }
    } finally {
        connection.disconnect()
    }
}

private fun java.io.Reader.readText(limit: Int): String {
    val buffer = CharArray(8_192)
    val content = StringBuilder()
    while (true) {
        val count = read(buffer)
        if (count < 0) return content.toString()
        check(content.length + count <= limit) { "Free Music Archive response was too large" }
        content.append(buffer, 0, count)
    }
}

private fun String.removeFieldPrefix(): String = when {
    startsWith("title:", ignoreCase = true) || startsWith("artist:", ignoreCase = true) || startsWith("album:", ignoreCase = true) -> substringAfter(':')
    else -> this
}
