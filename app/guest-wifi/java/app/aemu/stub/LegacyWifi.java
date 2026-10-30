package app.aemu.stub;

import android.content.Intent;
import android.os.Build;
import android.os.Parcelable;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.net.InetAddress;

/**
 * Android 2.3 - 4.4 (before NetworkAgent): ConnectivityService learns about Wi-Fi from the sticky broadcasts the
 * framework's WifiStateMachine would send (WifiStateTracker listens for them), and Settings / the status bar read the
 * same ones. The stock state machine has no hardware to drive, so this publishes them for the emulated connection.
 *
 * Gingerbread (2.3, API 9-10) differs in two ways. WifiStateTracker there is the Wi-Fi network of ConnectivityService itself and
 * listens to nothing, so ConnectivityManager keeps reporting the radio network; only WifiManager, Settings and the status bar
 * follow the broadcasts. And the status bar takes its bar level only from RSSI_CHANGED (a connected NETWORK_STATE_CHANGED resets
 * it to zero), so that one is sent as well.
 * Called from the watcher thread once a second; it re-sends now and then because the stock service may overwrite them.
 */
final class LegacyWifi {
    private static final String WIFI_STATE = "android.net.wifi.WIFI_STATE_CHANGED";
    private static final String SUPPLICANT_CONN = "android.net.wifi.supplicant.CONNECTION_CHANGE";
    private static final String SUPPLICANT_STATE = "android.net.wifi.supplicant.STATE_CHANGE";
    private static final String NETWORK_STATE = "android.net.wifi.STATE_CHANGE";
    private static final String SCAN_RESULTS = "android.net.wifi.SCAN_RESULTS";
    private static final String RSSI_CHANGED = "android.net.wifi.RSSI_CHANGED";
    private static final int FLAG_REGISTERED_ONLY = 0x40000000;

    private final WifiConfig cfg;
    private final WifiService svc;
    private volatile boolean dirty = true;
    private volatile boolean scan;
    private long nextAt;
    private int sent;
    private int failures;
    private boolean lastEnabled = true;

    LegacyWifi(WifiConfig cfg, WifiService svc) {
        this.cfg = cfg;
        this.svc = svc;
    }

    /** The connection or the on/off state changed: publish on the next tick. */
    void changed() {
        dirty = true;
    }

    /** startScan(): tell Settings that results are ready. */
    void scanDone() {
        scan = true;
    }

    void tick(boolean connectivityUp) {
        if (!connectivityUp) return;
        long now = SystemClock.elapsedRealtime();
        Object am = Refl.callStatic(Refl.cls("android.app.ActivityManagerNative"), "getDefault");
        if (am == null) return;
        if (scan) {
            scan = false;
            send(am, new Intent(SCAN_RESULTS), false);
        }
        if (!dirty && now < nextAt) return;
        dirty = false;
        nextAt = now + (sent < 4 ? 6000L : 30000L);
        try {
            publish(am);
            if (sent++ == 0) Refl.log("legacy Wi-Fi state published (enabled=" + svc.isEnabled() + ", connected=" + svc.isConnected() + ")");
        } catch (Throwable t) {
            if (failures++ < 5) Refl.log("legacy publish failed: " + t);
        }
    }

    private void publish(Object am) throws Exception {
        boolean enabled = svc.isEnabled();
        boolean connected = svc.isConnected();

        Intent ws = new Intent(WIFI_STATE);
        ws.addFlags(FLAG_REGISTERED_ONLY);
        ws.putExtra("wifi_state", enabled ? 3 : 1);
        ws.putExtra("previous_wifi_state", enabled ? (lastEnabled ? 3 : 1) : 3);
        send(am, ws, true);
        lastEnabled = enabled;

        Intent sc = new Intent(SUPPLICANT_CONN);
        sc.addFlags(FLAG_REGISTERED_ONLY);
        sc.putExtra("connected", enabled);
        send(am, sc, true);

        Object ss = supplicantState(connected ? "COMPLETED" : enabled ? "DISCONNECTED" : "INACTIVE");
        if (ss instanceof Parcelable) {
            Intent si = new Intent(SUPPLICANT_STATE);
            si.addFlags(FLAG_REGISTERED_ONLY);
            si.putExtra("newState", (Parcelable) ss);
            send(am, si, true);
        }

        Intent ns = new Intent(NETWORK_STATE);
        ns.addFlags(FLAG_REGISTERED_ONLY);
        Object ni = networkInfo(enabled, connected);
        if (ni instanceof Parcelable) ns.putExtra("networkInfo", (Parcelable) ni);
        Object lp = linkProperties(connected);
        if (lp instanceof Parcelable) ns.putExtra("linkProperties", (Parcelable) lp);
        Object lc = Refl.newNoArg("android.net.LinkCapabilities");
        if (lc instanceof Parcelable) ns.putExtra("linkCapabilities", (Parcelable) lc);
        if (connected) {
            ns.putExtra("bssid", cfg.bssid);
            Object wi = svc.wifiInfo();
            if (wi instanceof Parcelable) ns.putExtra("wifiInfo", (Parcelable) wi);
        }
        send(am, ns, true);

        if (Build.VERSION.SDK_INT < 14 && connected) {
            Intent rs = new Intent(RSSI_CHANGED);
            rs.addFlags(FLAG_REGISTERED_ONLY);
            rs.putExtra("newRssi", cfg.rssi);
            send(am, rs, true);
        }
    }

