package com.cpnt2.demo;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.cpnt2.demo.util.LogCollector;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Locale;

/**
 * 设置页
 *
 * - 显示库版本和支持的码率
 * - 提供 "运行单设备全码率测试" 入口 (无需蓝牙)
 *   使用 assets/hts1a.raw 作为测试音频
 * - 底部 Tab: 发现 / 设置
 */
public class SettingsActivity extends AppCompatActivity {

    private TextView versionText;
    private TextView modesText;
    private Button btnRunTest;
    private ScrollView testResultScroll;
    private TextView testResultText;
    private ScrollView logScroll;
    private TextView logText;

    private short[] pcmData;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuilder = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        versionText = findViewById(R.id.versionText);
        modesText = findViewById(R.id.modesText);
        btnRunTest = findViewById(R.id.btnRunTest);
        testResultScroll = findViewById(R.id.testResultScroll);
        testResultText = findViewById(R.id.testResultText);
        logScroll = findViewById(R.id.logScroll);
        logText = findViewById(R.id.logText);

        findViewById(R.id.tabDiscover).setOnClickListener(v -> finish());

        // 日志相关按钮
        Button btnViewLog = findViewById(R.id.btnViewLog);
        Button btnShareLog = findViewById(R.id.btnShareLog);
        Button btnClearLog = findViewById(R.id.btnClearLog);

        btnViewLog.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            // 先显示崩溃日志（如果有）
            String crashLog = LogCollector.readCrashLog();
            if (crashLog != null) {
                sb.append("========== 崩溃报告 ==========\n");
                sb.append(crashLog).append("\n\n");
            }
            // 再显示 BLE 日志
            sb.append("========== BLE 日志 ==========\n");
            sb.append(LogCollector.readLogFile());
            logText.setText(sb.toString());
            logScroll.setVisibility(View.VISIBLE);
        });

        btnShareLog.setOnClickListener(v -> {
            shareLogs();
        });

        btnClearLog.setOnClickListener(v -> {
            LogCollector.clearAll();
            logScroll.setVisibility(View.GONE);
            Toast.makeText(this, "日志已清除", Toast.LENGTH_SHORT).show();
        });

        Codec2Native codec2 = new Codec2Native();
        versionText.setText(codec2.getVersion());
        String[] modes = codec2.getSupportedModes();
        modesText.setText(String.join(", ", modes));

        // 设备信息
        TextView deviceInfoText = findViewById(R.id.deviceInfoText);
        deviceInfoText.setText(
                "制造商: " + android.os.Build.MANUFACTURER + "\n"
                        + "型号: " + android.os.Build.MODEL + "\n"
                        + "系统: Android " + android.os.Build.VERSION.RELEASE
                        + " (API " + android.os.Build.VERSION.SDK_INT + ")\n"
                        + "应用版本: " + getAppVersionName() + " (code " + getAppVersionCode() + ")");

        loadTestAudio();

        btnRunTest.setOnClickListener(v -> runFullTest(codec2));
    }

    private String getAppVersionName() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private int getAppVersionCode() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private void loadTestAudio() {
        try {
            InputStream is = getAssets().open("hts1a.raw");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
            is.close();
            byte[] raw = baos.toByteArray();
            ShortBuffer sb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            pcmData = new short[sb.remaining()];
            sb.get(pcmData);
        } catch (IOException e) {
            pcmData = null;
        }
    }

    private void runFullTest(Codec2Native codec2) {
        if (pcmData == null) {
            appendLog("未找到测试音频");
            return;
        }
        btnRunTest.setEnabled(false);
        logBuilder.setLength(0);
        testResultScroll.setVisibility(View.VISIBLE);
        appendLog("CPNT2 全码率测试 (assets/hts1a.raw, 13.8s @ 8kHz)\n");

        new Thread(() -> {
            String[] modes = codec2.getSupportedModes();
            int success = 0, fail = 0;
            StringBuilder summary = new StringBuilder();
            summary.append(String.format(Locale.US,
                    "%-8s  %-8s  %-8s  %-10s  %-10s  %-10s\n",
                    "模式", "采样率", "压缩比", "码率(bps)", "编码(ms)", "解码(ms)"));
            summary.append("────────────────────────────────────────────────────────\n");

            for (String mode : modes) {
                long[] stats = codec2.processAll(mode, pcmData);
                int sr = codec2.getSampleRate(mode);
                if (stats != null && stats.length >= 5) {
                    double ratio = Codec2Native.calcCompressionRatio(stats);
                    double bitrate = Codec2Native.calcBitrate(stats, sr);
                    summary.append(String.format(Locale.US,
                            "%-8s  %-8d  %-8.1f  %-10.0f  %-10d  %-10d\n",
                            mode, sr, ratio, bitrate, stats[3], stats[4]));
                    success++;
                } else {
                    summary.append(String.format("%-8s  [失败]\n", mode));
                    fail++;
                }
                final String line = summary.toString();
                handler.post(() -> testResultText.setText(line));
            }

            summary.append("────────────────────────────────────────────────────────\n");
            summary.append(String.format("完成: %d 成功, %d 失败\n", success, fail));
            final String finalText = summary.toString();
            handler.post(() -> {
                testResultText.setText(finalText);
                btnRunTest.setEnabled(true);
            });
        }).start();
    }

    private void appendLog(String s) {
        logBuilder.append(s).append("\n");
        testResultText.setText(logBuilder.toString());
    }

    /**
     * 分享日志文件
     */
    private void shareLogs() {
        try {
            // 合并崩溃日志和 BLE 日志到一个临时文件
            File shareFile = new File(getExternalFilesDir(null), "cpnt2_log_share.txt");
            java.io.FileWriter fw = new java.io.FileWriter(shareFile, false);
            java.io.PrintWriter pw = new java.io.PrintWriter(fw);

            pw.println("CPNT2 诊断日志");
            pw.println("设备: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            pw.println("Android: " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
            pw.println("时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date()));
            pw.println("========================================\n");

            // 崩溃日志
            String crashLog = LogCollector.readCrashLog();
            if (crashLog != null) {
                pw.println("===== 崩溃报告 =====");
                pw.println(crashLog);
                pw.println("\n");
            }

            // BLE 日志
            pw.println("===== BLE 日志 =====");
            pw.println(LogCollector.readLogFile());

            pw.flush();
            pw.close();
            fw.close();

            // 通过分享 Intent 发送
            android.net.Uri uri = FileProvider.getUriForFile(this,
                    getApplicationContext().getPackageName() + ".fileprovider", shareFile);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "CPNT2 BLE 诊断日志");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "分享日志"));
        } catch (Exception e) {
            Toast.makeText(this, "分享失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
