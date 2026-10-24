package com.cpnt2.demo;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.cpnt2.demo.audio.AudioPlayer;
import com.cpnt2.demo.audio.AudioRecorder;
import com.cpnt2.demo.audio.CodecSpec;
import com.cpnt2.demo.audio.DecoderPipeline;
import com.cpnt2.demo.audio.EncoderPipeline;
import com.cpnt2.demo.audio.NeuralEngineProvider;
import com.cpnt2.demo.audio.TestSource;
import com.cpnt2.demo.ble.BleAdvertiser;
import com.cpnt2.demo.ble.BleGattClient;
import com.cpnt2.demo.ble.BleGattServer;
import com.cpnt2.demo.ble.BleGattServerHolder;
import com.cpnt2.demo.ble.BleProtocol;
import com.cpnt2.demo.util.PermissionManager;
import com.google.android.flexbox.FlexboxLayout;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通话页
 *
 * 三种模式：
 *   - 主动连接另一台设备 (传入 EXTRA_REMOTE_DEVICE) -> 作为 GATT Client (中央)
 *     连接成功后同时启动编码端和解码端，实现双向通话
 *   - 作为 Server 端被连接 (启动时未传 device) -> 同时也是 GATT Server
 *     启动时即开始编码发送，连接建立后启动解码接收
 *   - 本地回环测试 (EXTRA_LOOPBACK=true) -> 无需蓝牙，编码后直接本地解码播放
 *
 * 音频链路 (编码端 → 发送)：
 *   AudioRecorder / TestSource -> EncoderPipeline -> BLE notify/write 或 本地解码器
 *
 * 音频链路 (接收 → 解码端)：
 *   BLE receive -> DecoderPipeline -> AudioPlayer -> 扬声器
 */
public class CallActivity extends AppCompatActivity {

    private static final String TAG = "CallActivity";
    private static final int REQ_PERM_AUDIO = 2001;

    public static final String EXTRA_REMOTE_DEVICE = "remote_device";
    public static final String EXTRA_REMOTE_NAME = "remote_name";
    public static final String EXTRA_ROLE = "role";
    public static final String EXTRA_LOOPBACK = "loopback";
    /** 指定初始编解码模式（内部模式名，如 "1300" / "NEURAL300"），便于深链/自动化测试 */
    public static final String EXTRA_MODE = "mode";

    public static final byte ROLE_ENCODER = 1;
    public static final byte ROLE_DECODER = 2;

    /**
     * 码率 chips 使用的全部真实模式。
     * 修复：原实现把 1000/800 都映射到 1200 模式、600 映射到 700C、400 映射到 450，
     * 导致 UI 显示值与实际码率不符且选中态错乱。现在 1:1 使用 CPNT2 全部 9 种模式。
     */
    private static final String[] BITRATE_MODES = {
            "3200", "2400", "1600", "1400", "1300", "1200", "700C", "450", "450PWB"
    };

    // UI
    private TextView remoteName;
    private TextView remoteAvatar;
    private TextView callStatus;
    private TextView encodeBitrate;
    private TextView decodeBitrate;
    private TextView codecMeta;
    private FlexboxLayout bitrateChips;
    private FlexboxLayout sourceChips;
    private View btnMute;
    private ImageView btnMuteIcon;
    private TextView btnMuteLabel;
    private View btnHangup;
    private View btnSpeaker;
    private ImageView btnSpeakerIcon;
    private TextView btnSpeakerLabel;

    // 状态
    private String remoteDeviceName = "对方";
    private BluetoothDevice remoteDevice;
    private byte myRole = ROLE_DECODER;
    private boolean loopback = false;

    private String currentMode = "1200";
    private int currentSource = TestSource.SOURCE_MIC;
    private boolean muted = false;
    private boolean speakerOn = true;
    private boolean hangingUp = false;

