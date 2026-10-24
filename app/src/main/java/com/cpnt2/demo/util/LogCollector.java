package com.cpnt2.demo.util;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 日志收集器
 *
 * 在后台线程持续捕获 logcat 输出（过滤相关 TAG），存入内存环形缓冲和文件。
 * 当崩溃发生时，CrashHandler 可以获取最近的日志。
 *
 * 用法：
 *   LogCollector.start(context);   // 在首个 Activity 中启动
 *   LogCollector.stop();           // 在退出时停止
 *   LogCollector.getRecentLogs(n); // 获取最近 n 行日志
 *   LogCollector.getLogFile();     // 获取完整日志文件
 */
public class LogCollector {

    private static final String TAG = "LogCollector";
    private static final String LOG_FILE = "ble_log.txt";
    private static final int MAX_BUFFER_LINES = 2000;

    // 过滤的 TAG 列表
    private static final String[] FILTER_TAGS = {
            "BleGattClient", "BleGattServer", "BleAdvertiser", "BleScanner",
            "CallActivity", "DiscoverActivity", "SettingsActivity",
            "AudioRecorder", "AudioPlayer", "EncoderPipeline", "DecoderPipeline",
            "CrashHandler", "LogCollector", "AndroidRuntime", "DEBUG",
            "BluetoothAdapter", "BluetoothGatt", "BluetoothGattServer",
            "BluetoothLeScanner", "BluetoothLeAdvertiser"
    };

    private static final List<String> logBuffer = new ArrayList<>();
    private static Process logcatProcess;
    private static Thread captureThread;
    private static volatile boolean running = false;
    private static Context appContext;
    private static FileWriter fileWriter;
    /** fileWriter 的锁: 采集线程写、崩溃处理器 flush、stop 关闭可能来自不同线程 */
    private static final Object fileLock = new Object();

    /**
     * 启动日志收集
     */
    public static synchronized void start(Context context) {
        if (running) return;
        appContext = context.getApplicationContext();
        running = true;

        // 清空旧缓冲
        synchronized (logBuffer) {
            logBuffer.clear();
        }

        // 打开日志文件 (覆盖模式)
        try {
            File file = getLogFile();
            fileWriter = new FileWriter(file, false);
            String header = "===== CPNT2 BLE 日志 =====\n"
                    + "开始时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "\n设备: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + "\nAndroid: " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")"
                    + "\n========================================\n\n";
            fileWriter.write(header);
            fileWriter.flush();
        } catch (IOException e) {
            Log.e(TAG, "打开日志文件失败", e);
        }

        captureThread = new Thread(() -> {
            try {
                // 构建 logcat 命令
                // -v time 显示时间戳
                // *:S 只显示指定 TAG
                StringBuilder cmd = new StringBuilder("logcat -v time");
                for (String tag : FILTER_TAGS) {
                    cmd.append(" -s ").append(tag).append(":*");
                }

                Log.i(TAG, "启动 logcat: " + cmd);
                logcatProcess = Runtime.getRuntime().exec(cmd.toString());

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(logcatProcess.getInputStream()));

                String line;
                while (running && (line = reader.readLine()) != null) {
                    final String logLine = line;

                    // 写入内存缓冲
                    synchronized (logBuffer) {
                        logBuffer.add(logLine);
                        if (logBuffer.size() > MAX_BUFFER_LINES) {
                            logBuffer.remove(0);
                        }
                    }

                    // 写入文件
                    synchronized (fileLock) {
                        if (fileWriter != null) {
                            try {
                                fileWriter.write(logLine + "\n");
                                fileWriter.flush();
                            } catch (IOException ignored) {}
                        }
                    }
                }
            } catch (IOException e) {
                if (running) {
                    Log.e(TAG, "logcat 捕获异常", e);
                }
            }
        }, "LogCollector");

        captureThread.start();
        Log.i(TAG, "日志收集器已启动");
    }

    /**
     * 停止日志收集
     */
    public static synchronized void stop() {
        running = false;
        if (logcatProcess != null) {
            logcatProcess.destroy();
            logcatProcess = null;
        }
        if (captureThread != null) {
            try { captureThread.join(1000); } catch (InterruptedException ignored) {}
            captureThread = null;
        }
        if (fileWriter != null) {
            synchronized (fileLock) {
                try { fileWriter.flush(); fileWriter.close(); } catch (IOException ignored) {}
                fileWriter = null;
            }
        }
        Log.i(TAG, "日志收集器已停止");
    }

    /**
     * 获取最近的 n 行日志
     */
    public static String getRecentLogs(int lines) {
        synchronized (logBuffer) {
            int size = logBuffer.size();
            int start = Math.max(0, size - lines);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < size; i++) {
                sb.append(logBuffer.get(i)).append("\n");
            }
            return sb.toString();
        }
    }

    /**
     * 立即刷新文件
     */
    public static void flushNow() {
        synchronized (fileLock) {
            if (fileWriter != null) {
                try { fileWriter.flush(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 获取日志文件
     */
    public static File getLogFile() {
        if (appContext == null) return null;
        return new File(appContext.getExternalFilesDir(null), LOG_FILE);
    }

    /**
     * 读取日志文件内容
     */
    public static String readLogFile() {
        File file = getLogFile();
        if (file == null || !file.exists()) return "无日志文件";

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            return "读取日志失败: " + e.getMessage();
        }
        return sb.toString();
    }

    /**
     * 获取崩溃日志文件
     */
    public static File getCrashFile() {
        if (appContext == null) return null;
        return new File(appContext.getExternalFilesDir(null), "crashes/last_crash.txt");
    }

    /**
     * 读取崩溃日志
     */
    public static String readCrashLog() {
        File file = getCrashFile();
        if (file == null || !file.exists()) return null;

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            return "读取崩溃日志失败: " + e.getMessage();
        }
        return sb.toString();
    }

    /**
     * 清除所有日志
     */
    public static void clearAll() {
        synchronized (logBuffer) {
            logBuffer.clear();
        }
        File logFile = getLogFile();
        if (logFile != null && logFile.exists()) logFile.delete();
        File crashFile = getCrashFile();
        if (crashFile != null && crashFile.exists()) crashFile.delete();
    }
}
