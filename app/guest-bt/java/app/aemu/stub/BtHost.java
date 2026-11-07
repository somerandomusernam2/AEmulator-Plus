package app.aemu.stub;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Client of the host Bluetooth bridge (BLUETOOTH_BRIDGE.md): one stream socket on /dev/aemu_bluetooth, framed as
 * u32le length (5 + payload) | u8 op | u32le id | payload. Requests block until the matching RESP arrives; host events
 * are handed to the {@link Listener} on one ordered worker so a listener may itself send requests.
 * Reconnects forever: the bridge only exists while the VM setting is on and the host granted the permissions.
 */
final class BtHost {
    // guest -> host
    static final int OP_HELLO = 0x01, OP_ADAPTER_INFO = 0x02, OP_BONDED = 0x03, OP_SCAN_START = 0x04, OP_SCAN_STOP = 0x05,
            OP_BOND_CREATE = 0x06,
            OP_SPP_OPEN = 0x10, OP_SPP_WRITE = 0x11, OP_SPP_CLOSE = 0x12, OP_SPP_LISTEN = 0x13, OP_SPP_UNLISTEN = 0x14,
            OP_GATT_OPEN = 0x20, OP_GATT_DISCOVER = 0x21, OP_GATT_READ = 0x22, OP_GATT_WRITE = 0x23,
            OP_GATT_NOTIFY = 0x24, OP_GATT_MTU = 0x25, OP_GATT_CLOSE = 0x26;
    static final int OP_RESP = 0x7F;
    // host -> guest events
    static final int EV_SCAN_RESULT = 0x80, EV_SCAN_DONE = 0x81, EV_ADAPTER_STATE = 0x82, EV_BOND_STATE = 0x83,
            EV_SPP_DATA = 0x90, EV_SPP_CLOSED = 0x91, EV_SPP_ACCEPT = 0x92,
            EV_GATT_STATE = 0xA0, EV_GATT_SERVICES = 0xA1, EV_GATT_READ = 0xA2, EV_GATT_WRITE = 0xA3,
            EV_GATT_CHANGED = 0xA4, EV_GATT_MTU = 0xA5;
    // status codes (negative errno)
    static final int OK = 0, EPERM = -1, ENOENT = -2, EIO = -5, EAGAIN = -11, EBUSY = -16, ENODEV = -19, EINVAL = -22,
            ETIMEDOUT = -110;

    static final int CAP_BOND = 16;
    static final int MAX_FRAME = 65536;
    static final String SOCKET = "/dev/aemu_bluetooth";

    interface Listener {
        /** HELLO done; [enabled] is the host adapter state. */
        void onConnected(int caps, boolean enabled, String name);
        void onDisconnected();
        void onEvent(int op, byte[] payload);
    }

    static final class Result {
        final int status;
        final byte[] data;
        Result(int status, byte[] data) { this.status = status; this.data = data; }
        boolean ok() { return status == OK; }
    }

    private static final class Pending {
        final CountDownLatch done = new CountDownLatch(1);
        volatile Result result;
    }

    private final Listener listener;
    private final Object writeLock = new Object();
    private final Map<Integer, Pending> pending = new HashMap<Integer, Pending>();
    private final ExecutorService events = Executors.newSingleThreadExecutor();
    private int nextId = 1;
    private volatile OutputStream out;
    private volatile boolean connected;
    private volatile int caps;

    BtHost(Listener listener) { this.listener = listener; }

    boolean isConnected() { return connected; }
    int caps() { return caps; }

    void start() {
        Thread t = new Thread("aemu-bt-host") {
            @Override public void run() { loop(); }
        };
        t.setDaemon(true);
        t.start();
    }

