package com.cpnt2.demo.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BLE GATT Client (Central 端运行)
 *
 * 连接远程 GATT server，启用 notification，
 * 发送音频帧 / 接收对端发来的音频帧。
 *
 * 兼容性修复：
 *   1. MTU 协商和 service 发现分步执行 (避免竞态)
 *   2. GATT 133 错误自动重试 (最多 3 次)
 *   3. 连接超时检测 (10 秒)
 *   4. status 错误码详细日志
 */
public class BleGattClient {

    private static final String TAG = "BleGattClient";

    /** 最大重连次数 */
    private static final int MAX_RETRY = 3;
    /** 连接超时 (毫秒) — 国产 ROM 上 BLE 连接可能较慢，增大到 20 秒 */
    private static final long CONNECT_TIMEOUT = 20000;
    /** characteristic 发现重试次数 */
    private static final int MAX_CHAR_RETRY = 5;
    /** characteristic 发现重试间隔 (毫秒) */
    private static final long CHAR_RETRY_DELAY = 1000;
    /** 完全重连次数 (断开→刷新缓存→重连) */
    private static final int MAX_FULL_RETRY = 2;

    public interface ClientListener {
        void onConnected(BluetoothDevice device);
        void onDisconnected(BluetoothDevice device);
        void onServicesDiscovered();
        void onAudioFrameReceived(byte[] encodedFrame, int codecId);
        void onControlReceived(byte subtype, byte[] payload);
        void onError(String msg);
    }

    private final Context context;
    private final BluetoothManager bluetoothManager;
    private final BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic txChar; // Client -> Peripheral (我们写)
    private BluetoothGattCharacteristic rxChar; // Peripheral -> Client (我们读 + notify)
    private ClientListener listener;
    private boolean ready = false;
    private int currentMtu = 23; // 默认 BLE ATT MTU

    // 重连相关
    private BluetoothDevice targetDevice;
    private int retryCount = 0;
    private int charRetryCount = 0;
    private int fullRetryCount = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable connectTimeoutRunnable;

    // BLE 写入队列 — writeCharacteristic 是异步操作，必须等 onCharacteristicWrite 回调后才能写下一帧
    // 否则连续写入会被 Android BLE 栈丢弃
    private final ConcurrentLinkedQueue<byte[]> writeQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean isWriting = new AtomicBoolean(false);
    private static final int MAX_WRITE_QUEUE = 50; // 防止写入失败时队列无限增长

    /**
     * 写看门狗超时：部分设备对 WRITE_NO_RESPONSE 不回调 onCharacteristicWrite，
     * 超过该时间强制继续发送下一帧，避免音频队列卡死。
     * 60ms 大于一帧间隔 (20-40ms) 的余量，不影响正常回调驱动的节奏。
     */
    private static final long WRITE_WATCHDOG_MS = 60;
    private final Runnable writeWatchdog = new Runnable() {
        @Override
        public void run() {
            // 仅当本次调度仍是最新的写操作时才继续（回调可能已接管）
            if (writeToken.get() == pendingWriteToken) {
                isWriting.set(false);
                if (!writeQueue.isEmpty()) {
                    processWriteQueue();
                }
            }
        }
    };
    /** 写操作代际令牌：使过期看门狗失效 */
    private final java.util.concurrent.atomic.AtomicLong writeToken = new java.util.concurrent.atomic.AtomicLong(0);
    private volatile long pendingWriteToken = 0;

    public BleGattClient(Context context) {
        this.context = context;
        this.bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
    }

