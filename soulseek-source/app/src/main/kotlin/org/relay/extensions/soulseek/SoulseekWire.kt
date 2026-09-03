package org.relay.extensions.soulseek

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Little-endian Soulseek wire primitives shared by the server, peer, and file connections. */
class SoulseekReader(private val payload: ByteArray) {
    private var position = 0

    val remaining: Int get() = payload.size - position

    fun readU8(): Int {
        requireAvailable(1)
        return payload[position++].toInt() and 0xFF
    }

    fun readU32(): Int {
        requireAvailable(4)
        val value = (payload[position].toInt() and 0xFF) or
            ((payload[position + 1].toInt() and 0xFF) shl 8) or
            ((payload[position + 2].toInt() and 0xFF) shl 16) or
            ((payload[position + 3].toInt() and 0xFF) shl 24)
        position += 4
        return value
    }

    fun readU64(): Long {
        requireAvailable(8)
        var value = 0L
        for (index in 0 until 8) {
            value = value or ((payload[position + index].toLong() and 0xFF) shl (8 * index))
        }
        position += 8
        return value
    }

    fun readBool(): Boolean = readU8() != 0

    fun readString(): String {
        val length = readU32()
        require(length in 0..remaining) { "Soulseek string length $length exceeds the message." }
        val text = String(payload, position, length, StandardCharsets.UTF_8)
        position += length
        return text
    }

    fun readIp(): String {
        val packed = readU32().toLong() and 0xFFFFFFFFL
        return "${packed and 0xFF}.${(packed shr 8) and 0xFF}.${(packed shr 16) and 0xFF}.${(packed shr 24) and 0xFF}"
    }

    private fun requireAvailable(count: Int) {
        if (position + count > payload.size) throw EOFException("Truncated Soulseek message.")
    }
}

/** Builds one framed Soulseek message: [uint32 length][uint32 code][payload]. */
class SoulseekMessageBuilder(private val code: Int) {
    private val payload = java.io.ByteArrayOutputStream()

    fun u8(value: Int) = apply { payload.write(value and 0xFF) }

    fun u32(value: Int) = apply {
        payload.write(value and 0xFF)
        payload.write((value shr 8) and 0xFF)
        payload.write((value shr 16) and 0xFF)
        payload.write((value shr 24) and 0xFF)
    }

    fun u64(value: Long) = apply {
        var raw = value
        repeat(8) {
            payload.write((raw and 0xFF).toInt())
            raw = raw shr 8
        }
    }

    fun bool(value: Boolean) = u8(if (value) 1 else 0)

    fun string(value: String) = apply {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        u32(bytes.size)
        payload.write(bytes)
    }

    fun toFrame(): ByteArray {
        val body = payload.toByteArray()
        val frame = ByteArray(body.size + 8)
        writeU32LE(frame, 0, body.size + 4)
        writeU32LE(frame, 4, code)
        System.arraycopy(body, 0, frame, 8, body.size)
        return frame
    }

    /** Raw payload without framing, for building nested or compressed messages in tests. */
    fun toPayload(): ByteArray = payload.toByteArray()
}

/** Peer-init framing: [uint32 length][uint8 code][payload]. */
class SoulseekInitBuilder(private val initCode: Int) {
    private val payload = java.io.ByteArrayOutputStream()

    fun u32(value: Int): SoulseekInitBuilder = apply {
        payload.write(value and 0xFF)
        payload.write((value shr 8) and 0xFF)
        payload.write((value shr 16) and 0xFF)
        payload.write((value shr 24) and 0xFF)
    }

    fun string(value: String): SoulseekInitBuilder = apply {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        u32(bytes.size)
        payload.write(bytes)
    }

    fun toFrame(): ByteArray {
        val body = payload.toByteArray()
        val frame = ByteArray(body.size + 5)
        writeU32LE(frame, 0, body.size + 1)
        frame[4] = initCode.toByte()
        System.arraycopy(body, 0, frame, 5, body.size)
        return frame
    }
}

