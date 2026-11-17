package org.relay.extensions.soulseek

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransferBufferTest {
    private lateinit var spoolDir: File

    @Before
    fun setUp() {
        spoolDir = File.createTempFile("relay-slsk-test-", "-dir")
        spoolDir.delete()
        spoolDir.mkdirs()
    }

    @After
    fun tearDown() {
        spoolDir.deleteRecursively()
    }

    @Test
    fun spoolsToDiskAndServesRanges() {
        val buffer = TransferBuffer(spoolDir)
        val first = "hello ".toByteArray()
        val second = "world".toByteArray()
        buffer.append(first, first.size)
        buffer.append(second, second.size)

        assertEquals(11L, buffer.size())
        val chunk = ByteArray(5)
        assertEquals(5, buffer.readInto(chunk, 6, 5))
        assertEquals("world", String(chunk))
        assertEquals(5, buffer.readInto(chunk, 0, 5))
        assertEquals("hello", String(chunk))
        assertEquals(0, buffer.readInto(chunk, 11, 5))

        assertEquals(1, spoolFiles().size)
        buffer.clear()
        assertEquals(0L, buffer.size())
        assertEquals(0, spoolFiles().size)
    }

    @Test
    fun waitForReturnsWhenBytesArrive() {
        val buffer = TransferBuffer(spoolDir)
        val deadline = System.currentTimeMillis() + 200
        assertFalse(buffer.waitFor(4, { false }, deadline))

        val bytes = byteArrayOf(1, 2, 3, 4)
        buffer.append(bytes, bytes.size)
        assertTrue(buffer.waitFor(4, { false }, System.currentTimeMillis() + 1_000))
        assertFalse(buffer.waitFor(5, { false }, System.currentTimeMillis() + 50))
    }

    @Test
    fun fallsBackToMemoryWithoutADirectory() {
        val buffer = TransferBuffer(null)
        val bytes = ByteArray(4_096) { it.toByte() }
        buffer.append(bytes, bytes.size)
        assertEquals(4_096L, buffer.size())

        val chunk = ByteArray(16)
        assertEquals(16, buffer.readInto(chunk, 1_024, 16))
        assertEquals(bytes[1_024], chunk[0])
        buffer.clear()
    }

    @Test
    fun largeLosslessFilesAreAcceptedNow() {
        assertTrue(isAcceptableSize(300L * 1_024 * 1_024))
        assertTrue(isAcceptableSize(MAX_STREAM_BYTES))
        assertFalse(isAcceptableSize(MAX_STREAM_BYTES + 1))
        assertFalse(isAcceptableSize(0))
    }

    @Test
    fun staleSpoolsAreRemovedButFreshOnesSurvive() {
        val old = File(spoolDir, "relay-slsk-old.part").apply {
            writeText("old")
            setLastModified(System.currentTimeMillis() - 48L * 60 * 60 * 1_000)
        }
        val fresh = File(spoolDir, "relay-slsk-fresh.part").apply { writeText("fresh") }
        val unrelated = File(spoolDir, "other.part").apply {
            writeText("other")
            setLastModified(System.currentTimeMillis() - 48L * 60 * 60 * 1_000)
        }

        TransferBuffer.cleanStaleSpools(spoolDir)

        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertTrue(unrelated.exists())
    }

    private fun spoolFiles(): List<File> =
        spoolDir.listFiles { file -> file.name.startsWith("relay-slsk-") }?.toList().orEmpty()
}
