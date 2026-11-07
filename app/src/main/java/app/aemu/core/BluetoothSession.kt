package app.aemu.core

import java.util.UUID
import app.aemu.core.BluetoothProtocol as P

data class BtDevice(val address: String, val name: String, val kind: Int, val deviceClass: Int = 0)

data class BtGattAttr(val id: Int, val kind: Int, val uuid: UUID, val parent: Int, val props: Int)

interface BtScanListener {
    fun onResult(device: BtDevice, rssi: Int, record: ByteArray)
    fun onDone()
}

interface BtSppListener {
    fun onData(data: ByteArray)
    fun onClosed(reason: Int)
}

interface BtSppLink {
    /** Queue [data] for sending; returns [P.OK] or a negative errno (for example [P.EAGAIN] when the queue is full). */
    fun write(data: ByteArray): Int
    fun close()
}

/** A listening RFCOMM server socket on the phone. */
interface BtListenLink {
    fun close()
}

/** One incoming RFCOMM connection, not yet read from: [link] first, register it, then [begin] starts the data flow. */
interface BtAcceptedSpp {
    fun link(listener: BtSppListener): BtSppLink
    fun begin()
    fun reject()
}

interface BtAcceptListener {
    /** Called on the accept thread for every incoming connection; the session takes it or calls [BtAcceptedSpp.reject]. */
    fun onAccepted(address: String, conn: BtAcceptedSpp)
}

interface BtGattListener {
    fun onState(connected: Boolean, status: Int)
    fun onServices(status: Int, attrs: List<BtGattAttr>)
    fun onRead(attr: Int, status: Int, value: ByteArray)
    fun onWrite(attr: Int, status: Int)
    fun onChanged(attr: Int, value: ByteArray)
    fun onMtu(mtu: Int, status: Int)
}

interface BtGattLink {
    fun discover(): Int
    fun read(attr: Int): Int
    fun write(attr: Int, writeType: Int, value: ByteArray): Int
    fun setNotify(attr: Int, enable: Boolean): Int
    fun requestMtu(mtu: Int): Int
    fun close()
}

/** What the session needs from the host; the Android implementation lives in BluetoothBridge.kt. */
interface BtBackend {
    val classicSupported: Boolean
    val leSupported: Boolean
    fun enabled(): Boolean
    fun adapterName(): String
    fun adapterAddress(): String
    fun bonded(): List<BtDevice>
    fun startScan(classic: Boolean, le: Boolean, listener: BtScanListener): Int
    fun stopScan()
    /** Connect on a worker thread and call [done] once with the status and the link (null on failure). */
    fun openSpp(address: String, uuid: UUID, secure: Boolean, listener: BtSppListener, done: (Int, BtSppLink?) -> Unit)
    fun openGatt(address: String, listener: BtGattListener): BtGattLink?
    /**
     * Ask the phone to pair with [address]; it shows its own pairing dialog and progress comes back through
     * [BluetoothSession.bondState]. Returns [P.OK] when pairing started.
     */
    fun createBond(address: String): Int = P.ENODEV
    /** Start listening for RFCOMM connections to [uuid] (the SDP service record is published as [name]). Null on failure. */
    fun listenSpp(name: String, uuid: UUID, secure: Boolean, listener: BtAcceptListener): BtListenLink? = null
}

/**
 * One guest connection. Validates every request, owns the channel/connection tables and the limits,
 * and drops everything the guest opened when [close] runs. Thread-safe: requests arrive on the socket
 * reader thread while backend callbacks arrive on Bluetooth threads.
 */