fun writeU32LE(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value and 0xFF).toByte()
    target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    target[offset + 2] = ((value shr 16) and 0xFF).toByte()
    target[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

fun readU32LE(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

/** Reads exactly [count] bytes or fails; InputStream.read is not guaranteed to fill a buffer. */
fun readFully(input: InputStream, count: Int): ByteArray {
    val buffer = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = input.read(buffer, offset, count - offset)
        if (read < 0) throw EOFException("Soulseek connection closed mid-message.")
        offset += read
    }
    return buffer
}

/** One framed server/peer message: [uint32 length][uint32 code][payload]. */
class SoulseekFrame(val code: Int, val payload: ByteArray)

/** [codeWidth] is 4 for server/peer messages and 1 for the leading peer-init frame. */
fun readFrame(input: InputStream, codeWidth: Int): SoulseekFrame {
    val length = readU32LE(readFully(input, 4), 0)
    if (length < codeWidth || length > MAX_FRAME_BYTES) throw IOException("Soulseek frame length $length is invalid.")
    val body = readFully(input, length)
    return if (codeWidth == 1) {
        SoulseekFrame(body[0].toInt() and 0xFF, body.copyOfRange(1, body.size))
    } else {
        SoulseekFrame(readU32LE(body, 0), body.copyOfRange(4, body.size))
    }
}

/**
 * Peer payloads above a small size are zlib-compressed. The zlib stream always starts with 0x78,
 * which no valid little-endian Soulseek message code can begin with, so the sniff is unambiguous.
 */
fun inflateIfCompressed(payload: ByteArray): ByteArray {
    if (payload.isEmpty() || (payload[0].toInt() and 0xFF) != 0x78) return payload
    val inflater = Inflater()
    inflater.setInput(payload)
    try {
        val output = java.io.ByteArrayOutputStream(payload.size * 4)
        val buffer = ByteArray(16_384)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
                continue
            }
            output.write(buffer, 0, count)
            if (output.size() > MAX_INFLATED_BYTES) throw IOException("Soulseek message inflates past the size limit.")
        }
        return output.toByteArray()
    } catch (error: DataFormatException) {
        return payload
    } finally {
        inflater.end()
    }
}

fun md5Hex(value: String): String =
    MessageDigest.getInstance("MD5").digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** URL-safe base64 for track identity; hand-rolled so JVM unit tests avoid android.util.Base64. */
object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String {
        val builder = StringBuilder(((bytes.size + 2) / 3) * 4)
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index].toInt() and 0xFF
            val hasSecond = index + 1 < bytes.size
            val hasThird = index + 2 < bytes.size
            val second = if (hasSecond) bytes[index + 1].toInt() and 0xFF else 0
            val third = if (hasThird) bytes[index + 2].toInt() and 0xFF else 0
            val triple = (first shl 16) or (second shl 8) or third
            builder.append(ALPHABET[(triple shr 18) and 0x3F])
            builder.append(ALPHABET[(triple shr 12) and 0x3F])
            if (hasSecond) builder.append(ALPHABET[(triple shr 6) and 0x3F]) else builder.append('=')
            if (hasThird) builder.append(ALPHABET[triple and 0x3F]) else builder.append('=')
            index += 3
        }
        return builder.toString()
    }

    fun decode(value: String): ByteArray {
        val cleaned = value.trimEnd('=')
        require(cleaned.length % 4 != 1) { "Base64url length is invalid." }
        val output = java.io.ByteArrayOutputStream(cleaned.length * 3 / 4 + 2)
        var bits = 0
        var bitsRead = 0
        for (character in cleaned) {
            val decoded = alphabetValue(character)
            require(decoded >= 0) { "Base64url character is invalid." }
            bits = (bits shl 6) or decoded
            bitsRead += 6
            if (bitsRead >= 8) {
                bitsRead -= 8
                output.write((bits shr bitsRead) and 0xFF)
            }
        }
        return output.toByteArray()
    }

    private fun alphabetValue(character: Char): Int = when (character) {
        in 'A'..'Z' -> character - 'A'
        in 'a'..'z' -> character - 'a' + 26
        in '0'..'9' -> character - '0' + 52
        '-' -> 62
        '_' -> 63
        else -> -1
    }
}

fun OutputStream.writeFrame(frame: ByteArray) {
    write(frame)
    flush()
}

private const val MAX_FRAME_BYTES = 4_194_304
private const val MAX_INFLATED_BYTES = 4_194_304
