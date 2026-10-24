package com.cpnt2.demo.audio;

import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 解码端 - 接收方
 *
 * 流程：BLE 接收 -> codec.decode -> AudioPlayer 播放
 *
 * 编解码内核随首个音频帧携带的 codecId 自适应：CPNT2 或 神经（ClariCodec）。
 * 一旦对端切换模式（codecId 变化），此处重建对应 codec 并通知上层采样率变化。
 */
public class DecoderPipeline {

    private static final String TAG = "DecoderPipeline";

    public interface Sink {
        void onPcmFrame(short[] pcm);
        void onDecodeError(String msg);
        /** 解码模式切换（含首次建帧）。sampleRate 为解码 PCM 的采样率，上层据此重建播放器 */
        void onModeChanged(String mode, int sampleRate);
    }

    private final Sink sink;
    private volatile String currentMode;   // 当前解码模式（内部模式名）
    private volatile AudioCodec codec;     // 当前 codec
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lock = new Object();

    public DecoderPipeline(Sink sink) {
        this.sink = sink;
    }

    public void start() {
        running.set(true);
        Log.i(TAG, "解码器启动");
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        synchronized (lock) {
            if (codec != null) {
                codec.close();
                codec = null;
            }
        }
        Log.i(TAG, "解码器停止");
    }

    /**
     * 收到一帧编码数据
     * @param codecId 线上 codecId
     * @param frame   编码后的字节
     */
    public void submitFrame(byte codecId, byte[] frame) {
        if (!running.get()) return;
        String mode = CodecSpec.fromWire(codecId);

        synchronized (lock) {
            // 模式变了，重建 codec
            if (!mode.equals(currentMode) || codec == null) {
                if (codec != null) {
                    codec.close();
                    codec = null;
                }
                codec = CodecSpec.isNeural(mode)
                        ? new NeuralCodec(mode, false)
                        : new Cpnt2Codec(mode);
                currentMode = mode;
                if (codec == null || !codec.isReady()) {
                    sink.onDecodeError("codec 创建失败: " + mode);
                    return;
                }
                Log.i(TAG, "切换解码模式: " + mode + " sampleRate=" + codec.sampleRate());
                try {
                    sink.onModeChanged(mode, codec.sampleRate());
                } catch (Exception e) {
                    Log.e(TAG, "onModeChanged 回调异常", e);
                }
            }

            short[] pcm = codec.decode(frame);
            if (pcm != null) {
                sink.onPcmFrame(pcm);
            } else {
                sink.onDecodeError("decode 返回 null");
            }
        }
    }

    public String getCurrentMode() {
        return currentMode;
    }
}
