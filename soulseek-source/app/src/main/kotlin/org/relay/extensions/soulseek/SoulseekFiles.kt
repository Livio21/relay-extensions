package org.relay.extensions.soulseek

/** One shared file from a peer's search response. */
data class SoulseekFile(
    val username: String,
    val path: String,
    val sizeBytes: Long,
    val bitrateKbps: Int?,
    val durationSeconds: Int?,
    val isVbr: Boolean,
)

data class SoulseekResponse(
    val username: String,
    val token: Int,
    val files: List<SoulseekFile>,
    val slotFree: Boolean,
    val uploadSpeed: Int,
    val queueLength: Int,
)

/** Stable, bounded identity Relay persists for playlists, history, and downloads. */
data class SoulseekTrackRef(val username: String, val path: String, val sizeBytes: Long)

object SoulseekTrackRefCodec {
    private const val SEPARATOR = '\u001F'

    fun encode(reference: SoulseekTrackRef): String = Base64Url.encode(
        "${reference.username}$SEPARATOR${reference.path}$SEPARATOR${reference.sizeBytes}"
            .toByteArray(Charsets.UTF_8),
    )

    fun decode(id: String): SoulseekTrackRef? {
        if (id.length > MAX_TRACK_ID_CHARS) return null
        return runCatching {
            val parts = String(Base64Url.decode(id), Charsets.UTF_8).split(SEPARATOR)
            val username = parts.getOrNull(0)?.trim().orEmpty()
            val path = parts.getOrNull(1)?.trim().orEmpty()
            val size = parts.getOrNull(2)?.toLongOrNull() ?: -1L
            if (username.isEmpty() || path.isEmpty() || size <= 0) null else SoulseekTrackRef(username, path, size)
        }.getOrNull()
    }

    const val MAX_TRACK_ID_CHARS = 512
}

/** Parses a decompressed FileSearchResponse payload. Skips trailing junk defensively. */
fun parseFileSearchResponse(peer: String, payload: ByteArray): SoulseekResponse? {
    val reader = SoulseekReader(payload)
    val username = reader.readString().ifBlank { peer }
    val token = reader.readU32()
    val fileCount = reader.readU32()
    if (fileCount < 0) return null
    val files = ArrayList<SoulseekFile>(fileCount.coerceAtMost(MAX_FILES_PER_RESPONSE))
    repeat(fileCount) {
        if (reader.remaining <= 0 || files.size >= MAX_FILES_PER_RESPONSE) return@repeat
        reader.readU8()
        val path = reader.readString()
        val size = reader.readU64()
        val extension = reader.readString()
        val attributeCount = reader.readU32()
        var bitrate: Int? = null
        var duration: Int? = null
        var vbr = false
        repeat(attributeCount) {
            if (reader.remaining < 8) return@repeat
            val code = reader.readU32()
            val value = reader.readU32()
            when (code) {
                0 -> bitrate = value.takeIf { it in 1..2_000 }
                1 -> duration = value.takeIf { it in 1..(24 * 3_600) }
                2 -> vbr = value != 0
            }
        }
        if (path.isBlank() || size <= 0) return@repeat
        files.add(
            SoulseekFile(
                username = username,
                path = path,
                sizeBytes = size,
                bitrateKbps = bitrate,
                durationSeconds = duration,
                isVbr = vbr,
            ),
        )
    }
    val slotFree = if (reader.remaining > 0) reader.readBool() else false
    val uploadSpeed = if (reader.remaining >= 4) reader.readU32() else 0
    val queueLength = if (reader.remaining >= 4) reader.readU32() else 0
    return SoulseekResponse(username, token, files, slotFree, uploadSpeed, queueLength)
}

/** Builds the empty FileSearchResponse Relay sends back to acknowledge a peer's results. */
fun fileSearchResponseAck(username: String, token: Int): ByteArray =
    SoulseekMessageBuilder(Code.PEER_FILE_SEARCH_RESPONSE)
        .string(username)
        .u32(token)
        .u32(0)
        .bool(false)
        .u32(0)
        .u32(0)
        .u32(0)
        .toFrame()

data class SoulseekTrackText(val title: String, val artist: String, val album: String?)

private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "oga", "m4a", "aac", "wav", "aif", "aiff", "opus", "wma")
private val REJECTED_EXTENSIONS = setOf("exe", "msi", "url", "lnk", "scr", "bat", "cmd", "nfo", "sfv", "m3u", "m3u8", "cue", "log", "txt", "jpg", "jpeg", "png", "gif", "pdf", "zip", "rar", "7z", "md5", "sha1", "sha256")

fun fileExtension(path: String): String =
    path.substringAfterLast('/', path.substringAfterLast('\\'))
        .substringAfterLast('.', "")
        .lowercase()
        .takeIf { it.isNotEmpty() && it.length <= 5 }
        .orEmpty()

fun isPlayableAudio(path: String): Boolean {
    val extension = fileExtension(path)
    return extension in AUDIO_EXTENSIONS && extension !in REJECTED_EXTENSIONS
}

fun isAcceptableSize(sizeBytes: Long): Boolean = sizeBytes in 1..MAX_STREAM_BYTES

