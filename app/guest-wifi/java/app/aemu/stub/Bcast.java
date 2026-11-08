package app.aemu.stub;

import android.content.Intent;

import java.lang.reflect.Method;

/**
 * Sends a broadcast from the stub's own process, on any release: IActivityManager.broadcastIntentWithFeature (12+) or
 * broadcastIntent (older), with the arguments filled by type. Used for SCAN_RESULTS_AVAILABLE once a startScan() is done:
 * Settings and the setup wizard wait for it and otherwise report "Could not find network".
 */
final class Bcast {
    private static int failures;
    private static boolean logged;

    private Bcast() {}

    static void send(Intent intent) {
        try {
            Object am = Refl.callStatic(Refl.cls("android.app.ActivityManager"), "getService");
            if (am == null) am = Refl.callStatic(Refl.cls("android.app.ActivityManagerNative"), "getDefault");
            if (am == null) { if (failures++ < 5) Refl.log("broadcast: no activity manager"); return; }
            Method m = null;
            for (Method c : am.getClass().getMethods()) if (c.getName().equals("broadcastIntentWithFeature")) { m = c; break; }
            if (m == null) for (Method c : am.getClass().getMethods()) if (c.getName().equals("broadcastIntent")) { m = c; break; }
            if (m == null) { if (failures++ < 5) Refl.log("broadcast: no broadcastIntent"); return; }
            Class<?>[] p = m.getParameterTypes();
            int ints = 0;
            for (Class<?> c : p) if (c == int.class) ints++;
            Object[] a = new Object[p.length];
            int intIdx = 0, boolIdx = 0;
            for (int i = 0; i < p.length; i++) {
                if (p[i] == Intent.class) a[i] = intent;
                else if (p[i] == boolean.class) { boolIdx++; a[i] = Boolean.FALSE; }               // not serialized, not sticky
                else if (p[i] == int.class) { a[i] = Integer.valueOf(ints == 3 && intIdx == 1 ? -1 : 0); intIdx++; } // resultCode, appOp, userId
                else a[i] = null;
            }
            m.invoke(am, a);
            if (!logged) { logged = true; Refl.log("broadcast sent through " + m.getName() + ": " + intent.getAction()); }
        } catch (Throwable t) {
            if (failures++ < 5) Refl.log("broadcast " + intent.getAction() + " failed: " + t);
        }
    }
}
