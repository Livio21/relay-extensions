package org.relay.extensions.octavepoc

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

class OctaveSourceFactory : RelaySourceFactory {
    override fun getApiVersion() = RelaySourceApi.VERSION
    override fun createSources(): List<RelaySource> = listOf(OctaveSource())
}

private class OctaveSource : BaseRelaySource() {
    @Volatile private var pageSize = DEFAULT_PAGE_SIZE
    @Volatile private var playbackKey = ""

    override fun getId() = "octave"
    override fun getName() = "Octave"

    override fun getSettings() = listOf(
        RelaySourceSetting(
            "playback-key",
            "Public development playback key",
            RelaySourceSetting.Type.TEXT,
            "",
        ),
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
        values["playback-key"]?.trim()?.takeIf(::isUsablePlaybackKey)?.let { playbackKey = it }
    }

    override fun search(query: String, page: Int): RelaySourcePage {
        require(page in 1..MAX_PAGE) { "Octave page is out of range." }
        val term = query.removeFieldPrefix().trim()
        if (term.isEmpty()) return RelaySourcePage(emptyList(), false)
        val encoded = URLEncoder.encode(term, StandardCharsets.UTF_8.name())
        val url = "https://api.octavestreaming.com/api/search/page?query=$encoded&limit=$pageSize&page=$page"
        val tracks = parseOctaveTracks(fetchJson(url))
        return RelaySourcePage(tracks, tracks.size >= pageSize)
    }

    override fun resolveStreamUrl(trackId: String): String =
        octaveStreamUrl(trackId, playbackKey)

    override fun resolveDownloadUrl(trackId: String): String =
        octaveStreamUrl(trackId, playbackKey)

    override fun getMediaRequestHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "audio/mpeg",
        "Origin" to "https://music.octavestreaming.com",
        "Referer" to "https://music.octavestreaming.com/",
    )

    private fun String.removeFieldPrefix(): String = when {
        startsWith("title:", ignoreCase = true) ||
            startsWith("artist:", ignoreCase = true) ||
            startsWith("album:", ignoreCase = true) -> substringAfter(':')
        else -> this
    }

    private fun fetchJson(url: String, redirectsRemaining: Int = MAX_REDIRECTS): String {
        require(url.startsWith("https://")) { "Octave requests must use HTTPS." }
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            when (val status = connection.responseCode) {
                in 300..399 -> {
                    require(redirectsRemaining > 0) { "Octave redirected too many times." }
                    val location = connection.getHeaderField("Location") ?: error("Octave redirect has no location.")
                    fetchJson(URI(url).resolve(location).toString(), redirectsRemaining - 1)
                }
                in 200..299 -> connection.inputStream.bufferedReader().use { it.readBounded(MAX_RESPONSE_CHARS) }
                else -> error("Octave API returned HTTP $status")
            }
        } finally {
            connection.disconnect()
        }
    }
}

internal fun octaveStreamUrl(trackId: String, playbackKey: String): String {
    require(trackId.trim().matches(Regex("[1-9]\\d{0,31}"))) { "Octave track ID must be numeric." }
    require(isUsablePlaybackKey(playbackKey)) { "Set the Octave playback key in source settings first." }
    return "https://api.octavestreaming.com/audio/320?track=${trackId.trim()}&k=${encode(playbackKey.trim())}"
}

internal fun isUsablePlaybackKey(value: String): Boolean =
    value.length in 8..256 && value.matches(Regex("[A-Za-z0-9._-]+"))

internal fun parseOctaveTracks(json: String): List<RelaySourceTrack> {
    require(json.length <= MAX_RESPONSE_CHARS) { "Octave response was too large." }
    val root = JSONObject(json)
    val tracksArray = root.optJSONArray("tracks") ?: return emptyList()
    val result = ArrayList<RelaySourceTrack>(tracksArray.length().coerceAtMost(MAX_PAGE_SIZE))
    for (index in 0 until tracksArray.length()) {
        if (result.size == MAX_PAGE_SIZE) break
        val item = tracksArray.optJSONObject(index) ?: continue
        val id = item.optString("id").trim().takeIf { it.matches(Regex("[1-9]\\d{0,31}")) } ?: continue
        val title = item.optString("title").trim().takeIf(String::isNotEmpty) ?: continue
        val artistObject = item.optJSONObject("artist")
        val artist = (artistObject?.optString("name") ?: item.optString("artist")).trim()
            .takeIf(String::isNotEmpty) ?: continue
        val albumObject = item.optJSONObject("album")
        val album = albumObject?.optString("title")?.trim()?.takeIf(String::isNotEmpty)
        val durationMs = item.optLong("duration", 0L).takeIf { it in 1..86_400L }?.times(1_000L)
        val artwork = albumObject?.let { albumJson ->
            listOf("cover_xl", "cover_big", "cover_medium", "cover_small")
                .asSequence()
                .mapNotNull { albumJson.optString(it).trim().takeIf(String::isNotEmpty) }
                .firstOrNull { it.startsWith("https://") && it.length <= 8_192 }
        }
        result += RelaySourceTrack(id, null, title, artist, album, durationMs, artwork)
    }
    return result
}

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

private fun java.io.Reader.readBounded(limit: Int): String {
    val buffer = CharArray(8_192)
    val content = StringBuilder()
    while (true) {
        val count = read(buffer)
        if (count < 0) return content.toString()
        require(content.length + count <= limit) { "Octave response was too large." }
        content.append(buffer, 0, count)
    }
}

private const val DEFAULT_PAGE_SIZE = 30
private const val MAX_PAGE = 1_000
private const val MAX_PAGE_SIZE = 100
private const val MAX_RESPONSE_CHARS = 2_000_000
private const val MAX_REDIRECTS = 3
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val USER_AGENT = "Relay-OctaveExtension/0.1 (personal music player)"
