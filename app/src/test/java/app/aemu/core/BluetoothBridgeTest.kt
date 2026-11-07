package app.aemu.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.UUID
import app.aemu.core.BluetoothProtocol as P

class BluetoothBridgeTest {
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    // ---- codec ----------------------------------------------------------------------------------------------------

    @Test fun framesRoundTrip() {
        val frame = P.Frame(P.OP_SPP_WRITE, -2, byteArrayOf(1, 2, 3))
        val back = P.read(ByteArrayInputStream(P.encode(frame)))!!
        assertEquals(P.OP_SPP_WRITE, back.op)
        assertEquals(-2, back.id)
        assertArrayEquals(byteArrayOf(1, 2, 3), back.payload)
    }

    @Test fun cleanEndOfStreamIsNullButTruncationThrows() {
        assertNull(P.read(ByteArrayInputStream(ByteArray(0))))
        val bytes = P.encode(P.Frame(P.OP_HELLO, 1, byteArrayOf(9, 9)))
        try { P.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 1))); fail() } catch (_: java.io.EOFException) {}
    }

    @Test fun rejectsBadLengths() {
        for (len in listOf(0L, 4L, P.MAX_FRAME + 1L, 0xffffffffL)) {
            val head = ByteArray(4).also { P.putU32(it, 0, len) }
            try { P.read(ByteArrayInputStream(head + ByteArray(16))); fail("accepted $len") } catch (_: P.FormatException) {}
        }
    }

    @Test fun readerBoundsAndStrings() {
        val r = BtReader(BtWriter().string("héllo").u16(7).build())
        assertEquals("héllo", r.string()); assertEquals(7, r.u16()); assertEquals(0, r.remaining)
        try { r.u8(); fail() } catch (_: P.FormatException) {}
        try { BtReader(byteArrayOf(5, 0, 1)).string(); fail() } catch (_: P.FormatException) {}
        try { BtReader(BtWriter().u16(300).build() + ByteArray(300)).string(); fail() } catch (_: P.FormatException) {}
    }

    @Test fun uuidsAreBigEndianAndRoundTrip() {
        val raw = P.uuidBytes(spp)
        assertEquals(0x00.toByte(), raw[0]); assertEquals(0x11.toByte(), raw[2]); assertEquals(0x01.toByte(), raw[3]); assertEquals(0xfb.toByte(), raw[15])
        assertEquals(spp, BtReader(raw).uuid())
    }

    @Test fun addressValidation() {
        assertEquals("AA:BB:CC:00:11:22", P.normalizeAddress("aa:bb:cc:00:11:22"))
        for (bad in listOf("", "AA:BB:CC:00:11", "AA-BB-CC-00-11-22", "GG:BB:CC:00:11:22", "AA:BB:CC:00:11:22:33", "../etc"))
            assertNull(bad, P.normalizeAddress(bad))
    }

    // ---- session --------------------------------------------------------------------------------------------------

    private class FakeSpp : BtSppLink {
        val written = mutableListOf<ByteArray>(); var closed = false; var status = P.OK
        override fun write(data: ByteArray): Int { written += data; return status }
        override fun close() { closed = true }
    }
    private class FakeListen : BtListenLink {
        var closed = false
        override fun close() { closed = true }
    }
    private class FakeAccepted(val spp: FakeSpp = FakeSpp()) : BtAcceptedSpp {
        var listener: BtSppListener? = null; var began = false; var rejected = false
        override fun link(listener: BtSppListener): BtSppLink { this.listener = listener; return spp }
        override fun begin() { began = true }
        override fun reject() { rejected = true }
    }
    private class FakeGatt : BtGattLink {
        val calls = mutableListOf<String>(); var closed = false
        override fun discover() = P.OK.also { calls += "discover" }
        override fun read(attr: Int) = P.OK.also { calls += "read $attr" }
        override fun write(attr: Int, writeType: Int, value: ByteArray) = P.OK.also { calls += "write $attr $writeType ${value.size}" }
        override fun setNotify(attr: Int, enable: Boolean) = P.OK.also { calls += "notify $attr $enable" }
        override fun requestMtu(mtu: Int) = P.OK.also { calls += "mtu $mtu" }
        override fun close() { closed = true }
    }
    private class FakeBackend : BtBackend {
        var on = true; var classic = true; var le = true
        override val classicSupported get() = classic
        override val leSupported get() = le
        override fun enabled() = on
        override fun adapterName() = "host"
        override fun adapterAddress() = "02:00:00:00:00:00"
        val bondedList = mutableListOf(BtDevice("AA:BB:CC:00:11:22", "Headset", P.KIND_CLASSIC, 0x240404))
        override fun bonded() = bondedList
        var scanListener: BtScanListener? = null; var scanStops = 0; var scanStatus = P.OK
        var scanModes: Pair<Boolean, Boolean>? = null
        override fun startScan(classic: Boolean, le: Boolean, listener: BtScanListener): Int {
            scanModes = classic to le; scanListener = listener; return scanStatus
        }
        override fun stopScan() { scanStops++ }
        val bondRequests = mutableListOf<String>(); var bondStatus = P.OK
        val listens = mutableListOf<Triple<String, UUID, Boolean>>(); var acceptor: BtAcceptListener? = null
        var listenLink: FakeListen? = FakeListen()
        override fun listenSpp(name: String, uuid: UUID, secure: Boolean, listener: BtAcceptListener): BtListenLink? {
            listens += Triple(name, uuid, secure); acceptor = listener; return listenLink
        }
        override fun createBond(address: String): Int { bondRequests += address; return bondStatus }
        val sppOpens = mutableListOf<Triple<String, UUID, Boolean>>()
        var sppListener: BtSppListener? = null; var sppDone: ((Int, BtSppLink?) -> Unit)? = null
        var autoSpp: FakeSpp? = null
        override fun openSpp(address: String, uuid: UUID, secure: Boolean, listener: BtSppListener, done: (Int, BtSppLink?) -> Unit) {
            sppOpens += Triple(address, uuid, secure); sppListener = listener
            val link = autoSpp
            if (link != null) done(P.OK, link) else sppDone = done
        }
        var gattListener: BtGattListener? = null; val gattLinks = mutableListOf<FakeGatt>()
        override fun openGatt(address: String, listener: BtGattListener): BtGattLink? {
            gattListener = listener; return FakeGatt().also { gattLinks += it }
        }
    }

    private class Harness {
        val backend = FakeBackend(); val sent = mutableListOf<P.Frame>(); var clock = 0L
        val session = BluetoothSession(backend, { sent += it }, now = { clock })
        fun req(op: Int, id: Int, payload: ByteArray = ByteArray(0)) = session.handle(P.Frame(op, id, payload))
        fun hello() = req(P.OP_HELLO, 1, "BTP1".toByteArray() + byteArrayOf(P.VERSION.toByte()))
        fun status(id: Int): Int = BtReader(sent.last { it.op == P.OP_RESP && it.id == id }.payload).i32()
        fun resp(id: Int): BtReader = BtReader(sent.last { it.op == P.OP_RESP && it.id == id }.payload).also { it.i32() }
        fun events(op: Int) = sent.filter { it.op == op }
        fun openSpp(id: Int, addr: String = "AA:BB:CC:00:11:22", secure: Boolean = true) =
            req(P.OP_SPP_OPEN, id, BtWriter().string(addr).uuid(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")).u8(if (secure) 1 else 0).build())
    }

    @Test fun requestsBeforeHelloAreRefused() {
        val h = Harness()
        h.req(P.OP_BONDED, 5)
        assertEquals(P.EPROTO, h.status(5))
        h.req(P.OP_HELLO, 6, "XXXX".toByteArray() + byteArrayOf(1))
        assertEquals(P.EPROTO, h.status(6))
        h.req(P.OP_HELLO, 7, "BTP1".toByteArray() + byteArrayOf(99))
        assertEquals(P.EPROTO, h.status(7))
    }

    @Test fun helloReportsCapabilitiesAndAdapter() {
        val h = Harness(); h.backend.le = false
        h.hello()
        val r = h.resp(1)
        assertEquals((P.CAP_CLASSIC_SCAN or P.CAP_SPP or P.CAP_LISTEN or P.CAP_BOND).toLong(), r.u32())
        assertEquals(1, r.u8()); assertEquals("host", r.string())
    }

    @Test fun bondRequestGoesToTheHostAndProgressComesBackAsEvents() {
        val h = Harness(); h.hello()
        h.req(P.OP_BOND_CREATE, 20, BtWriter().string("aa:bb:cc:00:11:33").build())
        assertEquals(P.OK, h.status(20))
        assertEquals(listOf("AA:BB:CC:00:11:33"), h.backend.bondRequests)
        h.session.bondState("aa:bb:cc:00:11:33", 11, 10)
        h.session.bondState("AA:BB:CC:00:11:33", 12, 11)
        val ev = h.events(P.EV_BOND_STATE)
        assertEquals(2, ev.size)
        val r = BtReader(ev.last().payload)
        assertEquals("AA:BB:CC:00:11:33", r.string()); assertEquals(12, r.u8()); assertEquals(11, r.u8())
    }

    @Test fun bondRequestIsValidated() {
        val h = Harness(); h.hello()
        h.req(P.OP_BOND_CREATE, 21, BtWriter().string("not-an-address").build())
        assertEquals(P.EINVAL, h.status(21))
        h.backend.on = false
        h.req(P.OP_BOND_CREATE, 22, BtWriter().string("AA:BB:CC:00:11:33").build())
        assertEquals(P.ENODEV, h.status(22))
        assertTrue(h.backend.bondRequests.isEmpty())
        h.backend.on = true; h.backend.bondStatus = P.EBUSY
        h.req(P.OP_BOND_CREATE, 23, BtWriter().string("AA:BB:CC:00:11:33").build())
        assertEquals(P.EBUSY, h.status(23))
    }

    @Test fun bondEventsAreHeldBackUntilHello() {
        val h = Harness()
        h.session.bondState("AA:BB:CC:00:11:33", 12, 10)
        assertTrue(h.events(P.EV_BOND_STATE).isEmpty())
    }

    private val serviceUuid = UUID.fromString("5e8945b0-9525-11e3-a5e2-0800200c9a66")
    private fun Harness.listen(id: Int, name: String = "clockwork") =
        req(P.OP_SPP_LISTEN, id, BtWriter().string(name).uuid(serviceUuid).u8(1).build())

    @Test fun listenRegistersAnRfcommServerOnTheHost() {
        val h = Harness(); h.hello()
        h.listen(30)
        assertEquals(P.OK, h.status(30))
        val listener = h.resp(30).u32()
        assertTrue(listener > 0)
        assertEquals(Triple("clockwork", serviceUuid, true), h.backend.listens.single())
        h.req(P.OP_SPP_UNLISTEN, 31, BtWriter().u32(listener).build())
        assertEquals(P.OK, h.status(31)); assertTrue(h.backend.listenLink!!.closed)
        h.req(P.OP_SPP_UNLISTEN, 32, BtWriter().u32(listener).build())
        assertEquals(P.ENOENT, h.status(32))
    }

    @Test fun listenFailuresAndLimit() {
        val h = Harness(); h.hello()
        h.backend.listenLink = null
        h.listen(40); assertEquals(P.EIO, h.status(40))
        h.backend.listenLink = FakeListen()
        h.listen(41); h.listen(42); h.listen(43)
        assertEquals(P.OK, h.status(41)); assertEquals(P.OK, h.status(42)); assertEquals(P.EMFILE, h.status(43))
        h.backend.on = false
        h.listen(44); assertEquals(P.ENODEV, h.status(44))
    }

    @Test fun acceptedConnectionBecomesAChannelAndDataFollowsTheAnnouncement() {
        val h = Harness(); h.hello(); h.listen(50)
        val listener = h.resp(50).u32()
        val conn = FakeAccepted()
        h.backend.acceptor!!.onAccepted("AA:BB:CC:00:11:99", conn)
        val ev = BtReader(h.events(P.EV_SPP_ACCEPT).single().payload)
        assertEquals(listener, ev.u32())
        val channel = ev.u32(); assertEquals("AA:BB:CC:00:11:99", ev.string())
        assertTrue(conn.began)
        conn.listener!!.onData(byteArrayOf(1, 2, 3))
        assertEquals(1, h.events(P.EV_SPP_DATA).size)
        // the guest can write to it like any other channel
        h.req(P.OP_SPP_WRITE, 51, BtWriter().u32(channel).bytes(byteArrayOf(9)).build())
        assertEquals(P.OK, h.status(51)); assertEquals(1, conn.spp.written.size)
    }

    @Test fun acceptIsRefusedAfterUnlistenOrAtTheChannelLimit() {
        val h = Harness(); h.hello(); h.listen(60)
        val listener = h.resp(60).u32()
        val acceptor = h.backend.acceptor!!
        repeat(4) { acceptor.onAccepted("AA:BB:CC:00:11:0$it", FakeAccepted()) }
        val extra = FakeAccepted()
        acceptor.onAccepted("AA:BB:CC:00:11:09", extra)
        assertTrue(extra.rejected); assertEquals(4, h.events(P.EV_SPP_ACCEPT).size)
        h.req(P.OP_SPP_UNLISTEN, 61, BtWriter().u32(listener).build())
        val late = FakeAccepted(); acceptor.onAccepted("AA:BB:CC:00:11:0A", late)
        assertTrue(late.rejected)
    }

    @Test fun closingTheSessionClosesListeners() {
        val h = Harness(); h.hello(); h.listen(70)
        h.session.close()
        assertTrue(h.backend.listenLink!!.closed)
    }

    @Test fun bondedListsDevices() {
        val h = Harness(); h.hello(); h.req(P.OP_BONDED, 2)
        val r = h.resp(2)
        assertEquals(1, r.u16()); assertEquals("AA:BB:CC:00:11:22", r.string()); assertEquals("Headset", r.string())
        h.backend.on = false; h.req(P.OP_BONDED, 3)
        assertEquals(P.ENODEV, h.status(3))
    }

    @Test fun malformedPayloadsAnswerEinvalNotCrash() {
        val h = Harness(); h.hello()
        h.req(P.OP_SPP_OPEN, 2, byteArrayOf(1))
        assertEquals(P.EINVAL, h.status(2))
        h.req(P.OP_SCAN_START, 3)
        assertEquals(P.EINVAL, h.status(3))
        h.req(P.OP_SCAN_START, 4, byteArrayOf(9))
        assertEquals(P.EINVAL, h.status(4))
        h.req(0x55, 5)
        assertEquals(P.EINVAL, h.status(5))
        h.openSpp(6, addr = "not-an-address")
        assertEquals(P.EINVAL, h.status(6))
        assertTrue(h.backend.sppOpens.isEmpty())
    }

    @Test fun scanThrottlesRepeatsAndStopsOnClose() {
        val h = Harness(); h.hello()
        h.req(P.OP_SCAN_START, 2, byteArrayOf(3))
        assertEquals(P.OK, h.status(2))
        assertEquals(true to true, h.backend.scanModes)
        val dev = BtDevice("11:22:33:44:55:66", "Tag", P.KIND_LE)
        val l = h.backend.scanListener!!
        l.onResult(dev, -60, byteArrayOf(2, 1, 6)); l.onResult(dev, -61, ByteArray(0))
        h.clock += 1_500; l.onResult(dev, -62, ByteArray(0))
        assertEquals(2, h.events(P.EV_SCAN_RESULT).size)
        l.onDone()
        assertEquals(1, h.events(P.EV_SCAN_DONE).size)
        l.onResult(dev, -60, ByteArray(0))               // after done: ignored
        assertEquals(2, h.events(P.EV_SCAN_RESULT).size)
        h.req(P.OP_SCAN_START, 3, byteArrayOf(1)); val stops = h.backend.scanStops
        h.session.close()
        assertTrue(h.backend.scanStops > stops)
    }

    @Test fun scanModeIsClampedToWhatTheHostSupports() {
        val h = Harness(); h.backend.le = false; h.hello()
        h.req(P.OP_SCAN_START, 2, byteArrayOf(3))
        assertEquals(true to false, h.backend.scanModes)
        h.req(P.OP_SCAN_START, 3, byteArrayOf(P.SCAN_LE.toByte()))
        assertEquals(P.ENODEV, h.status(3))
    }

    @Test fun sppConnectIsAsyncThenDataFlows() {
        val h = Harness(); h.hello(); h.openSpp(2)
        assertTrue(h.sent.none { it.op == P.OP_RESP && it.id == 2 })        // still connecting
        val link = FakeSpp(); h.backend.sppDone!!(P.OK, link)
        val ch = h.resp(2).u32().toInt()
        h.backend.sppListener!!.onData(byteArrayOf(7, 8))
        val ev = BtReader(h.events(P.EV_SPP_DATA).single().payload)
        assertEquals(ch, ev.i32()); assertArrayEquals(byteArrayOf(7, 8), ev.rest())
        h.req(P.OP_SPP_WRITE, 3, BtWriter().u32(ch.toLong()).bytes(byteArrayOf(1, 2, 3)).build())
        assertEquals(P.OK, h.status(3)); assertArrayEquals(byteArrayOf(1, 2, 3), link.written.single())
        h.backend.sppListener!!.onClosed(0)
        assertEquals(1, h.events(P.EV_SPP_CLOSED).size)
        h.req(P.OP_SPP_WRITE, 4, BtWriter().u32(ch.toLong()).bytes(byteArrayOf(1)).build())
        assertEquals(P.ENOENT, h.status(4))
    }

    @Test fun sppFailureAndWriteBackpressure() {
        val h = Harness(); h.hello(); h.openSpp(2)
        h.backend.sppDone!!(P.ENOTCONN, null)
        assertEquals(P.ENOTCONN, h.status(2))
        val link = FakeSpp().also { it.status = P.EAGAIN }; h.backend.autoSpp = link; h.openSpp(3)
        val ch = h.resp(3).u32()
        h.req(P.OP_SPP_WRITE, 4, BtWriter().u32(ch).bytes(byteArrayOf(1)).build())
        assertEquals(P.EAGAIN, h.status(4))
        h.req(P.OP_SPP_WRITE, 5, BtWriter().u32(ch).bytes(ByteArray(P.MAX_SPP_WRITE + 1)).build())
        assertEquals(P.EINVAL, h.status(5))
        h.req(P.OP_SPP_WRITE, 6, BtWriter().u32(ch).build())
        assertEquals(P.EINVAL, h.status(6))
    }

    @Test fun sppLimitCountsPendingConnects() {
        val h = Harness(); h.hello()
        repeat(BluetoothSession.MAX_SPP) { h.openSpp(10 + it) }              // all still connecting
        h.openSpp(99)
        assertEquals(P.EMFILE, h.status(99))
    }

    @Test fun closeReleasesEverythingAndLateConnectsAreDropped() {
        val h = Harness(); h.hello()
        val a = FakeSpp(); h.backend.autoSpp = a; h.openSpp(2)
        h.req(P.OP_GATT_OPEN, 3, BtWriter().string("AA:BB:CC:00:11:22").build())
        h.backend.autoSpp = null; h.openSpp(4)                                // pending when the guest vanishes
        h.session.close()
        assertTrue(a.closed); assertTrue(h.backend.gattLinks.single().closed)
        val late = FakeSpp(); h.backend.sppDone!!(P.OK, late)
        assertTrue(late.closed)
        val before = h.sent.size
        h.req(P.OP_BONDED, 9); h.backend.gattListener!!.onState(true, 0)
        assertEquals(before, h.sent.size)                                      // nothing after close
    }

    @Test fun gattFlowAndValidation() {
        val h = Harness(); h.hello()
        h.req(P.OP_GATT_OPEN, 2, BtWriter().string("AA:BB:CC:00:11:22").build())
        val conn = h.resp(2).u32(); val link = h.backend.gattLinks.single()
        h.backend.gattListener!!.onState(true, 0)
        assertEquals(1, h.events(P.EV_GATT_STATE).size)
        h.req(P.OP_GATT_DISCOVER, 3, BtWriter().u32(conn).build())
        h.req(P.OP_GATT_READ, 4, BtWriter().u32(conn).u16(5).build())
        h.req(P.OP_GATT_WRITE, 5, BtWriter().u32(conn).u16(5).u8(P.WRITE_NO_RESPONSE).bytes(byteArrayOf(1, 2)).build())
        h.req(P.OP_GATT_NOTIFY, 6, BtWriter().u32(conn).u16(5).u8(1).build())
        h.req(P.OP_GATT_MTU, 7, BtWriter().u32(conn).u16(185).build())
        assertEquals(listOf("discover", "read 5", "write 5 1 2", "notify 5 true", "mtu 185"), link.calls)
        h.req(P.OP_GATT_WRITE, 8, BtWriter().u32(conn).u16(5).u8(9).build())
        assertEquals(P.EINVAL, h.status(8))
        h.req(P.OP_GATT_WRITE, 9, BtWriter().u32(conn).u16(5).u8(P.WRITE_DEFAULT).bytes(ByteArray(P.MAX_GATT_VALUE + 1)).build())
        assertEquals(P.EINVAL, h.status(9))
        h.req(P.OP_GATT_MTU, 10, BtWriter().u32(conn).u16(10).build())
        assertEquals(P.EINVAL, h.status(10))
        h.req(P.OP_GATT_READ, 11, BtWriter().u32(12345).u16(1).build())
        assertEquals(P.ENOENT, h.status(11))
        h.req(P.OP_GATT_CLOSE, 12, BtWriter().u32(conn).build())
        assertTrue(link.closed)
        h.backend.gattListener!!.onChanged(5, byteArrayOf(1))                 // late callback after close
        assertTrue(h.events(P.EV_GATT_CHANGED).isEmpty())
    }

    @Test fun gattServicesEventCarriesAttributes() {
        val h = Harness(); h.hello()
        h.req(P.OP_GATT_OPEN, 2, BtWriter().string("AA:BB:CC:00:11:22").build())
        val conn = h.resp(2).u32()
        val svc = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        h.backend.gattListener!!.onServices(0, listOf(BtGattAttr(1, P.ATTR_SERVICE, svc, 0, 0), BtGattAttr(2, P.ATTR_CHARACTERISTIC, svc, 1, 0x12)))
        val r = BtReader(h.events(P.EV_GATT_SERVICES).single().payload)
        assertEquals(conn, r.u32()); assertEquals(0, r.i32()); assertEquals(2, r.u16())
        assertEquals(1, r.u16()); assertEquals(P.ATTR_SERVICE, r.u8()); assertEquals(svc, r.uuid()); assertEquals(0, r.u16()); assertEquals(0L, r.u32())
        assertEquals(2, r.u16()); assertEquals(P.ATTR_CHARACTERISTIC, r.u8()); r.uuid(); assertEquals(1, r.u16()); assertEquals(0x12L, r.u32())
    }

    @Test fun gattConnectionLimit() {
        val h = Harness(); h.hello()
        repeat(BluetoothSession.MAX_GATT) { h.req(P.OP_GATT_OPEN, 10 + it, BtWriter().string("AA:BB:CC:00:11:22").build()) }
        h.req(P.OP_GATT_OPEN, 99, BtWriter().string("AA:BB:CC:00:11:22").build())
        assertEquals(P.EMFILE, h.status(99))
    }

    @Test fun permissionsDependOnAndroidVersion() {
        assertEquals(listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN"), BluetoothPermissions.required(31))
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), BluetoothPermissions.required(30))
    }
}
