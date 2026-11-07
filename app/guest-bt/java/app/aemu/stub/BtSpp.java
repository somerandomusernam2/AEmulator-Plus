package app.aemu.stub;

import android.net.LocalSocket;
import android.os.ParcelFileDescriptor;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Client RFCOMM for BluetoothSocket.connect(). On 4.4 the framework hands connect() a socket fd and expects, on it,
 * first the channel number (native int) and then a 16-byte signal: short size(16), byte[6] address, int channel,
 * int status (0 = connected). Reads and writes after that are the raw stream. The fd is one end of a socketpair; the
 * other end lives here and is pumped to and from the host's SPP channel.
 */
final class BtSpp {
    private static final int CHUNK = 16384;

    private static final class Chan {
        volatile int id;
        final FileDescriptor fd;
        final FileInputStream in;
        final FileOutputStream out;
        final LinkedBlockingQueue<byte[]> toApp = new LinkedBlockingQueue<byte[]>();
        volatile boolean closed;
        Chan(int id, FileDescriptor fd) { this.id = id; this.fd = fd; this.in = new FileInputStream(fd); this.out = new FileOutputStream(fd); }
    }

    /** FileInputStream(FileDescriptor) never closes the descriptor on 4.4, so go through libcore. */
    private static final class Fd {
        private static Object os;
        private static Method shutdown, close;
        static synchronized void init() throws Exception {
            if (os != null) return;
            Object o = Class.forName("libcore.io.Libcore").getField("os").get(null);
            Class<?> osClass = Class.forName("libcore.io.Os");
            shutdown = osClass.getMethod("shutdown", FileDescriptor.class, int.class);
            close = Class.forName("libcore.io.IoUtils").getMethod("close", FileDescriptor.class);
            os = o;
        }
        static Object os() throws Exception { init(); return os; }
        static void close(FileDescriptor fd) {
            try { init(); } catch (Exception e) { return; }
            try { shutdown.invoke(os, fd, 2 /* SHUT_RDWR */); } catch (Throwable ignored) { }
            try { close.invoke(null, fd); } catch (Throwable ignored) { }
        }
    }

    private static final byte[] END = new byte[0];

    private final BtHost host;
    private final Object lock = new Object();
    private final Map<Integer, Chan> chans = new HashMap<Integer, Chan>();
    private final Map<Integer, List<byte[]>> early = new HashMap<Integer, List<byte[]>>();
    private final List<Chan> opening = new ArrayList<Chan>();

    BtSpp(BtHost host) { this.host = host; }

    ParcelFileDescriptor connect(final String address, final UUID uuid, final boolean secure) throws Exception {
        FileDescriptor[] pair = pair();
        final FileDescriptor mine = pair[0];
        final FileDescriptor theirs = pair[1];
        ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(theirs);
        Fd.close(theirs);                                     // our copy of the app's end

        final Chan pending = new Chan(-1, mine);
        synchronized (lock) { opening.add(pending); }
        Thread t = new Thread("aemu-bt-spp-open") {
            @Override public void run() { open(pending, address, uuid, secure); }
        };
        t.setDaemon(true);
        t.start();
        return pfd;
    }

    /** A connected AF_UNIX stream pair: [0] stays here, [1] goes to the app. */
    private static FileDescriptor[] pair() throws Exception {
        FileDescriptor a = new FileDescriptor();
        FileDescriptor b = new FileDescriptor();
        Method socketpair = Class.forName("libcore.io.Os").getMethod("socketpair", int.class, int.class, int.class,
                FileDescriptor.class, FileDescriptor.class);
        socketpair.invoke(Fd.os(), 1 /* AF_UNIX */, 1 /* SOCK_STREAM */, 0, a, b);
        return new FileDescriptor[]{a, b};
    }

    // ---------------------------------------------------------------------------------------------------------
    // server side: BluetoothServerSocket.bindListen() / accept()
    //
    // createSocketChannel hands the app a control socket. bindListen reads the channel number from it; every accept()
    // reads a 16-byte signal from it and receives the connection as an SCM_RIGHTS descriptor attached to that signal.

