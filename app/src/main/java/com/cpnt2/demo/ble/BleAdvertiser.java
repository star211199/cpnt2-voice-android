package com.cpnt2.demo.ble;

import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BLE 广播端 (Peripheral)
 *
 * 启动一个可被其他设备扫描到的 BLE 广播，包含：
 *   - 自定义 service UUID 用于过滤（放在广播包中）
 *   - 设备名称 (放在扫描响应中，避免广播包超 31 字节)
 *
 * 兼容性修复（Oppo / ColorOS）：
 *   1. 广播包只放 service UUID (18 字节 < 31 字节限制)
 *   2. 设备名放扫描响应 ScanResponse
 *   3. 失败后自动降级重试（去掉 manufacturer data、降低功率）
 *   4. 详细错误码日志
 */
public class BleAdvertiser {

    private static final String TAG = "BleAdvertiser";

    private final Context context;
    private final BluetoothLeAdvertiser advertiser;
    private final String deviceName;
    private AdvertiseCallback currentCallback;
    private AdvertiseListener currentListener;
    private final AtomicInteger retryCount = new AtomicInteger(0);

    /** 最大重试次数 */
    private static final int MAX_RETRY = 2;

    public interface AdvertiseListener {
        void onStartSuccess();
        void onStartFailure(int errorCode, String message);
    }

    public BleAdvertiser(Context context) {
        this.context = context;
        this.deviceName = BleProtocol.DEVICE_NAME_PREFIX + Build.MODEL;
        android.bluetooth.BluetoothManager bm =
                (android.bluetooth.BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        android.bluetooth.BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        this.advertiser = (adapter != null) ? adapter.getBluetoothLeAdvertiser() : null;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public boolean isSupported() {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
                && advertiser != null;
    }

    public void start(AdvertiseListener listener) {
        if (advertiser == null) {
            if (listener != null) listener.onStartFailure(
                    AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED,
                    "设备不支持 BLE Peripheral 模式");
            return;
        }
        currentListener = listener;
        retryCount.set(0);
        startAdvertisingInternal(0);
    }

    /**
     * 内部启动广播，根据 retryLevel 选择不同的参数策略
     * @param retryLevel 0=低延迟模式, 1=平衡模式, 2=低功耗模式
     *
     * 关键修复：广播包只放 service UUID (18 字节)，不放 manufacturer data
     * 128-bit UUID = 2字节AD头 + 16字节UUID = 18 字节，远小于 31 字节限制
     * manufacturer data 会导致总大小超 31 字节 (18 + 14 = 32 > 31)
     */
    private void startAdvertisingInternal(int retryLevel) {
        Log.i(TAG, "启动广播 (retryLevel=" + retryLevel + "): " + deviceName);

        AdvertiseSettings.Builder settingsBuilder = new AdvertiseSettings.Builder()
                .setConnectable(true)
                .setTimeout(0);

        if (retryLevel == 0) {
            // 低延迟模式：高功率
            settingsBuilder
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH);
        } else if (retryLevel == 1) {
            // 平衡模式：中功率
            settingsBuilder
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM);
        } else {
            // 低功耗模式：中功率 (最大兼容性)
            settingsBuilder
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM);
        }

        AdvertiseSettings settings = settingsBuilder.build();

        // 广播包：只放 service UUID (18 字节，远小于 31 字节限制)
        // 不放 manufacturer data — 会超出 31 字节限制
        AdvertiseData advertiseData = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(UUID.fromString(BleProtocol.SERVICE_UUID)))
                .build();

        // 扫描响应：放设备名（不占用广播包空间）
        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();

        currentCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                Log.i(TAG, "广播启动成功 (retryLevel=" + retryLevel + "): " + deviceName
                        + " mode=" + settingsInEffect.getMode()
                        + " txPower=" + settingsInEffect.getTxPowerLevel());
                if (currentListener != null) currentListener.onStartSuccess();
            }

            @Override
            public void onStartFailure(int errorCode) {
                String msg = describeError(errorCode);
                Log.e(TAG, "广播启动失败 (retryLevel=" + retryLevel + ") code=" + errorCode + " (" + msg + ")");

                int nextRetry = retryCount.incrementAndGet();
                if (nextRetry <= MAX_RETRY) {
                    Log.w(TAG, "将降级重试 (retryLevel=" + nextRetry + ")...");
                    // 先停止旧 callback
                    try {
                        advertiser.stopAdvertising(currentCallback);
                    } catch (Exception ignored) {}
                    // 延迟 500ms 后重试，避免连续调用冲突
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        startAdvertisingInternal(nextRetry);
                    }, 500);
                } else {
                    if (currentListener != null) currentListener.onStartFailure(errorCode, msg);
                }
            }
        };

        try {
            advertiser.startAdvertising(settings, advertiseData, scanResponse, currentCallback);
        } catch (SecurityException e) {
            Log.e(TAG, "startAdvertising 权限异常: " + e.getMessage());
            if (currentListener != null) currentListener.onStartFailure(
                    AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED,
                    "缺少蓝牙权限: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "startAdvertising 异常: " + e.getMessage());
            if (currentListener != null) currentListener.onStartFailure(
                    AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR,
                    e.getMessage() != null ? e.getMessage() : "未知异常");
        }
    }

    /**
     * 将 AdvertiseCallback 错误码转为可读描述
     */
    private static String describeError(int errorCode) {
        switch (errorCode) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                return "广播数据过大 (超 31 字节)";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                return "广播实例已满 (其他 App 占用)";
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                return "广播已在运行";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                return "内部错误 (可能被系统限制)";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                return "设备不支持 Peripheral 模式";
            default:
                return "未知错误 code=" + errorCode;
        }
    }

    public void stop() {
        if (advertiser == null || currentCallback == null) return;
        try {
            advertiser.stopAdvertising(currentCallback);
        } catch (Exception ignored) {}
        currentCallback = null;
        retryCount.set(0);
        Log.i(TAG, "广播已停止");
    }
}