    // 通话计时
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private long callStartTime = 0;
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (callStartTime == 0) return;
            long elapsed = (System.currentTimeMillis() - callStartTime) / 1000;
            String text = String.format(Locale.US, "通话中 %02d:%02d", elapsed / 60, elapsed % 60);
            callStatus.setText(text);
            timerHandler.postDelayed(this, 1000);
        }
    };

    // BLE
    private BleGattServer gattServer;
    private BleGattClient gattClient;
    private BleAdvertiser advertiser;

    // 音频
    private AudioRecorder audioRecorder;
    private AudioPlayer audioPlayer;
    private EncoderPipeline encoder;
    private DecoderPipeline decoder;
    private final double[] testSourcePhase = new double[]{0};
    /** 测试源生成线程 — 修复：切换音源/码率时必须先停掉旧线程，否则会叠音 */
    private Thread testSourceThread;

    // 码率切换时编码/解码的统计
    private final AtomicLong encodedBytes = new AtomicLong(0);
    private final AtomicLong encodedFrames = new AtomicLong(0);
    private final AtomicLong decodedBytes = new AtomicLong(0);
    private final AtomicLong decodedFrames = new AtomicLong(0);
    private final AtomicBoolean started = new AtomicBoolean(false);

    private final Handler handler = new Handler(Looper.getMainLooper());

    // ===== Lifecycle =====

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        // 通话过程中保持屏幕常亮（避免演示时熄屏）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 初始化神经编解码引擎的模型目录（供 buildBitrateChips 判断神经模式可用性）
        NeuralEngineProvider.init(this);

        // 解析 Intent
        remoteDevice = getIntent().getParcelableExtra(EXTRA_REMOTE_DEVICE);
        remoteDeviceName = getIntent().getStringExtra(EXTRA_REMOTE_NAME);
        if (remoteDeviceName == null) remoteDeviceName = "对方";
        myRole = getIntent().getByteExtra(EXTRA_ROLE, ROLE_DECODER);
        loopback = getIntent().getBooleanExtra(EXTRA_LOOPBACK, false);
        // 可选：深链指定初始编解码模式
        String forceMode = getIntent().getStringExtra(EXTRA_MODE);
        if (forceMode != null && forceMode.length() > 0) {
            currentMode = forceMode;
        }

        // UI 绑定
        remoteName = findViewById(R.id.remoteName);
        remoteAvatar = findViewById(R.id.remoteAvatar);
        callStatus = findViewById(R.id.callStatus);
        encodeBitrate = findViewById(R.id.encodeBitrate);
        decodeBitrate = findViewById(R.id.decodeBitrate);
        codecMeta = findViewById(R.id.codecMeta);
        bitrateChips = findViewById(R.id.bitrateChips);
        sourceChips = findViewById(R.id.sourceChips);
        btnMute = findViewById(R.id.btnMute);
        btnMuteIcon = findViewById(R.id.btnMuteIcon);
        btnMuteLabel = findViewById(R.id.btnMuteLabel);
        btnHangup = findViewById(R.id.btnHangup);
        btnSpeaker = findViewById(R.id.btnSpeaker);
        btnSpeakerIcon = findViewById(R.id.btnSpeakerIcon);
        btnSpeakerLabel = findViewById(R.id.btnSpeakerLabel);

        remoteName.setText(remoteDeviceName);
        // 头像首字母
        String firstChar = remoteDeviceName.length() > 0
                ? remoteDeviceName.substring(remoteDeviceName.length() - 1) : "C";
        remoteAvatar.setText(firstChar);

        buildBitrateChips();
        buildSourceChips();

        btnMute.setOnClickListener(v -> toggleMute());
        btnSpeaker.setOnClickListener(v -> toggleSpeaker());
        btnHangup.setOnClickListener(v -> hangup());

        // 初始化按钮视觉状态
        applyMuteButtonState();
        applySpeakerButtonState();

        // 默认扬声器外放
        setSpeakerOn(true);

        // 标记为已启动
        started.set(true);

        // 主动请求麦克风权限（不论编/解码角色都需要）
        if (!PermissionManager.hasAudioPermissions(this)) {
            PermissionManager.requestAudioPermissions(this, REQ_PERM_AUDIO);
        }

        // 启动
        if (loopback) {
            // 本地回环测试：无需蓝牙，编码 → 本地解码 → 扬声器
            callStatus.setText("本地回环测试中");
            startLoopback();
        } else if (remoteDevice != null) {
            // 主动连接对方 (作为 Client/中央)
            callStatus.setText("正在连接...");
            connectAsClient();
        } else {
            // 没有 device, 作为 Server 端
            // 可能是来电模式 (DiscoverActivity 检测到对方连接后跳转)
            callStatus.setText("等待对方连接...");
            startAsServer();
        }

        // 启动码率刷新定时器
        handler.post(bitrateUpdateRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hangup();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM_AUDIO) {
            // 修复：grantResults 可能为空 (用户点击外部关闭对话框)
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
                // 权限已授予，启动编码端
                if (myRole == ROLE_ENCODER) {
                    startEncoderPipeline();
                }
            } else {
                if (PermissionManager.shouldShowAudioRationale(this)) {
                    // 用户拒绝但没选"不再询问"，可再次请求
                    Toast.makeText(this, "需要麦克风权限以录制语音，请重新授权", Toast.LENGTH_LONG).show();
                    PermissionManager.requestAudioPermissions(this, REQ_PERM_AUDIO);
                } else {
                    // 用户永久拒绝，引导去设置，并自动切换到测试音源
                    Toast.makeText(this, "麦克风权限被永久拒绝，请到设置中手动开启", Toast.LENGTH_LONG).show();
                    switchSource(TestSource.SOURCE_SINE);
                    // switchSource 在编码端角色下已自动重建编码管线
                }
            }
        }
    }

    // ===== UI 构建 =====

    /**
     * 构建码率 chip 列表：全部 9 种 CPNT2 模式 + 神经编解码模式（模型就位时才显示）。
     * 用 tag 记录内部模式名，显示文本用 CodecSpec.label。
     */
    private void buildBitrateChips() {
        for (String mode : BITRATE_MODES) {
            addModeChip(mode);
        }
        // 神经编解码（ClariCodec）：模型推送到位才显示对应码率
        if (NeuralEngineProvider.hasBps("300")) {
            addModeChip(CodecSpec.NEURAL_300);
        }
        if (NeuralEngineProvider.hasBps("150")) {
            addModeChip(CodecSpec.NEURAL_150);
        }
        refreshChipSelection();
    }

    private void addModeChip(final String mode) {
        Button chip = new Button(this);
        chip.setText(CodecSpec.label(mode));
        chip.setTag(mode);
        chip.setAllCaps(false);
        chip.setBackgroundResource(R.drawable.bg_btn_chip);
        chip.setTextSize(12);
        chip.setTextColor(getResources().getColorStateList(R.color.chip_text_color));
        chip.setSingleLine(true);            // 单行显示，避免多芯片时逐字竖排
        chip.setPadding(dp(4), dp(0), dp(4), dp(0));
        // 固定尺寸 + flexWrap：形成整齐的等宽网格（约 5 列），间距均匀不拥挤
        FlexboxLayout.LayoutParams lp = new FlexboxLayout.LayoutParams(dp(58), dp(36));
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            currentMode = mode;
            refreshChipSelection();
            rebuildEncoder();
            notifyPeerBitrateChange(mode);
        });
        bitrateChips.addView(chip);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void refreshChipSelection() {
        for (int i = 0; i < bitrateChips.getChildCount(); i++) {
            View v = bitrateChips.getChildAt(i);
            if (v instanceof Button) {
                Button b = (Button) v;
                Object tag = b.getTag();
                b.setSelected(tag != null && tag.equals(currentMode));
            }
        }
    }

    private void buildSourceChips() {
        String[] labels = { "麦克风", "英语女声", "音乐" };
        int[] sources = { TestSource.SOURCE_MIC, TestSource.SOURCE_SINE, TestSource.SOURCE_MUSIC };

        for (int i = 0; i < labels.length; i++) {
            final int src = sources[i];
            Button chip = new Button(this);
            chip.setText(labels[i]);
            chip.setAllCaps(false);
            chip.setBackgroundResource(R.drawable.bg_btn_chip);
            chip.setTextSize(13);
            chip.setTextColor(getResources().getColorStateList(R.color.chip_text_color));
            chip.setMinWidth(72);
            chip.setPadding(24, 8, 24, 8);
            FlexboxLayout.LayoutParams lp = new FlexboxLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(6, 6, 6, 6);
            chip.setLayoutParams(lp);
            chip.setTag(src); // 记录音源类型，供 switchSource 高亮
            chip.setOnClickListener(v -> {
                switchSource(src);
                // 如果当前是解码端，切换为编码端以发送音频
                if (myRole == ROLE_DECODER) {
                    myRole = ROLE_ENCODER;
                    startEncoderPipeline();
                }
            });
            sourceChips.addView(chip);
        }
        // 默认选中麦克风
        if (sourceChips.getChildCount() > 0) {
            ((Button) sourceChips.getChildAt(0)).setSelected(true);
        }
    }

    /**
     * 切换音源并刷新 chip 选中态
     */
    private void switchSource(int src) {
        currentSource = src;
        for (int j = 0; j < sourceChips.getChildCount(); j++) {
            View v2 = sourceChips.getChildAt(j);
            if (v2 instanceof Button) {
                ((Button) v2).setSelected(false);
            }
        }
        // 通过 tag 记录音源类型，便于正确高亮
        for (int j = 0; j < sourceChips.getChildCount(); j++) {
            View v2 = sourceChips.getChildAt(j);
            if (v2 instanceof Button && v2.getTag() != null) {
                if ((int) v2.getTag() == src) {
                    ((Button) v2).setSelected(true);
                }
            }
        }
        // 若编码端已在运行，重启编码管线以切换输入
        if (myRole == ROLE_ENCODER && started.get()) {
            rebuildEncoder();
        }
    }

    // ===== BLE 初始化 =====

    @SuppressLint("MissingPermission")
    private void startAsServer() {
        myRole = ROLE_ENCODER;

        // 检查是否有 DiscoverActivity 已启动的 GATT Server (来电模式)
        BleGattServerHolder holder = BleGattServerHolder.getInstance();
        BleGattServer existing = holder.takeGattServer();
        if (existing != null) {
            // 接管已有的 GATT Server，只更新 listener
            gattServer = existing;
            gattServer.updateListener(createServerListener());
            // 如果对方已经连接（来电模式），直接进入通话
            BluetoothDevice connectedDevice = holder.getConnectedDevice();
            holder.clearIncomingConnection();
            if (connectedDevice != null) {
                callStartTime = System.currentTimeMillis();
                timerHandler.post(timerRunnable);
                callStatus.setText("通话中 00:00");
                try {
                    startAudioReceiveSide();
                } catch (Exception e) {
                    Log.e(TAG, "启动接收侧异常", e);
                    Toast.makeText(this, "音频初始化异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            } else {
                callStatus.setText("等待对方连接...");
            }
        } else {
            // 没有已有的 GATT Server，创建新的
            gattServer = new BleGattServer(this);
            gattServer.setListener(createServerListener());
            gattServer.start();
            callStatus.setText("等待对方连接...");
        }

        // 启动编码端（发送本地音频）
        try {
            startEncoderPipeline();
        } catch (Exception e) {
            Log.e(TAG, "启动编码端异常", e);
            Toast.makeText(this, "编码器初始化异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Server 端 Listener (已连接/断开/收帧/收控制/错误/Service 就绪)
     * 提取为公共方法，避免 existing/new 两处重复
     */
    private BleGattServer.ServerListener createServerListener() {
        return new BleGattServer.ServerListener() {
            @Override
            public void onCentralConnected(BluetoothDevice device) {
                runOnUiThread(() -> {
                    try {
                        Toast.makeText(CallActivity.this, "对方已连接", Toast.LENGTH_SHORT).show();
                        callStartTime = System.currentTimeMillis();
                        timerHandler.post(timerRunnable);
                        callStatus.setText("通话中 00:00");
                        startAudioReceiveSide();
                    } catch (Exception e) {
                        Log.e(TAG, "onCentralConnected 处理异常", e);
                    }
                });
            }

            @Override
            public void onCentralDisconnected(BluetoothDevice device) {
                runOnUiThread(() -> {
                    try {
                        Toast.makeText(CallActivity.this, "对方已断开", Toast.LENGTH_SHORT).show();
                        timerHandler.removeCallbacks(timerRunnable);
                        callStatus.setText("已断开");
                        stopAudioReceiveSide();
                    } catch (Exception e) {
                        Log.e(TAG, "onCentralDisconnected 处理异常", e);
                    }
                });
            }

            @Override
            public void onAudioFrameReceived(BluetoothDevice device, byte[] encodedFrame, int codecId) {
                if (decoder != null) {
                    decoder.submitFrame((byte) codecId, encodedFrame);
                    decodedFrames.incrementAndGet();
                    decodedBytes.addAndGet(encodedFrame.length);
                }
            }

            @Override
            public void onControlReceived(BluetoothDevice device, byte subtype, byte[] payload) {
                handleControlReceived(subtype, payload);
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> Toast.makeText(CallActivity.this, "GATT 错误: " + msg, Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onServiceReady() {
                Log.i(TAG, "GATT Server Service 已就绪，启动广播");
                runOnUiThread(() -> {
                    if (advertiser == null) {
                        advertiser = new BleAdvertiser(CallActivity.this);
                        advertiser.start(new BleAdvertiser.AdvertiseListener() {
                            @Override
                            public void onStartSuccess() {
                                Log.i(TAG, "CallActivity 广播启动成功");
                            }

                            @Override
                            public void onStartFailure(int errorCode, String message) {
                                Log.e(TAG, "CallActivity 广播失败: " + errorCode + " " + message);
                            }
                        });
                    }
                });
            }
        };
    }

    @SuppressLint("MissingPermission")
    private void connectAsClient() {
        myRole = ROLE_DECODER;
        gattClient = new BleGattClient(this);
        gattClient.setListener(new BleGattClient.ClientListener() {
            @Override
            public void onConnected(BluetoothDevice device) {
                runOnUiThread(() -> callStatus.setText("已连接, 准备就绪..."));
            }

            @Override
            public void onDisconnected(BluetoothDevice device) {
                runOnUiThread(() -> {
                    callStatus.setText("已断开");
                    timerHandler.removeCallbacks(timerRunnable);
                    stopAudioReceiveSide();
                });
            }

            @Override
            public void onServicesDiscovered() {
                runOnUiThread(() -> {
                    try {
                        callStartTime = System.currentTimeMillis();
                        timerHandler.post(timerRunnable);
                        callStatus.setText("通话中 00:00");
                        // 启动解码端（接收对方音频）
                        startAudioReceiveSide();
                        // 启动编码端（发送本机音频）— 双向通话
                        myRole = ROLE_ENCODER;
                        try {
                            startEncoderPipeline();
                        } catch (Exception e) {
                            Log.e(TAG, "启动编码端异常 (client)", e);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "onServicesDiscovered 处理异常", e);
                        Toast.makeText(CallActivity.this,
                                "音频初始化异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onAudioFrameReceived(byte[] encodedFrame, int codecId) {
                if (decoder != null) {
                    decoder.submitFrame((byte) codecId, encodedFrame);
                    decodedFrames.incrementAndGet();
                    decodedBytes.addAndGet(encodedFrame.length);
                }
            }

            @Override
            public void onControlReceived(byte subtype, byte[] payload) {
                handleControlReceived(subtype, payload);
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> {
                    // 更新状态文字（而不是弹 toast，避免干扰）
                    if (callStatus.getText().toString().startsWith("正在连接")
                            || callStatus.getText().toString().startsWith("已连接")) {
                        callStatus.setText("连接异常: " + msg);
                    }
                    Toast.makeText(CallActivity.this, msg, Toast.LENGTH_SHORT).show();
                });
            }
        });

        // 关键修复：延迟 500ms 再连接，确保 DiscoverActivity 的扫描已完全停止
        // 扫描和连接共用蓝牙天线，同时操作会导致连接超时
        callStatus.setText("正在连接...");
        handler.postDelayed(() -> {
            if (gattClient != null && remoteDevice != null) {
                gattClient.connect(remoteDevice);
            }
        }, 500);
    }

    /**
     * 处理收到的控制帧（Client / Server 共用）
     */
    private void handleControlReceived(byte subtype, byte[] payload) {
        Log.d(TAG, "收到 control subtype=" + subtype);
        if (subtype == BleProtocol.CTRL_BITRATE_CHANGE && payload != null && payload.length >= 1) {
            final String mode = CodecSpec.label(CodecSpec.fromWire(payload[0]));
            runOnUiThread(() -> Toast.makeText(this,
                    "对方切换码率: " + mode, Toast.LENGTH_SHORT).show());
        }
    }

    /**
     * 码率切换时通知对端（解码端会自动适配，此处仅用于 UI 即时反馈）
     */
    private void notifyPeerBitrateChange(String mode) {
        if (loopback) return;
        byte[] payload = new byte[]{CodecSpec.wireId(mode)};
        if (gattServer != null) {
            gattServer.broadcastControl(BleProtocol.CTRL_BITRATE_CHANGE, payload);
        } else if (gattClient != null) {
            gattClient.sendControl(BleProtocol.CTRL_BITRATE_CHANGE, payload);
        }
    }

    // ===== 本地回环测试 =====

    /**
     * 本地回环：编码器输出直接送入本地解码器播放，验证整条音频链路
     */
    private void startLoopback() {
        myRole = ROLE_ENCODER;
        startAudioReceiveSide();
        startEncoderPipeline();
        callStartTime = System.currentTimeMillis();
        timerHandler.post(timerRunnable);
    }

    // ===== 音频管线 =====

    private void startEncoderPipeline() {
        if (myRole != ROLE_ENCODER) return;
        // 检查权限
        if (currentSource == TestSource.SOURCE_MIC && !PermissionManager.hasAudioPermissions(this)) {
            PermissionManager.requestAudioPermissions(this, REQ_PERM_AUDIO);
            return;
        }

        if (encoder != null) encoder.stop();
        encoder = new EncoderPipeline(currentMode, new EncoderPipeline.Sink() {
            @Override
            public void onEncodedFrame(byte codecId, byte[] encoded) {
                encodedFrames.incrementAndGet();
                encodedBytes.addAndGet(encoded.length);
                if (loopback) {
                    // 本地回环：直接送解码器
                    if (decoder != null) {
                        decoder.submitFrame(codecId, encoded);
                        decodedFrames.incrementAndGet();
                        decodedBytes.addAndGet(encoded.length);
                    }
                    return;
                }
                // BLE 发送
                if (gattServer != null) {
                    gattServer.broadcastAudioFrame(codecId, encoded);
                } else if (gattClient != null) {
                    gattClient.sendAudioFrame(codecId, encoded);
                }
            }

            @Override
            public void onEncodeError(String msg) {
                Log.e(TAG, "encode error: " + msg);
            }
        });
        encoder.start();

        // 启动麦克风或测试源
        if (currentSource == TestSource.SOURCE_MIC) {
            startMicInput();
        } else {
            startTestSourceInput();
        }
    }

    private void startMicInput() {
        if (audioRecorder != null) audioRecorder.stop();
        final EncoderPipeline enc = encoder; // 捕获当前实例，避免切换/挂断时 encoder 被置空导致 NPE
        if (enc == null) return;
        audioRecorder = new AudioRecorder(enc.getSampleRate());
        audioRecorder.start(new AudioRecorder.RecorderListener() {
            @Override
            public void onPcmData(short[] pcm, int sampleCount) {
                // 静音时丢弃麦克风数据（测试源由生成线程自行暂停）
                if (muted) return;
                if (!enc.isRunning()) return; // 编码器已停止/被替换，丢弃该块
                enc.submitPcm(pcm, sampleCount);
            }

            @Override
            public void onError(String msg) {
                Log.e(TAG, "recorder error: " + msg);
                runOnUiThread(() -> Toast.makeText(CallActivity.this,
                        "录音错误: " + msg, Toast.LENGTH_SHORT).show());
            }
        });
    }

    /**
     * 启动测试源线程 (无麦克风权限时使用)
     * 修复：先停止旧线程再启动，避免切换音源/码率时多个生成线程叠音
     */
    private void startTestSourceInput() {
        stopTestSourceInput();
        final EncoderPipeline enc = encoder; // 捕获当前实例，旧线程停止后不会向新编码器喂数据
        testSourceThread = new Thread(() -> {
            int sampleRate = enc.getSampleRate();
            int chunkSamples = sampleRate / 1000 * 40; // 40ms
            testSourcePhase[0] = 0;
            while (enc.isRunning() && started.get()) {
                if (muted) {
                    // 静音：暂停生成，等解除静音
                    try { Thread.sleep(40); } catch (InterruptedException e) { break; }
                    continue;
                }
                short[] pcm = TestSource.generate(currentSource, sampleRate, chunkSamples, testSourcePhase);
                enc.submitPcm(pcm, pcm.length);
                try { Thread.sleep(40); } catch (InterruptedException ignored) { break; }
            }
        }, "TestSource-Generator");
        testSourceThread.start();
    }

    private void stopTestSourceInput() {
        if (testSourceThread != null) {
            testSourceThread.interrupt();
            testSourceThread = null;
        }
    }

    private void rebuildEncoder() {
        if (myRole != ROLE_ENCODER) return;
        if (audioRecorder != null) {
            audioRecorder.stop();
            audioRecorder = null;
        }
        stopTestSourceInput();
        startEncoderPipeline();
    }

    private void startAudioReceiveSide() {
        // 解码管线
        if (decoder == null) {
            decoder = new DecoderPipeline(new DecoderPipeline.Sink() {
                @Override
                public void onPcmFrame(short[] pcm) {
                    if (audioPlayer != null) {
                        audioPlayer.submit(pcm);
                    }
                }

                @Override
                public void onDecodeError(String msg) {
                    Log.e(TAG, "decode error: " + msg);
                }

                @Override
                public void onModeChanged(String mode, int sampleRate) {
                    // 修复：对端切换码率导致采样率变化时重建播放器，否则 8k/16k 混用会变速
                    Log.i(TAG, "解码模式切换: " + mode + " @ " + sampleRate + "Hz");
                    if (audioPlayer != null && audioPlayer.getSampleRate() != sampleRate) {
                        boolean spk = speakerOn;
                        audioPlayer.stop();
                        audioPlayer = new AudioPlayer(sampleRate, spk);
                        audioPlayer.start();
                    }
                }
            });
            decoder.start();
        }
        // 播放
        if (audioPlayer == null) {
            audioPlayer = new AudioPlayer(CodecSpec.sampleRate(currentMode), speakerOn);
            audioPlayer.start();
        }
    }

    private void stopAudioReceiveSide() {
        if (audioPlayer != null) {
            audioPlayer.stop();
            audioPlayer = null;
        }
        if (decoder != null) {
            decoder.stop();
            decoder = null;
        }
    }

    // ===== 控件 =====

    /**
     * 静音：对所有音源生效
     *  - 麦克风：停掉 recorder
     *  - 测试源：生成线程检测 muted 标志自动暂停
     */
    private void toggleMute() {
        muted = !muted;
        applyMuteButtonState();
        if (currentSource == TestSource.SOURCE_MIC) {
            if (muted) {
                // 静音：停掉 recorder 但保留 encoder
                if (audioRecorder != null) {
                    audioRecorder.stop();
                    audioRecorder = null;
                }
            } else {
                // 解除静音：如果编码端在运行则恢复麦克风
                if (encoder != null && encoder.isRunning() && myRole == ROLE_ENCODER) {
                    startMicInput();
                }
            }
        }
        // 测试源：无需额外处理，生成线程在 muted 时自动暂停
    }

    /**
     * 应用静音按钮的视觉状态
     * - 未静音：白底 + 紫色 mic 图标 + 灰文字 "静音"
     * - 静音：紫色填充背景 + 白色 mic_off 图标 + 紫色文字 "已静音"
     */
    private void applyMuteButtonState() {
        if (muted) {
            btnMute.setActivated(true);
            btnMute.setSelected(true);
            btnMuteIcon.setImageResource(R.drawable.ic_mic_off_white);
            btnMuteLabel.setText("已静音");
        } else {
            btnMute.setActivated(false);
            btnMute.setSelected(false);
            btnMuteIcon.setImageResource(R.drawable.ic_mic);
            btnMuteLabel.setText(R.string.btn_mute);
        }
    }

    private void toggleSpeaker() {
        speakerOn = !speakerOn;
        setSpeakerOn(speakerOn);
    }

    /**
     * 免提 / 听筒模式切换
     *
     * 核心原理：
     *   免提 → AudioPlayer 用 USAGE_MEDIA（扬声器大音量），AudioManager MODE_NORMAL
     *   听筒 → AudioPlayer 用 USAGE_VOICE_COMMUNICATION（听筒），
     *          AudioManager MODE_IN_COMMUNICATION + setSpeakerphoneOn(false)
     */
    @SuppressLint("MissingPermission")
    private void setSpeakerOn(boolean on) {
        speakerOn = on;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            if (on) {
                // === 免提模式 ===
                am.setMode(AudioManager.MODE_NORMAL);
                // 不调 setSpeakerphoneOn — 会跟 USAGE_MEDIA 冲突导致音量变小
                int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int targetVol = Math.max(maxVol * 9 / 10, maxVol - 2);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0);
                Log.i(TAG, "免提: MODE_NORMAL 媒体音量=" + targetVol + "/" + maxVol);
            } else {
                // === 听筒模式 ===
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.setSpeakerphoneOn(false);
                int maxCall = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                int targetCall = Math.max(maxCall * 7 / 10, maxCall - 2);
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetCall, 0);
                Log.i(TAG, "听筒: MODE_IN_COMMUNICATION 通话音量=" + targetCall + "/" + maxCall);
            }
        }
        // 重建 AudioTrack 以切换 usage
        if (audioPlayer != null) {
            audioPlayer.setSpeakerMode(on);
        }
        applySpeakerButtonState();
    }

    /**
     * 应用免提按钮的视觉状态
     * - 免提关闭（听筒）：白底 + 紫色 headset 图标 + 灰文字 "免提"
     * - 免提开启：紫色填充背景 + 白色 speaker 图标 + 紫色文字 "扬声器"
     */
    private void applySpeakerButtonState() {
        if (speakerOn) {
            btnSpeaker.setActivated(true);
            btnSpeaker.setSelected(true);
            btnSpeakerIcon.setImageResource(R.drawable.ic_speaker_white);
            btnSpeakerLabel.setText("扬声器");
        } else {
            btnSpeaker.setActivated(false);
            btnSpeaker.setSelected(false);
            btnSpeakerIcon.setImageResource(R.drawable.ic_headset);
            btnSpeakerLabel.setText(R.string.btn_speaker);
        }
    }

    /**
     * 挂断：幂等，可被按钮/onDestroy 重复调用
     */
    private void hangup() {
        if (hangingUp) return;
        hangingUp = true;
        started.set(false);
        timerHandler.removeCallbacks(timerRunnable);
        stopTestSourceInput();
        if (encoder != null) { encoder.stop(); encoder = null; }
        if (decoder != null) { decoder.stop(); decoder = null; }
        if (audioRecorder != null) { audioRecorder.stop(); audioRecorder = null; }
        if (audioPlayer != null) { audioPlayer.stop(); audioPlayer = null; }
        if (advertiser != null) { advertiser.stop(); advertiser = null; }
        if (gattServer != null) { gattServer.stop(); gattServer = null; }
        if (gattClient != null) { gattClient.disconnect(); gattClient = null; }
        BleGattServerHolder.getInstance().clear();
        // 释放神经编解码引擎（回收约 1.2GB 内存）
        NeuralEngineProvider.releaseAll();
        handler.removeCallbacks(bitrateUpdateRunnable);
        // onDestroy 期间无需再 finish()
        if (!isFinishing() && !isDestroyed()) {
            finish();
        }
    }

    // ===== 码率显示刷新 =====

    private final Runnable bitrateUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            // 编码码率：按真实帧率计算 (450PWB 为 640 样本/帧 @16kHz, 25fps)
            if (encodedFrames.get() > 0) {
                encodeBitrate.setText(describeBitrate(currentMode));
            } else {
                encodeBitrate.setText("--");
            }

            if (decodedFrames.get() > 0 && decoder != null && decoder.getCurrentMode() != null) {
                decodeBitrate.setText(describeBitrate(decoder.getCurrentMode()));
            } else {
                decodeBitrate.setText("--");
            }

            // 信息卡副标题随当前编解码模式变化（神经: 16kHz/80ms；CPNT2: 8kHz/40ms）
            if (codecMeta != null) {
                codecMeta.setText(describeCodecMeta(currentMode));
            }
            handler.postDelayed(this, 500);
        }
    };

    /**
     * 计算并格式化某模式的显示码率。
     * 神经编解码直接给出标称码率（300/150 bps）；CPNT2 按 bits/帧 × 帧率。
     */
    private static String describeBitrate(String mode) {
        if (CodecSpec.NEURAL_300.equals(mode)) return "300 bps";
        if (CodecSpec.NEURAL_150.equals(mode)) return "150 bps";
        int bitsPerFrame = Codec2Native.bitsPerFrameFromName(mode);
        double bps = bitsPerFrame * Codec2Native.frameRateForMode(mode);
        return formatBps(bps);
    }

    /** 信息卡副标题：随编解码模式给出采样率 / 帧长 / 编解码器名称。 */
    private static String describeCodecMeta(String mode) {
        if (CodecSpec.isNeural(mode)) {
            return "BLE · 16 kHz · 80 ms · ClariCodec 神经";
        }
        return "BLE · 8 kHz · 40 ms · CPNT2";
    }

    private static String formatBps(double bps) {
        if (bps >= 1000) {
            return String.format(Locale.US, "%.1f kbps", bps / 1000.0);
        }
        return String.format(Locale.US, "%.0f bps", bps);
    }
}
