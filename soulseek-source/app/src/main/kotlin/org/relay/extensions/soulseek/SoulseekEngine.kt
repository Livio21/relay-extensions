package org.relay.extensions.soulseek

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Login outcome surfaced to Relay as a user-readable source failure. */
class SoulseekLoginException(message: String) : IOException(message)

data class EngineConfig(val username: String, val password: String)

/**
 * Process-scoped Soulseek client shared by every source instance so a login survives host-side
 * re-instantiation. All threads are daemon and bounded; every socket read carries a timeout.
 */
class SoulseekEngine(private val config: EngineConfig) {
    private val stateLock = Any()
    private var serverSocket: Socket? = null
    private var serverInput: InputStream? = null
    private var serverOutput: OutputStream? = null
    private val connected = AtomicBoolean(false)
    private var listenerPort = 0
    private var listener: ServerSocket? = null
    private val loopbackServer = LoopbackStreamServer()
    private var closed = false

    private val sendLock = Any()
    private val lastSentAt = java.util.concurrent.atomic.AtomicLong(0)

    private val peerAddressWaits = ConcurrentHashMap<String, AddressWait>()
    private val searches = ConcurrentHashMap<Int, LinkedBlockingQueue<SoulseekResponse>>()
    private val transfers = ConcurrentHashMap<String, DownloadTransfer>()
    private val transfersByToken = ConcurrentHashMap<Int, DownloadTransfer>()
    private val transferConnectionWaits = ConcurrentHashMap<Int, LinkedBlockingQueue<Socket>>()
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val pierceExpectations = ConcurrentHashMap<Int, String>()

    private val peerSlots = Semaphore(MAX_PEER_CONNECTIONS)
    private val transferSlots = Semaphore(MAX_TRANSFERS)
    private val transferCounter = AtomicInteger(0)
    private val threadCounter = AtomicInteger(0)

    private class AddressWait(val latch: CountDownLatch) {
        @Volatile var ip: String = ""
        @Volatile var port: Int = 0
    }

    /** Ensures a live server connection, logging in with a fresh one when needed. */
    fun ensureConnected(deadlineMs: Long) {
        if (connected.get()) return
        synchronized(stateLock) {
            if (closed) throw IOException("The Soulseek source is shutting down.")
            if (connected.get()) return
            val budgetMs = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(1_000)
            connect(budgetMs)
        }
    }

    fun isConnected(): Boolean = connected.get()

    fun isShutdown(): Boolean = synchronized(stateLock) { closed }

    fun reset() {
        synchronized(stateLock) {
            closed = true
            closeAllLocked()
        }
    }

