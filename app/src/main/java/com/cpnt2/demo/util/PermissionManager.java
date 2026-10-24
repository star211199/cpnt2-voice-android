package com.cpnt2.demo.util;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限管理工具
 */
public class PermissionManager {

    private static final String TAG = "PermissionManager";

    /** Android 12+ 蓝牙权限 (含广播) */
    public static final String[] BLE_PERMISSIONS_S = new String[]{
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION
    };

    /** Android <12 蓝牙权限 */
    public static final String[] BLE_PERMISSIONS_OLD = new String[]{
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
    };

    public static final String[] AUDIO_PERMISSIONS = new String[]{
            Manifest.permission.RECORD_AUDIO
    };

    public static boolean hasBlePermissions(Activity activity) {
        for (String p : getBlePermissions()) {
            if (ContextCompat.checkSelfPermission(activity, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasAudioPermissions(Activity activity) {
        for (String p : AUDIO_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(activity, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public static String[] getBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return BLE_PERMISSIONS_S;
        }
        return BLE_PERMISSIONS_OLD;
    }

    public static void requestBlePermissions(Activity activity, int requestCode) {
        List<String> pending = new ArrayList<>();
        for (String p : getBlePermissions()) {
            if (ContextCompat.checkSelfPermission(activity, p) != PackageManager.PERMISSION_GRANTED) {
                pending.add(p);
            }
        }
        if (pending.isEmpty()) {
            Log.i(TAG, "BLE 权限已全部授予");
            return;
        }
        Log.i(TAG, "请求 BLE 权限: " + pending);
        ActivityCompat.requestPermissions(activity, pending.toArray(new String[0]), requestCode);
    }

    public static void requestAudioPermissions(Activity activity, int requestCode) {
        ActivityCompat.requestPermissions(activity, AUDIO_PERMISSIONS, requestCode);
    }

    /**
     * 检查是否需要显示权限说明 (用户之前拒绝过但没选"不再询问")
     */
    public static boolean shouldShowAudioRationale(Activity activity) {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO);
    }

    /**
     * 检查是否需要显示蓝牙权限说明
     */
    public static boolean shouldShowBleRationale(Activity activity) {
        for (String p : getBlePermissions()) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 打开应用设置页 (用户永久拒绝权限后引导手动开启)
     */
    public static void openAppSettings(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
        activity.startActivity(intent);
    }

    // ==================== 电池优化 / 后台限制 (Oppo/ColorOS 兼容) ====================

    /**
     * 检查应用是否被电池优化限制
     * @return true 表示正在被优化（需要请求忽略）
     */
    public static boolean isBatteryOptimized(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
            return pm != null && !pm.isIgnoringBatteryOptimizations(activity.getPackageName());
        }
        return false;
    }

    /**
     * 请求忽略电池优化（弹出系统对话框）
     */
    public static void requestIgnoreBatteryOptimization(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
                activity.startActivity(intent);
            } catch (Exception e) {
                Log.w(TAG, "无法请求忽略电池优化: " + e.getMessage());
                // 回退到电池优化设置列表
                try {
                    Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    activity.startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 打开 Oppo/ColorOS 自启动管理页
     * Oppo 包名: com.coloros.safecenter / com.coloros.securitypermission
     */
    public static boolean openOppoAutoStartSettings(Activity activity) {
        String[][] oppoConfigs = {
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.coloros.securitypermission", "com.coloros.securitypermission.startup.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
        };
        for (String[] cfg : oppoConfigs) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(cfg[0], cfg[1]));
                activity.startActivity(intent);
                Log.i(TAG, "打开 Oppo 自启动管理: " + cfg[0] + "/" + cfg[1]);
                return true;
            } catch (Exception e) {
                // 尝试下一个
            }
        }
        Log.w(TAG, "无法打开 Oppo 自启动管理页");
        return false;
    }

    /**
     * 检测是否为 Oppo/ColorOS 设备
     */
    public static boolean isOppoDevice() {
        String manufacturer = Build.MANUFACTURER;
        return "oppo".equalsIgnoreCase(manufacturer)
                || "realme".equalsIgnoreCase(manufacturer)
                || "oneplus".equalsIgnoreCase(manufacturer);
    }
}
