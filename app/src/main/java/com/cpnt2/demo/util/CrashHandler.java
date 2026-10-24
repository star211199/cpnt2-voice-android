package com.cpnt2.demo.util;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃处理器
 *
 * 捕获 Java 层未处理异常，将崩溃堆栈写入文件。
 * 结合 LogCollector 的 logcat 缓冲，可以在下次启动时查看完整崩溃日志。
 *
 * 用法：
 *   CrashHandler.init(context);  // 在 Application 或首个 Activity 的 onCreate 中调用
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private static final String CRASH_DIR = "crashes";
    private static final String CRASH_FILE = "last_crash.txt";

    private static CrashHandler instance;
    private final Context context;
    private Thread.UncaughtExceptionHandler defaultHandler;

    private CrashHandler(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized void init(Context context) {
        if (instance != null) return;
        instance = new CrashHandler(context);
        instance.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(instance);
        Log.i(TAG, "CrashHandler 已注册");
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        Log.e(TAG, "未捕获异常! thread=" + t.getName(), e);

        // 写入崩溃日志文件
        writeCrashLog(t, e);

        // 如果 LogCollector 在运行，让它立即 flush 日志
        LogCollector.flushNow();

        // 交给默认处理器（让系统弹出"应用已停止"对话框）
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(t, e);
        }
    }

    private void writeCrashLog(Thread t, Throwable e) {
        try {
            File dir = new File(context.getExternalFilesDir(null), CRASH_DIR);
            if (!dir.exists()) dir.mkdirs();

            File file = new File(dir, CRASH_FILE);
            FileWriter fw = new FileWriter(file, false); // 覆盖写
            PrintWriter pw = new PrintWriter(fw);

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
            pw.println("===== CPNT2 崩溃报告 =====");
            pw.println("时间: " + sdf.format(new Date()));
            pw.println("进程 PID: " + Process.myPid());
            pw.println("线程: " + t.getName());
            pw.println("设备: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            pw.println("Android: " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
            pw.println();

            pw.println("----- 异常堆栈 -----");
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            pw.println(sw.toString());
            pw.println();

            // 写入 logcat 缓冲的最近日志
            pw.println("----- 最近日志 (来自 LogCollector) -----");
            String recentLogs = LogCollector.getRecentLogs(200);
            pw.println(recentLogs);

            pw.flush();
            pw.close();
            fw.close();

            Log.i(TAG, "崩溃日志已写入: " + file.getAbsolutePath());
        } catch (IOException ex) {
            Log.e(TAG, "写入崩溃日志失败", ex);
        }
    }

    /**
     * 检查上次是否有崩溃记录
     */
    public static File getLastCrashFile(Context context) {
        File file = new File(context.getExternalFilesDir(null), CRASH_DIR + "/" + CRASH_FILE);
        return file.exists() ? file : null;
    }

    /**
     * 清除崩溃记录
     */
    public static void clearLastCrash(Context context) {
        File file = new File(context.getExternalFilesDir(null), CRASH_DIR + "/" + CRASH_FILE);
        if (file.exists()) file.delete();
    }
}