    private void loop() {
        long backoff = 1000;
        while (true) {
            LocalSocket s = null;
            try {
                s = new LocalSocket();
                s.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.FILESYSTEM));
                InputStream in = s.getInputStream();
                OutputStream o = s.getOutputStream();
                out = o;
                int helloId = allocId();
                send(OP_HELLO, helloId, new Wr().bytes(new byte[]{'B', 'T', 'P', '1'}).u8(1).build());
                Frame f;
                do {
                    f = readFrame(in);
                    if (f == null) throw new EOFException("closed during HELLO");
                } while (!(f.op == OP_RESP && f.id == helloId));
                Rd r = new Rd(f.payload);
                int status = r.i32();
                if (status != OK) throw new IOException("HELLO refused: " + status);
                final int c = (int) r.u32();
                final boolean en = r.u8() != 0;
                final String name = r.string();
                caps = c;
                connected = true;
                backoff = 1000;
                events.execute(new Runnable() { public void run() { safe(listener, c, en, name); } });
                while ((f = readFrame(in)) != null) {
                    if (f.op == OP_RESP) complete(f);
                    else {
                        final int op = f.op;
                        final byte[] payload = f.payload;
                        events.execute(new Runnable() {
                            public void run() {
                                try { listener.onEvent(op, payload); }
                                catch (Throwable e) { BtLog.w("event 0x" + Integer.toHexString(op) + " failed", e); }
                            }
                        });
                    }
                }
            } catch (Throwable e) {
                if (connected) BtLog.i("host link lost: " + e);
            } finally {
                boolean was = connected;
                connected = false;
                out = null;
                failAll();
                if (s != null) try { s.close(); } catch (IOException ignored) { }
                if (was) events.execute(new Runnable() { public void run() { try { listener.onDisconnected(); } catch (Throwable ignored) { } } });
            }
            try { Thread.sleep(backoff); } catch (InterruptedException ignored) { }
            if (backoff < 5000) backoff += 1000;
        }
    }

    private static void safe(Listener l, int caps, boolean en, String name) {
        try { l.onConnected(caps, en, name); } catch (Throwable e) { BtLog.w("onConnected failed", e); }
    }

    /** Sends a request and waits for its reply; never throws, a dead link answers ENODEV. */
    Result request(int op, byte[] payload, long timeoutMs) {
        if (!connected) return new Result(ENODEV, new byte[0]);
        int id = allocId();
        Pending p = new Pending();
        synchronized (pending) { pending.put(id, p); }
        try {
            send(op, id, payload);
            if (!p.done.await(timeoutMs, TimeUnit.MILLISECONDS)) return new Result(ETIMEDOUT, new byte[0]);
            return p.result != null ? p.result : new Result(ENODEV, new byte[0]);
        } catch (Throwable e) {
            return new Result(EIO, new byte[0]);
        } finally {
            synchronized (pending) { pending.remove(id); }
        }
    }

    Result request(int op, byte[] payload) { return request(op, payload, 15000); }

    private int allocId() {
        synchronized (pending) { int id = nextId++; if (nextId == 0 || nextId == 0x7fffffff) nextId = 1; return id; }
    }

    private void send(int op, int id, byte[] payload) throws IOException {
        if (payload.length + 5 > MAX_FRAME) throw new IOException("frame too large");
        byte[] b = new byte[9 + payload.length];
        int len = 5 + payload.length;
        put32(b, 0, len);
        b[4] = (byte) op;
        put32(b, 5, id);
        System.arraycopy(payload, 0, b, 9, payload.length);
        OutputStream o = out;
        if (o == null) throw new IOException("not connected");
        synchronized (writeLock) { o.write(b); o.flush(); }
    }

    private void complete(Frame f) {
        Pending p;
        synchronized (pending) { p = pending.get(f.id); }
        if (p == null) return;
        try {
            Rd r = new Rd(f.payload);
            int status = r.i32();
            p.result = new Result(status, r.rest());
        } catch (Throwable e) {
            p.result = new Result(EIO, new byte[0]);
        }
        p.done.countDown();
    }

    private void failAll() {
        synchronized (pending) {
            for (Pending p : pending.values()) { p.result = new Result(ENODEV, new byte[0]); p.done.countDown(); }
        }
    }

    private static final class Frame {
        final int op, id;
        final byte[] payload;
        Frame(int op, int id, byte[] payload) { this.op = op; this.id = id; this.payload = payload; }
    }

    private static Frame readFrame(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) return null;
        byte[] head = new byte[4];
        head[0] = (byte) first;
        readFully(in, head, 1, 3);
        long len = get32(head, 0) & 0xffffffffL;
        if (len < 5 || len > MAX_FRAME) throw new IOException("bad frame length " + len);
        byte[] body = new byte[(int) len];
        readFully(in, body, 0, body.length);
        byte[] payload = new byte[body.length - 5];
        System.arraycopy(body, 5, payload, 0, payload.length);
        return new Frame(body[0] & 255, get32(body, 1), payload);
    }

    private static void readFully(InputStream in, byte[] b, int off, int n) throws IOException {
        int done = 0;
        while (done < n) {
            int k = in.read(b, off + done, n - done);
            if (k < 0) throw new EOFException("stream ended inside a frame");
            done += k;
        }
    }

    static void put32(byte[] b, int at, int v) {
        for (int i = 0; i < 4; i++) b[at + i] = (byte) (v >>> (8 * i));
    }

    static int get32(byte[] b, int at) {
        int v = 0;
        for (int i = 0; i < 4; i++) v |= (b[at + i] & 255) << (8 * i);
        return v;
    }

    /** Little-endian payload reader; underflow throws so a malformed event is dropped, not half-applied. */
    static final class Rd {
        private final byte[] d;
        private int pos;
        Rd(byte[] d) { this.d = d; }
        int remaining() { return d.length - pos; }
        private void need(int n) { if (n < 0 || n > remaining()) throw new IllegalArgumentException("truncated payload"); }
        int u8() { need(1); return d[pos++] & 255; }
        int u16() { need(2); int v = (d[pos] & 255) | ((d[pos + 1] & 255) << 8); pos += 2; return v; }
        short i16() { return (short) u16(); }
        long u32() { need(4); long v = get32(d, pos) & 0xffffffffL; pos += 4; return v; }
        int i32() { need(4); int v = get32(d, pos); pos += 4; return v; }
        byte[] bytes(int n) { need(n); byte[] r = new byte[n]; System.arraycopy(d, pos, r, 0, n); pos += n; return r; }
        byte[] rest() { return bytes(remaining()); }
        String string() {
            int n = u16();
            try { return new String(bytes(n), "UTF-8"); } catch (java.io.UnsupportedEncodingException e) { return ""; }
        }
        UUID uuid() {
            byte[] raw = bytes(16);
            long hi = 0, lo = 0;
            for (int i = 0; i < 8; i++) { hi = (hi << 8) | (raw[i] & 255); lo = (lo << 8) | (raw[8 + i] & 255); }
            return new UUID(hi, lo);
        }
    }

    /** Little-endian payload builder; strings are cut at the protocol limit on a byte boundary. */
    static final class Wr {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();
        Wr u8(int v) { o.write(v & 255); return this; }
        Wr u16(int v) { o.write(v & 255); o.write((v >>> 8) & 255); return this; }
        Wr u32(long v) { for (int i = 0; i < 4; i++) o.write((int) (v >>> (8 * i)) & 255); return this; }
        Wr bytes(byte[] b) { o.write(b, 0, b.length); return this; }
        Wr string(String s) {
            byte[] raw;
            try { raw = s.getBytes("UTF-8"); } catch (java.io.UnsupportedEncodingException e) { raw = new byte[0]; }
            int n = Math.min(raw.length, 255);
            u16(n); o.write(raw, 0, n);
            return this;
        }
        Wr uuid(UUID u) {
            long hi = u.getMostSignificantBits(), lo = u.getLeastSignificantBits();
            for (int i = 7; i >= 0; i--) o.write((int) (hi >>> (8 * i)) & 255);
            for (int i = 7; i >= 0; i--) o.write((int) (lo >>> (8 * i)) & 255);
            return this;
        }
        byte[] build() { return o.toByteArray(); }
    }
}