    private fun connect(budgetMs: Long) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(SERVER_HOST, SERVER_PORT), 5_000)
            socket.soTimeout = SERVER_READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            serverSocket = socket
            serverInput = input
            serverOutput = output
            sendServer(
                SoulseekMessageBuilder(Code.SERVER_LOGIN)
                    .string(config.username)
                    .string(config.password)
                    .u32(LOGIN_MAJOR_VERSION)
                    .string(md5Hex(config.username + config.password))
                    .u32(LOGIN_MINOR_VERSION)
                    .toFrame(),
            )
            val login = readFrame(input, 4)
            require(login.code == Code.SERVER_LOGIN) { "Soulseek sent an unexpected message before login." }
            val reader = SoulseekReader(login.payload)
            if (!reader.readBool()) {
                val reason = reader.readString()
                val detail = if (reason == "INVALIDUSERNAME" && reader.remaining > 0) reader.readString() else ""
                throw SoulseekLoginException(loginFailureMessage(reason, detail))
            }
            startListener()
            loopbackServer.start()
            sendServer(SoulseekMessageBuilder(Code.SERVER_SET_WAIT_PORT).u32(listenerPort).toFrame())
            connected.set(true)
            lastSentAt.set(System.currentTimeMillis())
            Thread(
                { runServerLoop(input) },
                "relay-slsk-server-${threadCounter.incrementAndGet()}",
            ).apply { isDaemon = true }.start()
        } catch (error: Throwable) {
            closeAllLocked()
            if (error is SoulseekLoginException) throw error
            throw IOException("Could not reach the Soulseek server.", error)
        }
    }

    private fun runServerLoop(input: InputStream) {
        while (connected.get()) {
            val frame = try {
                readFrame(input, 4)
            } catch (error: SocketTimeoutException) {
                if (System.currentTimeMillis() - lastSentAt.get() > PING_INTERVAL_MS) {
                    runCatching { sendServer(SoulseekMessageBuilder(Code.SERVER_PING).toFrame()) }
                }
                continue
            } catch (error: Throwable) {
                onDisconnected()
                return
            }
            try {
                dispatchServerMessage(frame)
            } catch (error: Throwable) {
                Unit
            }
        }
    }

    private fun dispatchServerMessage(frame: SoulseekFrame) {
        when (frame.code) {
            Code.SERVER_GET_PEER_ADDRESS -> {
                val reader = SoulseekReader(frame.payload)
                val username = reader.readString()
                peerAddressWaits.remove(username)?.let { wait ->
                    wait.ip = reader.readIp()
                    wait.port = reader.readU32()
                    wait.latch.countDown()
                }
            }
            Code.SERVER_CONNECT_TO_PEER -> handleConnectToPeer(frame.payload)
            else -> Unit
        }
    }

    private fun handleConnectToPeer(payload: ByteArray) {
        val reader = SoulseekReader(payload)
        val username = reader.readString()
        val type = reader.readString().firstOrNull() ?: return
        val ip = reader.readIp()
        val port = reader.readU32()
        val token = reader.readU32()
        when (type) {
            'P' -> spawnPeerThread {
                dialAndPierce(ip, port, token)?.let { socket ->
                    val connection = PeerConnection(username, socket)
                    registerPeer(username, connection)
                    spawnReader(connection)
                }
            }
            'F' -> transferConnectionWaits.remove(token)?.let { queue ->
                spawnPeerThread {
                    val socket = runCatching {
                        dialAndPierce(ip, port, token)?.also { pierced ->
                            val remoteToken = readU32LE(readFully(pierced.getInputStream(), 4), 0)
                            require(remoteToken == token) { "File connection token mismatch." }
                        }
                    }.getOrNull()
                    queue.offer(socket ?: BROKEN_SOCKET, 10, TimeUnit.SECONDS)
                }
            }
        }
    }

    /** Runs one search within the host's bounded budget and returns the ranked raw results. */
    fun search(query: String, budgetMs: Long, format: String): List<SoulseekFile> {
        val deadline = System.currentTimeMillis() + budgetMs
        ensureConnected(deadline)
        val token = newToken()
        val queue = LinkedBlockingQueue<SoulseekResponse>()
        searches[token] = queue
        try {
            sendServer(
                SoulseekMessageBuilder(Code.SERVER_FILE_SEARCH)
                    .u32(token)
                    .string(query)
                    .toFrame(),
            )
            val responses = ArrayList<SoulseekResponse>()
            while (System.currentTimeMillis() < deadline) {
                val response = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                responses.add(response)
            }
            val slotFree = HashMap<String, Boolean>()
            val files = ArrayList<SoulseekFile>()
            for (response in responses) {
                slotFree[response.username] = slotFree[response.username] ?: (response.slotFree || response.queueLength == 0)
                files.addAll(response.files.take(MAX_FILES_TOTAL - files.size))
                if (files.size >= MAX_FILES_TOTAL) break
            }
            return rankSoulseekFiles(files, slotFree, query, format)
        } finally {
            searches.remove(token)
        }
    }

    /** Starts (or reuses) the peer transfer and returns its loopback playback URL immediately. */
    fun ensureTransfer(reference: SoulseekTrackRef, budgetMs: Long): String {
        ensureConnected(System.currentTimeMillis() + budgetMs)
        val key = transferKey(reference.username, reference.path)
        transfers[key]?.takeIf { it.state() != DownloadTransfer.State.FAILED }?.let { existing ->
            return loopbackServer.urlFor(existing.token)
        }
        transfers[key]?.let { evict(it) }
        evictFinished()
        if (!transferSlots.tryAcquire()) throw IOException("Too many Soulseek transfers are active.")
        val transfer = DownloadTransfer(reference.username, reference.path, transferCounter.incrementAndGet())
        transfers[key] = transfer
        transfersByToken[transfer.token] = transfer
        loopbackServer.register(transfer.token, transfer)
        Thread(
            { runTransfer(transfer) },
            "relay-slsk-xfer-${threadCounter.incrementAndGet()}",
        ).apply { isDaemon = true }.start()
        return loopbackServer.urlFor(transfer.token)
    }

    fun transfer(token: Int): DownloadTransfer? = transfersByToken[token]

    private fun runTransfer(transfer: DownloadTransfer) {
        try {
            val address = resolvePeerAddress(transfer.username)
            val peer = establishPeerConnection(transfer.username, address)
            transfer.peerConnection = peer
            peer.send(
                SoulseekMessageBuilder(Code.PEER_QUEUE_UPLOAD)
                    .string(transfer.path)
                    .toFrame(),
            )
            val requested = transfer.awaitTransferRequest(TRANSFER_QUEUE_TIMEOUT_MS) ?: run {
                transfer.fail("The peer has not offered this file yet. Try again shortly.")
                return
            }
            peer.send(
                SoulseekMessageBuilder(Code.PEER_TRANSFER_RESPONSE)
                    .u32(requested.first)
                    .bool(true)
                    .u64(requested.second)
                    .toFrame(),
            )
            val socket = awaitTransferConnection(transfer.username, requested.first)
            transfer.start(requested.first, requested.second)
            transfer.beginStreaming()
            streamFile(socket, transfer)
            if (transfer.sizeBytes in 1..transfer.received) {
                transfer.complete()
            } else {
                transfer.fail("The peer closed the connection before the file finished.")
            }
        } catch (error: Throwable) {
            transfer.fail(error.message ?: "The transfer failed.")
        } finally {
            runCatching { transfer.peerConnection?.close() }
        }
    }

    private fun resolvePeerAddress(username: String): InetSocketAddress {
        val wait = AddressWait(CountDownLatch(1))
        val existing = peerAddressWaits.putIfAbsent(username, wait)
        val active = if (existing != null) existing else wait
        try {
            sendServer(
                SoulseekMessageBuilder(Code.SERVER_GET_PEER_ADDRESS)
                    .string(username)
                    .toFrame(),
            )
            if (!active.latch.await(5, TimeUnit.SECONDS)) {
                throw IOException("The peer's address is unavailable.")
            }
        } finally {
            peerAddressWaits.remove(username, active)
        }
        if (active.port <= 0) throw IOException("The peer is offline.")
        return InetSocketAddress(active.ip, active.port)
    }

    private fun establishPeerConnection(username: String, address: InetSocketAddress): PeerConnection {
        val direct = runCatching { dialAndInit(address, username, 'P', 0) }.getOrNull()
        if (direct != null) {
            val connection = PeerConnection(username, direct)
            registerPeer(username, connection)
            spawnReader(connection)
            return connection
        }
        val token = newToken()
        pierceExpectations[token] = username
        try {
            sendServer(
                SoulseekMessageBuilder(Code.SERVER_CONNECT_TO_PEER)
                    .u32(token)
                    .string(username)
                    .string("P")
                    .toFrame(),
            )
            val deadline = System.currentTimeMillis() + INDIRECT_DIAL_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                peerConnections[username]?.let { return it }
                Thread.sleep(100)
            }
            throw IOException("The peer is unreachable.")
        } finally {
            pierceExpectations.remove(token)
        }
    }

    private fun awaitTransferConnection(username: String, remoteToken: Int): Socket {
        val queue = LinkedBlockingQueue<Socket>(1)
        transferConnectionWaits[remoteToken] = queue
        try {
            var socket = queue.poll(TRANSFER_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (socket == null) {
                val address = resolvePeerAddress(username)
                socket = runCatching {
                    dialAndInit(address, username, 'F', remoteToken).also { pierced ->
                        val tokenBytes = ByteArray(4)
                        writeU32LE(tokenBytes, 0, remoteToken)
                        pierced.getOutputStream().let { output ->
                            output.write(tokenBytes)
                            output.flush()
                        }
                    }
                }.getOrNull()
                if (socket == null) throw IOException("The file connection could not be established.")
            }
            if (socket === BROKEN_SOCKET) throw IOException("The file connection could not be established.")
            return socket
        } finally {
            transferConnectionWaits.remove(remoteToken)
        }
    }

    private fun streamFile(socket: Socket, transfer: DownloadTransfer) {
        socket.soTimeout = TRANSFER_STALL_TIMEOUT_MS
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        val buffer = ByteArray(16_384)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (error: SocketTimeoutException) {
                throw IOException("The peer stalled during the transfer.")
            }
            if (read < 0) break
            transfer.append(buffer, read)
            if (transfer.sizeBytes in 1..transfer.received) break
        }
    }

    private fun dialAndPierce(ip: String, port: Int, token: Int): Socket? {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(ip, port), 6_000)
            socket.soTimeout = PEER_READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            socket.getOutputStream().let { output ->
                output.write(SoulseekInitBuilder(Code.INIT_PIERCE_FIREWALL).u32(token).toFrame())
                output.flush()
            }
            socket
        } catch (error: Throwable) {
            runCatching { socket.close() }
            null
        }
    }

    private fun dialAndInit(address: InetSocketAddress, username: String, type: Char, token: Int): Socket {
        val socket = Socket()
        socket.connect(address, 6_000)
        socket.soTimeout = PEER_READ_TIMEOUT_MS
        socket.tcpNoDelay = true
        val output = socket.getOutputStream()
        output.write(
            SoulseekInitBuilder(Code.INIT_PEER_INIT)
                .string(config.username)
                .string(type.toString())
                .u32(token)
                .toFrame(),
        )
        output.flush()
        return socket
    }

    private fun startListener() {
        var bound: ServerSocket? = null
        for (candidate in intArrayOf(DEFAULT_LISTEN_PORT) + IntArray(8) { 0 }) {
            runCatching {
                val socket = ServerSocket()
                socket.bind(InetSocketAddress("0.0.0.0", candidate))
                bound = socket
            }
            if (bound != null) break
        }
        val socket = bound ?: throw IOException("No listen port is available.")
        listenerPort = socket.localPort
        listener = socket
        Thread({ runListenerLoop(socket) }, "relay-slsk-listen").apply { isDaemon = true }.start()
    }

    private fun runListenerLoop(socket: ServerSocket) {
        while (connected.get()) {
            val client = try {
                socket.accept()
            } catch (error: Throwable) {
                return
            }
            if (!peerSlots.tryAcquire()) {
                runCatching { client.close() }
                continue
            }
            spawnPeerThread {
                try {
                    acceptPeerConnection(client)
                } finally {
                    peerSlots.release()
                }
            }
        }
    }

    private fun acceptPeerConnection(socket: Socket) {
        try {
            socket.soTimeout = PEER_READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val init = readFrame(input, 1)
            when (init.code) {
                Code.INIT_PEER_INIT -> {
                    val reader = SoulseekReader(init.payload)
                    val username = reader.readString()
                    when (reader.readString().firstOrNull()) {
                        'P' -> runPeerLoopWithInput(socket, username, input)
                        'F' -> {
                            val token = reader.readU32()
                            transferConnectionWaits.remove(token)?.let { queue ->
                                queue.offer(socket, 10, TimeUnit.SECONDS)
                            } ?: runPeerLoopWithInput(socket, username, input)
                        }
                    }
                }
                Code.INIT_PIERCE_FIREWALL -> {
                    val token = readU32LE(init.payload, 0)
                    val username = pierceExpectations.remove(token)
                    if (username == null) {
                        runCatching { socket.close() }
                    } else {
                        val connection = PeerConnection(username, socket)
                        registerPeer(username, connection)
                        spawnReader(connection)
                    }
                }
            }
        } catch (error: Throwable) {
            runCatching { socket.close() }
        }
    }

    private fun handlePeerMessage(username: String, frame: SoulseekFrame, peer: PeerConnection?) {
        when (frame.code) {
            Code.PEER_FILE_SEARCH_RESPONSE -> {
                val payload = inflateIfCompressed(frame.payload)
                val response = parseFileSearchResponse(username, payload) ?: return
                searches[response.token]?.offer(response)
                peer?.send(fileSearchResponseAck(response.username, response.token))
            }
            Code.PEER_TRANSFER_REQUEST -> {
                val reader = SoulseekReader(frame.payload)
                val direction = reader.readU32()
                val token = reader.readU32()
                val filename = reader.readString()
                val size = if (direction == 1) reader.readU64() else -1L
                if (direction != 1) return
                findTransfer(username, filename)?.onTransferRequest(token, size)
            }
            Code.PEER_PLACE_IN_QUEUE -> {
                val reader = SoulseekReader(frame.payload)
                val filename = reader.readString()
                findTransfer(username, filename)?.updateQueuePlace(reader.readU32())
            }
            Code.PEER_UPLOAD_FAILED -> {
                val reader = SoulseekReader(frame.payload)
                findTransfer(username, reader.readString())
                    ?.fail("The peer failed to upload this file.")
            }
            Code.PEER_UPLOAD_DENIED -> {
                val reader = SoulseekReader(frame.payload)
                val filename = reader.readString()
                val reason = reader.readString()
                findTransfer(username, filename)?.fail(uploadDeniedMessage(reason))
            }
        }
    }

    private fun findTransfer(username: String, filename: String): DownloadTransfer? {
        transfers[transferKey(username, filename)]?.let { return it }
        return transfersByToken.values.firstOrNull { transfer ->
            (transfer.username == username && transfer.path.endsWith(filename)) || filename.endsWith(transfer.path)
        }
    }

    private fun runPeerLoopWithInput(socket: Socket, username: String, input: InputStream) {
        try {
            while (connected.get()) {
                val frame = readFrame(input, 4)
                handlePeerMessage(username, frame, peerConnections[username])
            }
        } catch (error: Throwable) {
            Unit
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun registerPeer(username: String, connection: PeerConnection) {
        peerConnections[username]?.close()
        peerConnections[username] = connection
    }

    private fun spawnReader(connection: PeerConnection) {
        spawnPeerThread {
            try {
                while (connected.get() && !connection.isClosed()) {
                    val frame = readFrame(connection.input, 4)
                    handlePeerMessage(connection.username, frame, connection)
                }
            } catch (error: Throwable) {
                Unit
            } finally {
                connection.close()
                if (peerConnections[connection.username] === connection) {
                    peerConnections.remove(connection.username)
                }
            }
        }
    }

    private fun spawnPeerThread(block: () -> Unit) {
        Thread(block, "relay-slsk-peer-${threadCounter.incrementAndGet()}").apply { isDaemon = true }.start()
    }

    fun sendServer(frame: ByteArray) {
        synchronized(sendLock) {
            val output = serverOutput ?: throw IOException("The Soulseek server is not connected.")
            output.write(frame)
            output.flush()
            lastSentAt.set(System.currentTimeMillis())
        }
    }

    private fun newToken(): Int {
        var token: Int
        do {
            token = (Math.random() * Int.MAX_VALUE).toInt()
        } while (token <= 0)
        return token
    }

    private fun onDisconnected() {
        synchronized(stateLock) {
            closeAllLocked()
        }
        transfersByToken.values.forEach { transfer ->
            if (!transfer.isFinished()) transfer.fail("The Soulseek connection closed.")
        }
    }

    private fun closeAllLocked() {
        connected.set(false)
        runCatching { serverSocket?.close() }
        runCatching { listener?.close() }
        runCatching { loopbackServer.stop() }
        serverSocket = null
        serverInput = null
        serverOutput = null
        listener = null
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        pierceExpectations.clear()
        peerAddressWaits.clear()
        transfersByToken.values.forEach { transfer -> transfer.fail("The Soulseek connection closed.") }
    }

    private fun evict(transfer: DownloadTransfer) {
        transfers.remove(transferKey(transfer.username, transfer.path))
        transfersByToken.remove(transfer.token)
        loopbackServer.unregister(transfer.token)
        transfer.fail("This track was replaced by a newer request.")
        transfer.release()
        transferSlots.release()
    }

    private fun evictFinished() {
        transfersByToken.values
            .filter { it.isFinished() && System.currentTimeMillis() - it.lastAccessMs() > EVICT_AFTER_MS }
            .forEach { evict(it) }
    }

    val loopbackPort: Int get() = loopbackServer.port

    private companion object {
        const val SERVER_HOST = "server.slsknet.org"
        const val SERVER_PORT = 2242
        const val DEFAULT_LISTEN_PORT = 2234
        const val LOGIN_MAJOR_VERSION = 177
        const val LOGIN_MINOR_VERSION = 1
        const val SERVER_READ_TIMEOUT_MS = 65_000
        const val PEER_READ_TIMEOUT_MS = 300_000
        const val PING_INTERVAL_MS = 55_000
        const val TRANSFER_QUEUE_TIMEOUT_MS = 240_000L
        const val TRANSFER_CONNECT_TIMEOUT_MS = 20_000L
        const val TRANSFER_STALL_TIMEOUT_MS = 120_000
        const val INDIRECT_DIAL_TIMEOUT_MS = 25_000L
        const val EVICT_AFTER_MS = 60_000L
        const val MAX_PEER_CONNECTIONS = 8
        const val MAX_TRANSFERS = 3
        const val MAX_FILES_TOTAL = 600

        val BROKEN_SOCKET = Socket()
    }
}

/** One P connection to a peer with serialized writes. */
class PeerConnection internal constructor(val username: String, private val socket: Socket) {
    val input: InputStream = BufferedInputStream(socket.getInputStream())
    private val output: OutputStream = BufferedOutputStream(socket.getOutputStream())
    private val writeLock = Any()
    private val closed = AtomicBoolean(false)

    fun send(frame: ByteArray) {
        synchronized(writeLock) {
            if (closed.get()) throw IOException("The peer connection is closed.")
            output.write(frame)
            output.flush()
        }
    }

    fun isClosed(): Boolean = closed.get()

    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
        }
    }
}

