/* Modified for AEmulator Super Plus, 2026-10-11: hidden setDetailedState reached by reflection (builds with the public SDK).
 * Modified for AEmulator Plus, 2026-11-01: Android 8 - 17 (NetworkAgent without NetworkInfo, Ethernet fallback). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.stub;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Looper;

import java.net.InetAddress;

/**
 * Android 5.0+: the guest's connection as a Wi-Fi network in ConnectivityService (the 5.0–7.x placeholder in
 * aemu-stubs.jar registers an Ethernet one). Same layout, but TYPE_WIFI / TRANSPORT_WIFI on "wlan0".
 */
final class WifiNet extends NetworkAgent implements WifiLink {
    /** NetworkInfo.setDetailedState is hidden in the public SDK; same call through reflection */
    private static void setDetailedState(NetworkInfo ni, NetworkInfo.DetailedState st) throws Exception {
        NetworkInfo.class.getMethod("setDetailedState", NetworkInfo.DetailedState.class, String.class, String.class).invoke(ni, st, null, null);
    }
    private static final int TYPE_WIFI = 1;
    private static final int TRANSPORT_WIFI = 1;
    private static final int TYPE_ETHERNET = 9;
    private static final int TRANSPORT_ETHERNET = 3;

    /** What the network calls itself in ConnectivityService: Wi-Fi, or (fallback of 8+, see WifiStub) the old Ethernet placeholder. */
    static final class Identity {
        final int type, transport;
        final String legacyName, iface;
        private Identity(int type, int transport, String legacyName, String iface) {
            this.type = type; this.transport = transport; this.legacyName = legacyName; this.iface = iface;
        }
        static final Identity WIFI = new Identity(TYPE_WIFI, TRANSPORT_WIFI, "WIFI", "wlan0");
        static final Identity ETHERNET = new Identity(TYPE_ETHERNET, TRANSPORT_ETHERNET, "ETHERNET", "eth0");
    }
    // INTERNET, NOT_RESTRICTED, TRUSTED, NOT_VPN, NOT_METERED
    private static final int[] CAPABILITIES = { 12, 13, 14, 15, 11 };

    private final NetworkInfo info;

    private WifiNet(Looper looper, Context ctx, NetworkInfo ni, NetworkCapabilities nc, LinkProperties lp, Identity id) {
        super(looper, ctx, id == Identity.WIFI ? "AEmuWifi" : "AEmuNet", ni, nc, lp, 60);
        info = ni;
    }

    @Override
    protected void unwanted() {
        Refl.log("the system no longer wants the Wi-Fi network");
    }

    /** Tells ConnectivityService the network is gone; an agent cannot come back afterwards, a new one is registered instead. */
    @Override
    public void disconnect() {
        try {
            setDetailedState(info, NetworkInfo.DetailedState.DISCONNECTED);
            sendNetworkInfo(info);
        } catch (Throwable t) {
            Refl.log("disconnect: " + t);
        }
    }

    /** A Context whose only service is the given ConnectivityManager (NetworkAgent asks its Context for it). */
    private static final class Ctx extends ContextWrapper {
        Object cm;

        Ctx() {
            super(null);
        }

        @Override public Context getApplicationContext() { return this; }
        @Override public String getPackageName() { return "android"; }
        @Override public Object getSystemService(String name) { return "connectivity".equals(name) ? cm : null; }
        // (16: the agent asks for the manager by its class, when it is made)
        /* API 23+ */ public String getSystemServiceName(Class<?> c) { return "android.net.ConnectivityManager".equals(c.getName()) ? "connectivity" : null; }
    }

    /** The ConnectivityManager needs a Context to be built and the Context needs the manager, hence the setter. */
    private static Ctx contextFor(android.os.IBinder binder) throws Exception {
        Class<?> iface = Class.forName("android.net.IConnectivityManager");
        Object service = Class.forName("android.net.IConnectivityManager$Stub")
                .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
        Ctx ctx = new Ctx();
        ctx.cm = Refl.create("android.net.ConnectivityManager", new Class<?>[] { Context.class, iface }, ctx, service);
        if (ctx.cm == null) ctx.cm = Refl.create("android.net.ConnectivityManager", new Class<?>[] { iface }, service);
        if (ctx.cm == null) throw new IllegalStateException("no ConnectivityManager");
        return ctx;
    }

    private static InetAddress addr(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    /**
     * Registers the network described by [cfg]; must be called on the thread of [looper].
     * [wifiInfo] (may be null) is what apps get as the transport info of the network on Android 10+ (the status bar of 12+
     * reads the signal level from it).
     */
    static WifiLink register(Looper looper, android.os.IBinder connectivity, WifiConfig cfg, Identity id, Object wifiInfo) throws Exception {
        Context ctx = contextFor(connectivity);

        NetworkInfo ni = (NetworkInfo) Refl.create("android.net.NetworkInfo",
                new Class<?>[] { int.class, int.class, String.class, String.class },
                Integer.valueOf(id.type), Integer.valueOf(0), id.legacyName, "");
        if (ni == null) throw new IllegalStateException("no NetworkInfo");
        setDetailedState(ni, NetworkInfo.DetailedState.CONNECTED);
        Refl.call(ni, "setIsAvailable", Boolean.TRUE);

        NetworkCapabilities nc = (NetworkCapabilities) Refl.newNoArg("android.net.NetworkCapabilities");
        Refl.call(nc, "addTransportType", Integer.valueOf(id.transport));
        for (int c : CAPABILITIES) Refl.call(nc, "addCapability", Integer.valueOf(c));
        Refl.call(nc, "setLinkUpstreamBandwidthKbps", Integer.valueOf(100000));
        Refl.call(nc, "setLinkDownstreamBandwidthKbps", Integer.valueOf(100000));
        if (id == Identity.WIFI && wifiInfo != null) Refl.call(nc, "setTransportInfo", wifiInfo); // 10+; absent before: ignored

        LinkProperties lp = (LinkProperties) Refl.newNoArg("android.net.LinkProperties");
        Refl.call(lp, "setInterfaceName", id.iface);
        Object la = Refl.create("android.net.LinkAddress", new Class<?>[] { InetAddress.class, int.class },
                addr(cfg.ip), Integer.valueOf(cfg.prefix));
        Refl.call(lp, "addLinkAddress", la);
        Object route = Refl.create("android.net.RouteInfo", new Class<?>[] { InetAddress.class }, addr(cfg.gateway));
        Refl.call(lp, "addRoute", route);
        Refl.call(lp, "addDnsServer", addr(cfg.dns1));
        Refl.call(lp, "addDnsServer", addr(cfg.dns2));

        WifiLink n;
        try {
            n = new WifiNet(looper, ctx, ni, nc, lp, id);
        } catch (NoSuchMethodError e) {
            // 11 and 12+: the constructor with a NetworkInfo is gone, the agent is made from a NetworkAgentConfig
            n = WifiNet2.start(ctx, looper, nc, lp, id);
        }
        Refl.log((id == Identity.WIFI ? "Wi-Fi" : "Ethernet (fallback)") + " network registered with ConnectivityService");
        return n;
    }
}
