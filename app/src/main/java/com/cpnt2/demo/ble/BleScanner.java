package com.cpnt2.demo.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.bluetooth.le.ScanRecord;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BLE 扫描器 (Central)
 *
 * 扫描策略（兼容 Oppo/国产 ROM）：
 *   1. 先用 service UUID 过滤扫描 5 秒
 *   2. 如果没找到设备，自动切换到无过滤扫描，手动匹配
 *      (部分国产 ROM 广播包中 UUID 格式不标准导致过滤失败)
 *   3. 手动匹配条件：设备名以 "Oulab_" 开头，或包含目标 service UUID，
 *      或 manufacturer data 包含我们的 ID
 */
public class BleScanner {

    private static final String TAG = "BleScanner";

    public interface DeviceListener {
        void onDeviceFound(BleDeviceInfo info);
        void onScanFailed(int errorCode);
    }

    public static class BleDeviceInfo {
        public final BluetoothDevice device;
        public final String name;
        public final int rssi;
        public final long lastSeen;

        public BleDeviceInfo(BluetoothDevice device, String name, int rssi) {
            this.device = device;
            this.name = name;
            this.rssi = rssi;
            this.lastSeen = System.currentTimeMillis();
        }
    }

    private final Context context;
    private final BluetoothAdapter adapter;
    private final android.bluetooth.le.BluetoothLeScanner scanner;
    private ScanCallback callback;
    // onScanResult 回调运行在 binder 线程, 与 UI 线程的 clear/getDevices 并发, 必须用并发容器
    private final Map<String, BleDeviceInfo> deviceMap = new ConcurrentHashMap<>();
    private DeviceListener listener;
    private boolean scanning = false;
    private boolean useFilter = true; // 是否使用 UUID 过滤
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable fallbackRunnable;

    public BleScanner(Context context) {
        this.context = context;
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm != null ? bm.getAdapter() : null;
        this.scanner = (adapter != null) ? adapter.getBluetoothLeScanner() : null;
    }

    public boolean isSupported() {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
                && scanner != null;
    }

    public boolean isScanning() { return scanning; }

    @SuppressLint("MissingPermission")
    public void startScan(DeviceListener listener) {
        if (scanner == null) {
            listener.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
            return;
        }
        this.listener = listener;
        deviceMap.clear();
        useFilter = true;

        startScanInternal(true);

        // 5 秒后如果没找到设备，自动切换到无过滤扫描
        cancelFallback();
        fallbackRunnable = () -> {
            if (scanning && deviceMap.isEmpty()) {
                Log.i(TAG, "5 秒内未发现设备，切换到无过滤扫描");
                stopScanInternal();
                useFilter = false;
                startScanInternal(false);
            }
        };
        handler.postDelayed(fallbackRunnable, 5000);
    }

    @SuppressLint("MissingPermission")
    private void startScanInternal(boolean withFilter) {
        ScanSettings settings;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                    .build();
        } else {
            settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
        }

        List<ScanFilter> filters = null;
        if (withFilter) {
            // 用 service UUID 过滤
            filters = new ArrayList<>();
            ScanFilter filter = new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(UUID.fromString(BleProtocol.SERVICE_UUID)))
                    .build();
            filters.add(filter);
        }
        // withFilter=false 时 filters=null，扫描所有 BLE 设备

        callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice dev = result.getDevice();
                String addr = dev.getAddress();

                // 尝试获取设备名
                String name = null;
                ScanRecord record = result.getScanRecord();
                if (record != null) {
                    name = record.getDeviceName();
                    // 也检查 service UUIDs
                    if (name == null || !name.startsWith(BleProtocol.DEVICE_NAME_PREFIX)) {
                        List<ParcelUuid> uuids = record.getServiceUuids();
                        if (uuids != null) {
                            for (ParcelUuid pu : uuids) {
                                if (BleProtocol.SERVICE_UUID.equalsIgnoreCase(pu.toString())) {
                                    // 是我们的设备但名字在 scan response 中
                                    if (name == null) name = BleProtocol.DEVICE_NAME_PREFIX + "Device";
                                    break;
                                }
                            }
                        }
                    }
                }
                if (name == null) {
                    try { name = dev.getName(); } catch (SecurityException ignored) {}
                }

                // 如果无过滤扫描，需要手动过滤
                if (!useFilter) {
                    if (!isOurDevice(name, record)) {
                        return; // 不是我们的设备，跳过
                    }
                }

                // 兼容：有些设备名在 manufacturer data 中
                if (name == null || !name.startsWith(BleProtocol.DEVICE_NAME_PREFIX)) {
                    if (record != null) {
                        byte[] manu = record.getManufacturerSpecificData(BleProtocol.MANUFACTURER_ID);
                        if (manu != null && manu.length > 0) {
                            String manuName = new String(manu);
                            if (manuName.startsWith(BleProtocol.DEVICE_NAME_PREFIX)) {
                                name = manuName;
                            }
                        }
                    }
                }
                if (name == null) name = "Oulab_设备";

                BleDeviceInfo info = new BleDeviceInfo(dev, name, result.getRssi());
                deviceMap.put(addr, info);
                if (BleScanner.this.listener != null) {
                    BleScanner.this.listener.onDeviceFound(info);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "扫描失败 code=" + errorCode);
                scanning = false;
                if (BleScanner.this.listener != null) {
                    BleScanner.this.listener.onScanFailed(errorCode);
                }
            }
        };

        try {
            scanner.startScan(filters, settings, callback);
            scanning = true;
            Log.i(TAG, "开始扫描 (filter=" + withFilter + ")");
        } catch (SecurityException e) {
            Log.e(TAG, "扫描权限不足: " + e.getMessage());
            listener.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
        }
    }

    /**
     * 判断是否为我们的设备 (无过滤扫描时手动匹配)
     */
    private boolean isOurDevice(String name, ScanRecord record) {
        // 1. 检查设备名
        if (name != null && name.startsWith(BleProtocol.DEVICE_NAME_PREFIX)) {
            return true;
        }
        // 2. 检查 service UUID
        if (record != null) {
            List<ParcelUuid> uuids = record.getServiceUuids();
            if (uuids != null) {
                for (ParcelUuid pu : uuids) {
                    if (BleProtocol.SERVICE_UUID.equalsIgnoreCase(pu.toString())) {
                        return true;
                    }
                }
            }
            // 3. 检查 manufacturer data
            byte[] manu = record.getManufacturerSpecificData(BleProtocol.MANUFACTURER_ID);
            if (manu != null && manu.length > 0) {
                String manuName = new String(manu);
                if (manuName.startsWith(BleProtocol.DEVICE_NAME_PREFIX)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void cancelFallback() {
        if (fallbackRunnable != null) {
            handler.removeCallbacks(fallbackRunnable);
            fallbackRunnable = null;
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScanInternal() {
        if (scanner == null || callback == null) return;
        try {
            scanner.stopScan(callback);
        } catch (Exception ignored) {}
        callback = null;
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        cancelFallback();
        stopScanInternal();
        scanning = false;
        Log.i(TAG, "停止扫描, 共发现 " + deviceMap.size() + " 个设备");
    }

    public List<BleDeviceInfo> getDevices() {
        List<BleDeviceInfo> list = new ArrayList<>(deviceMap.values());
        Collections.sort(list, (a, b) -> Integer.compare(b.rssi, a.rssi));
        return list;
    }
}
