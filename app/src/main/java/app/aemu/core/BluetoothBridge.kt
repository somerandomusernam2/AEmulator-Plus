package app.aemu.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import app.aemu.core.BluetoothProtocol as P

/**
 * Host Bluetooth for the guest: the guest's Bluetooth service talks to /dev/aemu_bluetooth, this class
 * answers from the phone's adapter. Only available when the VM setting is on and permissions are granted;
 * one guest connection at a time (a new one replaces the old one and releases its devices).
 */
class BluetoothBridge(private val ctx: Context, paths: VmPaths, private val enabled: () -> Boolean, private val log: (String) -> Unit) {
    private val backend = AndroidBtBackend(ctx, log)
    private val lock = Any()
    private var session: BluetoothSession? = null
    private var client: LocalSocket? = null
    private var stateReceiver: BroadcastReceiver? = null
    private val server = UnixServer(File(paths.root, "dev/aemu_bluetooth"), "bluetooth") { socket -> serveClient(socket) }

    fun serve(): Boolean {
        if (!enabled()) return false
        if (!backend.available) { log("bluetooth: host has no Bluetooth adapter"); return false }
        val missing = BluetoothPermissions.missing(ctx)
        if (missing.isNotEmpty()) { log("bluetooth: permission not granted (${missing.joinToString { it.substringAfterLast('.') }})"); return false }
        val ok = server.start(log)
        if (ok) registerStateReceiver()
        log("bluetooth: bridge ${if (ok) "ready" else "unavailable"}; host adapter ${if (backend.enabled()) "on" else "off"}")
        return ok
    }

    fun stop() {
        unregisterStateReceiver()
        server.stop()
        val (s, c) = synchronized(lock) { Pair(session, client).also { session = null; client = null } }
        s?.close()
        runCatching { c?.close() }
        backend.close()
    }

    private fun serveClient(socket: LocalSocket) {
        val out = socket.outputStream
        val mine = BluetoothSession(backend, { frame -> writeFrame(out, frame) }, log)
        val (oldSession, oldClient) = synchronized(lock) { Pair(session, client).also { session = mine; client = socket } }
        // A second guest process replaces the first: release the old one's links before serving the new one.
        oldSession?.close()
        runCatching { oldClient?.close() }
        try {
            val input: InputStream = socket.inputStream
            while (true) {
                val frame = P.read(input) ?: break
                mine.handle(frame)
            }
        } catch (e: Exception) {
            log("bluetooth: guest connection ended: ${e.message}")
        } finally {
            mine.close()
            synchronized(lock) { if (session === mine) { session = null; client = null } }
        }
    }

    private fun writeFrame(out: OutputStream, frame: P.Frame) {
        val bytes = P.encode(frame)
        synchronized(out) { out.write(bytes); out.flush() }
    }

    private fun registerStateReceiver() {
        if (stateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    val device = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val now = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    if (device != null && now in 10..12) synchronized(lock) { session }
                        ?.bondState(device.address, now, if (prev in 10..12) prev else BluetoothDevice.BOND_NONE)
                    return
                }
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                val on = state == BluetoothAdapter.STATE_ON
                if (on || state == BluetoothAdapter.STATE_OFF) synchronized(lock) { session }?.adapterState(on)
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply { addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED) }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(receiver, filter)
        stateReceiver = receiver
    }

    private fun unregisterStateReceiver() {
        stateReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        stateReceiver = null
    }
}

