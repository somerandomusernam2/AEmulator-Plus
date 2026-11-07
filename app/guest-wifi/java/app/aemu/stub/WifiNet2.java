/* Modified for AEmulator Plus, 2026-11-01: Android 11 - 17. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.stub;

import android.content.Context;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.os.Looper;

/**
 * The emulated Wi-Fi network for guests whose NetworkAgent has no constructor with a NetworkInfo (Android 11/12 and newer): the agent
 * is made from a NetworkAgentConfig, then registered and marked connected. Same steps as NetAgent2 in aemu-stubs.jar, which
 * does it for the Ethernet placeholder.
 */
final class WifiNet2 extends NetworkAgent implements WifiLink {
    private WifiNet2(Context c, Looper l, NetworkCapabilities nc, LinkProperties lp, NetworkAgentConfig cfg, WifiNet.Identity id) {
        super(c, l, id == WifiNet.Identity.WIFI ? "AEmuWifi" : "AEmuNet", nc, lp, 60, cfg, null);
    }

    @Override
    protected void unwanted() {
        Refl.log("the system no longer wants the network");
    }

    @Override
    public void disconnect() {
        try {
            unregister();
        } catch (Throwable t) {
            Refl.log("disconnect: " + t);
        }
    }

    static WifiNet2 start(Context ctx, Looper l, NetworkCapabilities nc, LinkProperties lp, WifiNet.Identity id) throws Exception {
        // (required of a default network since 9 and 12, and not there to add before)
        for (int cap : new int[] { 18 /* NOT_ROAMING */, 20 /* NOT_CONGESTED */, 21 /* NOT_SUSPENDED */, 28 /* NOT_VCN_MANAGED */ }) {
            Refl.call(nc, "addCapability", Integer.valueOf(cap));
        }
        Object b = Refl.newNoArg("android.net.NetworkAgentConfig$Builder");
        if (b == null) throw new IllegalStateException("no NetworkAgentConfig.Builder");
        Refl.call(b, "setLegacyType", Integer.valueOf(id.type));
        Refl.call(b, "setLegacyTypeName", id.legacyName);
        NetworkAgentConfig cfg = (NetworkAgentConfig) Refl.call(b, "build");
        if (cfg == null) throw new IllegalStateException("NetworkAgentConfig.Builder.build failed");
        WifiNet2 a = new WifiNet2(ctx, l, nc, lp, cfg, id);
        a.register();
        a.markConnected();
        return a;
    }
}
