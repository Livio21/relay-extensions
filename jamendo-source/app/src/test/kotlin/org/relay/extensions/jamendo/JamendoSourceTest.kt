package org.relay.extensions.jamendo

import dev.relay.music.source.api.RelaySourceFactory
import dev.relay.music.source.contract.RelaySourceContractTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JamendoSourceTest : RelaySourceContractTest() {
    override fun createFactory(): RelaySourceFactory = JamendoSourceFactory()

    @Test
    fun parsesAlbumTracksAndSingles() {
        val json = """
            {
              "headers": { "status": "success", "code": 0, "error_message": "", "results_count": 2 },
              "results": [
                {
                  "id": "1848357",
                  "name": "manana sera tarde",
                  "duration": 272,
                  "artist_name": "fankel",
                  "album_name": "manana sera tarde",
                  "image": "https://usercontent.jamendo.com?type=album&id=368084&width=300&trackid=1848357"
                },
                {
                  "id": "42",
                  "name": "Single",
                  "duration": 0,
                  "artist_name": "Someone",
                  "album_name": "",
                  "image": "",
                  "album_image": "https://example.com/single.jpg"
                }
              ]
            }
        """.trimIndent()
        val tracks = parseJamendoPage(json).tracks
        assertEquals(2, tracks.size)
        assertEquals("1848357", tracks[0].id)
        assertEquals("fankel", tracks[0].artist)
        assertEquals("manana sera tarde", tracks[0].album)
        assertEquals(272_000L, tracks[0].durationMs)
        assertEquals(
            "https://usercontent.jamendo.com?type=album&id=368084&width=300&trackid=1848357",
            tracks[0].artworkUrl,
        )
        assertNull(tracks[0].streamUrl)
        assertNull(tracks[1].album)
        assertNull(tracks[1].durationMs)
        assertEquals("https://example.com/single.jpg", tracks[1].artworkUrl)
    }

    @Test
    fun skipsRowsRelayCannotRender() {
        val json = """
            {
              "headers": { "status": "success", "code": 0 },
              "results": [
                { "id": "", "name": "No id", "duration": 100, "artist_name": "A" },
                { "id": "1", "name": "", "duration": 100, "artist_name": "A" },
                { "id": "2", "name": "No artist", "duration": 100, "artist_name": "" },
                { "id": "3", "name": "Insecure art", "duration": 100, "artist_name": "A", "image": "http://example.com/a.jpg" },
                { "id": "4", "name": "Good", "duration": 100, "artist_name": "A" }
              ]
            }
        """.trimIndent()
        val tracks = parseJamendoPage(json).tracks
        // A track with unusable artwork stays playable; only the artwork is dropped.
        assertEquals(2, tracks.size)
        assertEquals("3", tracks[0].id)
        assertNull(tracks[0].artworkUrl)
        assertEquals("4", tracks[1].id)
        assertNull(tracks[1].artworkUrl)
    }

    @Test
    fun rejectsInvalidClientIdWithGuidance() {
        assertFalse(isUsableClientId(""))
        assertFalse(isUsableClientId("   "))
        assertFalse(isUsableClientId("abc def"))
        assertTrue(isUsableClientId("a1b2c3d4e5f6a7b8c9d0"))
        assertTrue(isUsableClientId("short"))
    }

    @Test
    fun mapsApiErrorsToReadableMessages() {
        val missingKey = """
            { "headers": { "status": "failed", "code": 5, "error_message": "Invalid Client Id" }, "results": [] }
        """.trimIndent()
        val error = runCatching { parseJamendoPage(missingKey) }.exceptionOrNull()
        assertTrue(error is JamendoApiException)
        assertTrue(error!!.message!!.contains("client ID"))

        val other = jamendoErrorMessage(7, "boom\nline")
        assertFalse(other.contains("\n"))
        assertTrue(other.contains("code 7"))
    }

    @Test
    fun buildsStableLosslessAndFallbackUrls() {
        assertEquals(
            "https://prod-1.storage.jamendo.com/?trackid=1848357&format=flac&from=relay",
            jamendoMediaUrl("1848357", FORMAT_FLAC),
        )
        assertEquals(
            "https://prod-1.storage.jamendo.com/?trackid=1848357&format=mp32&from=relay",
            jamendoMediaUrl(" 1848357 ", FORMAT_MP3),
        )
        assertTrue(runCatching { jamendoMediaUrl("abc", FORMAT_FLAC) }.isFailure)
        assertTrue(runCatching { jamendoMediaUrl("12/34", FORMAT_FLAC) }.isFailure)
        assertEquals(
            "https://prod-1.storage.jamendo.com/?trackid=7&format=flac&from=relay",
            jamendoMediaUrl("7", "unknown"),
        )
    }

    @Test
    fun listingsAndTheirQueriesStayInSync() {
        assertEquals(JAMENDO_LISTINGS.size, JAMENDO_QUERY_BY_LISTING.size)
        JAMENDO_LISTINGS.forEach { listing ->
            assertTrue(listing.id, JAMENDO_QUERY_BY_LISTING.containsKey(listing.id))
        }
        assertEquals("popular", JAMENDO_QUERY_BY_LISTING.keys.first())
    }

    @Test
    fun nextHeaderDrivesPagination() {
        val withNext = """{"headers":{"status":"success","code":0,"next":"https://api.jamendo.com/v3.0/tracks?offset=50"},"results":[{"id":"1","name":"A","duration":60,"artist_name":"X"}]}"""
        assertTrue(parseJamendoPage(withNext).hasNext)
        val lastPage = """{"headers":{"status":"success","code":0},"results":[{"id":"1","name":"A","duration":60,"artist_name":"X"}]}"""
        assertEquals(false, parseJamendoPage(lastPage, expectedPageSize = 50).hasNext)
        assertEquals(true, parseJamendoPage(lastPage, expectedPageSize = 1).hasNext)
    }

    @Test
    fun pageIsCappedAtHostMaximum() {
        val rows = StringBuilder()
        repeat(120) { index ->
            if (index > 0) rows.append(',')
            rows.append("""{"id":"$index","name":"T$index","duration":60,"artist_name":"A"}""")
        }
        val json = """{"headers":{"status":"success","code":0},"results":[$rows]}"""
        assertEquals(100, parseJamendoPage(json).tracks.size)
    }
}