@SuppressLint("MissingPermission")   // BluetoothBridge.serve() checks the permissions before any of this runs
internal class AndroidBtBackend(private val ctx: Context, private val log: (String) -> Unit) : BtBackend {
    private val adapter: BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "bt-io").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var discoveryReceiver: BroadcastReceiver? = null
    private var leCallback: ScanCallback? = null

    val available get() = adapter != null
    override val classicSupported get() = adapter != null && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
    override val leSupported get() = adapter != null && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    override fun enabled() = guard(false) { adapter?.isEnabled == true }
    override fun adapterName() = guard("") { adapter?.name ?: "" }
    // Android 6+ hides the real address from apps and reports this constant; the guest sees the same.
    override fun adapterAddress() = guard("02:00:00:00:00:00") { adapter?.address ?: "02:00:00:00:00:00" }

    override fun bonded(): List<BtDevice> = guard(emptyList()) {
        adapter?.bondedDevices.orEmpty().map { toDevice(it) }
    }

    private fun toDevice(d: BluetoothDevice, kind: Int? = null): BtDevice {
        val k = kind ?: if (guard(BluetoothDevice.DEVICE_TYPE_UNKNOWN) { d.type } == BluetoothDevice.DEVICE_TYPE_LE) P.KIND_LE else P.KIND_CLASSIC
        return BtDevice(d.address.uppercase(), guard("") { d.name ?: "" }, k, guard(0) { d.bluetoothClass?.deviceClass ?: 0 })
    }

    private fun <T> guard(fallback: T, block: () -> T): T =
        try { block() } catch (_: SecurityException) { fallback } catch (_: Exception) { fallback }

    // The phone shows its own pairing dialog; removing a bond is deliberately not offered to the guest.
    override fun createBond(address: String): Int {
        val a = adapter ?: return P.ENODEV
        return try {
            val device = a.getRemoteDevice(address)
            if (device.bondState == BluetoothDevice.BOND_BONDED) P.EBUSY
            else if (device.createBond()) P.OK
            else P.EIO
        } catch (_: SecurityException) {
            P.EPERM
        } catch (_: Exception) {
            P.EIO
        }
    }

    // ---- scanning -------------------------------------------------------------------------------------------------

    @Synchronized
    override fun startScan(classic: Boolean, le: Boolean, listener: BtScanListener): Int {
        val a = adapter ?: return P.ENODEV
        stopScan()
        try {
            if (classic) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        when (intent.action) {
                            BluetoothDevice.ACTION_FOUND -> {
                                val device = if (Build.VERSION.SDK_INT >= 33)
                                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                else @Suppress("DEPRECATION") intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                                if (device != null) listener.onResult(toDevice(device, P.KIND_CLASSIC),
                                    if (rssi == Short.MIN_VALUE.toInt()) -127 else rssi, ByteArray(0))
                            }
                            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> listener.onDone()
                        }
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else ctx.registerReceiver(receiver, filter)
                discoveryReceiver = receiver
                if (!a.startDiscovery()) { stopScan(); if (!le) return P.EBUSY }
            }
            if (le) {
                val scanner = a.bluetoothLeScanner
                if (scanner == null) { if (!classic) return P.ENODEV }
                else {
                    val cb = object : ScanCallback() {
                        override fun onScanResult(callbackType: Int, result: ScanResult) {
                            listener.onResult(toDevice(result.device, P.KIND_LE), result.rssi, result.scanRecord?.bytes ?: ByteArray(0))
                        }
                        override fun onScanFailed(errorCode: Int) { log("bluetooth: LE scan failed ($errorCode)"); listener.onDone() }
                    }
                    scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), cb)
                    leCallback = cb
                }
            }
            return P.OK
        } catch (_: SecurityException) {
            stopScan(); return P.EPERM
        } catch (e: Exception) {
            log("bluetooth: scan start failed: $e"); stopScan(); return P.EIO
        }
    }

    @Synchronized
    override fun stopScan() {
        val a = adapter
        discoveryReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        discoveryReceiver = null
        runCatching { if (a?.isDiscovering == true) a.cancelDiscovery() }
        leCallback?.let { cb -> runCatching { a?.bluetoothLeScanner?.stopScan(cb) } }
        leCallback = null
    }

    // ---- RFCOMM ---------------------------------------------------------------------------------------------------

    override fun openSpp(address: String, uuid: UUID, secure: Boolean, listener: BtSppListener, done: (Int, BtSppLink?) -> Unit) {
        val a = adapter ?: return done(P.ENODEV, null)
        pool.execute {
            var socket: BluetoothSocket? = null
            try {
                val device = a.getRemoteDevice(address)
                socket = if (secure) device.createRfcommSocketToServiceRecord(uuid)
                         else device.createInsecureRfcommSocketToServiceRecord(uuid)
                runCatching { a.cancelDiscovery() }          // discovery makes RFCOMM connects slow and flaky
                socket.connect()
                val link = SppLink(socket, listener, pool)
                done(P.OK, link)
                link.begin()      // after the session has registered the channel, so no early data is dropped
            } catch (_: SecurityException) {
                runCatching { socket?.close() }; done(P.EPERM, null)
            } catch (e: Exception) {
                runCatching { socket?.close() }
                log("bluetooth: RFCOMM connect to $address failed: ${e.message}")
                done(if (e is java.io.IOException) P.ENOTCONN else P.EIO, null)
            }
        }
    }

    // ---- listening (RFCOMM server) --------------------------------------------------------------------------------

    override fun listenSpp(name: String, uuid: UUID, secure: Boolean, listener: BtAcceptListener): BtListenLink? {
        val a = adapter ?: return null
        val server = try {
            if (secure) a.listenUsingRfcommWithServiceRecord(name, uuid)
            else a.listenUsingInsecureRfcommWithServiceRecord(name, uuid)
        } catch (e: SecurityException) {
            return null
        } catch (e: Exception) {
            log("bluetooth: RFCOMM listen failed: ${e.message}")
            return null
        }
        val link = ListenLink(server)
        pool.execute {
            try {
                while (!link.closed) {
                    val socket = server.accept()               // blocks; throws once the server socket is closed
                    val address = socket.remoteDevice.address.uppercase()
                    listener.onAccepted(address, AcceptedSpp(socket, pool))
                }
            } catch (e: Exception) {
                if (!link.closed) log("bluetooth: RFCOMM accept stopped: ${e.message}")
            } finally {
                link.close()
            }
        }
        return link
    }

    private class ListenLink(private val server: BluetoothServerSocket) : BtListenLink {
        @Volatile var closed = false
        override fun close() {
            if (closed) return
            closed = true
            runCatching { server.close() }
        }
    }

    private class AcceptedSpp(private val socket: BluetoothSocket, private val pool: ExecutorService) : BtAcceptedSpp {
        private var link: SppLink? = null
        override fun link(listener: BtSppListener): BtSppLink = SppLink(socket, listener, pool).also { link = it }
        override fun begin() { link?.begin() }
        override fun reject() { runCatching { socket.close() } }
    }

    private class SppLink(private val socket: BluetoothSocket, private val listener: BtSppListener, private val pool: ExecutorService) : BtSppLink {
        private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "bt-spp-write").apply { isDaemon = true } }
        private val queued = AtomicInteger()
        @Volatile private var closed = false

        /** Starts the reader; called once, after the link is registered with the session. */
        fun begin() {
            pool.execute {
                val buf = ByteArray(4096)
                var reason = 0
                try {
                    val input = socket.inputStream
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) listener.onData(buf.copyOf(n))
                    }
                } catch (e: Exception) { if (!closed) reason = P.EIO }
                close(); listener.onClosed(reason)
            }
        }

        override fun write(data: ByteArray): Int {
            if (closed) return P.ENOTCONN
            if (queued.get() >= 64) return P.EAGAIN             // guest is writing faster than the radio drains
            queued.incrementAndGet()
            writer.execute {
                try { socket.outputStream.run { write(data); flush() } }
                catch (_: Exception) { close() }
                finally { queued.decrementAndGet() }
            }
            return P.OK
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { socket.close() }
            writer.shutdown()
        }
    }

    // ---- GATT -----------------------------------------------------------------------------------------------------

    override fun openGatt(address: String, listener: BtGattListener): BtGattLink? {
        val a = adapter ?: return null
        return try {
            val link = GattLink(listener, main)
            val device = a.getRemoteDevice(address)
            link.gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(ctx, false, link.callback, BluetoothDevice.TRANSPORT_LE)
                        else device.connectGatt(ctx, false, link.callback)
            if (link.gatt == null) null else link
        } catch (e: Exception) { log("bluetooth: GATT open failed: $e"); null }
    }

    /**
     * Android allows one GATT operation in flight per connection, so reads, writes, discovery and MTU requests
     * are queued here; the reply to the guest is the event the operation produces. A lost callback must not
     * freeze the queue, hence the timeout.
     */
    private class GattLink(private val listener: BtGattListener, private val handler: Handler) : BtGattLink {
        var gatt: BluetoothGatt? = null
        private class Op(val start: () -> Boolean, val fail: (Int) -> Unit)
        private val queue = ArrayDeque<Op>()
        private var current: Op? = null
        private var serial = 0
        private val chars = HashMap<Int, BluetoothGattCharacteristic>()
        private val descs = HashMap<Int, BluetoothGattDescriptor>()
        private val ids = HashMap<Any, Int>()
        @Volatile private var closed = false

        @SuppressLint("MissingPermission")
        private fun enqueue(op: Op): Int {
            synchronized(this) {
                if (closed || gatt == null) return P.ENOTCONN
                if (queue.size >= 32) return P.EAGAIN
                queue.addLast(op)
            }
            pump()
            return P.OK
        }

        private fun pump() {
            val op: Op
            val mine: Int
            synchronized(this) {
                if (current != null || closed) return
                op = queue.removeFirstOrNull() ?: return
                current = op; mine = ++serial
            }
            val started = try { op.start() } catch (_: Exception) { false }
            if (!started) { synchronized(this) { current = null }; op.fail(P.EIO); pump(); return }
            handler.postDelayed({
                val stalled = synchronized(this) { if (serial == mine && current === op) { current = null; true } else false }
                if (stalled) { op.fail(P.ETIMEDOUT); pump() }
            }, 10_000)
        }

        private fun finish() { synchronized(this) { current = null }; pump() }

        @SuppressLint("MissingPermission")
        override fun discover(): Int = enqueue(Op({ gatt?.discoverServices() == true }, { listener.onServices(it, emptyList()) }))

        override fun read(attr: Int): Int {
            val c = synchronized(this) { chars[attr] }
            val d = synchronized(this) { descs[attr] }
            if (c == null && d == null) return P.ENOENT
            return enqueue(Op({ if (c != null) gatt?.readCharacteristic(c) == true else gatt?.readDescriptor(d) == true },
                { listener.onRead(attr, it, ByteArray(0)) }))
        }

        @Suppress("DEPRECATION")
        override fun write(attr: Int, writeType: Int, value: ByteArray): Int {
            val c = synchronized(this) { chars[attr] }
            val d = synchronized(this) { descs[attr] }
            if (c == null && d == null) return P.ENOENT
            val type = if (writeType == P.WRITE_NO_RESPONSE) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                       else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            return enqueue(Op({
                val g = gatt ?: return@Op false
                if (Build.VERSION.SDK_INT >= 33) {
                    if (c != null) g.writeCharacteristic(c, value, type) == BluetoothGatt.GATT_SUCCESS
                    else g.writeDescriptor(d!!, value) == BluetoothGatt.GATT_SUCCESS
                } else if (c != null) { c.writeType = type; c.value = value; g.writeCharacteristic(c) }
                else { d!!.value = value; g.writeDescriptor(d) }
            }, { listener.onWrite(attr, it) }))
        }

        @SuppressLint("MissingPermission")
        override fun setNotify(attr: Int, enable: Boolean): Int {
            val c = synchronized(this) { chars[attr] } ?: return P.ENOENT
            return if (gatt?.setCharacteristicNotification(c, enable) == true) P.OK else P.EIO
        }

        @SuppressLint("MissingPermission")
        override fun requestMtu(mtu: Int): Int =
            enqueue(Op({ gatt?.requestMtu(mtu) == true }, { listener.onMtu(mtu, it) }))

        @SuppressLint("MissingPermission")
        override fun close() {
            val g: BluetoothGatt?
            synchronized(this) { if (closed) return; closed = true; queue.clear(); current = null; g = gatt }
            runCatching { g?.disconnect() }
            runCatching { g?.close() }
        }

        private fun index(g: BluetoothGatt): List<BtGattAttr> {
            val out = ArrayList<BtGattAttr>()
            synchronized(this) {
                chars.clear(); descs.clear(); ids.clear()
                var next = 1
                for (service in g.services) {
                    val sid = next++
                    out += BtGattAttr(sid, P.ATTR_SERVICE, service.uuid, 0, 0)
                    for (c in service.characteristics) {
                        val cid = next++
                        chars[cid] = c; ids[c] = cid
                        out += BtGattAttr(cid, P.ATTR_CHARACTERISTIC, c.uuid, sid, c.properties)
                        for (d in c.descriptors) {
                            val did = next++
                            descs[did] = d; ids[d] = did
                            out += BtGattAttr(did, P.ATTR_DESCRIPTOR, d.uuid, cid, 0)
                        }
                    }
                }
            }
            return out
        }

        private fun idOf(o: Any): Int = synchronized(this) { ids[o] } ?: 0
        private fun ok(status: Int) = if (status == BluetoothGatt.GATT_SUCCESS) 0 else status

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                val up = newState == BluetoothProfile.STATE_CONNECTED
                listener.onState(up, ok(status))
                if (!up && newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val ops: List<Op>
                    synchronized(this@GattLink) { ops = queue.toList() + listOfNotNull(current); queue.clear(); current = null }
                    ops.forEach { it.fail(P.ENOTCONN) }
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val attrs = if (status == BluetoothGatt.GATT_SUCCESS) index(g) else emptyList()
                listener.onServices(ok(status), attrs); finish()
            }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT >= 33) return
                listener.onRead(idOf(c), ok(status), c.value ?: ByteArray(0)); finish()
            }
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                listener.onRead(idOf(c), ok(status), value); finish()
            }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onDescriptorRead(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                if (Build.VERSION.SDK_INT >= 33) return
                listener.onRead(idOf(d), ok(status), d.value ?: ByteArray(0)); finish()
            }
            override fun onDescriptorRead(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int, value: ByteArray) {
                listener.onRead(idOf(d), ok(status), value); finish()
            }
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                listener.onWrite(idOf(c), ok(status)); finish()
            }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                listener.onWrite(idOf(d), ok(status)); finish()
            }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= 33) return
                listener.onChanged(idOf(c), c.value ?: ByteArray(0))
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                listener.onChanged(idOf(c), value)
            }
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                listener.onMtu(mtu, ok(status)); finish()
            }
        }
    }

    /** The bridge is restartable (VM reboot), so the I/O pool stays; its idle threads expire on their own. */
    fun close() { stopScan() }
}
