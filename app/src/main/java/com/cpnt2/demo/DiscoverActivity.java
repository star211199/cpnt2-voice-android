package com.cpnt2.demo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.cpnt2.demo.ble.BleAdvertiser;
import com.cpnt2.demo.ble.BleGattServer;
import com.cpnt2.demo.ble.BleGattServerHolder;
import com.cpnt2.demo.ble.BleScanner;
import com.cpnt2.demo.util.CrashHandler;
import com.cpnt2.demo.util.LogCollector;
import com.cpnt2.demo.util.PermissionManager;

import java.util.HashMap;
import java.util.Map;

/**
 * 发现页
 *
 * - 顶部 CPNT AppBar
 * - 紫色信息卡
 * - 扫描附近的 BLE Oulab 设备
 * - 设备列表项：头像 + 名称 + 信号/距离 + 连接按钮
 * - 底部 Tab：发现 / 设置
 * - 同时启动 BLE 广播，让对方也能扫到我
 */
public class DiscoverActivity extends AppCompatActivity {

    private static final int REQ_PERM_BLE = 1001;
    private static final int REQ_ENABLE_BT = 1002;

    private BleScanner scanner;
    private BleAdvertiser advertiser;
    private BleGattServer gattServer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Button btnScan;
    private TextView scanStatus;
    private TextView onlineStatus;
    private LinearLayout deviceListContainer;
    private TextView emptyHint;