/** Growable in-memory partial file that HTTP range requests read while bytes arrive. */
class TransferBuffer {
    private val lock = Object()
    private var data = ByteArray(INITIAL_CAPACITY)
    private var size = 0L

    fun append(source: ByteArray, count: Int) {
        synchronized(lock) {
            if (size + count > MAX_STREAM_BYTES) throw EOFException("The file exceeds the streaming limit.")
            if (size + count > data.size) {
                var capacity = data.size
                while (capacity < size + count) capacity = capacity shl 1
                data = data.copyOf(capacity)
            }
            System.arraycopy(source, 0, data, size.toInt(), count)
            size += count
            lock.notifyAll()
        }
    }

    /** Blocks until at least [offset] bytes exist, the deadline passes, or the transfer fails. */
    fun waitFor(offset: Long, failed: () -> Boolean, deadlineMs: Long): Boolean {
        synchronized(lock) {
            while (size < offset) {
                if (failed()) return false
                val remaining = deadlineMs - System.currentTimeMillis()
                if (remaining <= 0) return size >= offset
                lock.wait(remaining.coerceAtMost(1_000))
            }
            return true
        }
    }

    fun wake() {
        synchronized(lock) { lock.notifyAll() }
    }

    fun size(): Long = synchronized(lock) { size }

