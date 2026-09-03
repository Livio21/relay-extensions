package org.relay.extensions.soulseek

import dev.relay.music.source.api.BaseRelaySource
import dev.relay.music.source.api.RelaySource
import dev.relay.music.source.api.RelaySourceApi
import dev.relay.music.source.api.RelaySourceFactory
import dev.relay.music.source.api.RelaySourceListing
import dev.relay.music.source.api.RelaySourcePage
import dev.relay.music.source.api.RelaySourceSetting
import dev.relay.music.source.api.RelaySourceTrack

/**
 * Soulseek peer network. The user logs in with their own Soulseek account through the source
 * settings; searches fan out across peers and playback streams a peer transfer the extension
 * serves over loopback HTTP. No credentials are embedded and none are ever logged.
 */
class SoulseekSourceFactory : RelaySourceFactory {
    override fun getApiVersion() = RelaySourceApi.VERSION

    override fun createSources(): List<RelaySource> = listOf(SoulseekSource())
}

private class SoulseekSource : BaseRelaySource() {
    @Volatile private var username = ""
    @Volatile private var password = ""
    @Volatile private var maxResults = DEFAULT_MAX_RESULTS
    @Volatile private var format = FORMAT_ANY
    @Volatile private var engine: SoulseekEngine? = null

    override fun getId() = "soulseek"

    override fun getName() = "Soulseek"

    override fun getListings() = emptyList<RelaySourceListing>()

    override fun getSettings() = listOf(
        RelaySourceSetting("username", "Soulseek username", RelaySourceSetting.Type.TEXT, ""),
        RelaySourceSetting("password", "Soulseek password", RelaySourceSetting.Type.TEXT, ""),
        RelaySourceSetting(
            "max-results",
            "Results per search",
            RelaySourceSetting.Type.CHOICE,
            DEFAULT_MAX_RESULTS.toString(),
            listOf("25", "50", "75", "100"),
        ),
        RelaySourceSetting(
            "format",
            "Preferred format",
            RelaySourceSetting.Type.CHOICE,
            FORMAT_ANY,
            listOf(FORMAT_ANY, FORMAT_MP3, FORMAT_FLAC),
        ),
    )

    override fun applySettings(values: Map<String, String>) {
        val newUser = values["username"]?.trim().orEmpty().take(64)
        val newPassword = values["password"]?.trim().orEmpty().take(128)
        val newMax = values["max-results"]?.toIntOrNull()?.takeIf { it in 1..100 } ?: DEFAULT_MAX_RESULTS
        val newFormat = when (values["format"]) {
            FORMAT_MP3 -> FORMAT_MP3
            FORMAT_FLAC -> FORMAT_FLAC
            else -> FORMAT_ANY
        }
        if (newUser != username || newPassword != password) {
            synchronized(this) {
                engine?.reset()
                engine = null
            }
        }
        username = newUser
        password = newPassword
        maxResults = newMax
        format = newFormat
    }

    override fun search(query: String, page: Int): RelaySourcePage {
        require(page in 1..MAX_PAGE) { "Soulseek page is out of range." }
        if (page > 1) return RelaySourcePage(emptyList(), false)
        val term = query.removeFieldPrefix().trim()
        if (term.isEmpty()) return RelaySourcePage(emptyList(), false)
        require(username.isNotEmpty() && password.isNotEmpty()) {
            "Add your Soulseek username and password in the extension settings."
        }
        require(term.length <= MAX_QUERY) { "The search is too long." }
        val engine = engine()
        val budget = if (engine.isConnected()) SEARCH_BUDGET_MS else SEARCH_COLD_BUDGET_MS
        val files = engine.search(term, budget, format)
        val tracks = files.take(maxResults).mapNotNull(::toTrack)
        return RelaySourcePage(tracks, false)
    }

    override fun resolveStreamUrl(trackId: String): String {
        val reference = SoulseekTrackRefCodec.decode(trackId)
            ?: error("This Soulseek track reference is no longer valid.")
        if (reference.sizeBytes > MAX_STREAM_BYTES) {
            error("This file is too large to stream from Soulseek.")
        }
        require(username.isNotEmpty() && password.isNotEmpty()) {
            "Add your Soulseek username and password in the extension settings."
        }
        val engine = engine()
        return engine.ensureTransfer(reference, STREAM_BUDGET_MS)
    }

    private fun engine(): SoulseekEngine = synchronized(this) {
        engine?.takeIf { !it.isShutdown() } ?: SoulseekEngine(EngineConfig(username, password)).also { engine = it }
    }

    private fun toTrack(file: SoulseekFile): RelaySourceTrack? {
        val id = SoulseekTrackRefCodec.encode(
            SoulseekTrackRef(file.username, file.path, file.sizeBytes),
        )
        if (id.length > SoulseekTrackRefCodec.MAX_TRACK_ID_CHARS) return null
        val text = describeTrack(file.path) ?: return null
        val title = text.title.take(1_024)
        val artist = (text.artist.ifBlank { file.username }).take(1_024)
        if (title.isBlank() || artist.isBlank()) return null
        return RelaySourceTrack(
            id,
            null,
            title,
            artist,
            text.album?.take(1_024),
            file.durationSeconds?.let { it * 1_000L }?.takeIf { it in 1..86_400_000L },
            null,
        )
    }

    private fun String.removeFieldPrefix(): String = when {
        startsWith("title:", ignoreCase = true) ||
            startsWith("artist:", ignoreCase = true) ||
            startsWith("album:", ignoreCase = true) -> substringAfter(':')
        else -> this
    }

    private companion object {
        const val DEFAULT_MAX_RESULTS = 50
        const val MAX_PAGE = 1_000
        const val MAX_QUERY = 256
        const val SEARCH_BUDGET_MS = 9_200L
        const val SEARCH_COLD_BUDGET_MS = 7_000L
        const val STREAM_BUDGET_MS = 8_000L
    }
}
