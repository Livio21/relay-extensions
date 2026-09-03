package org.relay.extensions.soulseek

import dev.relay.music.source.api.RelaySourceFactory
import dev.relay.music.source.contract.RelaySourceContractTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoulseekSourceTest : RelaySourceContractTest() {
    override fun createFactory(): RelaySourceFactory = SoulseekSourceFactory()

    private fun searchPayload(
        username: String,
        token: Int,
        files: List<Triple<String, Long, List<Pair<Int, Int>>>>,
        slotFree: Boolean = true,
        queueLength: Int = 0,
    ): ByteArray {
        val builder = SoulseekMessageBuilder(Code.PEER_FILE_SEARCH_RESPONSE)
            .string(username)
            .u32(token)
            .u32(files.size)
        files.forEach { (path, size, attributes) ->
            builder.u8(1)
                .string(path)
                .u64(size)
                .string(path.substringAfterLast('.', ""))
                .u32(attributes.size)
            attributes.forEach { (code, value) -> builder.u32(code).u32(value) }
        }
        builder.bool(slotFree).u32(120_000).u32(queueLength).u32(0)
        return builder.toPayload()
    }

    @Test
    fun wireRoundTripsLittleEndianValues() {
        val frame = SoulseekMessageBuilder(26)
            .u32(123456)
            .u64(Long.MAX_VALUE)
            .string("query")
            .bool(true)
            .toFrame()
        val parsed = readFrame(ByteArrayInputStream(frame), 4)
        assertEquals(26, parsed.code)
        val reader = SoulseekReader(parsed.payload)
        assertEquals(123456, reader.readU32())
        assertEquals(Long.MAX_VALUE, reader.readU64())
        assertEquals("query", reader.readString())
        assertTrue(reader.readBool())
        assertEquals(0, reader.remaining)
    }

    @Test
    fun initFramingUsesSingleByteCode() {
        val frame = SoulseekInitBuilder(Code.INIT_PEER_INIT)
            .string("user")
            .string("P")
            .u32(0)
            .toFrame()
        val parsed = readFrame(ByteArrayInputStream(frame), 1)
        assertEquals(Code.INIT_PEER_INIT, parsed.code)
        val reader = SoulseekReader(parsed.payload)
        assertEquals("user", reader.readString())
        assertEquals("P", reader.readString())
        assertEquals(0, reader.readU32())
    }

    @Test
    fun compressedSearchResponseInflates() {
        val inner = searchPayload(
            "peer1",
            42,
            listOf(Triple("@@abc\\Music\\Artist - Album\\01 - Song.mp3", 4_000_000L, listOf(0 to 320, 1 to 201, 2 to 1))),
        )
        val deflater = Deflater()
        deflater.setInput(inner)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            compressed.write(buffer, 0, count)
        }
        deflater.end()
        val payload = compressed.toByteArray()
        assertEquals(0x78, payload[0].toInt() and 0xFF)
        val response = parseFileSearchResponse("peer1", inflateIfCompressed(payload))
        assertNotNull(response)
        assertEquals(42, response!!.token)
        assertEquals(1, response.files.size)
        val file = response.files.first()
        assertEquals(320, file.bitrateKbps)
        assertEquals(201, file.durationSeconds)
        assertTrue(file.isVbr)
        assertTrue(response.slotFree)
        assertEquals(120_000, response.uploadSpeed)
    }

    @Test
    fun plainSearchResponseParsesWithoutCompression() {
        val payload = searchPayload(
            "peer2",
            7,
            listOf(
                Triple("@@abc\\flac\\Artist\\Album\\song.flac", 30_000_000L, listOf(1 to 245, 4 to 44_100, 5 to 16)),
                Triple("@@abc\\junk.txt", 10L, emptyList()),
            ),
            slotFree = false,
            queueLength = 4,
        )
        val response = parseFileSearchResponse("peer2", payload)
        assertNotNull(response)
        assertEquals(2, response!!.files.size)
        assertFalse(response.slotFree)
        assertEquals(4, response.queueLength)
        assertNull(response.files[1].bitrateKbps)
    }

    @Test
    fun trackRefCodecRoundTripsAndRejectsGarbage() {
        val reference = SoulseekTrackRef("peer", "@@abc\\Music\\01 - Song.mp3", 4_000_000L)
        val id = SoulseekTrackRefCodec.encode(reference)
        assertTrue(id.length <= SoulseekTrackRefCodec.MAX_TRACK_ID_CHARS)
        assertEquals(reference, SoulseekTrackRefCodec.decode(id))
        assertNull(SoulseekTrackRefCodec.decode("!!!not-base64!!!"))
        assertNull(SoulseekTrackRefCodec.decode(SoulseekTrackRefCodec.encode(SoulseekTrackRef("peer", "", 1L))))
        assertNull(SoulseekTrackRefCodec.decode(SoulseekTrackRefCodec.encode(SoulseekTrackRef("peer", "path", 0L))))
    }

    @Test
    fun describeTrackExtractsArtistAlbumAndTitle() {
        val text = describeTrack("@@abc\\Music\\Artist - Album (2001)\\03 - Song Title.mp3")!!
        assertEquals("Song Title", text.title)
        assertEquals("Artist", text.artist)
        assertEquals("Album (2001)", text.album)
        val flat = describeTrack("@@abc\\downloads\\Artist - Title.mp3")!!
        assertEquals("Title", flat.title)
        assertEquals("Artist", flat.artist)
        assertNull(flat.album)
        val bare = describeTrack("@@abc\\song.mp3")!!
        assertEquals("song", bare.title)
        assertEquals("", bare.artist)
    }

    @Test
    fun rankingPrefersFreeSlotsAndAudioExtensions() {
        val query = "artist song"
        val slotPeer = "free"
        val busyPeer = "busy"
        val files = listOf(
            SoulseekFile(busyPeer, "@@a\\Music\\artist song.exe", 4_000_000, 320, 200, false),
            SoulseekFile(slotPeer, "@@a\\Music\\artist - song.mp3", 4_000_000, 320, 200, false),
            SoulseekFile(slotPeer, "@@a\\Music\\artist - song.nfo", 1_000, null, null, false),
            SoulseekFile(slotPeer, "@@a\\Music\\artist - song.flac", 30_000_000, null, 240, false),
        )
        val ranked = rankSoulseekFiles(files, mapOf(slotPeer to true, busyPeer to false), query, FORMAT_ANY)
        assertEquals(2, ranked.size)
        assertEquals("flac", fileExtension(ranked[0].path))
        assertEquals("mp3", fileExtension(ranked[1].path))
        assertTrue(isPlayableAudio(ranked[0].path))
        assertTrue(isPlayableAudio(ranked[1].path))
    }

    @Test
    fun formatFilterRestrictsResults() {
        val files = listOf(
            SoulseekFile("peer", "@@a\\song.mp3", 4_000_000, 320, 200, false),
            SoulseekFile("peer", "@@a\\song.flac", 30_000_000, null, 200, false),
        )
        assertEquals(1, rankSoulseekFiles(files, emptyMap(), "song", FORMAT_FLAC).size)
        assertEquals(2, rankSoulseekFiles(files, emptyMap(), "song", FORMAT_ANY).size)
        assertEquals(1, rankSoulseekFiles(files, emptyMap(), "song", FORMAT_MP3).size)
    }

    @Test
    fun oversizedFilesNeverStream() {
        val oversized = SoulseekFile("peer", "@@a\\huge.flac", MAX_STREAM_BYTES + 1, null, 300, false)
        assertTrue(rankSoulseekFiles(listOf(oversized), emptyMap(), "huge", FORMAT_ANY).isEmpty())
        val id = SoulseekTrackRefCodec.encode(SoulseekTrackRef("peer", "@@a\\huge.flac", MAX_STREAM_BYTES + 1))
        val decoded = SoulseekTrackRefCodec.decode(id)
        assertNotNull(decoded)
        assertNotEquals(MAX_STREAM_BYTES, decoded!!.sizeBytes)
    }

    @Test
    fun fileSearchResponseAckIsWellFormed() {
        val frame = fileSearchResponseAck("peer", 99)
        val parsed = readFrame(ByteArrayInputStream(frame), 4)
        assertEquals(Code.PEER_FILE_SEARCH_RESPONSE, parsed.code)
        val reader = SoulseekReader(parsed.payload)
        assertEquals("peer", reader.readString())
        assertEquals(99, reader.readU32())
        assertEquals(0, reader.readU32())
        assertFalse(reader.readBool())
        assertEquals(0, reader.readU32())
        assertEquals(0, reader.readU32())
        assertEquals(0, reader.readU32())
    }
}
