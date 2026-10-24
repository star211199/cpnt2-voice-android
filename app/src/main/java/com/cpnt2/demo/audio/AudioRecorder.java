package com.cpnt2.demo.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 麦克风录音器
 *
 * 工作在固定采样率 (8kHz 或 16kHz)，输出 PCM short[] 块，
 * 由 AudioStreamPipeline 读取并送入编码器。
 *
 * 双向通话优化：
 *   - 使用 VOICE_COMMUNICATION 音源（启用硬件 AEC/NS）
 *   - 回退到 MIC 如果 VOICE_COMMUNICATION 不可用
 *   - 显式添加 AcousticEchoCanceler 和 NoiseSuppressor
 */
public class AudioRecorder {

    private static final String TAG = "AudioRecorder";

    public static final int DEFAULT_SAMPLE_RATE = 8000;

    private final int sampleRate;
    private final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord audioRecord;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private Thread readThread;
    private final AtomicBoolean recording = new AtomicBoolean(false);

    public interface RecorderListener {
        /** 录音产生一块 PCM 数据 (16kHz 时 sampleCount = 320*2) */
        void onPcmData(short[] pcm, int sampleCount);
        void onError(String msg);
    }

    public AudioRecorder(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    @SuppressLint("MissingPermission")
    public void start(RecorderListener listener) {
        if (recording.get()) return;
        int minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        if (minBuf <= 0) {
            listener.onError("AudioRecord.getMinBufferSize 失败: " + minBuf);
            return;
        }
        // 4x 最小缓冲 (大约 200ms)
        int bufSize = minBuf * 4;

        // 优先使用 VOICE_COMMUNICATION 音源（启用硬件回声消除）
        // 不支持时回退到 MIC
        int audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        try {
            audioRecord = new AudioRecord(audioSource,
                    sampleRate, channelConfig, audioFormat, bufSize);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "VOICE_COMMUNICATION 音源初始化失败, 回退到 MIC");
                audioRecord.release();
                audioRecord = null;
                audioSource = MediaRecorder.AudioSource.MIC;
                audioRecord = new AudioRecord(audioSource,
                        sampleRate, channelConfig, audioFormat, bufSize);
            }
        } catch (Exception e) {
            Log.w(TAG, "VOICE_COMMUNICATION 音源异常, 回退到 MIC: " + e.getMessage());
            audioSource = MediaRecorder.AudioSource.MIC;
            try {
                audioRecord = new AudioRecord(audioSource,
                        sampleRate, channelConfig, audioFormat, bufSize);
            } catch (Exception e2) {
                listener.onError("AudioRecord 创建失败: " + e2.getMessage());
                return;
            }
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            listener.onError("AudioRecord 未初始化");
            audioRecord.release();
            audioRecord = null;
            return;
        }

        // 显式添加回声消除 (AEC)
        int sessionId = audioRecord.getAudioSessionId();
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(sessionId);
            if (aec != null) {
                int aecResult = aec.setEnabled(true);
                Log.i(TAG, "AEC 已启用 (result=" + aecResult + ")");
            }
        } else {
            Log.w(TAG, "设备不支持 AEC");
        }

        // 显式添加噪声抑制 (NS)
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(sessionId);
            if (ns != null) {
                int nsResult = ns.setEnabled(true);
                Log.i(TAG, "NS 已启用 (result=" + nsResult + ")");
            }
        }

        try {
            audioRecord.startRecording();
        } catch (Exception e) {
            listener.onError("startRecording 失败: " + e.getMessage());
            audioRecord.release();
            audioRecord = null;
            return;
        }

        recording.set(true);
        readThread = new Thread(() -> {
            // 一次读 40ms 的 PCM (320 样本 @ 8kHz)
            int chunkSamples = sampleRate / 1000 * 40;
            short[] buf = new short[chunkSamples];
            while (recording.get()) {
                int read = audioRecord.read(buf, 0, buf.length);
                if (read > 0) {
                    // 复制到新数组防止下一轮覆盖
                    short[] out = new short[read];
                    System.arraycopy(buf, 0, out, 0, read);
                    listener.onPcmData(out, read);
                } else if (read < 0) {
                    Log.e(TAG, "read 返回 " + read);
                    listener.onError("AudioRecord.read 错误: " + read);
                    break;
                }
            }
        }, "AudioRecorder-Read");
        readThread.start();
        Log.i(TAG, "录音启动 sampleRate=" + sampleRate + " source=" + audioSource);
    }

    public void stop() {
        if (!recording.compareAndSet(true, false)) return;
        if (readThread != null) {
            try { readThread.join(500); } catch (InterruptedException ignored) {}
            readThread = null;
        }
        if (aec != null) {
            aec.release();
            aec = null;
        }
        if (ns != null) {
            ns.release();
            ns = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception ignored) {}
            audioRecord.release();
            audioRecord = null;
        }
        Log.i(TAG, "录音停止");
    }

    public int getSampleRate() { return sampleRate; }
}
