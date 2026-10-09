package app.aemu.stub;

import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothManager;
import android.bluetooth.IBluetoothManagerCallback;
import android.bluetooth.IBluetoothStateChangeCallback;

/** "bluetooth_manager": what BluetoothAdapter.getDefaultAdapter() looks up. Thin; BtCore holds the state. */
final class BtManager extends IBluetoothManager.Stub {
    private final BtCore core;

    BtManager(BtCore core) { this.core = core; }

    @Override public IBluetooth registerAdapter(IBluetoothManagerCallback callback) { return core.registerAdapter(callback); }
    @Override public void unregisterAdapter(IBluetoothManagerCallback callback) { core.unregisterAdapter(callback); }
    @Override public void registerStateChangeCallback(IBluetoothStateChangeCallback callback) { core.registerStateChange(callback); }
    @Override public void unregisterStateChangeCallback(IBluetoothStateChangeCallback callback) { core.unregisterStateChange(callback); }
    @Override public boolean isEnabled() { return core.isEnabled(); }
    @Override public boolean enable() { return core.enable(); }
    @Override public boolean enableNoAutoConnect() { return core.enableNoAutoConnect(); }
    @Override public boolean disable(boolean persist) { return core.disable(); }
    @Override public IBluetoothGatt getBluetoothGatt() { return core.gatt; }
    @Override public String getAddress() { return core.getAddress(); }
    @Override public String getName() { return core.getName(); }
}