    private static final class Server {
        final int id;
        final FileDescriptor fd;
        final LocalSocket sock;
        volatile boolean closed;
        Server(int id, FileDescriptor fd, LocalSocket sock) { this.id = id; this.fd = fd; this.sock = sock; }
    }

    private final Map<Integer, Server> servers = new HashMap<Integer, Server>();

    ParcelFileDescriptor listen(String name, UUID uuid, boolean secure) throws Exception {
        FileDescriptor[] pair = pair();
        final FileDescriptor mine = pair[0];
        final FileDescriptor theirs = pair[1];
        BtHost.Result res = host.request(BtHost.OP_SPP_LISTEN,
                new BtHost.Wr().string(name == null ? "" : name).uuid(uuid).u8(secure ? 1 : 0).build(), 15000);
        if (!res.ok() || res.data.length < 4) {
            Fd.close(mine); Fd.close(theirs);
            return null;
        }
        int id = BtHost.get32(res.data, 0);
        final Server s;
        try {
            LocalSocket sock = LocalSocket.class.getConstructor(FileDescriptor.class).newInstance(mine);
            s = new Server(id, mine, sock);
            sock.getOutputStream().write(le32(id));            // the "channel" bindListen() waits for
            sock.getOutputStream().flush();
            synchronized (lock) { servers.put(id, s); }
            ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(theirs);
            Fd.close(theirs);
            Thread t = new Thread("aemu-bt-spp-listen") {
                @Override public void run() {
                    try { new FileInputStream(mine).read(); } catch (Throwable ignored) { }   // returns when the app closes its end
                    closeServer(s, true);
                }
            };
            t.setDaemon(true);
            t.start();
            return pfd;
        } catch (Throwable e) {
            host.request(BtHost.OP_SPP_UNLISTEN, new BtHost.Wr().u32(id).build());
            Fd.close(mine); Fd.close(theirs);
            throw e instanceof Exception ? (Exception) e : new RuntimeException(e);
        }
    }

    private void closeServer(Server s, boolean tellHost) {
        synchronized (lock) { if (s.closed) return; s.closed = true; servers.remove(s.id); }
        if (tellHost) host.request(BtHost.OP_SPP_UNLISTEN, new BtHost.Wr().u32(s.id).build());
        Fd.close(s.fd);
    }

    private void accepted(int listener, int channel, String address) {
        Server s;
        synchronized (lock) { s = servers.get(listener); }
        if (s == null || s.closed) { host.request(BtHost.OP_SPP_CLOSE, new BtHost.Wr().u32(channel).build()); return; }
        FileDescriptor[] pair;
        try { pair = pair(); }
        catch (Exception e) { BtLog.w("accept: socketpair failed", e); host.request(BtHost.OP_SPP_CLOSE, new BtHost.Wr().u32(channel).build()); return; }
        Chan c = new Chan(channel, pair[0]);
        List<byte[]> backlog;
        synchronized (lock) { chans.put(channel, c); backlog = early.remove(channel); }
        if (backlog != null) c.toApp.addAll(backlog);
        startPumps(c);
        try {
            synchronized (s) {
                s.sock.setFileDescriptorsForSend(new FileDescriptor[]{pair[1]});
                s.sock.getOutputStream().write(signal(address, channel));
                s.sock.getOutputStream().flush();
            }
        } catch (Throwable e) {
            BtLog.w("accept: handing the connection to the app failed", e);
            drop(c, true);
        } finally {
            Fd.close(pair[1]);                                // the app now owns its own copy
        }
    }

    private void open(Chan c, String address, UUID uuid, boolean secure) {
        BtHost.Result res = host.request(BtHost.OP_SPP_OPEN,
                new BtHost.Wr().string(address).uuid(uuid).u8(secure ? 1 : 0).build(), 40000);
        synchronized (lock) { opening.remove(c); }
        int channel = -1;
        if (res.ok() && res.data.length >= 4) channel = BtHost.get32(res.data, 0);
        if (channel <= 0) {
            try { c.out.write(le32(-1)); } catch (IOException ignored) { }
            closeQuietly(c);
            return;
        }
        final Chan chan = c;
        chan.id = channel;
        List<byte[]> backlog;
        synchronized (lock) { chans.put(channel, chan); backlog = early.remove(channel); }
        try {
            chan.out.write(le32(channel));
            chan.out.write(signal(address, channel));
            chan.out.flush();
        } catch (IOException e) {
            drop(chan, true);
            return;
        }
        if (backlog != null) chan.toApp.addAll(backlog);
        startPumps(chan);
    }