class BluetoothSession(
    private val backend: BtBackend,
    private val send: (P.Frame) -> Unit,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MAX_SPP = 4
        const val MAX_GATT = 7
        const val MAX_LISTEN = 2
        const val SCAN_REPEAT_MS = 1_000L
        const val MAX_SCAN_TRACKED = 512
    }

    private val lock = Any()
    private val spp = HashMap<Int, BtSppLink>()
    private val gatt = HashMap<Int, BtGattLink>()
    private val listeners = HashMap<Int, BtListenLink>()
    private var sppPending = 0
    private var nextId = 1
    private var greeted = false
    private var scanning = false
    private val lastSeen = HashMap<String, Long>()
    @Volatile private var closed = false

    fun handle(frame: P.Frame) {
        if (closed) return
        try {
            dispatch(frame)
        } catch (e: P.FormatException) {
            reply(frame.id, P.EINVAL)
        } catch (e: SecurityException) {
            reply(frame.id, P.EPERM)
        } catch (e: Exception) {
            log("bluetooth: request 0x${frame.op.toString(16)} failed: $e")
            reply(frame.id, P.EIO)
        }
    }

    fun close() {
        val sppLinks: List<BtSppLink>
        val gattLinks: List<BtGattLink>
        val listenLinks: List<BtListenLink>
        synchronized(lock) {
            if (closed) return
            closed = true
            sppLinks = spp.values.toList(); spp.clear()
            gattLinks = gatt.values.toList(); gatt.clear()
            listenLinks = listeners.values.toList(); listeners.clear()
            sppPending = 0
            lastSeen.clear()
        }
        runCatching { backend.stopScan() }
        sppLinks.forEach { runCatching { it.close() } }
        gattLinks.forEach { runCatching { it.close() } }
        listenLinks.forEach { runCatching { it.close() } }
    }

    /** Host-side bond change for [address] (10 none, 11 bonding, 12 bonded), forwarded by the bridge. */
    fun bondState(address: String, state: Int, previous: Int) {
        if (greeted) event(P.EV_BOND_STATE, BtWriter().string(address.uppercase()).u8(state).u8(previous).build())
    }

    /** Host-side adapter on/off change, forwarded by the bridge. */
    fun adapterState(enabled: Boolean) {
        if (greeted) event(P.EV_ADAPTER_STATE, BtWriter().u8(if (enabled) 1 else 0).build())
    }

    private fun dispatch(f: P.Frame) {
        if (f.op != P.OP_HELLO && !greeted) return reply(f.id, P.EPROTO)
        val r = BtReader(f.payload)
        when (f.op) {
            P.OP_HELLO -> {
                if (String(r.bytes(4), Charsets.US_ASCII) != "BTP1" || r.u8() != P.VERSION) return reply(f.id, P.EPROTO)
                greeted = true
                reply(f.id, P.OK, BtWriter()
                    .u32(P.capabilities(backend.classicSupported, backend.leSupported).toLong())
                    .u8(if (backend.enabled()) 1 else 0).string(backend.adapterName()).build())
            }
            P.OP_ADAPTER_INFO -> reply(f.id, P.OK, BtWriter()
                .u8(if (backend.enabled()) 1 else 0).string(backend.adapterName()).string(backend.adapterAddress()).build())
            P.OP_BONDED -> {
                if (!backend.enabled()) return reply(f.id, P.ENODEV)
                val devices = backend.bonded().take(256)
                val w = BtWriter().u16(devices.size)
                devices.forEach { w.string(it.address).string(it.name).u8(it.kind).u32(it.deviceClass.toLong() and 0xffffffffL) }
                reply(f.id, P.OK, w.build())
            }
            P.OP_SCAN_START -> scanStart(f.id, r.u8())
            P.OP_SCAN_STOP -> { scanStop(); reply(f.id, P.OK) }
            P.OP_BOND_CREATE -> {
                val address = P.normalizeAddress(r.string()) ?: return reply(f.id, P.EINVAL)
                if (!backend.enabled()) return reply(f.id, P.ENODEV)
                reply(f.id, backend.createBond(address))
            }
            P.OP_SPP_OPEN -> sppOpen(f.id, r)
            P.OP_SPP_WRITE -> {
                val channel = r.i32(); val data = r.rest()
                if (data.isEmpty() || data.size > P.MAX_SPP_WRITE) return reply(f.id, P.EINVAL)
                val link = synchronized(lock) { spp[channel] } ?: return reply(f.id, P.ENOENT)
                reply(f.id, link.write(data))
            }
            P.OP_SPP_CLOSE -> {
                val link = synchronized(lock) { spp.remove(r.i32()) } ?: return reply(f.id, P.ENOENT)
                link.close(); reply(f.id, P.OK)
            }
            P.OP_SPP_LISTEN -> sppListen(f.id, r)
            P.OP_SPP_UNLISTEN -> {
                val link = synchronized(lock) { listeners.remove(r.i32()) } ?: return reply(f.id, P.ENOENT)
                link.close(); reply(f.id, P.OK)
            }
            P.OP_GATT_OPEN -> gattOpen(f.id, r)
            P.OP_GATT_CLOSE -> {
                val link = synchronized(lock) { gatt.remove(r.i32()) } ?: return reply(f.id, P.ENOENT)
                link.close(); reply(f.id, P.OK)
            }
            P.OP_GATT_DISCOVER, P.OP_GATT_READ, P.OP_GATT_WRITE, P.OP_GATT_NOTIFY, P.OP_GATT_MTU -> gattOp(f, r)
            else -> reply(f.id, P.EINVAL)
        }
    }

    private fun scanStart(id: Int, mode: Int) {
        if (mode !in 1..3) return reply(id, P.EINVAL)
        if (!backend.enabled()) return reply(id, P.ENODEV)
        val classic = mode and P.SCAN_CLASSIC != 0 && backend.classicSupported
        val le = mode and P.SCAN_LE != 0 && backend.leSupported
        if (!classic && !le) return reply(id, P.ENODEV)
        scanStop()
        synchronized(lock) { lastSeen.clear(); scanning = true }
        val status = backend.startScan(classic, le, object : BtScanListener {
            override fun onResult(device: BtDevice, rssi: Int, record: ByteArray) {
                val t = now()
                synchronized(lock) {
                    if (closed || !scanning) return
                    val last = lastSeen[device.address]
                    if (last != null && t - last < SCAN_REPEAT_MS) return
                    if (last == null && lastSeen.size >= MAX_SCAN_TRACKED) lastSeen.clear()
                    lastSeen[device.address] = t
                }
                val rec = if (record.size > 1024) record.copyOf(1024) else record
                event(P.EV_SCAN_RESULT, BtWriter().string(device.address).string(device.name)
                    .u16(rssi).u8(device.kind).u32(device.deviceClass.toLong() and 0xffffffffL)
                    .u16(rec.size).bytes(rec).build())
            }
            override fun onDone() {
                val was = synchronized(lock) { scanning.also { scanning = false } }
                if (was && !closed) event(P.EV_SCAN_DONE, ByteArray(0))
            }
        })
        if (status != P.OK) synchronized(lock) { scanning = false }
        reply(id, status)
    }

    private fun scanStop() {
        val was = synchronized(lock) { scanning.also { scanning = false } }
        if (was) runCatching { backend.stopScan() }
    }

    private fun sppOpen(id: Int, r: BtReader) {
        val address = P.normalizeAddress(r.string()) ?: return reply(id, P.EINVAL)
        val uuid = r.uuid()
        val secure = r.u8() != 0
        if (!backend.enabled()) return reply(id, P.ENODEV)
        val channel: Int
        synchronized(lock) {
            if (spp.size + sppPending >= MAX_SPP) return reply(id, P.EMFILE)
            sppPending++
            channel = nextId++
        }
        val listener = channelListener(channel)
        backend.openSpp(address, uuid, secure, listener) { status, link ->
            val keep = synchronized(lock) {
                sppPending = (sppPending - 1).coerceAtLeast(0)
                if (status == P.OK && link != null && !closed) { spp[channel] = link; true } else false
            }
            if (!keep) link?.let { runCatching { it.close() } }
            if (closed) return@openSpp
            if (keep) reply(id, P.OK, BtWriter().u32(channel.toLong()).build())
            else reply(id, if (status == P.OK) P.ENODEV else status)
        }
    }

    private fun channelListener(channel: Int) = object : BtSppListener {
        override fun onData(data: ByteArray) {
            if (!closed && synchronized(lock) { spp.containsKey(channel) })
                event(P.EV_SPP_DATA, BtWriter().u32(channel.toLong()).bytes(data).build())
        }
        override fun onClosed(reason: Int) {
            val known = synchronized(lock) { spp.remove(channel) != null }
            if (known && !closed) event(P.EV_SPP_CLOSED, BtWriter().u32(channel.toLong()).i32(reason).build())
        }
    }

    private fun sppListen(id: Int, r: BtReader) {
        val name = r.string()
        val uuid = r.uuid()
        val secure = r.u8() != 0
        if (!backend.enabled()) return reply(id, P.ENODEV)
        val listenerId: Int
        synchronized(lock) {
            if (listeners.size >= MAX_LISTEN) return reply(id, P.EMFILE)
            listenerId = nextId++
        }
        val accept = object : BtAcceptListener {
            override fun onAccepted(address: String, conn: BtAcceptedSpp) {
                val channel: Int
                synchronized(lock) {
                    if (closed || !listeners.containsKey(listenerId) || spp.size + sppPending >= MAX_SPP) { conn.reject(); return }
                    channel = nextId++
                }
                val link = conn.link(channelListener(channel))
                val keep = synchronized(lock) { if (closed) false else { spp[channel] = link; true } }
                if (!keep) { link.close(); return }
                event(P.EV_SPP_ACCEPT, BtWriter().u32(listenerId.toLong()).u32(channel.toLong()).string(address).build())
                conn.begin()          // data events only after the guest knows the channel
            }
        }
        // Register first: a connection can be accepted before listenSpp returns.
        val placeholder = object : BtListenLink { override fun close() {} }
        synchronized(lock) { listeners[listenerId] = placeholder }
        val link = backend.listenSpp(name, uuid, secure, accept)
        if (link == null) { synchronized(lock) { listeners.remove(listenerId) }; return reply(id, P.EIO) }
        val keep = synchronized(lock) { if (closed || !listeners.containsKey(listenerId)) false else { listeners[listenerId] = link; true } }
        if (!keep) { runCatching { link.close() }; return }
        reply(id, P.OK, BtWriter().u32(listenerId.toLong()).build())
    }

    private fun gattOpen(id: Int, r: BtReader) {
        val address = P.normalizeAddress(r.string()) ?: return reply(id, P.EINVAL)
        if (!backend.enabled()) return reply(id, P.ENODEV)
        val conn: Int
        synchronized(lock) {
            if (gatt.size >= MAX_GATT) return reply(id, P.EMFILE)
            conn = nextId++
        }
        val listener = object : BtGattListener {
            private fun live() = !closed && synchronized(lock) { gatt.containsKey(conn) }
            override fun onState(connected: Boolean, status: Int) {
                if (live()) event(P.EV_GATT_STATE, BtWriter().u32(conn.toLong()).u8(if (connected) 1 else 0).i32(status).build())
            }
            override fun onServices(status: Int, attrs: List<BtGattAttr>) {
                if (!live()) return
                // Keep each event inside one frame: ~25 bytes per attribute.
                val limited = attrs.take(2000)
                val w = BtWriter().u32(conn.toLong()).i32(status).u16(limited.size)
                limited.forEach { w.u16(it.id).u8(it.kind).uuid(it.uuid).u16(it.parent).u32(it.props.toLong() and 0xffffffffL) }
                event(P.EV_GATT_SERVICES, w.build())
            }
            override fun onRead(attr: Int, status: Int, value: ByteArray) {
                if (live()) event(P.EV_GATT_READ, BtWriter().u32(conn.toLong()).u16(attr).i32(status)
                    .bytes(value.copyOf(minOf(value.size, P.MAX_GATT_VALUE))).build())
            }
            override fun onWrite(attr: Int, status: Int) {
                if (live()) event(P.EV_GATT_WRITE, BtWriter().u32(conn.toLong()).u16(attr).i32(status).build())
            }
            override fun onChanged(attr: Int, value: ByteArray) {
                if (live()) event(P.EV_GATT_CHANGED, BtWriter().u32(conn.toLong()).u16(attr)
                    .bytes(value.copyOf(minOf(value.size, P.MAX_GATT_VALUE))).build())
            }
            override fun onMtu(mtu: Int, status: Int) {
                if (live()) event(P.EV_GATT_MTU, BtWriter().u32(conn.toLong()).u16(mtu).i32(status).build())
            }
        }
        // Register before connecting so early callbacks are not dropped as "unknown connection".
        val link = backend.openGatt(address, listener) ?: return reply(id, P.EIO)
        val keep = synchronized(lock) { if (closed) false else { gatt[conn] = link; true } }
        if (!keep) { runCatching { link.close() }; return }
        reply(id, P.OK, BtWriter().u32(conn.toLong()).build())
    }

    private fun gattOp(f: P.Frame, r: BtReader) {
        val link = synchronized(lock) { gatt[r.i32()] } ?: return reply(f.id, P.ENOENT)
        when (f.op) {
            P.OP_GATT_DISCOVER -> reply(f.id, link.discover())
            P.OP_GATT_READ -> reply(f.id, link.read(r.u16()))
            P.OP_GATT_WRITE -> {
                val attr = r.u16(); val type = r.u8(); val value = r.rest()
                if (type != P.WRITE_NO_RESPONSE && type != P.WRITE_DEFAULT) return reply(f.id, P.EINVAL)
                if (value.size > P.MAX_GATT_VALUE) return reply(f.id, P.EINVAL)
                reply(f.id, link.write(attr, type, value))
            }
            P.OP_GATT_NOTIFY -> { val attr = r.u16(); reply(f.id, link.setNotify(attr, r.u8() != 0)) }
            P.OP_GATT_MTU -> {
                val mtu = r.u16()
                if (mtu !in 23..517) return reply(f.id, P.EINVAL)
                reply(f.id, link.requestMtu(mtu))
            }
        }
    }

    private fun reply(id: Int, status: Int, data: ByteArray = ByteArray(0)) =
        emit(P.Frame(P.OP_RESP, id, BtWriter().i32(status).bytes(data).build()))

    private fun event(op: Int, payload: ByteArray) = emit(P.Frame(op, 0, payload))

    private fun emit(frame: P.Frame) {
        if (closed) return
        try { send(frame) } catch (e: Exception) { log("bluetooth: send failed: $e") }
    }
}
