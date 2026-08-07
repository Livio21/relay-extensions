package org.relay.extensions.octavepoc

import dev.relay.music.source.api.RelaySourceFactory
import dev.relay.music.source.contract.RelaySourceContractTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OctavePocSourceTest : RelaySourceContractTest() {
    override fun createFactory(): RelaySourceFactory = OctaveSourceFactory()

    @Test
    fun parsesTrackMetadataAndArtworkWithoutNetwork() {
        val result = parseOctaveTracks(
            """{"tracks":[{"id":"2954912511","title":"Stupid song","artist":{"name":"Olivia Rodrigo"},"album":{"title":"GUTS","cover_medium":"https://images.example/250.jpg"},"duration":181}]}""",
        )
        assertEquals(1, result.size)
        assertEquals("2954912511", result[0].id)
        assertEquals("Olivia Rodrigo", result[0].artist)
        assertEquals("GUTS", result[0].album)
        assertEquals(181_000L, result[0].durationMs)
        assertEquals("https://images.example/250.jpg", result[0].artworkUrl)
    }

    @Test
    fun streamResolutionRequiresUserKeyAndValidTrackId() {
        assertEquals(
            "https://api.octavestreaming.com/audio/320?track=2954912511&k=octk_public-key",
            octaveStreamUrl("2954912511", "octk_public-key"),
        )
        assertTrue(runCatching { octaveStreamUrl("0", "octk_public-key") }.isFailure)
        assertTrue(runCatching { octaveStreamUrl("2954912511", "") }.isFailure)
    }

    @Test
    fun malformedRowsAreSkipped() {
        val result = parseOctaveTracks(
            """{"tracks":[{"id":"bad","title":"Missing artist"},{"id":"1","title":"Valid","artist":{"name":"Artist"}}]}""",
        )
        assertEquals(1, result.size)
        assertNull(result.single().album)
    }
}
