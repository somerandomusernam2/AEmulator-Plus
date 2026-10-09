package app.aemu.stub;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothGattCallback;
import android.bluetooth.IBluetoothGattServerCallback;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IBluetoothGatt for the 4.4 client API (BluetoothAdapter.startLeScan, BluetoothDevice.connectGatt) on top of the host's
 * LE scan and GATT client. Attribute ids that the host assigns at discovery double as the "instance ids" the 4.4
 * framework wants, so a characteristic's instance id is its host attribute id. GATT server and advertising are not
 * bridged: those calls are accepted and do nothing.
 */
final class BtGatt extends IBluetoothGatt.Stub {
    private static final int GATT_ERROR = 133;
    private static final int GATT_LOCAL_TERMINATED = 22;
    private static final int KIND_SERVICE = 1, KIND_CHAR = 2, KIND_DESC = 3;
    private static final int STATE_CONNECTED = 2;
    private static final int TYPE_PRIMARY = 0;

    private static final class Attr {
        int id, kind, parent;
        long props;
        UUID uuid;
    }

    private static final class Conn {
        final int host;
        final String address;
        final Client client;
        final Map<Integer, Attr> attrs = new LinkedHashMap<Integer, Attr>();
        Conn(int host, String address, Client client) { this.host = host; this.address = address; this.client = client; }
    }

    private final class Client implements IBinder.DeathRecipient {
        final int id;
        final IBluetoothGattCallback cb;
        boolean scanning;
        UUID[] filter;
        final Map<String, Conn> conns = new HashMap<String, Conn>();
        Client(int id, IBluetoothGattCallback cb) { this.id = id; this.cb = cb; }
        @Override public void binderDied() { unregisterClient(id); }
    }

    private final BtCore core;
    private final Object lock = new Object();
    private final Map<Integer, Client> clients = new HashMap<Integer, Client>();
    private final Map<Integer, Conn> byHost = new HashMap<Integer, Conn>();
    private final ExecutorService callbacks = Executors.newSingleThreadExecutor();
    private int nextClient = 1;

    BtGatt(BtCore core) { this.core = core; }

    private interface Call { void run(IBluetoothGattCallback cb) throws RemoteException; }

    /** Callbacks go out in order on their own thread, so neither a binder thread nor the host reader waits on an app. */
    private void deliver(final Client c, final Call call) {
        callbacks.execute(new Runnable() {
            public void run() {
                try { call.run(c.cb); }
                catch (RemoteException e) { unregisterClient(c.id); }
                catch (Throwable e) { BtLog.w("gatt callback failed", e); }
            }
        });
    }

    private Client client(int id) { synchronized (lock) { return clients.get(id); } }