    fun clear() {
        synchronized(lock) {
            data = ByteArray(INITIAL_CAPACITY)
            size = 0
        }
    }

    /** Copies at most [count] bytes from [offset]; callers reserve via waitFor first. */
    fun readInto(destination: ByteArray, offset: Long, count: Int): Int = synchronized(lock) {
        val available = (size - offset).toInt().coerceAtLeast(0)
        val toCopy = count.coerceAtMost(available)
        System.arraycopy(data, offset.toInt(), destination, 0, toCopy)
        toCopy
    }

    private companion object {
        const val INITIAL_CAPACITY = 1 shl 20
        const val MAX_STREAM_BYTES = 64L * 1_024 * 1_024
    }
}

/** Lifecycle for one file transfer; the loopback HTTP server renders it as a stream. */
class DownloadTransfer internal constructor(val username: String, val path: String, val token: Int) {
    enum class State { QUEUED, CONNECTING, STREAMING, DONE, FAILED }

    private val lock = Object()
    private var state = State.QUEUED
    private var failReason: String? = null
    private val requestLatch = CountDownLatch(1)
    private var remoteToken = -1
    private var queuePosition: String? = null

    val buffer = TransferBuffer()

    @Volatile var sizeBytes = -1L
    @Volatile var received = 0L
    @Volatile internal var peerConnection: PeerConnection? = null
    @Volatile private var lastAccessMs = System.currentTimeMillis()