    public void setListener(ClientListener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return gatt != null && ready;
    }

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device) {
        if (adapter == null || device == null) {
            notifyError("adapter 或 device 为空");
            return;
        }
        targetDevice = device;
        retryCount = 0;
        charRetryCount = 0;
        fullRetryCount = 0;
        connectInternal(device);
    }

    @SuppressLint("MissingPermission")
    private void connectInternal(BluetoothDevice device) {
        // 关闭旧连接
        if (gatt != null) {
            try {
                refreshGattCache();
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {}
            gatt = null;
        }
        ready = false;
        txChar = null;
        rxChar = null;

        Log.i(TAG, "连接 " + device.getAddress()
                + " (尝试 " + (retryCount + 1) + "/" + (MAX_RETRY + 1) + ")"
                + " mtu=" + currentMtu);

        // 连接超时检测
        cancelConnectTimeout();
        connectTimeoutRunnable = () -> {
            Log.w(TAG, "连接超时 (" + CONNECT_TIMEOUT + "ms)");
            if (gatt != null) {
                // 超时后刷新 GATT 缓存，防止旧状态影响重试
                refreshGattCache();
                try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
                gatt = null;
            }
            handleConnectionFailure("连接超时");
        };
        handler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT);

        try {
            // 使用 autoConnect=false 直接连接
            // TRANSPORT_LE 明确指定 BLE 传输层
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            Log.i(TAG, "connectGatt 返回: " + (gatt != null ? "成功" : "null"));
        } catch (SecurityException e) {
            Log.e(TAG, "connectGatt 权限异常: " + e.getMessage());
            cancelConnectTimeout();
            notifyError("连接权限不足: " + e.getMessage());
        }
    }

    private void cancelConnectTimeout() {
        if (connectTimeoutRunnable != null) {
            handler.removeCallbacks(connectTimeoutRunnable);
            connectTimeoutRunnable = null;
        }
    }

    /**
     * 处理连接失败，决定是否重试
     */
    private void handleConnectionFailure(String reason) {
        if (retryCount < MAX_RETRY) {
            retryCount++;
            // 重试间隔增大：1s, 2s, 3s — 给蓝牙栈足够时间释放资源
            long delay = 1000L * retryCount;
            Log.w(TAG, reason + ", " + delay + "ms 后重试 (" + retryCount + "/" + MAX_RETRY + ")");
            handler.postDelayed(() -> {
                if (targetDevice != null) {
                    connectInternal(targetDevice);
                }
            }, delay);
        } else {
            notifyError(reason + "，已重试 " + MAX_RETRY + " 次仍失败");
        }
    }

    @SuppressLint("MissingPermission")
    public void disconnect() {
        targetDevice = null; // 防止重连
        cancelConnectTimeout();
        handler.removeCallbacks(writeWatchdog);
        writeToken.incrementAndGet();
        writeQueue.clear();
        isWriting.set(false);
        if (gatt == null) return;
        try {
            gatt.disconnect();
            gatt.close();
        } catch (Exception ignored) {}
        gatt = null;
        ready = false;
    }

    /**
     * 刷新 GATT 缓存（清除旧的服务发现结果）
     * 使用反射调用隐藏 API BluetoothGatt.refresh()
     * 这是解决 "service 找到但 characteristic 缺失" 的关键方法
     */
    private boolean refreshGattCache() {
        if (gatt == null) return false;
        try {
            Method refresh = gatt.getClass().getMethod("refresh");
            if (refresh != null) {
                boolean result = (boolean) refresh.invoke(gatt);
                Log.i(TAG, "refreshGattCache: " + result);
                return result;
            }
        } catch (Exception e) {
            Log.w(TAG, "refreshGattCache 失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 完全重连：断开连接 → 刷新缓存 → 重新连接
     * 当 characteristic 重试全部失败时调用
     */
    @SuppressLint("MissingPermission")
    private void fullReconnect() {
        Log.w(TAG, "执行完全重连 (刷新缓存 + 重新连接), fullRetryCount=" + fullRetryCount);
        if (gatt != null) {
            refreshGattCache();
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        ready = false;
        txChar = null;
        rxChar = null;
        charRetryCount = 0;

        if (targetDevice != null) {
            handler.postDelayed(() -> {
                if (targetDevice != null) {
                    connectInternal(targetDevice);
                }
            }, 1000);
        }
    }

    /**
     * 通过 txChar 写入音频数据（加入写入队列，由 onCharacteristicWrite 回调驱动逐帧发送）
     */
    public boolean sendAudioFrame(byte codecId, byte[] encodedFrame) {
        if (gatt == null || !ready || txChar == null) return false;
        // MTU 减去 3 字节 ATT 头，再减去 2 字节协议头
        int maxPayload = currentMtu - 3;
        int frameLen = Math.min(encodedFrame.length, maxPayload - 2);
        byte[] packet = new byte[2 + frameLen];
        packet[0] = BleProtocol.PACKET_TYPE_AUDIO;
        packet[1] = codecId;
        System.arraycopy(encodedFrame, 0, packet, 2, frameLen);
        enqueueWrite(packet);
        return true;
    }

    @SuppressLint("MissingPermission")
    public void sendControl(byte subtype, byte[] payload) {
        if (gatt == null || !ready || txChar == null) return;
        int maxPayload = currentMtu - 3;
        int payloadLen = payload != null ? Math.min(payload.length, maxPayload - 2) : 0;
        byte[] packet = new byte[2 + payloadLen];
        packet[0] = BleProtocol.PACKET_TYPE_CONTROL;
        packet[1] = subtype;
        if (payload != null) {
            System.arraycopy(payload, 0, packet, 2, payloadLen);
        }
        enqueueWrite(packet);
    }

    /**
     * 将数据包加入写入队列并尝试发送
     */
    private void enqueueWrite(byte[] packet) {
        // 队列满时丢弃最旧的数据，避免内存溢出
        while (writeQueue.size() >= MAX_WRITE_QUEUE) {
            writeQueue.poll();
        }
        writeQueue.offer(packet);
        processWriteQueue();
    }

    /**
     * 从队列取出一帧并发送，需要等 onCharacteristicWrite 回调后才能发下一帧
     * 优化：使用 WRITE_NO_RESPONSE (不等待 ACK)，降低语音延迟；
     *       并加看门狗兜底，防止部分设备不回调导致队列卡死
     */
    @SuppressLint("MissingPermission")
    private void processWriteQueue() {
        if (!isWriting.compareAndSet(false, true)) return;
        byte[] packet = writeQueue.poll();
        if (packet == null) {
            isWriting.set(false);
            return;
        }
        if (gatt == null || txChar == null) {
            isWriting.set(false);
            return;
        }
        txChar.setValue(packet);
        boolean ok = gatt.writeCharacteristic(txChar);
        if (!ok) {
            Log.w(TAG, "writeCharacteristic 返回 false, 跳过此帧");
            isWriting.set(false);
            // 短暂延迟后尝试下一帧
            if (!writeQueue.isEmpty()) {
                handler.postDelayed(this::processWriteQueue, 5);
            }
            return;
        }
        // 记录本次写操作代际，并安排看门狗
        pendingWriteToken = writeToken.incrementAndGet();
        handler.removeCallbacks(writeWatchdog);
        handler.postDelayed(writeWatchdog, WRITE_WATCHDOG_MS);
    }

    private void notifyError(String msg) {
        Log.e(TAG, msg);
        if (listener != null) listener.onError(msg);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            BluetoothDevice device = g.getDevice();

            if (status != BluetoothGatt.GATT_SUCCESS) {
                // 连接失败或异常断开
                Log.e(TAG, "GATT 状态变更失败: status=" + status + " newState=" + newState
                        + " (" + describeGattStatus(status) + ")");
                cancelConnectTimeout();
                ready = false;

                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // 尝试关闭并重连
                    try { g.close(); } catch (Exception ignored) {}
                    if (status == 133 || status == 19 || status == 22) {
                        // GATT 133/19/22 是常见可恢复错误，自动重试
                        handleConnectionFailure("GATT 错误 " + status);
                    } else {
                        if (listener != null) listener.onDisconnected(device);
                    }
                }
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                cancelConnectTimeout();
                Log.i(TAG, "GATT 已连接, 先请求 MTU");
                if (listener != null) listener.onConnected(device);
                // 先请求 MTU，等 onMtuChanged 回调后再 discoverServices
                // 这样避免并发请求导致的竞态条件
                boolean mtuOk = g.requestMtu(247);
                if (!mtuOk) {
                    Log.w(TAG, "requestMtu 返回 false, 刷新缓存后直接发现 service");
                    refreshGattCache();
                    handler.postDelayed(() -> {
                        if (gatt != null) {
                            gatt.discoverServices();
                        }
                    }, 300);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cancelConnectTimeout();
                Log.i(TAG, "GATT 已断开");
                ready = false;
                try { g.close(); } catch (Exception ignored) {}
                if (listener != null) listener.onDisconnected(device);
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu;
                Log.i(TAG, "MTU 协商成功: " + mtu + " (有效载荷 " + (mtu - 3) + " 字节)");
            } else {
                Log.w(TAG, "MTU 协商失败, 使用默认 23, status=" + status);
            }
            // MTU 协商完成后，先刷新 GATT 缓存，再发现 service
            // 这是解决 "service 找到但 characteristic 缺失" 的关键步骤
            Log.i(TAG, "刷新 GATT 缓存后开始发现 service");
            refreshGattCache();
            // 延迟 300ms 让缓存刷新完成
            handler.postDelayed(() -> {
                if (gatt != null) {
                    gatt.discoverServices();
                }
            }, 300);
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyError("service 发现失败 status=" + status);
                return;
            }

            // 打印所有发现的 service 和 characteristic，帮助诊断
            List<BluetoothGattService> allServices = g.getServices();
            Log.i(TAG, "onServicesDiscovered: 共发现 " + allServices.size() + " 个 service");
            for (BluetoothGattService s : allServices) {
                Log.i(TAG, "  service: " + s.getUuid()
                        + " type=" + s.getType()
                        + " chars=" + s.getCharacteristics().size());
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    Log.i(TAG, "    char: " + c.getUuid()
                            + " props=0x" + Integer.toHexString(c.getProperties())
                            + " perms=0x" + Integer.toHexString(c.getPermissions()));
                }
            }

            UUID targetServiceUuid = UUID.fromString(BleProtocol.SERVICE_UUID);

            // 方法 1: 使用 getService (某些设备有 bug)
            BluetoothGattService svc = g.getService(targetServiceUuid);

            // 方法 2: 如果 getService 返回 null，手动遍历查找
            if (svc == null) {
                Log.w(TAG, "getService() 返回 null，尝试手动遍历查找...");
                for (BluetoothGattService s : allServices) {
                    if (s.getUuid().equals(targetServiceUuid)) {
                        svc = s;
                        Log.i(TAG, "手动遍历找到目标 service!");
                        break;
                    }
                }
            }

            if (svc == null) {
                // Service 未找到 — 说明对端 GATT Server 的 service 未成功注册到蓝牙栈
                // (onServiceAdded 未回调，或 GATT Server 未启动)
                // 列出发现的 service UUID 帮助诊断
                StringBuilder svcList = new StringBuilder();
                for (BluetoothGattService s : allServices) {
                    svcList.append(s.getUuid()).append(" ");
                }
                Log.e(TAG, "未找到目标 service! 已发现 " + allServices.size()
                        + " 个 service: " + svcList);

                if (charRetryCount < MAX_CHAR_RETRY) {
                    charRetryCount++;
                    Log.w(TAG, "未找到目标 service (" + BleProtocol.SERVICE_UUID + "), "
                            + CHAR_RETRY_DELAY + "ms 后刷新缓存并重试 discoverServices ("
                            + charRetryCount + "/" + MAX_CHAR_RETRY + ")");
                    handler.postDelayed(() -> {
                        if (gatt != null) {
                            refreshGattCache();
                            gatt.discoverServices();
                        }
                    }, CHAR_RETRY_DELAY);
                } else {
                    // 所有 char 重试失败，尝试完全重连
                    if (fullRetryCount < MAX_FULL_RETRY) {
                        fullRetryCount++;
                        Log.w(TAG, "Service 未找到，char 重试耗尽，执行完全重连 ("
                                + fullRetryCount + "/" + MAX_FULL_RETRY + ")");
                        fullReconnect();
                    } else {
                        notifyError("未找到目标 service (UUID=" + BleProtocol.SERVICE_UUID
                                + ")，共发现 " + allServices.size() + " 个 service，已重试 "
                                + MAX_CHAR_RETRY + " 次 + " + MAX_FULL_RETRY + " 次完全重连仍失败。"
                                + "请确保对方手机 App 已打开并显示在线状态，或让对方重启 App");
                        try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
                        gatt = null;
                    }
                }
                return;
            }

            UUID rxUuid = UUID.fromString(BleProtocol.CHAR_RX_UUID);
            UUID txUuid = UUID.fromString(BleProtocol.CHAR_TX_UUID);

            // txChar (我们写) 对应对方的 rxChar，反之亦然
            txChar = svc.getCharacteristic(rxUuid);
            rxChar = svc.getCharacteristic(txUuid);

            // 手动遍历兜底
            if (txChar == null || rxChar == null) {
                Log.w(TAG, "getCharacteristic() 返回 null，尝试手动遍历...");
                for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                    if (c.getUuid().equals(rxUuid) && txChar == null) {
                        txChar = c;
                        Log.i(TAG, "手动遍历找到 txChar (rxUuid)");
                    }
                    if (c.getUuid().equals(txUuid) && rxChar == null) {
                        rxChar = c;
                        Log.i(TAG, "手动遍历找到 rxChar (txUuid)");
                    }
                }
            }

            if (txChar == null || rxChar == null) {
                // Characteristic 缺失，可能是 BLE 缓存问题或对端 Service 尚未完全注册
                if (charRetryCount < MAX_CHAR_RETRY) {
                    charRetryCount++;
                    Log.w(TAG, "characteristic 缺失 (tx=" + (txChar != null) + " rx=" + (rxChar != null)
                            + "), " + CHAR_RETRY_DELAY + "ms 后刷新缓存并重试 discoverServices ("
                            + charRetryCount + "/" + MAX_CHAR_RETRY + ")");
                    // 清空引用以便重试
                    txChar = null;
                    rxChar = null;
                    handler.postDelayed(() -> {
                        if (gatt != null) {
                            refreshGattCache();
                            gatt.discoverServices();
                        }
                    }, CHAR_RETRY_DELAY);
                } else {
                    // 所有 char 重试失败，尝试完全重连
                    if (fullRetryCount < MAX_FULL_RETRY) {
                        fullRetryCount++;
                        Log.w(TAG, "Characteristic 缺失，char 重试耗尽，执行完全重连 ("
                                + fullRetryCount + "/" + MAX_FULL_RETRY + ")");
                        fullReconnect();
                    } else {
                        notifyError("characteristic 缺失 (tx=" + (txChar != null)
                                + " rx=" + (rxChar != null) + ")，已重试 " + MAX_CHAR_RETRY
                                + " 次 + " + MAX_FULL_RETRY + " 次完全重连仍失败");
                        try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
                        gatt = null;
                    }
                }
                return;
            }

            // 重置重试计数
            charRetryCount = 0;

            // 使用 WRITE_NO_RESPONSE 发送音频帧（低延迟，适合语音）
            // 对端 rxChar 声明了 WRITE_NO_RESPONSE 属性，无需等待 ATT ACK
            txChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

            // 启用 rxChar 的 notification
            boolean ok = g.setCharacteristicNotification(rxChar, true);
            if (!ok) {
                notifyError("setCharacteristicNotification 失败");
                return;
            }

            // 查找 CCCD descriptor — 先用 getDescriptor，再手动遍历兜底
            UUID cccdUuid = UUID.fromString(BleProtocol.DESC_UUID);
            BluetoothGattDescriptor desc = rxChar.getDescriptor(cccdUuid);
            if (desc == null) {
                // 手动遍历查找
                Log.w(TAG, "getDescriptor() 返回 null，尝试手动遍历...");
                for (BluetoothGattDescriptor d : rxChar.getDescriptors()) {
                    Log.i(TAG, "  rxChar descriptor: " + d.getUuid());
                    if (d.getUuid().equals(cccdUuid)) {
                        desc = d;
                        break;
                    }
                }
            }

            if (desc != null) {
                Log.i(TAG, "找到 CCCD descriptor, 写入 ENABLE_NOTIFICATION");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Android 13+ (API 33+) 使用新 API
                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(desc);
                }
            } else {
                Log.w(TAG, "CCCD descriptor 不存在, 仅依赖 setCharacteristicNotification"
                        + " (descriptors count=" + rxChar.getDescriptors().size() + ")");
                // 不阻塞流程 — setCharacteristicNotification(true) 在部分设备上已足够启用通知
            }
            ready = true;
            if (listener != null) listener.onServicesDiscovered();
            Log.i(TAG, "GATT 就绪, 可以收发数据 (MTU=" + currentMtu + ")");
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value == null || value.length < 2) return;

            byte type = value[0];
            if (type == BleProtocol.PACKET_TYPE_AUDIO) {
                byte codecId = value[1];
                int frameLen = value.length - 2;
                byte[] frame = new byte[frameLen];
                System.arraycopy(value, 2, frame, 0, frameLen);
                if (listener != null) listener.onAudioFrameReceived(frame, codecId);
            } else if (type == BleProtocol.PACKET_TYPE_CONTROL) {
                byte subtype = value[1];
                int payloadLen = value.length - 2;
                byte[] payload = new byte[payloadLen];
                System.arraycopy(value, 2, payload, 0, payloadLen);
                if (listener != null) listener.onControlReceived(subtype, payload);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "writeCharacteristic status=" + status);
            }
            // 使看门狗失效（本次写入已有回调，无需兜底）
            writeToken.incrementAndGet();
            handler.removeCallbacks(writeWatchdog);
            // 标记写入完成，处理队列中的下一帧
            isWriting.set(false);
            if (!writeQueue.isEmpty()) {
                handler.post(BleGattClient.this::processWriteQueue);
            }
        }
    };

    /**
     * 将 GATT status 错误码转为可读描述
     */
    private static String describeGattStatus(int status) {
        switch (status) {
            case 133: return "GATT_ERROR (常见超时/权限/资源问题)";
            case 19: return "GATT_REMOTE_USER_TERMINATED";
            case 22: return "GATT_LOCAL_HOST_TERMINATED";
            case 8: return "GATT_CONN_TIMEOUT";
            case 62: return "GATT_CONN_FAIL_ESTABLISH";
            case 129: return "GATT_INTERNAL_ERROR";
            case 5: return "GATT_AUTH_FAIL";
            case 15: return "GATT_INSUF_AUTHENTICATION";
            case 13: return "GATT_INSUF_ENCRYPTION";
            case 137: return "GATT_AUTH_FAIL_ENC_MISSING";
            default: return "未知 status=" + status;
        }
    }
}
