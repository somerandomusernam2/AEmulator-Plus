/* Added for AEmulator Aster (2026-11-07). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The adb wire protocol of one connection: the RSA handshake ([AdbAuth]) and the streams. It knows nothing of the
 * machine - [serve] runs a service ("shell:…", "sync:"…) on a stream - so it is tested against the real adb client.
 *
 * With [required] false there is no handshake at all, as before it existed: for adb clients older than 1.0.31
 * (2013), which do not know the AUTH packet and stay "offline" when they get one.
 */
internal class AdbConn(
    private val sock: Socket,
    private val auth: AdbAuth,
    private val required: () -> Boolean,
    /** asks whether the computer with this key (and address) may connect; blocks until there is an answer */
    private val ask: (key: AdbAuth.Key, from: String) -> AdbAuth.Answer,
    private val banner: () -> String,
    private val log: (String) -> Unit,
    private val serve: (AdbStream, String) -> Unit,
) {
    private val input = sock.getInputStream().buffered(65536)
    private val output = sock.getOutputStream()
    private var maxData = 4096
    private var nextId = 1
    private val streams = ConcurrentHashMap<Int, AdbStream>()
    /** the handshake is done: only then are streams opened */
    private var authorized = false
    /** the token the client has to sign (a new one after every wrong signature, as adbd does) */
    private var token: ByteArray? = null
    private var tokens = 0
    private var keyOffered = false
    private val peer: String = sock.inetAddress?.hostAddress ?: "?"

    fun run() {
        sock.tcpNoDelay = true
        while (true) {
            val h = ByteArray(24)
            if (!readFully(input, h)) break
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
            b.int                                               // checksum (0 from clients of version 0x01000001)
            val magic = b.int
            // not an adb client (or a broken stream): a length straight from the network is not allocated blindly
            if (magic != cmd.inv() || len !in 0..MAX_PAYLOAD) { log("adb: $peer: bad packet header, closing"); break }
            val data = ByteArray(len)
            if (len > 0 && !readFully(input, data)) break
            when (cmd) {
                CNXN -> {
                    maxData = a1.coerceIn(4096, MAX_PAYLOAD)
                    if (!authorized && !required()) authorized = true
                    if (authorized) connected() else sendToken()
                }
                AUTH -> if (!authorized && !auth(a0, data)) break
                // (nothing but the handshake before it is done, as in adbd)
                OPEN -> if (authorized) open(a0, String(data).trimEnd('\u0000'))
                OKAY -> if (authorized) streams[a1]?.acked(a0)
                WRTE -> if (authorized) streams[a1]?.let { it.feed(data); send(OKAY, it.local, it.remote) }
                CLSE -> if (authorized) streams.remove(a1)?.closedByPeer()
            }
        }
        streams.values.forEach { it.closedByPeer() }
    }

    private fun connected() = send(CNXN, VERSION, maxData, banner().toByteArray())

    private fun sendToken() {
        val t = auth.token()
        token = t
        send(AUTH, AUTH_TOKEN, 0, t)
    }

    /** one AUTH packet of the client; false ends the connection */
    private fun auth(type: Int, data: ByteArray): Boolean {
        when (type) {
            AUTH_SIGNATURE -> {
                val t = token ?: return false
                token = null
                if (auth.verify(t, data)) {
                    authorized = true
                    log("adb: $peer connected")
                    connected()
                    return true
                }
                // the client tries each of its keys in turn, then offers its public key
                if (++tokens > 16) { log("adb: $peer: too many wrong signatures"); return false }
                sendToken()
                return true
            }
            AUTH_RSAPUBLICKEY -> {
                if (keyOffered) return false
                keyOffered = true
                val key = AdbAuth.parse(String(data, Charsets.ISO_8859_1))
                if (key == null) { log("adb: $peer: unreadable public key"); return false }
                log("adb: $peer asks to connect, key ${key.fingerprint}")
                val answer = runCatching { ask(key, if (key.comment.isEmpty()) peer else "${key.comment} ($peer)") }.getOrDefault(AdbAuth.Answer.DENY)
                if (answer == AdbAuth.Answer.DENY || sock.isClosed) { log("adb: $peer refused"); return false }
                auth.trust(key, answer == AdbAuth.Answer.ALWAYS)
                authorized = true
                log("adb: $peer allowed" + if (answer == AdbAuth.Answer.ALWAYS) " (remembered)" else "")
                connected()
                return true
            }
            else -> return false
        }
    }

    @Synchronized fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray = EMPTY, off: Int = 0, len: Int = data.size) {
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0
        for (i in off until off + len) sum += data[i].toInt() and 0xff
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum).putInt(cmd.inv())
        output.write(h.array()); if (len > 0) output.write(data, off, len); output.flush()
    }

    private fun open(remote: Int, service: String) {
        val s = AdbStream(this, nextId++, remote)
        streams[s.local] = s
        send(OKAY, s.local, remote)
        thread(name = "adb-$service".take(40), isDaemon = true) {
            runCatching { serve(s, service) }.onFailure { log("adb: $service: ${it.message}") }
            s.close()
        }
    }

    fun maxData() = maxData
    fun drop(s: AdbStream) { streams.remove(s.local) }

    companion object {
        const val CNXN = 0x4e584e43
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val WRTE = 0x45545257
        const val CLSE = 0x45534c43
        const val AUTH = 0x48545541
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3
        const val VERSION = 0x01000000
        /** adbd's own limit on a packet's payload */
        const val MAX_PAYLOAD = 1024 * 1024
        val EMPTY = ByteArray(0)
    }
}

/** One adb stream: incoming WRTE payloads as an InputStream, outgoing writes paced by the peer's OKAYs. */
internal class AdbStream(private val c: AdbConn, val local: Int, @Volatile var remote: Int) {
    private val q = LinkedBlockingQueue<ByteArray>()
    private val window = Semaphore(1)
    @Volatile var closed = false

    fun feed(d: ByteArray) { if (d.isNotEmpty()) q.put(d) }
    fun acked(r: Int) { remote = r; window.release() }
    fun closedByPeer() { closed = true; q.put(AdbConn.EMPTY); window.release(100) }

    val inp = object : InputStream() {
        private var cur: ByteArray = AdbConn.EMPTY
        private var pos = 0
        private fun fill(): Boolean {
            while (pos >= cur.size) {
                if (closed && q.isEmpty()) return false
                val n = q.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (n.isEmpty()) return false
                cur = n; pos = 0
            }
            return true
        }
        override fun read(): Int = if (fill()) cur[pos++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, cur.size - pos)
            System.arraycopy(cur, pos, b, off, n); pos += n
            return n
        }
    }

    val out = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            while (o < off + len) {
                if (closed) throw java.io.IOException("stream closed")
                window.acquire()
                if (closed) throw java.io.IOException("stream closed")
                val n = minOf(c.maxData(), off + len - o)
                c.send(AdbConn.WRTE, local, remote, b, o, n)
                o += n
            }
        }
    }

    fun close() {
        if (!closed) { closed = true; runCatching { c.send(AdbConn.CLSE, local, remote) } }
        c.drop(this)
    }
}

internal fun readFully(i: InputStream, b: ByteArray): Boolean {
    var o = 0
    while (o < b.size) { val n = i.read(b, o, b.size - o); if (n < 0) return false; o += n }
    return true
}
