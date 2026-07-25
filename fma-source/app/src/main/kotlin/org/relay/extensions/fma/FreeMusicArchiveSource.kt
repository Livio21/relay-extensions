package org.relay.extensions.fma

import android.text.Html
import dev.relay.music.source.api.BaseRelaySource
import dev.relay.music.source.api.RelaySource
import dev.relay.music.source.api.RelaySourceApi
import dev.relay.music.source.api.RelaySourceFactory
import dev.relay.music.source.api.RelaySourcePage
import dev.relay.music.source.api.RelaySourceSetting
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

private class FreeMusicArchiveSource : BaseRelaySource() {
    private val trackPages = mutableMapOf<String, String>()
    @Volatile private var pageSize = 20

    override fun getId() = "free-music-archive"
    override fun getName() = "Free Music Archive"

    override fun getSettings() = listOf(
        RelaySourceSetting("page-size", "Results per page", RelaySourceSetting.Type.CHOICE, "20", listOf("10", "20", "40")),
    )

    override fun applySettings(values: Map<String, String>) {
        values["page-size"]?.toIntOrNull()?.let { pageSize = it }
    }

    override fun search(query: String, page: Int): RelaySourcePage {
        val term = query.removeFieldPrefix().trim()
        val encoded = URLEncoder.encode(term, StandardCharsets.UTF_8.name())
        val url = "https://freemusicarchive.org/search?adv=1&quicksearch=$encoded&pageSize=$pageSize&sort=track&d=1&page=$page"
        val html = fetchPublicPage(url)
        val tracks = parseTracks(html, pageSize)
        if (page == 1) trackPages.clear()
        tracks.forEach { parsed -> parsed.detailsUrl?.let { trackPages[parsed.track.id] = it } }
        return RelaySourcePage(tracks.map(FmaTrack::track), html.contains("page=${page + 1}"))
    }

    override fun resolveArtworkUrl(trackId: String): String? = trackPages[trackId]
        ?.let(::fetchPublicPage)
        ?.fmaArtworkUrl()
}

private data class FmaTrack(
    val track: RelaySourceTrack,
    val detailsUrl: String?,
)

private fun parseTracks(html: String, limit: Int): List<FmaTrack> = buildList {
    var cursor = 0
    while (size < limit) {
        val start = html.indexOf("data-track-info='", cursor).takeIf { it >= 0 } ?: return@buildList
        val jsonStart = start + "data-track-info='".length
        val jsonEnd = html.indexOf("}'>", jsonStart).takeIf { it >= 0 } ?: return@buildList
        val nextTrack = html.indexOf("data-track-info='", jsonEnd + 3).takeIf { it >= 0 } ?: html.length
        val card = html.substring(jsonEnd + 3, nextTrack)
        cursor = nextTrack
        val track = JSONObject(html.substring(jsonStart, jsonEnd + 1))
        val streamUrl = track.optString("playbackUrl")
        if (!streamUrl.startsWith("https://freemusicarchive.org/track/")) continue
        add(
            FmaTrack(
                RelaySourceTrack(
                    track.optString("id", track.optString("handle")),
                    streamUrl,
                    track.optString("title").ifBlank { "Untitled" },
                    track.optString("artistName").ifBlank { "Unknown artist" },
                    card.fmaAlbum(),
                    card.fmaDurationMs(),
                    null,
                ),
                track.optString("url").takeIf { it.startsWith("https://freemusicarchive.org/") },
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

private fun String.fmaAlbum(): String? = substringAfter("ptxt-album", "")
    .substringBefore("</span>")
    .substringAfter("<a", "")
    .substringAfter(">", "")
    .substringBefore("</a>")
    .htmlText()

private fun String.fmaDurationMs(): Long? {
    val value = substringAfter("col-span-1 align-self-end", "")
        .substringAfter(">", "")
        .substringBefore("<")
        .trim()
    val parts = value.split(':')
    if (parts.size != 2) return null
    val minutes = parts[0].toLongOrNull() ?: return null
    val seconds = parts[1].toLongOrNull() ?: return null
    return (minutes * 60 + seconds) * 1_000
}

private fun String.fmaArtworkUrl(): String? {
    val albumMarker = indexOf("type=album")
    if (albumMarker < 0) return null
    val imageStart = lastIndexOf("<img", albumMarker)
    if (imageStart < 0) return null
    return substring(imageStart, albumMarker)
        .substringAfter("src=\"", "")
        .substringBefore('"')
        .htmlText()
        ?.takeIf { it.startsWith("https://freemusicarchive.org/image/") }
}

@Suppress("DEPRECATION")
private fun String.htmlText(): String? = Html.fromHtml(this).toString().trim().takeIf(String::isNotEmpty)
