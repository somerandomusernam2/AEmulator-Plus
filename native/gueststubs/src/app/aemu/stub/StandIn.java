package app.aemu.stub;

import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import java.lang.reflect.Method;

/**
 * Binder services a firmware's own programs call without checking that they exist, where the program that would
 * register them needs the phone's hardware (Samsung's SurfaceFlinger tells "Exynos.HWCService", which lives in the
 * Exynos display HAL, that the boot is over, and dies on a null pointer without it).
 *
 * app_process /system/bin app.aemu.stub.StandIn <service name>...
 *
 * Every call is answered "no exception" and an empty reply. A name somebody has registered is left alone.
 *
 * One exception: "android.security.keystore" (the legacy IKeystoreService, which a firmware without a keymaster HAL
 * never gets from the real keystore daemon). A client reads its answer with reply.readInt(), and the empty reply
 * reads as 0, which KeyStore.state() does not know: it throws AssertionError(1) (mError, NO_ERROR), LockSettingsService
 * calls it from its own HandlerThread when the user unlocks, the uncaught error kills system_server and zygote exits
 * with 137 right after the boot completed. getState is therefore answered with a real ResponseCode, NO_ERROR (1),
 * which KeyStore.state() maps to UNLOCKED (nothing is stored here, so there is nothing that could be locked).
 */
public final class StandIn {
    /** ResponseCode.NO_ERROR of keystore: android.security.KeyStore.NO_ERROR, State.UNLOCKED when it answers getState. */
    private static final int KEYSTORE_NO_ERROR = 1;

    /** The transaction code of IKeystoreService.getState: the first method of the interface in every release (6.0 to 12), read from the framework when it lets us. */
    private static int keystoreGetState() {
        try {
            java.lang.reflect.Field f = Class.forName("android.security.keystore.IKeystoreService$Stub").getDeclaredField("TRANSACTION_getState");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return IBinder.FIRST_CALL_TRANSACTION;
        }
    }

    public static void main(String[] args) throws Exception {
        Looper.prepare();
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method check = sm.getMethod("checkService", String.class);
        Method add = sm.getMethod("addService", String.class, IBinder.class);
        for (final String name : args) {
            try {
                if (check.invoke(null, name) != null) continue;
                final int getState = "android.security.keystore".equals(name) ? keystoreGetState() : -1;
                add.invoke(null, name, new Binder() {
                    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                        if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(name); return true; }
                        if (reply != null) {
                            reply.writeNoException();
                            if (code == getState) reply.writeInt(KEYSTORE_NO_ERROR);
                        }
                        return true;
                    }
                });
                System.out.println("aemu-standin: " + name);
            } catch (Throwable t) { t.printStackTrace(System.out); }
        }
        System.out.flush();
        Looper.loop();
    }
}
