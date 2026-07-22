package org.relay.extensions.fma

import android.text.Html
import dev.relay.music.source.api.RelaySource
import dev.relay.music.source.api.RelaySourceApi
import dev.relay.music.source.api.RelaySourceFactory
import dev.relay.music.source.api.RelaySourcePage
import dev.relay.music.source.api.RelaySourceTrack
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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

private val trackInfoPattern = Regex("""data-track-info='(\\{.*?})'""")
private val stringFieldPattern = Regex("""\"([^\"]+)\":\"((?:\\.|[^\"])*)\"""")
private val playItemBoundary = Regex("""<div class=\"play-item""")
private val albumPattern = Regex("""ptxt-album.*?<a[^>]*>\s*(.*?)\s*</a>""", setOf(RegexOption.DOT_MATCHES_ALL))
private val durationPattern = Regex("""<span[^>]*>\s*(\\d{1,2}:\\d{2})\s*</span>""")

private fun parseTracks(html: String): List<RelaySourceTrack> = buildList {
    trackInfoPattern.findAll(html).take(20).forEach { match ->
        val fields = stringFieldPattern.findAll(match.groupValues[1]).associate {
            it.groupValues[1] to it.groupValues[2].unescapeJson()
        }
        val streamUrl = fields["playbackUrl"] ?: return@forEach
        if (!streamUrl.startsWith("https://freemusicarchive.org/track/")) return@forEach

        val end = playItemBoundary.find(html, match.range.last + 1)?.range?.first ?: html.length
        val card = html.substring(match.range.last + 1, end)
        add(
            RelaySourceTrack(
                fields["id"] ?: fields["handle"] ?: return@forEach,
                streamUrl,
                fields["title"].orEmpty().ifBlank { "Untitled" },
                fields["artistName"].orEmpty().ifBlank { "Unknown artist" },
                albumPattern.find(card)?.groupValues?.getOrNull(1)?.asPlainText(),
                durationPattern.find(card)?.groupValues?.getOrNull(1)?.toDurationMs(),
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

private fun String.unescapeJson(): String = replace("\\/", "/")
    .replace("\\\"", "\"")
    .replace("\\\\", "\\")
    .replace("\\u0026", "&")

private fun String.asPlainText(): String? = Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY).toString().trim().ifEmpty { null }

private fun String.toDurationMs(): Long? = split(':').takeIf { it.size == 2 }?.let { (minutes, seconds) ->
    ((minutes.toLongOrNull() ?: return null) * 60 + (seconds.toLongOrNull() ?: return null)) * 1_000
}
