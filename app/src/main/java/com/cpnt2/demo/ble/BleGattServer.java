package com.cpnt2.demo.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BLE GATT Server (Peripheral 端运行)
 *
 * 只负责 GATT Server 的 Service/Characteristic 管理，
 * 不负责广播（广播由 BleAdvertiser 统一处理）。
 *
 * 关键修复（第十轮）：
 *   1. addService 是异步操作，必须等 onServiceAdded 回调后才能认为 Service 就绪
 *   2. openGattServer 后延迟 300ms 再 addService，给蓝牙栈初始化时间
 *   3. onServiceAdded 超时后，不再用 getServices() 兜底（本地列表不代表已注册到蓝牙栈），
 *      而是关闭并重启整个 GATT Server 后重新 addService
 *   4. 最多重启 3 次，只有全部失败后才用 getServices() 作为最后手段
 *   5. 显式添加 CCCD Descriptor
 */
public class BleGattServer {

    private static final String TAG = "BleGattServer";

    /** onServiceAdded 超时时间 — 增大到 5 秒，给慢速 ROM 足够时间 */
    private static final long SERVICE_ADD_TIMEOUT_MS = 5000;
    /** GATT Server 重启最大次数（每次重启 = close + reopen + addService） */
    private static final int MAX_SERVICE_ADD_RETRY = 3;
    /** openGattServer 后延迟，让蓝牙栈完成初始化 */
    private static final long GATT_SERVER_INIT_DELAY = 300;
    /** 重启 GATT Server 前的延迟 */
    private static final long GATT_SERVER_RESTART_DELAY = 500;

    public interface ServerListener {
        void onCentralConnected(BluetoothDevice device);
        void onCentralDisconnected(BluetoothDevice device);
        void onAudioFrameReceived(BluetoothDevice device, byte[] encodedFrame, int codecId);
        void onControlReceived(BluetoothDevice device, byte subtype, byte[] payload);
        void onError(String msg);
        /** Service 已添加完成，可以启动广播了 */
        void onServiceReady();
    }

    private final Context context;
    private final BluetoothManager bluetoothManager;
    private final BluetoothAdapter adapter;
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic rxChar; // Central -> Peripheral
    private BluetoothGattCharacteristic txChar; // Peripheral -> Central
    private ServerListener listener;
    private boolean started = false;
    private boolean serviceReady = false;
    private boolean stopped = false;
    private int currentMtu = 23; // 默认 BLE ATT MTU
    private int serviceAddRetry = 0; // addService 重试计数
    private BluetoothGattService pendingService; // 用于重试 addService
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable serviceTimeoutRunnable;

    // 手动跟踪已连接设备 — 不依赖废弃的 getConnectedDevices()
    private final CopyOnWriteArrayList<BluetoothDevice> connectedDevices = new CopyOnWriteArrayList<>();

    public BleGattServer(Context context) {
        this.context = context;
        this.bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
    }