    fun state(): State = synchronized(lock) { state }
    fun failReason(): String? = synchronized(lock) { failReason }
    fun lastAccessMs(): Long = lastAccessMs

    fun isFinished(): Boolean = state().let { it == State.DONE || it == State.FAILED }

    fun onTransferRequest(remoteToken: Int, sizeBytes: Long) {
        synchronized(lock) {
            if (state == State.DONE || state == State.FAILED) return
            this.remoteToken = remoteToken
            this.sizeBytes = sizeBytes
            state = State.CONNECTING
        }
        requestLatch.countDown()
    }

    fun awaitTransferRequest(timeoutMs: Long): Pair<Int, Long>? {
        if (!requestLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        synchronized(lock) {
            if (state == State.FAILED) return null
            return remoteToken to sizeBytes
        }
    }

    fun start(remoteToken: Int, sizeBytes: Long) {
        synchronized(lock) {
            this.remoteToken = remoteToken
            this.sizeBytes = sizeBytes
            state = State.CONNECTING
        }
    }

    fun updateQueuePlace(place: Int) {
        synchronized(lock) {
            queuePosition = if (place > 0) "Queued at position $place" else null
        }
    }

    fun beginStreaming() {
        synchronized(lock) {
            if (state == State.FAILED) return
            state = State.STREAMING
        }
    }

    fun append(source: ByteArray, count: Int) {
        buffer.append(source, count)
        received += count
        lastAccessMs = System.currentTimeMillis()
    }

    fun complete() {
        synchronized(lock) {
            state = State.DONE
        }
    }

    fun fail(message: String) {
        synchronized(lock) {
            if (state == State.DONE || state == State.FAILED) return
            failReason = message
            state = State.FAILED
        }
        buffer.wake()
    }

    fun release() {
        buffer.clear()
    }

    /** Blocks until the buffer holds [offset] bytes or the transfer fails; bounded by [deadlineMs]. */
    fun awaitBytes(offset: Long, deadlineMs: Long): Boolean =
        buffer.waitFor(offset, { isFinished() && failReason() != null }, deadlineMs)
}

private fun transferKey(username: String, path: String): String = "$username\u0000$path"

private fun loginFailureMessage(reason: String, detail: String): String = when (reason) {
    "INVALIDPASS" -> "The Soulseek password is incorrect. Check the extension settings."
    "INVALIDUSERNAME" -> when (detail) {
        "Nick too long." -> "The Soulseek username is too long."
        "Invalid characters in nick." -> "The Soulseek username has invalid characters."
        "Nick empty." -> "Enter a Soulseek username in the extension settings."
        else -> "The Soulseek username is invalid. Check the extension settings."
    }
    "EMPTYPASSWORD" -> "Enter a Soulseek password in the extension settings."
    "INVALIDVERSION" -> "The Soulseek server rejected this client version."
    "SVRFULL" -> "The Soulseek server is full. Try again soon."
    "SVRPRIVATE" -> "The Soulseek server is not accepting new connections."
    else -> "The Soulseek server rejected the login ($reason)."
}

private fun uploadDeniedMessage(reason: String): String = when (reason.trim().lowercase()) {
    "banned", "file not shared." -> "The peer no longer shares this file."
    "too many files", "too many megabytes" -> "The peer's download limit is exhausted."
    else -> "The peer refused this file ($reason)."
}
