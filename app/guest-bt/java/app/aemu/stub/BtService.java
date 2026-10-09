package app.aemu.stub;

import android.os.Looper;

/**
 * Entry point: app_process ... app.aemu.stub.BtService bluetooth_manager
 * Registers the guest Bluetooth manager and keeps a connection to the host bridge (/dev/aemu_bluetooth).
 * Built for the Android 4.4 (API 19) Wear image; other API levels keep the placeholder BtStub.
 */
public final class BtService {
    private BtService() { }

    public static void main(String[] args) throws Exception {
        String name = args.length > 0 ? args[0] : "bluetooth_manager";
        Looper.prepare();
        BtCore core = new BtCore();
        BtUtil.primeDeviceService(core);
        BtUtil.addService(name, new BtManager(core));
        BtLog.i("service " + name + " registered (host bridge client, API 19)");
        core.start();
        Looper.loop();
    }
}