    public void setListener(ServerListener listener) {
        this.listener = listener;
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isServiceReady() {
        return serviceReady;
    }

    /**
     * 更新 listener 但不重启 server (用于 Activity 切换时)
     */
    public void updateListener(ServerListener listener) {
        this.listener = listener;
        Log.i(TAG, "listener 已更新");
    }

    @SuppressLint("MissingPermission")
    public void start() {
        if (started || adapter == null) return;
        if (!adapter.isEnabled()) {
            notifyError("蓝牙未开启");
            return;
        }

        stopped = false;
        serviceReady = false;
        serviceAddRetry = 0;

        // 创建 Service 和 Characteristic (只创建一次，重启时复用)
        createServiceConfig();

        // 打开 GATT Server
        openAndAddService();
    }

    /**
     * 创建 Service 配置（Service + Characteristics + Descriptor）
     */
    private void createServiceConfig() {
        if (pendingService != null) return;

        BluetoothGattService service = new BluetoothGattService(
                UUID.fromString(BleProtocol.SERVICE_UUID),
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        rxChar = new BluetoothGattCharacteristic(
                UUID.fromString(BleProtocol.CHAR_RX_UUID),
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        txChar = new BluetoothGattCharacteristic(
                UUID.fromString(BleProtocol.CHAR_TX_UUID),
                BluetoothGattCharacteristic.PROPERTY_READ
                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);

        // 显式添加 CCCD Descriptor
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                UUID.fromString(BleProtocol.DESC_UUID),
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        txChar.addDescriptor(cccd);

        service.addCharacteristic(rxChar);
        service.addCharacteristic(txChar);
        pendingService = service;

        Log.i(TAG, "Service 配置已创建: UUID=" + BleProtocol.SERVICE_UUID
                + " chars=" + service.getCharacteristics().size());
    }

    /**
     * 打开 GATT Server 并添加 Service
     * 延迟 GATT_SERVER_INIT_DELAY 后再 addService，给蓝牙栈初始化时间
     */
    @SuppressLint("MissingPermission")
    private void openAndAddService() {
        // 关闭可能残留的旧 GATT Server
        if (gattServer != null) {
            try { gattServer.clearServices(); gattServer.close(); } catch (Exception ignored) {}
            gattServer = null;
        }

        // 打开新的 GATT Server
        gattServer = bluetoothManager.openGattServer(context, gattCallback);
        if (gattServer == null) {
            notifyError("openGattServer 失败");
            return;
        }

        Log.i(TAG, "GATT Server 已打开，延迟 " + GATT_SERVER_INIT_DELAY
                + "ms 后 addService (让蓝牙栈初始化)");

        // 延迟后 addService
        handler.postDelayed(() -> {
            if (stopped) return;
            doAddService();
        }, GATT_SERVER_INIT_DELAY);
    }

    /**
     * 执行 addService 并设置超时监控
     */
    @SuppressLint("MissingPermission")
    private void doAddService() {
        if (stopped || gattServer == null || pendingService == null) return;

        // 清理可能残留的旧 service
        try { gattServer.clearServices(); } catch (Exception ignored) {}

        Log.i(TAG, "调用 addService (异步)... UUID=" + BleProtocol.SERVICE_UUID
                + " chars=" + pendingService.getCharacteristics().size()
                + " (尝试 " + (serviceAddRetry + 1) + "/" + (MAX_SERVICE_ADD_RETRY + 1) + ")");

        boolean addOk = gattServer.addService(pendingService);

        if (!addOk) {
            Log.e(TAG, "addService 返回 false");
            // addService 返回 false，尝试重启 GATT Server
            if (serviceAddRetry < MAX_SERVICE_ADD_RETRY) {
                serviceAddRetry++;
                Log.w(TAG, "addService 返回 false，重启 GATT Server 重试 ("
                        + serviceAddRetry + "/" + MAX_SERVICE_ADD_RETRY + ")");
                handler.postDelayed(this::openAndAddService, GATT_SERVER_RESTART_DELAY);
            } else {
                notifyError("addService 多次失败 (返回 false)，可能因 UUID 冲突或资源不足");
                try { gattServer.clearServices(); gattServer.close(); } catch (Exception ignored) {}
                gattServer = null;
            }
            return;
        }

        // 设置超时监控
        cancelServiceTimeout();
        serviceTimeoutRunnable = () -> {
            if (!serviceReady && !stopped) {
                Log.w(TAG, "onServiceAdded 超时未回调 (" + SERVICE_ADD_TIMEOUT_MS + "ms, retry="
                        + serviceAddRetry + "/" + MAX_SERVICE_ADD_RETRY + ")");
                handleServiceAddTimeout();
            }
        };
        handler.postDelayed(serviceTimeoutRunnable, SERVICE_ADD_TIMEOUT_MS);
    }

    /**
     * 处理 onServiceAdded 超时
     * 核心修复：不再用 getServices() 兜底启动广播
     * 如果 onServiceAdded 从未回调，说明蓝牙栈未注册 service，广播也无意义
     */
    @SuppressLint("MissingPermission")
    private void handleServiceAddTimeout() {
        if (serviceAddRetry < MAX_SERVICE_ADD_RETRY) {
            serviceAddRetry++;
            Log.w(TAG, "重启 GATT Server 并重新 addService ("
                    + serviceAddRetry + "/" + MAX_SERVICE_ADD_RETRY + ")");

            // 关闭当前 GATT Server
            if (gattServer != null) {
                try {
                    gattServer.clearServices();
                    gattServer.close();
                } catch (Exception ignored) {}
                gattServer = null;
            }

            // 延迟后重新打开并 addService
            handler.postDelayed(() -> {
                if (stopped) return;
                openAndAddService();
            }, GATT_SERVER_RESTART_DELAY);
        } else {
            // 所有重启都失败 — 不再启动广播！
            // 之前用 getServices() 验证会误判（本地列表 ≠ 蓝牙栈已注册）
            Log.e(TAG, "所有重启重试耗尽 (" + MAX_SERVICE_ADD_RETRY + " 次)，onServiceAdded 仍未回调");
            Log.e(TAG, "Service 未注册到蓝牙栈，广播无意义，不启动广播");

            // 打印本地 service 列表仅用于诊断
            try {
                if (gattServer != null) {
                    List<BluetoothGattService> services = gattServer.getServices();
                    Log.w(TAG, "诊断: 本地 service 列表 (不代表已注册到蓝牙栈): " + services.size());
                    for (BluetoothGattService s : services) {
                        Log.w(TAG, "  本地 service: " + s.getUuid()
                                + " chars=" + s.getCharacteristics().size());
                    }
                }
            } catch (Exception ignored) {}

            // 报告错误，不调用 onServiceReady
            notifyError("GATT Service 注册超时 (onServiceAdded 未回调)。\n"
                    + "请尝试：\n"
                    + "1. 关闭蓝牙再重新打开\n"
                    + "2. 重启 App\n"
                    + "3. 检查是否有其他蓝牙 App 占用");
        }
    }

    private void cancelServiceTimeout() {
        if (serviceTimeoutRunnable != null) {
            handler.removeCallbacks(serviceTimeoutRunnable);
            serviceTimeoutRunnable = null;
        }
    }

    /**
     * Service 添加完成（由 onServiceAdded 回调或超时兜底调用）
     */
    private void onServiceAddComplete(int status) {
        if (serviceReady) return; // 防止重复调用

        cancelServiceTimeout();

        if (status == BluetoothGatt.GATT_SUCCESS) {
            serviceReady = true;
            started = true;
            Log.i(TAG, "Service 已就绪 (onServiceAdded 确认)，GATT Server 可以接受连接");
            if (listener != null) listener.onServiceReady();
        } else {
            Log.e(TAG, "Service 添加失败 status=" + status);
            notifyError("Service 添加失败 status=" + status
                    + "，请尝试：1.重启手机蓝牙 2.重启 App 3.检查是否有其他蓝牙 App 占用");
        }
    }

    /**
     * 向所有已连接的 Central 推送音频数据
     *
     * 关键修复：不使用 BluetoothGattServer.getConnectedDevices()（Android 9+ 废弃，直接抛异常）
     * 改用手动维护的 connectedDevices 列表，BluetoothManager.getConnectedDevices 作为兜底
     */
    @SuppressLint("MissingPermission")
    public void broadcastAudioFrame(byte codecId, byte[] encodedFrame) {
        if (gattServer == null || txChar == null) return;

        int maxPayload = currentMtu - 3;
        int frameLen = Math.min(encodedFrame.length, maxPayload - 2);
        if (frameLen <= 0) return;
        byte[] packet = new byte[2 + frameLen];
        packet[0] = BleProtocol.PACKET_TYPE_AUDIO;
        packet[1] = codecId;
        System.arraycopy(encodedFrame, 0, packet, 2, frameLen);
        boolean ok = txChar.setValue(packet);
        if (!ok) return;

        // 获取已连接设备列表（不使用废弃的 gattServer.getConnectedDevices()）
        List<BluetoothDevice> devices = getConnectedDeviceList();
        for (BluetoothDevice dev : devices) {
            try {
                gattServer.notifyCharacteristicChanged(dev, txChar, false);
            } catch (Exception e) {
                Log.e(TAG, "notify 失败: " + e.getMessage());
            }
        }
    }

    /**
     * 获取已连接设备列表
     * 优先使用手动跟踪的列表，BluetoothManager 作为兜底
     */
    @SuppressLint("MissingPermission")
    private List<BluetoothDevice> getConnectedDeviceList() {
        // 优先使用手动跟踪的列表
        if (!connectedDevices.isEmpty()) {
            return new ArrayList<>(connectedDevices);
        }
        // 兜底：通过 BluetoothManager 获取（官方推荐方式）
        if (bluetoothManager != null) {
            try {
                return bluetoothManager.getConnectedDevices(BluetoothProfile.GATT_SERVER);
            } catch (Exception e) {
                Log.w(TAG, "BluetoothManager.getConnectedDevices 也失败: " + e.getMessage());
            }
        }
        return Collections.emptyList();
    }

    @SuppressLint("MissingPermission")
    public void sendControl(BluetoothDevice device, byte subtype, byte[] payload) {
        if (gattServer == null || txChar == null) return;
        int maxPayload = currentMtu - 3;
        int payloadLen = payload != null ? Math.min(payload.length, maxPayload - 2) : 0;
        byte[] packet = new byte[2 + payloadLen];
        packet[0] = BleProtocol.PACKET_TYPE_CONTROL;
        packet[1] = subtype;
        if (payload != null) {
            System.arraycopy(payload, 0, packet, 2, payloadLen);
        }
        txChar.setValue(packet);
        try {
            gattServer.notifyCharacteristicChanged(device, txChar, false);
        } catch (Exception e) {
            Log.e(TAG, "sendControl 失败: " + e.getMessage());
        }
    }

    /**
     * 向所有已连接的 Central 广播控制帧（如码率切换通知）
     */
    @SuppressLint("MissingPermission")
    public void broadcastControl(byte subtype, byte[] payload) {
        if (gattServer == null || txChar == null) return;
        int maxPayload = currentMtu - 3;
        int payloadLen = payload != null ? Math.min(payload.length, maxPayload - 2) : 0;
        byte[] packet = new byte[2 + payloadLen];
        packet[0] = BleProtocol.PACKET_TYPE_CONTROL;
        packet[1] = subtype;
        if (payload != null) {
            System.arraycopy(payload, 0, packet, 2, payloadLen);
        }
        txChar.setValue(packet);
        List<BluetoothDevice> devices = getConnectedDeviceList();
        for (BluetoothDevice dev : devices) {
            try {
                gattServer.notifyCharacteristicChanged(dev, txChar, false);
            } catch (Exception e) {
                Log.e(TAG, "broadcastControl 失败: " + e.getMessage());
            }
        }
    }

    @SuppressLint("MissingPermission")
    public void stop() {
        stopped = true;
        started = false;
        serviceReady = false;
        serviceAddRetry = 0;
        pendingService = null;

        cancelServiceTimeout();

        // 移除所有待执行的延迟任务（重启等）
        handler.removeCallbacksAndMessages(null);

        // 清理已连接设备列表
        connectedDevices.clear();

        if (gattServer != null) {
            try {
                gattServer.clearServices();
                gattServer.close();
            } catch (Exception ignored) {}
        }
        gattServer = null;
        rxChar = null;
        txChar = null;
        Log.i(TAG, "GATT server 已停止");
    }

    private void notifyError(String msg) {
        Log.e(TAG, msg);
        if (listener != null) listener.onError(msg);
    }

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {

        @SuppressLint("MissingPermission")
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            if (stopped) return;
            Log.i(TAG, "onServiceAdded: status=" + status + " uuid=" + service.getUuid()
                    + " chars=" + service.getCharacteristics().size());
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // 验证 service 确实在列表中
                try {
                    List<BluetoothGattService> services = gattServer.getServices();
                    Log.i(TAG, "GATT Server services 数量: " + services.size());
                    for (BluetoothGattService s : services) {
                        Log.i(TAG, "  service: " + s.getUuid()
                                + " chars=" + s.getCharacteristics().size());
                    }
                } catch (Exception ignored) {}
            }
            onServiceAddComplete(status);
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            Log.i(TAG, "onConnectionStateChange: " + device.getAddress()
                    + " status=" + status + " newState=" + newState
                    + " serviceReady=" + serviceReady + " started=" + started);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Central 已连接: " + device.getAddress());
                // 手动跟踪已连接设备
                if (!connectedDevices.contains(device)) {
                    connectedDevices.add(device);
                }
                Log.i(TAG, "已连接设备数: " + connectedDevices.size());
                // 打印当前 GATT Server 的 service 状态
                try {
                    List<BluetoothGattService> services = gattServer.getServices();
                    Log.i(TAG, "连接时 GATT Server services 数量: " + services.size());
                    for (BluetoothGattService s : services) {
                        Log.i(TAG, "  service: " + s.getUuid()
                                + " chars=" + s.getCharacteristics().size());
                    }
                } catch (Exception ignored) {}
                if (listener != null) listener.onCentralConnected(device);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Central 已断开: " + device.getAddress());
                // 从已连接列表中移除
                connectedDevices.remove(device);
                Log.i(TAG, "已连接设备数: " + connectedDevices.size());
                if (listener != null) listener.onCentralDisconnected(device);
            }
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            currentMtu = mtu;
            Log.i(TAG, "MTU 协商成功: " + mtu + " (有效载荷 " + (mtu - 3) + " 字节)");
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            if (gattServer != null && responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
            if (value == null || value.length < 2) return;

            byte type = value[0];
            if (type == BleProtocol.PACKET_TYPE_AUDIO) {
                byte codecId = value[1];
                int frameLen = value.length - 2;
                byte[] frame = new byte[frameLen];
                System.arraycopy(value, 2, frame, 0, frameLen);
                if (listener != null) listener.onAudioFrameReceived(device, frame, codecId);
            } else if (type == BleProtocol.PACKET_TYPE_CONTROL) {
                byte subtype = value[1];
                int payloadLen = value.length - 2;
                byte[] payload = new byte[payloadLen];
                System.arraycopy(value, 2, payload, 0, payloadLen);
                if (listener != null) listener.onControlReceived(device, subtype, payload);
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            Log.i(TAG, "onDescriptorWriteRequest: " + descriptor.getUuid());
            if (gattServer != null && responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
        }
    };
}
