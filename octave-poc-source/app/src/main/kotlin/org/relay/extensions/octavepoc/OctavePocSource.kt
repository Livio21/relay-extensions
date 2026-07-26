package org.relay.extensions.octavepoc

import dev.relay.music.source.api.BaseRelaySource
import dev.relay.music.source.api.RelaySource
import dev.relay.music.source.api.RelaySourceApi
import dev.relay.music.source.api.RelaySourceFactory
import dev.relay.music.source.api.RelaySourcePage
import dev.relay.music.source.api.RelaySourceSetting
import dev.relay.music.source.api.RelaySourceTrack
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Narrow local proof: a numeric search query is an Octave track ID. It does not catalogue,
 * scrape, discover metadata, or embed a key. It is a test-only catalog entry, not a marketplace
 * recommendation or a general-purpose Octave client.
 */
class OctavePocSourceFactory : RelaySourceFactory {
    override fun getApiVersion() = RelaySourceApi.VERSION

    override fun createSources(): List<RelaySource> = listOf(OctavePocSource())
}

private class OctavePocSource : BaseRelaySource() {
    @Volatile private var publicPlaybackKey = ""

    override fun getId() = "octave-poc"
    override fun getName() = "Octave (proof of concept)"

    override fun getSettings() = listOf(
        RelaySourceSetting(
            "public-playback-key",
            "Public playback key",
            RelaySourceSetting.Type.TEXT,
            "",
        ),
    )

    override fun applySettings(values: Map<String, String>) {
        publicPlaybackKey = values["public-playback-key"].orEmpty().trim().take(256)
    }

    override fun search(query: String, page: Int): RelaySourcePage {
        if (page != 1) return RelaySourcePage(emptyList(), false)
        val trackId = octaveTrackId(query) ?: return RelaySourcePage(emptyList(), false)
        // Metadata endpoints are intentionally not guessed from browser traffic in this POC.
        return RelaySourcePage(
            listOf(
                RelaySourceTrack(
                    trackId,
                    null,
                    "Octave track $trackId",
                    "Octave",
                    null,
                    null,
                    null,
                ),
            ),
            false,
        )
    }

    override fun resolveStreamUrl(trackId: String): String? {
        val safeTrackId = octaveTrackId(trackId) ?: return null
        val key = publicPlaybackKey.takeIf(::isPublicPlaybackKey) ?: return null
        val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8.name())
        return "https://api.octavestreaming.com/audio/320?track=$safeTrackId&k=$encodedKey"
    }

    override fun getMediaRequestHeaders() = mapOf(
        "Accept" to "audio/mpeg,*/*;q=0.8",
        "Origin" to "https://music.octavestreaming.com",
        "User-Agent" to USER_AGENT,
    )
}

internal fun octaveTrackId(value: String): String? = value.trim().takeIf { it.matches(TRACK_ID) }

private fun isPublicPlaybackKey(value: String): Boolean =
    value.matches(PUBLIC_KEY) && value.startsWith("octk_")

private val TRACK_ID = Regex("[1-9][0-9]{0,18}")
private val PUBLIC_KEY = Regex("[A-Za-z0-9_-]{8,256}")
private const val USER_AGENT = "RelayOctavePoc/0.1"