    private final Map<String, View> deviceViewMap = new HashMap<>();
    private BleScanner.DeviceListener scanListener;
    private boolean bluetoothReady = false;
    private boolean batteryHintShown = false;
    private boolean transitioningToCall = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_discover);

        // 深链/自动化测试入口：直接以指定编解码模式进入本地回环
        //   adb shell am start -n com.cpnt2.demo/.DiscoverActivity --es autoLoopback NEURAL300
        String autoLoopback = getIntent().getStringExtra("autoLoopback");
        if (autoLoopback != null) {
            Intent it = new Intent(this, CallActivity.class);
            it.putExtra(CallActivity.EXTRA_LOOPBACK, true);
            it.putExtra(CallActivity.EXTRA_MODE, autoLoopback);
            it.putExtra(CallActivity.EXTRA_REMOTE_NAME, "回环-" + autoLoopback);
            startActivity(it);
            finish();
            return;
        }

        // 注册全局崩溃处理器和日志收集器
        CrashHandler.init(this);
        LogCollector.start(this);

        // 检查上次是否崩溃
        String crashLog = LogCollector.readCrashLog();
        if (crashLog != null) {
            Log.w("DiscoverActivity", "检测到上次崩溃记录");
            new AlertDialog.Builder(this)
                    .setTitle("上次运行异常退出")
                    .setMessage("检测到上次运行时应用崩溃了。\n\n崩溃日志已保存，可以在「设置」页查看并分享，以便开发者诊断问题。")
                    .setPositiveButton("去设置查看", (d, w) -> {
                        startActivity(new Intent(this, SettingsActivity.class));
                    })
                    .setNegativeButton("忽略", null)
                    .show();
        }

        btnScan = findViewById(R.id.btnScan);
        scanStatus = findViewById(R.id.scanStatus);
        onlineStatus = findViewById(R.id.onlineStatus);
        deviceListContainer = findViewById(R.id.deviceListContainer);
        emptyHint = findViewById(R.id.emptyHint);

        findViewById(R.id.tabSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        // tabDiscover 已在当前页

        // 单机测试：无需蓝牙，本地回环验证编解码链路
        findViewById(R.id.btnLoopback).setOnClickListener(v -> {
            Intent intent = new Intent(DiscoverActivity.this, CallActivity.class);
            intent.putExtra(CallActivity.EXTRA_LOOPBACK, true);
            intent.putExtra(CallActivity.EXTRA_REMOTE_NAME, "本机回环测试");
            startActivity(intent);
        });

        btnScan.setOnClickListener(v -> {
            if (!bluetoothReady) {
                // 重新检查权限
                if (PermissionManager.hasBlePermissions(this)) {
                    bluetoothReady = true;
                    onBluetoothReady();
                } else {
                    Toast.makeText(this, "请先授予蓝牙权限", Toast.LENGTH_SHORT).show();
                    requestBlePermissions();
                }
                return;
            }
            if (scanner != null && scanner.isScanning()) {
                stopScanning();
            } else {
                startScanning();
            }
        });

        // 1. 请求 BLE 权限
        requestBlePermissions();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScanning();
        stopAdvertising();
        // 注意：GATT Server 不停止！
        // 如果是跳转到 CallActivity，GATT Server 需要保持运行
        // 如果是来电连接跳转，GATT Server 由 CallActivity 接管
        // 如果不是跳转通话，在 onDestroy 中清理
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从通话页返回后，重置状态
        transitioningToCall = false;
        BleGattServerHolder holder = BleGattServerHolder.getInstance();
        holder.clearIncomingConnection();

        // 从通话页返回后，旧 GATT Server 已被 CallActivity 销毁，需要重建
        if (gattServer != null && !holder.hasGattServer()) {
            gattServer = null; // 清除旧引用
        }

        // 首次进入时检查电池优化（Oppo 常见问题）
        if (bluetoothReady && PermissionManager.isBatteryOptimized(this) && !batteryHintShown) {
            batteryHintShown = true;
            new AlertDialog.Builder(this)
                    .setTitle("建议允许后台运行")
                    .setMessage("Oppo/ColorOS 默认会限制后台 BLE 广播，\n"
                            + "导致对方无法发现你的设备。\n\n"
                            + "建议点击\"忽略电池优化\"以允许后台蓝牙广播。")
                    .setPositiveButton("忽略电池优化", (d, w) ->
                            PermissionManager.requestIgnoreBatteryOptimization(this))
                    .setNegativeButton("暂不", null)
                    .show();
        }
        if (bluetoothReady) {
            // 确保 GATT Server 在运行 (从通话页返回后需要重启)
            if (gattServer == null) {
                ensureGattServer();
            } else if (gattServer.isServiceReady()) {
                // Service 已就绪，直接启动广播
                ensureAdvertising();
            }
            // 如果 Service 尚未就绪，等 onServiceReady 回调中自动启动广播
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScanning();
        stopAdvertising();
        // 只有不在通话跳转中才停止 GATT Server
        if (!transitioningToCall) {
            stopGattServer();
        }
    }

    private void requestBlePermissions() {
        // 如果权限已全部授予，直接初始化蓝牙（避免回调不触发）
        if (PermissionManager.hasBlePermissions(this)) {
            bluetoothReady = true;
            onBluetoothReady();
            return;
        }
        PermissionManager.requestBlePermissions(this, REQ_PERM_BLE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM_BLE) {
            boolean allGranted = grantResults != null && grantResults.length > 0;
            if (allGranted) {
                for (int r : grantResults) {
                    if (r != PackageManager.PERMISSION_GRANTED) {
                        allGranted = false;
                        break;
                    }
                }
            }
            if (allGranted) {
                bluetoothReady = true;
                onBluetoothReady();
            } else {
                onlineStatus.setText("权限缺失");
                if (PermissionManager.shouldShowBleRationale(this)) {
                    // 用户拒绝但没选"不再询问"，可再次请求
                    Toast.makeText(this, "蓝牙权限被拒绝，部分功能不可用，请重新授权", Toast.LENGTH_LONG).show();
                } else {
                    // 用户永久拒绝，引导去设置
                    Toast.makeText(this, "蓝牙权限被永久拒绝，请到设置中手动开启", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void onBluetoothReady() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null) {
            onlineStatus.setText("不支持蓝牙");
            Toast.makeText(this, "此设备不支持蓝牙", Toast.LENGTH_LONG).show();
            return;
        }
        if (!adapter.isEnabled()) {
            onlineStatus.setText("蓝牙未开启");
            // 引导用户开启蓝牙
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQ_ENABLE_BT);
            return;
        }

        onlineStatus.setText(R.string.online);

        // 1. 启动 scanner
        scanner = new BleScanner(this);
        scanListener = new BleScanner.DeviceListener() {
            @Override
            public void onDeviceFound(BleScanner.BleDeviceInfo info) {
                handler.post(() -> addOrUpdateDevice(info));
            }

            @Override
            public void onScanFailed(int errorCode) {
                handler.post(() -> {
                    scanStatus.setText("扫描失败 code=" + errorCode);
                    btnScan.setText(R.string.scan);
                });
            }
        };

        // 2. 先启动 GATT Server (接受对方的连接)
        //    广播 (BleAdvertiser) 在 GATT Server 的 onServiceReady 回调中启动
        //    确保 Service 注册完成后才广播，避免客户端连接时 characteristic 缺失
        ensureGattServer();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                // 蓝牙已开启，继续初始化
                onBluetoothReady();
            } else {
                onlineStatus.setText("蓝牙未开启");
                Toast.makeText(this, "请打开蓝牙以使用此功能", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void ensureAdvertising() {
        if (advertiser != null) return;
        advertiser = new BleAdvertiser(this);
        if (!advertiser.isSupported()) {
            onlineStatus.setText("不支持广播");
            Toast.makeText(this, "此设备不支持 BLE Peripheral 模式", Toast.LENGTH_LONG).show();
            return;
        }
        advertiser.start(new BleAdvertiser.AdvertiseListener() {
            @Override
            public void onStartSuccess() {
                runOnUiThread(() -> onlineStatus.setText("在线 · " + advertiser.getDeviceName()));
            }

            @Override
            public void onStartFailure(int errorCode, String message) {
                runOnUiThread(() -> {
                    onlineStatus.setText("广播失败");
                    showAdvertiseFailureDialog(errorCode, message);
                });
            }
        });
    }

    /**
     * 广播失败时显示详细对话框，引导用户处理 Oppo 后台限制
     */
    private void showAdvertiseFailureDialog(int errorCode, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("广播启动失败\n\n");
        sb.append("错误码: ").append(errorCode).append("\n");
        sb.append("原因: ").append(message).append("\n\n");

        boolean isOppo = PermissionManager.isOppoDevice();
        boolean batteryOptimized = PermissionManager.isBatteryOptimized(this);

        if (errorCode == 3 /* ADVERTISE_FAILED_TOO_MANY_ADVERTISERS */) {
            sb.append("其他 App 占用了 BLE 广播资源。\n");
            sb.append("请关闭其他蓝牙相关 App 后重试。");
        } else if (errorCode == 1 /* ADVERTISE_FAILED_DATA_TOO_LARGE */) {
            sb.append("广播数据过大，已自动降级仍失败。\n");
            sb.append("请重启蓝牙后重试。");
        } else if (batteryOptimized) {
            sb.append("系统电池优化可能限制了 BLE 广播。\n");
            sb.append("建议：\n");
            sb.append("1. 点击\"忽略电池优化\"授权\n");
            if (isOppo) {
                sb.append("2. 点击\"自启动管理\"允许本 App 后台运行\n");
            }
        } else if (isOppo) {
            sb.append("Oppo/ColorOS 可能限制了后台 BLE 广播。\n");
            sb.append("建议：\n");
            sb.append("1. 点击\"自启动管理\"允许本 App 后台运行\n");
            sb.append("2. 在设置→电池→应用耗电管理中设为\"允许后台运行\"\n");
            sb.append("3. 重启蓝牙后重试");
        } else {
            sb.append("请尝试：\n");
            sb.append("1. 重启蓝牙\n");
            sb.append("2. 重启 App\n");
            sb.append("3. 检查是否有其他 App 占用蓝牙");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("广播失败")
                .setMessage(sb.toString())
                .setPositiveButton("重试广播", (d, w) -> {
                    stopAdvertising();
                    onlineStatus.setText("正在重试...");
                    handler.postDelayed(this::ensureAdvertising, 1000);
                })
                .setNegativeButton("关闭", null);

        if (batteryOptimized) {
            builder.setNeutralButton("忽略电池优化", (d, w) -> {
                PermissionManager.requestIgnoreBatteryOptimization(DiscoverActivity.this);
            });
        } else if (isOppo) {
            builder.setNeutralButton("自启动管理", (d, w) -> {
                boolean opened = PermissionManager.openOppoAutoStartSettings(DiscoverActivity.this);
                if (!opened) {
                    Toast.makeText(DiscoverActivity.this,
                            "无法自动打开，请手动进入 设置→应用管理→Oulab语音→自启动",
                            Toast.LENGTH_LONG).show();
                    PermissionManager.openAppSettings(DiscoverActivity.this);
                }
            });
        }

        builder.show();
    }

    private void stopAdvertising() {
        if (advertiser != null) {
            advertiser.stop();
            advertiser = null;
        }
    }

    /**
     * 启动 GATT Server，接受对方的 BLE GATT 连接
     * 当对方连接进来时，自动跳转到 CallActivity (服务端模式)
     */
    private void ensureGattServer() {
        if (gattServer != null) return;

        // 如果 holder 中已有 (从上一个 Activity 遗留)，先清理
        BleGattServerHolder holder = BleGattServerHolder.getInstance();
        if (holder.hasGattServer()) {
            holder.clear();
        }

        gattServer = new BleGattServer(this);
        gattServer.setListener(new BleGattServer.ServerListener() {
            @Override
            public void onCentralConnected(BluetoothDevice device) {
                runOnUiThread(() -> {
                    // 收到对方连接 → 自动跳转到通话页
                    transitioningToCall = true;
                    holder.setIncomingConnection(device);
                    String name = "对方";
                    try { name = device.getName(); } catch (SecurityException ignored) {}
                    if (name == null || name.isEmpty()) name = "对方";

                    Intent intent = new Intent(DiscoverActivity.this, CallActivity.class);
                    intent.putExtra(CallActivity.EXTRA_REMOTE_NAME, name);
                    intent.putExtra(CallActivity.EXTRA_ROLE, CallActivity.ROLE_ENCODER);
                    // 标记为来电模式 (不传 EXTRA_REMOTE_DEVICE)
                    startActivity(intent);
                });
            }

            @Override
            public void onCentralDisconnected(BluetoothDevice device) {
            }

            @Override
            public void onAudioFrameReceived(BluetoothDevice device, byte[] encodedFrame, int codecId) {
            }

            @Override
            public void onControlReceived(BluetoothDevice device, byte subtype, byte[] payload) {
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> {
                    Log.w("DiscoverActivity", "GATT Server 错误: " + msg);
                    onlineStatus.setText("GATT 错误");
                    Toast.makeText(DiscoverActivity.this, msg, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onServiceReady() {
                runOnUiThread(() -> {
                    Log.i("DiscoverActivity", "GATT Server Service 已就绪，启动广播");
                    ensureAdvertising();
                });
            }
        });
        gattServer.start();

        // 存入 holder，让 CallActivity 可以接管
        holder.setGattServer(gattServer);
    }

    private void stopGattServer() {
        if (gattServer != null) {
            gattServer.stop();
            gattServer = null;
        }
        BleGattServerHolder.getInstance().clear();
    }

    @SuppressLint("MissingPermission")
    private void startScanning() {
        if (scanner == null) return;
        deviceListContainer.removeAllViews();
        deviceViewMap.clear();
        emptyHint.setVisibility(View.GONE);
        scanStatus.setText(R.string.scanning);
        btnScan.setText(R.string.stop);
        scanner.startScan(scanListener);
        // 10 秒后自动停止
        handler.postDelayed(this::stopScanning, 10000);
    }

    @SuppressLint("MissingPermission")
    private void stopScanning() {
        boolean wasScanning = scanner != null && scanner.isScanning();
        if (scanner != null) scanner.stopScan();
        // 只有真的在扫描时才刷新 UI, 避免从未扫描过也显示"扫描已停止"
        // 注意：深链快速路径(autoLoopback)会在视图初始化前 finish()，此处视图可能为 null。
        if (wasScanning && scanStatus != null && btnScan != null) {
            scanStatus.setText("扫描已停止");
            btnScan.setText(R.string.scan);
        }
        if (deviceViewMap != null && deviceViewMap.isEmpty() && emptyHint != null) {
            emptyHint.setVisibility(View.VISIBLE);
        }
    }

    private void addOrUpdateDevice(BleScanner.BleDeviceInfo info) {
        View view = deviceViewMap.get(info.device.getAddress());
        if (view == null) {
            view = LayoutInflater.from(this).inflate(R.layout.item_device, deviceListContainer, false);
            deviceListContainer.addView(view);
            deviceViewMap.put(info.device.getAddress(), view);
        }

        TextView name = view.findViewById(R.id.deviceName);
        TextView meta = view.findViewById(R.id.deviceMeta);
        TextView avatar = view.findViewById(R.id.deviceAvatar);
        ImageView action = view.findViewById(R.id.deviceAction);

        name.setText(info.name);
        int distance = rssiToDistance(info.rssi);
        meta.setText(String.format("约 %d 米 · 可连接", distance));
        // 头像首字母
        String firstChar = info.name.length() > 0 ? info.name.substring(info.name.length() - 1) : "C";
        avatar.setText(firstChar);

        View.OnClickListener connectListener = v -> {
            if (!bluetoothReady) return;

            // 关键修复：连接前必须先停止扫描！
            // Android BLE 规范要求：connectGatt 之前必须停止扫描，
            // 否则扫描和连接共用蓝牙天线会导致连接超时
            if (scanner != null && scanner.isScanning()) {
                Log.i("DiscoverActivity", "连接前停止扫描");
                stopScanning();
            }

            // 启动通话页
            Intent intent = new Intent(DiscoverActivity.this, CallActivity.class);
            intent.putExtra(CallActivity.EXTRA_REMOTE_DEVICE, info.device);
            intent.putExtra(CallActivity.EXTRA_REMOTE_NAME, info.name);
            intent.putExtra(CallActivity.EXTRA_ROLE, CallActivity.ROLE_DECODER);
            startActivity(intent);
        };
        action.setOnClickListener(connectListener);
        view.setOnClickListener(connectListener);
    }

    /** RSSI 估距离 (粗略) */
    private int rssiToDistance(int rssi) {
        // 经验值: -50dBm ≈ 1m, -70dBm ≈ 5m, -85dBm ≈ 15m
        if (rssi >= -50) return 1;
        if (rssi >= -60) return 2;
        if (rssi >= -70) return 5;
        if (rssi >= -80) return 10;
        return 15;
    }
}