/**
 * Derives display text from a Soulseek share path such as
 * `@@abcde\\Music\\Artist - Album (2001)\\03 - Song Title.mp3`.
 */
fun describeTrack(path: String): SoulseekTrackText? {
    val segments = path.split('/', '\\').filter { it.isNotBlank() }
    if (segments.isEmpty()) return null
    val fileName = segments.last().substringBeforeLast('.')
    val parentText = segments.dropLast(1).lastOrNull()?.trim().orEmpty()
    val parentIsFolder = parentText.isNotEmpty() && !parentText.matches(Regex("^[0-9]{1,4}$"))
    return when {
        parentIsFolder && parentText.contains(Regex("\\s+-\\s+")) -> {
            val split = parentText.split(Regex("\\s+-\\s+"), limit = 2)
            SoulseekTrackText(
                title = cleanTitle(fileName) ?: return null,
                artist = split[0].trim(),
                album = cleanTitle(split[1]),
            )
        }
        fileName.contains(Regex("\\s+-\\s+")) -> {
            val split = fileName.split(Regex("\\s+-\\s+"), limit = 2)
            SoulseekTrackText(
                title = cleanTitle(split[1]) ?: return null,
                artist = split[0].trim(),
                album = null,
            )
        }
        else -> SoulseekTrackText(
            title = cleanTitle(fileName) ?: return null,
            artist = "",
            album = null,
        )
    }
}

private fun cleanTitle(raw: String): String? {
    var title = raw.trim()
    if (title.isEmpty()) return null
    title = title.replace(Regex("^[0-9]{1,3}\\s*[.\\-_)]+\\s*"), "")
    title = title.replace(
        Regex("\\s*[(\\[{](official|audio|video|lyric|remaster|hd|hq)[^)\\]}]*[)\\]}]\\s*", RegexOption.IGNORE_CASE),
        " ",
    )
    title = title.replace(Regex("\\s*\\[[^]]*]\\s*$"), " ")
    title = title.replace(Regex("\\s+"), " ").trim(' ', '-', '_')
    return title.takeIf { it.isNotBlank() }?.take(1_024)
}

/** Ranks shared files for one query. Pure so it is cheap to test against captured responses. */
fun rankSoulseekFiles(
    files: List<SoulseekFile>,
    slotFreeByUser: Map<String, Boolean>,
    query: String,
    format: String,
): List<SoulseekFile> {
    val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    return files.asSequence()
        .filter { isPlayableAudio(it.path) }
        .filter { isAcceptableSize(it.sizeBytes) }
        .filter { format == FORMAT_ANY || fileExtension(it.path) == format }
        .filter { it.durationSeconds == null || it.durationSeconds >= MIN_DURATION_SECONDS }
        .map { file -> file to score(file, slotFreeByUser[file.username] ?: false, terms) }
        .filter { (_, score) -> score > Int.MIN_VALUE / 2 }
        .sortedByDescending { (_, score) -> score }
        .map { (file, _) -> file }
        .toList()
}

private fun score(file: SoulseekFile, slotFree: Boolean, terms: List<String>): Int {
    var value = 0
    val path = file.path.lowercase()
    if (slotFree) value += 1_000
    value -= (file.sizeBytes / (1 shl 22)).toInt().coerceIn(0, 15)
    if (file.durationSeconds != null && file.durationSeconds in 60..600) value += 40
    val extension = fileExtension(file.path)
    when (extension) {
        "mp3" -> value += 30
        "flac" -> value += 70
        "ogg", "oga", "m4a", "aac", "opus" -> value += 10
    }
    file.bitrateKbps?.let { bitrate ->
        when {
            bitrate >= 256 -> value += 30
            bitrate >= 160 -> value += 15
            bitrate < 128 -> value -= 25
        }
    }
    terms.forEach { term ->
        if (path.contains(term)) value += 20
    }
    return value
}

const val FORMAT_ANY = "any"
const val FORMAT_MP3 = "mp3"
const val FORMAT_FLAC = "flac"
const val MIN_DURATION_SECONDS = 30
// Disk-spooled, so hi-res FLAC and long live sets fit; the cap only stops absurd entries.
const val MAX_STREAM_BYTES = 2L * 1_024 * 1_024 * 1_024
const val MAX_FILES_PER_RESPONSE = 250

/** Server and peer message codes used by this client. */
object Code {
    const val SERVER_LOGIN = 1
    const val SERVER_SET_WAIT_PORT = 2
    const val SERVER_GET_PEER_ADDRESS = 3
    const val SERVER_CONNECT_TO_PEER = 18
    const val SERVER_FILE_SEARCH = 26
    const val SERVER_PING = 32
    const val SERVER_CANT_CONNECT = 1001

    const val PEER_FILE_SEARCH_RESPONSE = 9
    const val PEER_TRANSFER_REQUEST = 40
    const val PEER_TRANSFER_RESPONSE = 41
    const val PEER_QUEUE_UPLOAD = 43
    const val PEER_PLACE_IN_QUEUE = 44
    const val PEER_UPLOAD_FAILED = 46
    const val PEER_UPLOAD_DENIED = 50

    const val INIT_PIERCE_FIREWALL = 0
    const val INIT_PEER_INIT = 1
}
