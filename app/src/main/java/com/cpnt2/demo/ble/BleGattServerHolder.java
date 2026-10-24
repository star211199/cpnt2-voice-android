package com.cpnt2.demo.ble;

import android.bluetooth.BluetoothDevice;
import android.util.Log;

/**
 * GATT Server 全局持有者 (单例)
 *
 * 解决问题：DiscoverActivity 启动 GATT Server 后，当对方连接进来时
 * 需要切换到 CallActivity，但不能销毁 GATT Server（否则连接断开）。
 *
 * 工作流程：
 *   1. DiscoverActivity 启动 BleGattServer → 存入 holder
 *   2. 对方连接进来 → onCentralConnected → DiscoverActivity 启动 CallActivity
 *   3. CallActivity 从 holder 取出已有的 GATT Server，只更新 listener
 *   4. CallActivity 销毁时停止 GATT Server 并清除 holder
 */
public class BleGattServerHolder {

    private static final String TAG = "GattServerHolder";

    private static BleGattServerHolder instance;

    private BleGattServer gattServer;
    private BluetoothDevice connectedDevice;
    private boolean incomingConnection = false;

    private BleGattServerHolder() {}

    public static synchronized BleGattServerHolder getInstance() {
        if (instance == null) {
            instance = new BleGattServerHolder();
        }
        return instance;
    }

    /**
     * 存入 GATT Server (由 DiscoverActivity 调用)
     */
    public void setGattServer(BleGattServer server) {
        this.gattServer = server;
        Log.i(TAG, "GATT Server 已存入 holder");
    }

    /**
     * 获取并移除 GATT Server (由 CallActivity 调用)
     * CallActivity 取出后会接管 GATT Server 的生命周期
     */
    public BleGattServer takeGattServer() {
        BleGattServer server = this.gattServer;
        this.gattServer = null;
        if (server != null) {
            Log.i(TAG, "GATT Server 已从 holder 取出");
        }
        return server;
    }

    public boolean hasGattServer() {
        return gattServer != null;
    }

    public void setIncomingConnection(BluetoothDevice device) {
        this.incomingConnection = true;
        this.connectedDevice = device;
        Log.i(TAG, "收到来电连接: " + (device != null ? device.getAddress() : "null"));
    }

    public boolean isIncomingConnection() {
        return incomingConnection;
    }

    public BluetoothDevice getConnectedDevice() {
        return connectedDevice;
    }

    public void clearIncomingConnection() {
        this.incomingConnection = false;
        this.connectedDevice = null;
    }

    /**
     * 清理 (CallActivity 销毁时调用)
     */
    public void clear() {
        if (gattServer != null) {
            gattServer.stop();
            gattServer = null;
        }
        incomingConnection = false;
        connectedDevice = null;
        Log.i(TAG, "holder 已清理");
    }
}