    private static Object enumConst(String cls, String name) {
        try {
            return Class.forName(cls).getField(name).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object supplicantState(String name) {
        return enumConst("android.net.wifi.SupplicantState", name);
    }

    private Object networkInfo(boolean enabled, boolean connected) {
        Object ni = Refl.create("android.net.NetworkInfo", new Class<?>[] { int.class, int.class, String.class, String.class },
                Integer.valueOf(1), Integer.valueOf(0), "WIFI", "");
        if (ni == null) return null;
        Object ds = enumConst("android.net.NetworkInfo$DetailedState", connected ? "CONNECTED" : "DISCONNECTED");
        Refl.call(ni, "setDetailedState", ds, null, connected ? cfg.bssid : null);
        Refl.call(ni, "setIsAvailable", Boolean.valueOf(enabled));
        return ni;
    }

    private Object linkProperties(boolean connected) {
        Object lp = Refl.newNoArg("android.net.LinkProperties");
        if (lp == null || !connected) return lp;
        try {
            Refl.call(lp, "setInterfaceName", "wlan0");
            Object addr = Refl.create("android.net.LinkAddress", new Class<?>[] { InetAddress.class, int.class },
                    InetAddress.getByName(cfg.ip), Integer.valueOf(cfg.prefix));
            Refl.call(lp, "addLinkAddress", addr);
            Object route = Refl.create("android.net.RouteInfo", new Class<?>[] { InetAddress.class }, InetAddress.getByName(cfg.gateway));
            Refl.call(lp, "addRoute", route);
            Refl.call(lp, "addDns", InetAddress.getByName(cfg.dns1));
            Refl.call(lp, "addDns", InetAddress.getByName(cfg.dns2));
        } catch (Throwable t) {
            Refl.log("link properties: " + t);
        }
        return lp;
    }

    /** IActivityManager.broadcastIntent; the parameter list differs per release, so the arguments are filled by type. */
    private void send(Object am, Intent intent, boolean sticky) {
        try {
            Method m = null;
            for (Method c : am.getClass().getMethods()) {
                if (c.getName().equals("broadcastIntent")) { m = c; break; }
            }
            if (m == null) { if (failures++ < 5) Refl.log("no broadcastIntent"); return; }
            Class<?>[] p = m.getParameterTypes();
            int ints = 0;
            for (Class<?> c : p) if (c == int.class) ints++;
            Object[] a = new Object[p.length];
            int intIdx = 0, boolIdx = 0;
            for (int i = 0; i < p.length; i++) {
                if (p[i] == Intent.class) {
                    a[i] = intent;
                } else if (p[i] == boolean.class) {
                    a[i] = Boolean.valueOf(boolIdx++ == 1 && sticky); // (..., serialized, sticky)
                } else if (p[i] == int.class) {
                    // (resultCode [, appOp] [, userId]): resultCode 0, appOp none (-1), user 0
                    a[i] = Integer.valueOf(ints == 3 && intIdx == 1 ? -1 : 0);
                    intIdx++;
                } else {
                    a[i] = null;
                }
            }
            m.invoke(am, a);
        } catch (Throwable t) {
            if (failures++ < 5) Refl.log("broadcast " + intent.getAction() + " failed: " + t);
        }
    }
}