    private void startPumps(final Chan c) {
        Thread w = new Thread("aemu-bt-spp-w") {
            @Override public void run() {
                try {
                    while (true) {
                        byte[] b = c.toApp.take();
                        if (b == END) break;
                        c.out.write(b);
                        c.out.flush();
                    }
                } catch (Throwable ignored) { }
                closeQuietly(c);
            }
        };
        Thread r = new Thread("aemu-bt-spp-r") {
            @Override public void run() {
                byte[] buf = new byte[CHUNK];
                try {
                    int n;
                    while (!c.closed && (n = c.in.read(buf)) > 0) {
                        byte[] part = new byte[n];
                        System.arraycopy(buf, 0, part, 0, n);
                        if (!send(c, part)) break;
                    }
                } catch (Throwable ignored) { }
                drop(c, true);
            }
        };
        w.setDaemon(true); r.setDaemon(true);
        w.start(); r.start();
    }

    private boolean send(Chan c, byte[] data) {
        for (int tries = 0; tries < 100 && !c.closed; tries++) {
            BtHost.Result res = host.request(BtHost.OP_SPP_WRITE, new BtHost.Wr().u32(c.id).bytes(data).build());
            if (res.status == BtHost.OK) return true;
            if (res.status != BtHost.EAGAIN) return false;
            try { Thread.sleep(20); } catch (InterruptedException e) { return false; }
        }
        return false;
    }

    void onEvent(int op, BtHost.Rd r) {
        if (op == BtHost.EV_SPP_ACCEPT) {
            int listener = (int) r.u32();
            int ch = (int) r.u32();
            accepted(listener, ch, r.string());
            return;
        }
        int channel = (int) r.u32();
        if (op == BtHost.EV_SPP_DATA) {
            byte[] data = r.rest();
            Chan c;
            synchronized (lock) {
                c = chans.get(channel);
                if (c == null) {
                    // data raced ahead of the open reply; hold it until the channel is registered
                    List<byte[]> l = early.get(channel);
                    if (l == null && early.size() < 8) { l = new ArrayList<byte[]>(); early.put(channel, l); }
                    if (l != null && l.size() < 256) l.add(data);
                    return;
                }
            }
            c.toApp.add(data);
        } else {
            Chan c;
            synchronized (lock) { c = chans.remove(channel); early.remove(channel); }
            if (c != null) { c.toApp.add(END); }
        }
    }

    void closeAll() {
        List<Server> srv;
        synchronized (lock) { srv = new ArrayList<Server>(servers.values()); }
        for (Server s : srv) closeServer(s, false);
        List<Chan> all;
        synchronized (lock) { all = new ArrayList<Chan>(chans.values()); all.addAll(opening); chans.clear(); early.clear(); opening.clear(); }
        for (Chan c : all) { c.toApp.add(END); closeQuietly(c); }
    }

    private void drop(Chan c, boolean tellHost) {
        boolean known;
        synchronized (lock) { known = chans.remove(c.id) != null; }
        if (known && tellHost) host.request(BtHost.OP_SPP_CLOSE, new BtHost.Wr().u32(c.id).build());
        c.toApp.add(END);
        closeQuietly(c);
    }

    private static void closeQuietly(Chan c) {
        if (c.closed) return;
        c.closed = true;
        Fd.close(c.fd);
    }

    private static byte[] le32(int v) {
        byte[] b = new byte[4];
        BtHost.put32(b, 0, v);
        return b;
    }

    private static byte[] signal(String address, int channel) {
        byte[] s = new byte[16];
        s[0] = 16; s[1] = 0;                                  // size, native (little) endian
        String[] parts = address.split(":");
        for (int i = 0; i < 6 && i < parts.length; i++) s[2 + i] = (byte) Integer.parseInt(parts[i], 16);
        BtHost.put32(s, 8, channel);
        BtHost.put32(s, 12, 0);                               // status: connected
        return s;
    }
}