    private Conn conn(int clientIf, String address) {
        synchronized (lock) {
            Client c = clients.get(clientIf);
            return c == null ? null : c.conns.get(address.toUpperCase());
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // LE scan

    @Override public void registerClient(ParcelUuid appId, IBluetoothGattCallback callback) {
        if (callback == null) return;
        final Client c;
        synchronized (lock) { c = new Client(nextClient++, callback); clients.put(c.id, c); }
        try { callback.asBinder().linkToDeath(c, 0); } catch (RemoteException e) { unregisterClient(c.id); return; }
        deliver(c, new Call() { public void run(IBluetoothGattCallback cb) throws RemoteException { cb.onClientRegistered(0, c.id); } });
    }

    @Override public void unregisterClient(int clientIf) {
        Client c;
        synchronized (lock) { c = clients.remove(clientIf); }
        if (c == null) return;
        try { c.cb.asBinder().unlinkToDeath(c, 0); } catch (Throwable ignored) { }
        boolean wasScanning;
        List<Conn> conns;
        synchronized (lock) {
            wasScanning = c.scanning;
            c.scanning = false;
            conns = new ArrayList<Conn>(c.conns.values());
            c.conns.clear();
            for (Conn k : conns) byHost.remove(k.host);
        }
        for (Conn k : conns) core.host.request(BtHost.OP_GATT_CLOSE, new BtHost.Wr().u32(k.host).build());
        if (wasScanning) core.leClientDelta(-1);
    }

    @Override public void startScan(int appIf, boolean isServer) { scan(appIf, null); }
    @Override public void startScanWithUuids(int appIf, boolean isServer, ParcelUuid[] ids) {
        UUID[] f = null;
        if (ids != null && ids.length > 0) {
            f = new UUID[ids.length];
            for (int i = 0; i < ids.length; i++) f[i] = ids[i].getUuid();
        }
        scan(appIf, f);
    }

    private void scan(int appIf, UUID[] filter) {
        Client c = client(appIf);
        if (c == null || !core.isOn() || !core.has(BtCore.CAP_LE_SCAN)) return;
        boolean first;
        synchronized (lock) { first = !c.scanning; c.scanning = true; c.filter = filter; }
        if (first) core.leClientDelta(+1);
    }

    @Override public void stopScan(int appIf, boolean isServer) {
        Client c = client(appIf);
        if (c == null) return;
        boolean was;
        synchronized (lock) { was = c.scanning; c.scanning = false; }
        if (was) core.leClientDelta(-1);
    }

    void leResult(final String addr, final int rssi, final byte[] record) {
        List<Client> targets = new ArrayList<Client>();
        synchronized (lock) {
            for (Client c : clients.values()) if (c.scanning && (c.filter == null || advHasAny(record, c.filter))) targets.add(c);
        }
        for (Client c : targets) deliver(c, new Call() {
            public void run(IBluetoothGattCallback cb) throws RemoteException { cb.onScanResult(addr, rssi, record); }
        });
    }

    /** True when the advertising record lists at least one of the wanted service UUIDs (16/32/128-bit lists). */
    static boolean advHasAny(byte[] rec, UUID[] wanted) {
        int i = 0;
        while (i + 1 < rec.length) {
            int len = rec[i] & 255;
            if (len == 0 || i + 1 + len > rec.length) break;
            int type = rec[i + 1] & 255;
            int size = type == 0x02 || type == 0x03 ? 2 : type == 0x04 || type == 0x05 ? 4 : type == 0x06 || type == 0x07 ? 16 : 0;
            if (size > 0) {
                for (int p = i + 2; p + size <= i + 1 + len; p += size) {
                    UUID u = uuidFromLe(rec, p, size);
                    for (UUID w : wanted) if (w.equals(u)) return true;
                }
            }
            i += len + 1;
        }
        return false;
    }

    private static UUID uuidFromLe(byte[] b, int at, int size) {
        if (size == 16) {
            long hi = 0, lo = 0;
            for (int k = 0; k < 8; k++) { lo = (lo << 8) | (b[at + 7 - k] & 255); hi = (hi << 8) | (b[at + 15 - k] & 255); }
            return new UUID(hi, lo);
        }
        long v = 0;
        for (int k = size - 1; k >= 0; k--) v = (v << 8) | (b[at + k] & 255);
        return new UUID((v << 32) | 0x1000L, 0x800000805F9B34FBL);
    }

    // ---------------------------------------------------------------------------------------------------------
    // connections

    @Override public void clientConnect(final int clientIf, final String address, boolean isDirect) {
        final Client c = client(clientIf);
        if (c == null) return;
        final String addr = address.toUpperCase();
        if (!core.isOn() || !core.has(BtCore.CAP_GATT)) { failConnect(c, addr); return; }
        synchronized (lock) {
            Conn existing = c.conns.get(addr);
            if (existing != null) {
                deliver(c, new Call() { public void run(IBluetoothGattCallback cb) throws RemoteException {
                    cb.onClientConnectionState(0, clientIf, true, addr); } });
                return;
            }
        }
        BtHost.Result res = core.host.request(BtHost.OP_GATT_OPEN, new BtHost.Wr().string(addr).build());
        if (!res.ok() || res.data.length < 4) { failConnect(c, addr); return; }
        int hostConn = BtHost.get32(res.data, 0);
        Conn k = new Conn(hostConn, addr, c);
        synchronized (lock) {
            if (clients.get(clientIf) != c) { core.host.request(BtHost.OP_GATT_CLOSE, new BtHost.Wr().u32(hostConn).build()); return; }
            c.conns.put(addr, k);
            byHost.put(hostConn, k);
        }
    }

    private void failConnect(Client c, final String addr) {
        final int id = c.id;
        deliver(c, new Call() { public void run(IBluetoothGattCallback cb) throws RemoteException {
            cb.onClientConnectionState(GATT_ERROR, id, false, addr); } });
    }

    @Override public void clientDisconnect(final int clientIf, final String address) {
        final Client c = client(clientIf);
        if (c == null) return;
        final String addr = address.toUpperCase();
        Conn k;
        synchronized (lock) { k = c.conns.remove(addr); if (k != null) byHost.remove(k.host); }
        if (k != null) core.host.request(BtHost.OP_GATT_CLOSE, new BtHost.Wr().u32(k.host).build());
        // the host reports nothing for a local close, but the framework waits for this callback
        deliver(c, new Call() { public void run(IBluetoothGattCallback cb) throws RemoteException {
            cb.onClientConnectionState(0, clientIf, false, addr); } });
    }

    /** The phone's adapter went off or the link to the bridge dropped: every link is gone. */
    void hostLost() {
        List<Conn> all;
        synchronized (lock) {
            all = new ArrayList<Conn>(byHost.values());
            byHost.clear();
            for (Client c : clients.values()) c.conns.clear();
        }
        for (final Conn k : all) {
            final int id = k.client.id;
            deliver(k.client, new Call() { public void run(IBluetoothGattCallback cb) throws RemoteException {
                cb.onClientConnectionState(GATT_LOCAL_TERMINATED, id, false, k.address); } });
        }
    }

    @Override public void refreshDevice(int clientIf, String address) { }

    @Override public java.util.List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> out = new ArrayList<BluetoothDevice>();
        boolean wantConnected = false;
        if (states != null) for (int s : states) if (s == STATE_CONNECTED) wantConnected = true;
        if (!wantConnected) return out;
        List<String> seen = new ArrayList<String>();
        synchronized (lock) {
            for (Conn k : byHost.values()) if (!seen.contains(k.address)) seen.add(k.address);
        }
        for (String a : seen) out.add(BtUtil.device(a));
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------
    // discovery and attribute access

    @Override public void discoverServices(int clientIf, String address) {
        final Conn k = conn(clientIf, address);
        if (k == null) return;
        BtHost.Result res = core.host.request(BtHost.OP_GATT_DISCOVER, new BtHost.Wr().u32(k.host).build());
        if (!res.ok()) searchComplete(k, GATT_ERROR);
    }

    private void searchComplete(final Conn k, final int status) {
        deliver(k.client, new Call() { public void run(IBluetoothGattCallback cb) throws RemoteException {
            cb.onSearchComplete(k.address, status); } });
    }

    @Override public void readCharacteristic(int clientIf, String address, int srvcType, int srvcInstanceId, ParcelUuid srvcId,
                                             int charInstanceId, ParcelUuid charId, int authReq) {
        Conn k = conn(clientIf, address);
        if (k == null) return;
        BtHost.Result res = core.host.request(BtHost.OP_GATT_READ, new BtHost.Wr().u32(k.host).u16(charInstanceId).build());
        if (!res.ok()) attrResult(k, charInstanceId, GATT_ERROR, new byte[0], true, false);
    }

    @Override public void readDescriptor(int clientIf, String address, int srvcType, int srvcInstanceId, ParcelUuid srvcId,
                                         int charInstanceId, ParcelUuid charId, int descrInstanceId, ParcelUuid descrUuid, int authReq) {
        Conn k = conn(clientIf, address);
        if (k == null) return;
        BtHost.Result res = core.host.request(BtHost.OP_GATT_READ, new BtHost.Wr().u32(k.host).u16(descrInstanceId).build());
        if (!res.ok()) attrResult(k, descrInstanceId, GATT_ERROR, new byte[0], true, false);
    }

    @Override public void writeCharacteristic(int clientIf, String address, int srvcType, int srvcInstanceId, ParcelUuid srvcId,
                                              int charInstanceId, ParcelUuid charId, int writeType, int authReq, byte[] value) {
        write(clientIf, address, charInstanceId, writeType, value);
    }

    @Override public void writeDescriptor(int clientIf, String address, int srvcType, int srvcInstanceId, ParcelUuid srvcId,
                                          int charInstanceId, ParcelUuid charId, int descrInstanceId, ParcelUuid descrId,
                                          int writeType, int authReq, byte[] value) {
        write(clientIf, address, descrInstanceId, writeType, value);
    }

    private void write(int clientIf, String address, int attr, int writeType, byte[] value) {
        Conn k = conn(clientIf, address);
        if (k == null) return;
        // Android: 1 no response, 2 default, 4 signed. The host knows the first two; signed goes as a normal write.
        int type = writeType == 1 ? 1 : 2;
        byte[] v = value == null ? new byte[0] : value;
        if (v.length > 512) { attrResult(k, attr, GATT_ERROR, null, false, false); return; }
        BtHost.Result res = core.host.request(BtHost.OP_GATT_WRITE,
                new BtHost.Wr().u32(k.host).u16(attr).u8(type).bytes(v).build());
        if (!res.ok()) attrResult(k, attr, GATT_ERROR, null, false, false);
    }

    @Override public void registerForNotification(int clientIf, String address, int srvcType, int srvcInstanceId, ParcelUuid srvcId,
                                                  int charInstanceId, ParcelUuid charId, boolean enable) {
        Conn k = conn(clientIf, address);
        if (k == null) return;
        core.host.request(BtHost.OP_GATT_NOTIFY, new BtHost.Wr().u32(k.host).u16(charInstanceId).u8(enable ? 1 : 0).build());
    }

    @Override public void beginReliableWrite(int clientIf, String address) { }

    @Override public void endReliableWrite(int clientIf, String address, boolean execute) {
        final Conn k = conn(clientIf, address);
        if (k == null) return;
        deliver(k.client, new Call() { public void run(IBluetoothGattCallback cb) throws RemoteException {
            cb.onExecuteWrite(k.address, GATT_ERROR); } });
    }

    @Override public void readRemoteRssi(int clientIf, String address) {
        final Conn k = conn(clientIf, address);
        if (k == null) return;
        deliver(k.client, new Call() { public void run(IBluetoothGattCallback cb) throws RemoteException {
            cb.onReadRemoteRssi(k.address, 0, GATT_ERROR); } });
    }

    // ---------------------------------------------------------------------------------------------------------
    // host events

    void onEvent(int op, BtHost.Rd r) {
        int hostConn = (int) r.u32();
        Conn k;
        synchronized (lock) { k = byHost.get(hostConn); }
        if (k == null) return;
        switch (op) {
            case BtHost.EV_GATT_STATE: {
                final boolean connected = r.u8() != 0;
                final int status = r.i32();
                final int id = k.client.id;
                final String addr = k.address;
                if (!connected) {
                    // the remote dropped us: release the host handle; the app reconnects with a new clientConnect
                    synchronized (lock) { k.client.conns.remove(addr); byHost.remove(hostConn); }
                    core.host.request(BtHost.OP_GATT_CLOSE, new BtHost.Wr().u32(hostConn).build());
                }
                deliver(k.client, new Call() { public void run(IBluetoothGattCallback cb) throws RemoteException {
                    cb.onClientConnectionState(status, id, connected, addr); } });
                break;
            }
            case BtHost.EV_GATT_SERVICES: services(k, r.i32(), r); break;
            case BtHost.EV_GATT_READ: {
                int attr = r.u16();
                int status = r.i32();
                attrResult(k, attr, fix(status), r.rest(), true, true);
                break;
            }
            case BtHost.EV_GATT_WRITE: {
                int attr = r.u16();
                attrResult(k, attr, fix(r.i32()), null, false, true);
                break;
            }
            case BtHost.EV_GATT_CHANGED: notify(k, r.u16(), r.rest()); break;
            default: break;      // MTU: the 4.4 client API has no way to ask for or report it
        }
    }

    private static int fix(int status) { return status < 0 ? GATT_ERROR : status; }

    private void services(Conn k, int status, BtHost.Rd r) {
        if (status != 0) { searchComplete(k, fix(status)); return; }
        int n = r.u16();
        List<Attr> list = new ArrayList<Attr>();
        synchronized (lock) {
            k.attrs.clear();
            for (int i = 0; i < n; i++) {
                Attr a = new Attr();
                a.id = r.u16(); a.kind = r.u8(); a.uuid = r.uuid(); a.parent = r.u16(); a.props = r.u32();
                k.attrs.put(a.id, a);
                list.add(a);
            }
        }
        final String addr = k.address;
        for (final Attr s : list) {
            if (s.kind != KIND_SERVICE) continue;
            deliver(k.client, new Call() { public void run(IBluetoothGattCallback cb) throws RemoteException {
                cb.onGetService(addr, TYPE_PRIMARY, s.id, new ParcelUuid(s.uuid)); } });
            for (final Attr c : list) {
                if (c.kind != KIND_CHAR || c.parent != s.id) continue;
                deliver(k.client, new Call() { public void run(IBluetoothGattCallback cb) throws RemoteException {
                    cb.onGetCharacteristic(addr, TYPE_PRIMARY, s.id, new ParcelUuid(s.uuid), c.id, new ParcelUuid(c.uuid), (int) c.props); } });
                for (final Attr d : list) {
                    if (d.kind != KIND_DESC || d.parent != c.id) continue;
                    deliver(k.client, new Call() { public void run(IBluetoothGattCallback cb) throws RemoteException {
                        cb.onGetDescriptor(addr, TYPE_PRIMARY, s.id, new ParcelUuid(s.uuid), c.id, new ParcelUuid(c.uuid),
                                d.id, new ParcelUuid(d.uuid)); } });
                }
            }
        }
        searchComplete(k, 0);
    }

    /** Read/write result for a characteristic or descriptor, found by its host attribute id. */
    private void attrResult(final Conn k, int attrId, final int status, final byte[] value, final boolean isRead, boolean fromHost) {
        final Attr a, c, s;
        synchronized (lock) {
            a = k.attrs.get(attrId);
            Attr cc = a == null ? null : a.kind == KIND_DESC ? k.attrs.get(a.parent) : a;
            c = cc;
            s = cc == null ? null : k.attrs.get(cc.parent);
        }
        if (a == null || c == null || s == null) return;
        final String addr = k.address;
        final byte[] v = value == null ? new byte[0] : value;
        final boolean desc = a.kind == KIND_DESC;
        deliver(k.client, new Call() { public void run(IBluetoothGattCallback cb) throws RemoteException {
            ParcelUuid su = new ParcelUuid(s.uuid), cu = new ParcelUuid(c.uuid);
            if (desc) {
                ParcelUuid du = new ParcelUuid(a.uuid);
                if (isRead) cb.onDescriptorRead(addr, status, TYPE_PRIMARY, s.id, su, c.id, cu, a.id, du, v);
                else cb.onDescriptorWrite(addr, status, TYPE_PRIMARY, s.id, su, c.id, cu, a.id, du);
            } else {
                if (isRead) cb.onCharacteristicRead(addr, status, TYPE_PRIMARY, s.id, su, c.id, cu, v);
                else cb.onCharacteristicWrite(addr, status, TYPE_PRIMARY, s.id, su, c.id, cu);
            }
        } });
    }

    private void notify(Conn k, int attrId, final byte[] value) {
        final Attr c, s;
        synchronized (lock) {
            c = k.attrs.get(attrId);
            s = c == null ? null : k.attrs.get(c.parent);
        }
        if (c == null || s == null || c.kind != KIND_CHAR) return;
        final String addr = k.address;
        deliver(k.client, new Call() { public void run(IBluetoothGattCallback cb) throws RemoteException {
            cb.onNotify(addr, TYPE_PRIMARY, s.id, new ParcelUuid(s.uuid), c.id, new ParcelUuid(c.uuid), value); } });
    }

    // ---------------------------------------------------------------------------------------------------------
    // GATT server and advertising: not bridged

    @Override public void startAdvertising(int appIf) { }
    @Override public void stopAdvertising() { }
    @Override public boolean setAdvServiceData(byte[] serviceData) { return false; }
    @Override public byte[] getAdvServiceData() { return null; }
    @Override public boolean setAdvManufacturerCodeAndData(int manufactureCode, byte[] manufacturerData) { return false; }
    @Override public byte[] getAdvManufacturerData() { return null; }
    @Override public java.util.List<ParcelUuid> getAdvServiceUuids() { return new ArrayList<ParcelUuid>(); }
    @Override public void removeAdvManufacturerCodeAndData(int manufacturerCode) { }
    @Override public boolean isAdvertising() { return false; }

    @Override public void registerServer(ParcelUuid appId, IBluetoothGattServerCallback callback) { }
    @Override public void unregisterServer(int serverIf) { }
    @Override public void serverConnect(int servertIf, String address, boolean isDirect) { }
    @Override public void serverDisconnect(int serverIf, String address) { }
    @Override public void beginServiceDeclaration(int serverIf, int srvcType, int srvcInstanceId, int minHandles, ParcelUuid srvcId,
                                                  boolean advertisePreferred) { }
    @Override public void addIncludedService(int serverIf, int srvcType, int srvcInstanceId, ParcelUuid srvcId) { }
    @Override public void addCharacteristic(int serverIf, ParcelUuid charId, int properties, int permissions) { }
    @Override public void addDescriptor(int serverIf, ParcelUuid descId, int permissions) { }
    @Override public void endServiceDeclaration(int serverIf) { }
    @Override public void removeService(int serverIf, int srvcType, int srvcInstanceId, ParcelUuid srvcId) { }
    @Override public void clearServices(int serverIf) { }
    @Override public void sendResponse(int serverIf, String address, int requestId, int status, int offset, byte[] value) { }
    @Override public void sendNotification(int serverIf, String address, int srvcType, int srvcInstanceId, ParcelUuid srvcId,
                                           int charInstanceId, ParcelUuid charId, boolean confirm, byte[] value) { }
}
