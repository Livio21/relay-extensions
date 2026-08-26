package org.relay.extensions.audius

import dev.relay.music.source.api.RelaySourceFactory
import dev.relay.music.source.contract.RelaySourceContractTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiusSourceTest : RelaySourceContractTest() {
    override fun createFactory(): RelaySourceFactory = AudiusSourceFactory()

    @Test
    fun parsesTrackMetadataAndArtworkWithoutNetwork() {
        val json = """
            {
              "data": [
                {
                  "id": "QJpGKw",
                  "title": "Hello World",
                  "duration": 146,
                  "track_cid": "QmVTestCid123",
                  "is_delete": false,
                  "is_available": true,
                  "is_unlisted": false,
                  "is_streamable": true,
                  "is_stream_gated": false,
                  "artwork": {
                    "150x150": "https://example.com/150.jpg",
                    "480x480": "https://example.com/480.jpg",
                    "1000x1000": "https://example.com/1000.jpg"
                  },
                  "user": { "name": "Test Artist" }
                }
              ]
            }
        """.trimIndent()
        val result = parseAudiusTracks(json)
        assertEquals(1, result.size)
        assertEquals("QJpGKw", result[0].id)
        assertEquals("Hello World", result[0].title)
        assertEquals("Test Artist", result[0].artist)
        assertEquals(146_000L, result[0].durationMs)
        assertEquals("https://example.com/480.jpg", result[0].artworkUrl)
        assertNull(result[0].streamUrl)
    }

    @Test
    fun filtersNonPlayableTracks() {
        val json = """
            {
              "data": [
                { "id": "good1", "title": "Keep", "duration": 120, "track_cid": "Qm1", "is_delete": false, "is_available": true, "is_unlisted": false, "is_streamable": true, "user": { "name": "A" } },
                { "id": "badDel", "title": "Deleted", "duration": 120, "track_cid": "Qm2", "is_delete": true, "is_available": true, "is_unlisted": false, "is_streamable": true, "user": { "name": "A" } },
                { "id": "badUn", "title": "Unlisted", "duration": 120, "track_cid": "Qm3", "is_delete": false, "is_available": true, "is_unlisted": true, "is_streamable": true, "user": { "name": "A" } },
                { "id": "badStream", "title": "Not streamable", "duration": 120, "track_cid": "Qm4", "is_delete": false, "is_available": true, "is_unlisted": false, "is_streamable": false, "user": { "name": "A" } },
                { "id": "badCid", "title": "No CID", "duration": 120, "track_cid": "", "is_delete": false, "is_available": true, "is_unlisted": false, "is_streamable": true, "user": { "name": "A" } },
                { "id": "badGated", "title": "Gated", "duration": 120, "track_cid": "Qm5", "is_delete": false, "is_available": true, "is_unlisted": false, "is_streamable": true, "is_stream_gated": true, "user": { "name": "A" } }
              ]
            }
        """.trimIndent()
        val result = parseAudiusTracks(json)
        assertEquals(1, result.size)
        assertEquals("good1", result[0].id)
    }

    @Test
    fun malformedRowsAreSkippedAndPageIsCapped() {
        val data = buildString {
            append("""{"data":[""")
            append("""{"id":"","title":"Bad id","duration":120,"track_cid":"Qm1","is_available":true,"is_streamable":true,"user":{"name":"A"}},""")
            append("""{"id":"ok1","title":"","duration":120,"track_cid":"Qm1","is_available":true,"is_streamable":true,"user":{"name":"A"}},""")
            repeat(120) { index ->
                if (index > 0) append(",")
                append("""{"id":"id$index","title":"T$index","duration":120,"track_cid":"Qm$index","is_available":true,"is_streamable":true,"user":{"name":"A"}}""")
            }
            append("]}")
        }
        assertEquals(100, parseAudiusTracks(data).size)
    }

    @Test
    fun streamUrlRequiresValidIdAndIsLazy() {
        assertEquals(
            "https://discoveryprovider.audius.co/v1/tracks/QJpGKw/stream?app_name=Relay",
            audiusStreamUrl("QJpGKw"),
        )
        assertTrue(runCatching { audiusStreamUrl("") }.isFailure)
        assertTrue(runCatching { audiusStreamUrl("ab") }.isFailure)
        assertTrue(runCatching { audiusStreamUrl("bad/id") }.isFailure)
        assertTrue(isValidAudiusId("abc123"))
        assertFalse(isValidAudiusId("ab"))
        assertFalse(isValidAudiusId("a/b"))
    }

    @Test
    fun artworkPrefersRowFriendlySizes() {
        val withAll = JSONObject(
            """{"150x150":"https://example.com/150.jpg","480x480":"https://example.com/480.jpg","1000x1000":"https://example.com/1000.jpg"}""",
        )
        assertEquals("https://example.com/480.jpg", selectAudiusArtwork(withAll))
        val onlyLarge = JSONObject("""{"1000x1000":"https://example.com/1000.jpg"}""")
        assertEquals("https://example.com/1000.jpg", selectAudiusArtwork(onlyLarge))
        assertNull(selectAudiusArtwork(null))
        assertNull(selectAudiusArtwork(JSONObject("{}")))
        // Non-HTTPS rejected.
        val insecure = JSONObject("""{"480x480":"http://example.com/480.jpg"}""")
        assertNull(selectAudiusArtwork(insecure))
    }

    @Test
    fun listingsAreWithinHostBounds() {
        // Contract test already checks getListings bounds, but also verify mapping is consistent.
        assertEquals(AUDIUS_LISTINGS.size, AUDIUS_GENRE_BY_LISTING.size)
        AUDIUS_LISTINGS.forEach { listing ->
            assertTrue(AUDIUS_GENRE_BY_LISTING.containsKey(listing.id))
        }
    }
}
