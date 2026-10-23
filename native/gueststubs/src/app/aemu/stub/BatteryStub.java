package app.aemu.stub;

import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The "batteryproperties" binder service, for a firmware whose own healthd cannot be started here (a static 32-bit
 * /sbin/healthd is linked at 0x8000, an address qemu-arm cannot reserve on some phones). BatteryService (4.4+)
 * registers a listener with it and keeps the last BatteryProperties the listener got: without them it holds null
 * and the first isPowered() call (JobScheduler's battery controller) kills system_server.
 *
 * app_process /system/bin app.aemu.stub.BatteryStub
 *
 * The values are read from the guest's /sys/class/power_supply (the engine writes the phone's battery there) and
 * pushed to every registered listener whenever they change. A name somebody has registered is left alone.
 */
public final class BatteryStub {
    private static final String REG = "android.os.IBatteryPropertiesRegistrar";
    private static final String LIS = "android.os.IBatteryPropertiesListener";
    private static final String PS = "/sys/class/power_supply/";

    private static final List<IBinder> listeners = new ArrayList<IBinder>();
    private static String last = "";
    private static int level = 100;

    public static void main(String[] args) throws Exception {
        Looper.prepare();
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method check = sm.getMethod("checkService", String.class);
        Method add = sm.getMethod("addService", String.class, IBinder.class);
        if (check.invoke(null, "batteryproperties") != null) { System.out.println("aemu-battery: already registered"); return; }
        add.invoke(null, "batteryproperties", new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                switch (code) {
                    case INTERFACE_TRANSACTION:
                        if (reply != null) reply.writeString(REG);
                        return true;
                    case 1: {                                   // registerListener(IBatteryPropertiesListener)
                        data.enforceInterface(REG);
                        final IBinder l = data.readStrongBinder();
                        if (l != null) {
                            synchronized (listeners) { listeners.add(l); }
                            new Thread() { @Override public void run() { try { push(l, snapshot()); } catch (Throwable t) { } } }.start();
                        }
                        if (reply != null) reply.writeNoException();
                        return true;
                    }
                    case 2: {                                   // unregisterListener
                        data.enforceInterface(REG);
                        IBinder l = data.readStrongBinder();
                        synchronized (listeners) { listeners.remove(l); }
                        if (reply != null) reply.writeNoException();
                        return true;
                    }
                    case 3: {                                   // int getProperty(int id, out BatteryProperty prop)
                        data.enforceInterface(REG);
                        int id = data.readInt();
                        if (reply != null) {
                            reply.writeNoException();
                            if (id == 4) { reply.writeInt(0); reply.writeInt(1); reply.writeLong(level); }   // CAPACITY
                            else reply.writeInt(-19);                                                         // -ENODEV
                        }
                        return true;
                    }
                    default:
                        if (reply != null) reply.writeNoException();
                        return true;
                }
            }
        });
        System.out.println("aemu-battery: batteryproperties");
        System.out.flush();
        new Thread() {
            @Override public void run() {
                while (true) {
                    try {
                        Object p = snapshot();
                        String key = describe(p);
                        List<IBinder> copy;
                        synchronized (listeners) {
                            if (key.equals(last)) copy = null; else { last = key; copy = new ArrayList<IBinder>(listeners); }
                        }
                        if (copy != null) for (IBinder l : copy) push(l, p);
                        Thread.sleep(3000);
                    } catch (Throwable t) { try { Thread.sleep(3000); } catch (InterruptedException e) { return; } }
                }
            }
        }.start();
        Looper.loop();
    }

    private static String read(String rel, String def) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(PS + rel));
            try { String s = r.readLine(); return s == null ? def : s.trim(); } finally { r.close(); }
        } catch (Throwable t) { return def; }
    }

    private static int num(String rel, int def) {
        try { return Integer.parseInt(read(rel, "")); } catch (Throwable t) { return def; }
    }

    private static String describe(Object p) {
        StringBuilder b = new StringBuilder();
        for (Field f : p.getClass().getFields()) {
            try { b.append(f.getName()).append('=').append(f.get(p)).append(';'); } catch (Throwable t) { }
        }
        return b.toString();
    }

    private static void set(Object o, String name, Object v) {
        try {
            Field f = o.getClass().getField(name);
            Class<?> t = f.getType();
            if (t == boolean.class) f.setBoolean(o, ((Boolean) v).booleanValue());
            else if (t == int.class) f.setInt(o, ((Integer) v).intValue());
            else f.set(o, v);
        } catch (Throwable t) { }                               // a field this release has not got
    }

    /** an android.os.BatteryProperties filled from the guest's power_supply nodes */
    private static Object snapshot() throws Exception {
        Object p = Class.forName("android.os.BatteryProperties").newInstance();
        level = Math.max(0, Math.min(100, num("battery/capacity", 100)));
        String st = read("battery/status", "Full").toLowerCase();
        int status = st.startsWith("charg") ? 2 : st.startsWith("disch") ? 3 : st.startsWith("not") ? 4 : st.startsWith("full") ? 5 : 1;
        boolean ac = num("ac/online", 0) != 0, usb = num("usb/online", 0) != 0, wl = num("wireless/online", 0) != 0;
        int mv = num("battery/voltage_now", 4200000);
        if (mv > 100000) mv /= 1000;
        set(p, "chargerAcOnline", ac);
        set(p, "chargerUsbOnline", usb);
        set(p, "chargerWirelessOnline", wl);
        set(p, "maxChargingCurrent", 0);
        set(p, "batteryStatus", status);
        set(p, "batteryHealth", 2);                             // BATTERY_HEALTH_GOOD
        set(p, "batteryPresent", true);
        set(p, "batteryLevel", level);
        set(p, "batteryVoltage", mv);
        set(p, "batteryTemperature", num("battery/temp", 250));
        set(p, "batteryTechnology", read("battery/technology", "Li-ion"));
        return p;
    }

    private static void push(IBinder l, Object props) {
        Parcel d = Parcel.obtain();
        try {
            d.writeInterfaceToken(LIS);
            d.writeInt(1);
            props.getClass().getMethod("writeToParcel", Parcel.class, int.class).invoke(props, d, 0);
            l.transact(IBinder.FIRST_CALL_TRANSACTION, d, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable t) {
            synchronized (listeners) { listeners.remove(l); }
        } finally { d.recycle(); }
    }
}
