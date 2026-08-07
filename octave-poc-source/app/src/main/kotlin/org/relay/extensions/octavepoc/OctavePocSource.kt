package org.relay.extensions.octave

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
    @Volatile
    private var pageSize = 30

    override fun getId() = "octave"
    override fun getName() = "Octave"

    override fun getSettings() = listOf(
        // Positional arguments: key, name, type, defaultValue, choices (5 args)
        RelaySourceSetting(
            "page-size",
            "Results per page",
            RelaySourceSetting.Type.CHOICE,
            "30",
            listOf("10", "20", "30", "50")
        )
    )

    override fun applySettings(values: Map<String, String>) {
        values["page-size"]?.toIntOrNull()?.takeIf { it in 1..100 }?.let { pageSize = it }
    }

    override fun search(query: String, page: Int): RelaySourcePage {
        val term = query.removeFieldPrefix().trim()
        if (term.isEmpty()) {
            return RelaySourcePage(emptyList(), false)
        }
        val encoded = URLEncoder.encode(term, StandardCharsets.UTF_8.name())
        val url = "https://api.octavestreaming.com/api/search/page?query=$encoded&limit=$pageSize&page=$page"
        val json = fetchJson(url)
        val tracks = parseTracks(json)
        val hasNext = tracks.size >= pageSize
        return RelaySourcePage(tracks, hasNext)
    }

    override fun getListings() = emptyList<Nothing>()
    override fun browse(listingId: String, page: Int) = RelaySourcePage(emptyList(), false)

    override fun resolveStreamUrl(trackId: String): String? {
        return "https://api.octavestreaming.com/audio/320?track=$trackId&k=octk_tjezki_4df3168bbc8c12c1193b4017d235b25b"
    }

    override fun resolveArtworkUrl(trackId: String): String? = null

    override fun resolveDownloadUrl(trackId: String): String? = resolveStreamUrl(trackId)

    override fun getMediaRequestHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Relay-OctaveExtension/0.1.0"
            // Add Referer if needed: "Referer" to "https://music.octavestreaming.com/"
        )
    }

    // ---------- helpers ----------

    private fun String.removeFieldPrefix(): String {
        val prefixes = listOf("title:", "artist:", "album:")
        for (prefix in prefixes) {
            if (this.startsWith(prefix, ignoreCase = true)) {
                return this.substring(prefix.length).trimStart()
            }
        }
        return this
    }

    private fun parseTracks(json: String): List<RelaySourceTrack> {
        val root = JSONObject(json)
        val tracksArray = root.optJSONArray("tracks") ?: return emptyList()
        val result = mutableListOf<RelaySourceTrack>()
        for (i in 0 until tracksArray.length()) {
            val item = tracksArray.getJSONObject(i)
            val id = item.optString("id", "").takeIf { it.isNotEmpty() } ?: continue
            val title = item.optString("title", "").takeIf { it.isNotEmpty() } ?: continue
            val artistObj = item.optJSONObject("artist")
            val artist = artistObj?.optString("name", "")?.takeIf { it.isNotEmpty() } ?: "Unknown"
            val albumObj = item.optJSONObject("album")
            val album = albumObj?.optString("title", "")?.takeIf { it.isNotEmpty() } ?: ""
            val durationSec = item.optLong("duration", 0L)
            val durationMs = durationSec * 1000L
            val artwork = albumObj?.let {
                it.optString("cover_medium", "").takeIf { art -> art.isNotEmpty() }
                    ?: it.optString("cover_big", "")
            } ?: ""

            // Positional arguments: id, streamUrl (null), title, artist, album, durationMs, artworkUrl
            result.add(
                RelaySourceTrack(
                    id,
                    null,           // streamUrl – lazy resolution
                    title,
                    artist,
                    album,
                    durationMs,
                    artwork
                )
            )
        }
        return result
    }

    private fun fetchJson(url: String): String {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "Relay-OctaveExtension/0.1.0")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            check(connection.responseCode in 200..299) {
                "Octave API returned HTTP ${connection.responseCode}"
            }
            connection.inputStream.bufferedReader().use { reader ->
                reader.readText(limit = 2_000_000)
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
            check(content.length + count <= limit) { "Octave response was too large" }
            content.append(buffer, 0, count)
        }
    }
}