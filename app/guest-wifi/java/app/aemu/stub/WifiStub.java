/* Modified for AEmulator Plus, 2026-11-01: Android 8 - 17. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.stub;

import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Guest process of the emulated Wi-Fi (started by the host before zygote, like the Bluetooth service):
 *  - serves "wifi" (IWifiManager, see {@link WifiService}),
 *  - Android 5.0+: registers the connection as a Wi-Fi network with ConnectivityService ({@link WifiNet}, {@link WifiNet2}).
 *
 * Android 8+ (the engine with the Treble guests): the network is registered once the system has booted (as aemu-net of aemu-stubs.jar
 * does, vendors' additions to ConnectivityService are not ready earlier), and when it cannot be registered as a Wi-Fi network it is
 * registered as the Ethernet placeholder, so the guest never ends up without internet because of this service.
 *
 * Usage: app_process -Djava.class.path=/system/framework/aemu-wifi.jar /system/bin app.aemu.stub.WifiStub
 */
public final class WifiStub {
    private static WifiLink net;

    private WifiStub() { }

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.flush();
        }
    }


    private static IBinder getService(String name) {
        try {
            return (IBinder) Refl.callStatic(Refl.cls("android.os.ServiceManager"), "getService", name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static IBinder checkService(String name) {
        try {
            return (IBinder) Refl.callStatic(Refl.cls("android.os.ServiceManager"), "checkService", name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isOurs(IBinder b, WifiService svc) {
        if (b == svc) return true;
        if (b == null) return false;
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            return b.transact(WifiService.PROBE, data, reply, 0) && reply.readInt() == WifiService.PROBE_ANSWER;
        } catch (Throwable t) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void addService(String name, IBinder b) {
        Refl.callStatic(Refl.cls("android.os.ServiceManager"), "addService", name, b);
    }

    private static void run() throws Exception {
        Looper.prepare();
        final Looper looper = Looper.myLooper();
        final WifiConfig cfg = WifiConfig.load();
        if (cfg == null) {
            Refl.log("no settings file, nothing to do");
            return;
        }
        WifiService made = null;
        try {
            made = new WifiService(cfg, looper);
        } catch (Throwable e) {
            // (8+: IWifiManager of the guest could not be read; the network below is still registered, as Wi-Fi without the manager)
            Refl.log("no IWifiManager service: " + e);
            e.printStackTrace(System.out);
            System.out.flush();
        }
        final WifiService svc = made;
        final Handler main = new Handler(looper);
        final boolean agent = Build.VERSION.SDK_INT >= 21;
        final LegacyWifi legacy = svc != null && Build.VERSION.SDK_INT < 21 ? new LegacyWifi(cfg, svc) : null;
        if (svc != null) svc.setLegacy(legacy);

        if (agent && svc != null) {
            svc.setListener(new WifiService.Listener() {
                @Override public void onConnectionChanged(final boolean connected) {
                    main.post(new Runnable() { @Override public void run() { applyConnection(looper, svc, cfg, connected); } });
                }
            });
        }

        Thread t = new Thread("aemu-wifi-register") {
            @Override public void run() {
                try {
                    watch(svc, main, looper, cfg, agent, legacy);
                } catch (Throwable e) {
                    e.printStackTrace(System.out);
                    System.out.flush();
                }
            }
        };
        t.setDaemon(true);
        t.start();
        Looper.loop();
    }

    // IBinder of ConnectivityService, once system_server has published it
    private static volatile IBinder connectivity;

    private static boolean bootCompleted() {
        try {
            Object v = Refl.callStatic(Refl.cls("android.os.SystemProperties"), "get", "sys.boot_completed");
            return "1".equals(v);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Registers "wifi" and keeps it ours: system_server may add its own WifiService later, which would replace ours. */
    private static void watch(final WifiService svc, Handler main, final Looper looper, final WifiConfig cfg, boolean agent, LegacyWifi legacy) throws Exception {
        // 8+ boots slower under the translator: ConnectivityService can take minutes to show up
        final boolean modern = Build.VERSION.SDK_INT >= 26;
        long agentDeadline = System.currentTimeMillis() + (modern ? 900000L : 180000L);
        boolean agentDone = !agent;
        int takeovers = 0;
        int refused = 0;
        // Gingerbread (API 9-10): ConnectivityService creates the framework's WifiService itself and does
        // ServiceManager.addService("wifi") in its constructor. That servicemanager refuses a second registration
        // ("ALREADY REGISTERED", EPERM -> SecurityException in system_server), so if we own "wifi" first, system_server dies in
        // ConnectivityService.<init>. Here the name is left to the framework; the state still reaches Settings and the status
        // bar through the broadcasts of LegacyWifi.
        final boolean leaveName = Build.VERSION.SDK_INT < 14;
        if (leaveName) Refl.log("Gingerbread: \"wifi\" is left to the framework's own service (its servicemanager refuses a second one)");
        for (;;) {
            if (svc != null && !leaveName && refused < 5 && !isOurs(checkService("wifi"), svc)) {
                try {
                    addService("wifi", svc);
                    if (takeovers++ == 0) Refl.log("service \"wifi\" registered");
                    else if (takeovers <= 3) Refl.log("service \"wifi\" taken back from the framework's own");
                } catch (Throwable e) {
                    // a refused registration must not end the watcher (it also registers the network and publishes the state)
                    if (++refused <= 5) Refl.log("service \"wifi\" refused: " + e);
                }
            }
            if (!agentDone) {
                IBinder cs = connectivity != null ? connectivity : getService("connectivity");
                if (cs != null && (!modern || bootCompleted())) {
                    connectivity = cs;
                    agentDone = true;
                    main.post(new Runnable() { @Override public void run() { applyConnection(looper, svc, cfg, svc == null || svc.isConnected()); } });
                } else if (System.currentTimeMillis() > agentDeadline) {
                    Refl.log("ConnectivityService did not appear, no Wi-Fi network registered");
                    agentDone = true;
                }
            }
            if (legacy != null) legacy.tick(checkService("connectivity") != null);
            Thread.sleep(agentDone ? 1000L : 500L);
        }
    }

    /** On the main thread: bring the registered network in line with the connection state. */
    private static void applyConnection(Looper looper, WifiService svc, WifiConfig cfg, boolean connected) {
        if (connectivity == null) return; // registered once ConnectivityService is up (see watch)
        try {
            if (connected && net == null) {
                Object info = svc != null ? svc.wifiInfo() : null;
                try {
                    net = WifiNet.register(looper, connectivity, cfg, WifiNet.Identity.WIFI, info);
                } catch (Throwable t) {
                    if (Build.VERSION.SDK_INT < 26) throw t;
                    // 8+: not as a Wi-Fi network; the guest must not lose its internet over that
                    Refl.log("registering as Wi-Fi failed (" + t + "), registering the Ethernet placeholder instead");
                    t.printStackTrace(System.out);
                    net = WifiNet.register(looper, connectivity, cfg, WifiNet.Identity.ETHERNET, null);
                }
            } else if (!connected && net != null) {
                net.disconnect();
                net = null;
            }
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.flush();
        }
    }
}
