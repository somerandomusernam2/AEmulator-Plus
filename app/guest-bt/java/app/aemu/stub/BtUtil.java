package app.aemu.stub;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;

/** Hidden-API plumbing the service needs: the activity manager (broadcasts), the service manager, device parcels. */
final class BtUtil {
    static final String PERM_BLUETOOTH = "android.permission.BLUETOOTH";
    private static Object am;
    private static Method broadcast;
    private static boolean amFailed;

    private BtUtil() { }

    static void addService(String name, IBinder binder) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        sm.getMethod("addService", String.class, IBinder.class).invoke(null, name, binder);
    }

    /**
     * BluetoothDevice's constructor calls BluetoothDevice.getService(), which would bind back to this very service.
     * Hand it our IBluetooth first so building devices (for replies and for incoming arguments) never loops through binder.
     */
    static void primeDeviceService(IBinder core) {
        try {
            java.lang.reflect.Field f = BluetoothDevice.class.getDeclaredField("sService");
            f.setAccessible(true);
            if (f.get(null) == null) f.set(null, android.bluetooth.IBluetooth.Stub.asInterface(core));
        } catch (Throwable e) {
            BtLog.w("cannot prime BluetoothDevice.sService", e);
        }
    }

    static BluetoothDevice device(String address) {
        Parcel p = Parcel.obtain();
        try {
            p.writeString(address);
            p.setDataPosition(0);
            return BluetoothDevice.CREATOR.createFromParcel(p);
        } finally {
            p.recycle();
        }
    }

    static BluetoothClass deviceClass(int cls) {
        Parcel p = Parcel.obtain();
        try {
            p.writeInt(cls);
            p.setDataPosition(0);
            return BluetoothClass.CREATOR.createFromParcel(p);
        } finally {
            p.recycle();
        }
    }

    static String address(BluetoothDevice d) { return d.getAddress().toUpperCase(); }

    /** Protected broadcasts need a system/root caller; the service runs as the guest's root, like `am broadcast`. */
    static synchronized void broadcast(Intent intent, String permission) {
        if (amFailed) return;
        try {
            if (am == null) {
                am = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
                if (am == null) return;    // activity manager not up yet; the caller may retry
                for (Method m : am.getClass().getMethods()) {
                    if (m.getName().equals("broadcastIntent") && m.getParameterTypes().length == 12) { broadcast = m; break; }
                }
                if (broadcast == null) { amFailed = true; BtLog.i("no IActivityManager.broadcastIntent(12 args); broadcasts disabled"); return; }
            }
            // (caller, intent, resolvedType, resultTo, resultCode, resultData, map, requiredPermission, appOp, serialized, sticky, userId)
            broadcast.invoke(am, null, intent, null, null, 0, null, null, permission, -1, false, false, -1);
        } catch (Throwable e) {
            am = null;
            Throwable c = e.getCause() != null ? e.getCause() : e;
            BtLog.w("broadcast " + intent.getAction() + " failed", c);
        }
    }
}
