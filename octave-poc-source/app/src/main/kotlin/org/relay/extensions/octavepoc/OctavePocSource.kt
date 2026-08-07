package org.relay.extensions.octave

import android.util.Log
import dev.relay.music.source.api.BaseRelaySource
import dev.relay.music.source.api.RelaySourcePage
import dev.relay.music.source.api.RelaySourceSetting
import dev.relay.music.source.api.RelaySourceTrack
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections

private const val TAG = "OctavePocSource"

// Hardcoded API key for audio streaming.
private const val API_KEY = "octk_tjezki_4df3168bbc8c12c1193b4017d235b25b"

// Base endpoints.
private const val API_BASE = "https://api.octavestreaming.com"
private const val SEARCH_ENDPOINT = "$API_BASE/api/search/page"
private const val AUDIO_ENDPOINT = "$API_BASE/audio/320"

// User‑Agent sent with all requests.
private const val USER_AGENT = "Relay-OctaveExtension/0.1.0"

/**
 * Octave source extension.
 *
 * Uses the public search endpoint `/api/search/page` which returns a `tracks` array.
 * Stream URLs are resolved lazily because they require the API key.
 */
private class OctavePocSource : BaseRelaySource() {

    @Volatile
    private var pageSize = 30   // default, matches the example.

    override fun getId(): String = "octave"
    override fun getName(): String = "Octave"

    // No static listings – only search.
    override fun getListings(): List<Nothing> = emptyList()
    override fun browse(listingId: String, page: Int): RelaySourcePage =
        RelaySourcePage(emptyList(), false)

    override fun search(query: String, page: Int): RelaySourcePage {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return RelaySourcePage(emptyList(), false)
        }
        val searchTerm = removeFieldPrefix(trimmed)
        val encoded = URLEncoder.encode(searchTerm, "UTF-8")
        // Assume the API supports a `page` parameter (1‑based).
        // If not, we won't get more than one page anyway.
        val url = "$SEARCH_ENDPOINT?query=$encoded&limit=$pageSize&page=$page"
        Log.d(TAG, "Search URL: $url")

        return try {
            val response = fetchJson(url)
            val json = JSONObject(response)
            val tracksArray = json.optJSONArray("tracks")
                ?: return RelaySourcePage(emptyList(), false)

            val tracks = mutableListOf<RelaySourceTrack>()
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

                // Artwork: prefer cover_medium, fallback to cover_big, etc.
                val artwork = albumObj?.let {
                    it.optString("cover_medium", "").takeIf { art -> art.isNotEmpty() }
                        ?: it.optString("cover_big", "")
                } ?: ""

                // Lazy stream – set streamUrl = null; we resolve on playback.
                val track = RelaySourceTrack(
                    id = id,
                    streamUrl = null,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    artworkUrl = artwork
                )
                tracks.add(track)
            }

            // Determine if there might be more pages.
            // The API doesn't return total count. We'll assume if we got `pageSize` results,
            // there may be a next page. This is a best effort.
            val hasNext = tracks.size >= pageSize
            RelaySourcePage(tracks, hasNext)
        } catch (e: Exception) {
            Log.e(TAG, "Search error", e)
            RelaySourcePage(emptyList(), false)
        }
    }

    override fun resolveStreamUrl(trackId: String): String? {
        // Build the audio URL with the track ID and the hardcoded key.
        return "$AUDIO_ENDPOINT?track=$trackId&k=$API_KEY"
    }

    override fun resolveArtworkUrl(trackId: String): String? {
        // Not needed because we provide artwork in search results.
        return null
    }

    override fun resolveDownloadUrl(trackId: String): String? {
        // Downloads use the same stream URL.
        return resolveStreamUrl(trackId)
    }

    override fun getMediaRequestHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT
            // Add Referer if the media host requires it – test with curl.
            // "Referer" to "https://music.octavestreaming.com/"
        )
    }

    override fun getSettings(): List<RelaySourceSetting> {
        return listOf(
            RelaySourceSetting(
                key = "page-size",
                name = "Results per page",
                type = RelaySourceSetting.Type.CHOICE,
                defaultValue = "30",
                choices = listOf("10", "20", "30", "50")
            )
        )
    }

    override fun applySettings(values: Map<String, String>) {
        values["page-size"]?.toIntOrNull()?.takeIf { it in 1..100 }?.let {
            pageSize = it
        }
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

    private fun fetchJson(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode from $urlString")
            }
            // Bound response size to avoid memory issues (2 MB).
            val maxBytes = 2 * 1024 * 1024
            val inputStream = connection.inputStream
            val bytes = inputStream.use { it.readBytes() }
            if (bytes.size > maxBytes) {
                throw IOException("Response too large: ${bytes.size} bytes")
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }
}