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

/**
 * Octave source extension.
 *
 * Uses the public search endpoint: /api/search/page?query=<q>&limit=<n>&page=<p>
 * Audio is streamed via /audio/320?track=<id>&k=<apiKey>
 * Artwork is taken from the album object in search results.
 */
class OctaveSourceFactory : RelaySourceFactory {
    override fun getApiVersion() = RelaySourceApi.VERSION
    override fun createSources(): List<RelaySource> = listOf(OctaveSource())
}

private class OctaveSource : BaseRelaySource() {
    @Volatile
    private var pageSize = 30   // default matches the example

    override fun getId() = "octave"
    override fun getName() = "Octave"

    override fun getSettings() = listOf(
        RelaySourceSetting(
            key = "page-size",
            name = "Results per page",
            type = RelaySourceSetting.Type.CHOICE,
            defaultValue = "30",
            choices = listOf("10", "20", "30", "50")
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
        // Determine if there is a next page – we assume that if we got exactly `pageSize` tracks,
        // there may be more. The API doesn't return a total count.
        val hasNext = tracks.size >= pageSize
        return RelaySourcePage(tracks, hasNext)
    }

    // No static listings
    override fun getListings() = emptyList<Nothing>()
    override fun browse(listingId: String, page: Int) = RelaySourcePage(emptyList(), false)

    // Lazy stream resolution – called just before playback
    override fun resolveStreamUrl(trackId: String): String? {
        // Hardcoded API key (as per user request)
        return "https://api.octavestreaming.com/audio/320?track=$trackId&k=octk_tjezki_4df3168bbc8c12c1193b4017d235b25b"
    }

    override fun resolveArtworkUrl(trackId: String): String? {
        // We already provide artwork in search results, so no need for this.
        return null
    }

    override fun resolveDownloadUrl(trackId: String): String? {
        // Same as stream URL
        return resolveStreamUrl(trackId)
    }

    override fun getMediaRequestHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Relay-OctaveExtension/0.1.0"
            // Add Referer if media host requires it – test with curl.
            // "Referer" to "https://music.octavestreaming.com/"
        )
    }

    // ---------- helpers ----------

    private fun removeFieldPrefix(query: String): String {
        val prefixes = listOf("title:", "artist:", "album:")
        var result = query
        for (prefix in prefixes) {
            if (result.startsWith(prefix, ignoreCase = true)) {
                result = result.substring(prefix.length).trimStart()
                break
            }
        }
        return result
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

            // Prefer cover_medium, fallback to cover_big
            val artwork = albumObj?.let {
                it.optString("cover_medium", "").takeIf { art -> art.isNotEmpty() }
                    ?: it.optString("cover_big", "")
            } ?: ""

            // streamUrl is null – we resolve lazily in resolveStreamUrl()
            val track = RelaySourceTrack(
                id = id,
                streamUrl = null,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                artworkUrl = artwork
            )
            result.add(track)
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
                // Bound the response size (2 MB)
                reader.readText(limit = 2_000_000)
            }
        } finally {
            connection.disconnect()
        }
    }

    // Helper to read with size limit (same as FMA)
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