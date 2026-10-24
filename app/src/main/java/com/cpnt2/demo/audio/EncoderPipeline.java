package com.cpnt2.demo.audio;

import android.util.Log;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 编码端 - 发送方
 *
 * 流程：TestSource / AudioRecorder -> 按帧切分 -> codec.encode -> BLE 发送
 *
 * 编解码内核由 {@link AudioCodec} 抽象：CPNT2 或 神经（ClariCodec）由 modeName 决定，
 * 上层无需关心。帧长由 codec.frameSamples() 自适应（CPNT2 320/640；神经 1280）。
 */
public class EncoderPipeline {

    private static final String TAG = "EncoderPipeline";

    public interface Sink {
        void onEncodedFrame(byte codecId, byte[] encoded);
        void onEncodeError(String msg);
    }

    private final String modeName;
    private final Sink sink;
    private AudioCodec codec;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // 缓存未凑够一帧的 PCM 数据
    private short[] pcmBuffer = new short[0];
    private int pcmBufferLen = 0;

    public EncoderPipeline(String modeName, Sink sink) {
        this.modeName = modeName;
        this.sink = sink;
    }

    public String getModeName() { return modeName; }
    public int getSampleRate() { return CodecSpec.sampleRate(modeName); }

    public void start() {
        if (running.get()) return;
        try {
            codec = CodecSpec.isNeural(modeName)
                    ? new NeuralCodec(modeName, true)
                    : new Cpnt2Codec(modeName);
        } catch (Throwable e) {
            Log.e(TAG, "创建编解码器异常", e);
            sink.onEncodeError("创建编解码器异常: " + e.getMessage());
            codec = null;
            return;
        }
        if (codec == null || !codec.isReady()) {
            sink.onEncodeError("编解码器创建失败 (mode=" + modeName + ")");
            codec = null;
            return;
        }
        pcmBufferLen = 0;
        running.set(true);
        Log.i(TAG, "编码器启动 mode=" + modeName + " sampleRate=" + getSampleRate()
                + " frameSamples=" + codec.frameSamples());
    }

    /**
     * 停止编码器。
     *
     * 必须与 {@link #submitPcm} 互斥：否则音频线程可能刚通过 codec 判空、
     * 正卡在 codec.encode()/codec.wireId() 之间时，本方法把字段置空并 close()，
     * 导致 NPE（Attempt to invoke ... wireId() on a null object reference）
     * 或对已关闭的引擎做推理。
     */
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (codec != null) {
            codec.close();
            codec = null;
        }
        Log.i(TAG, "编码器停止");
    }

    public boolean isRunning() { return running.get(); }

    /**
     * 送入 PCM 块，内部按帧切分并编码
     */
    public synchronized void submitPcm(short[] pcm, int length) {
        // 本方法全程只使用本地捕获的 c，避免 stop()/start() 并发改写字段后
        // 在 encode() 与 wireId() 之间出现空引用（见 stop() 注释）。
        final AudioCodec c = codec;
        if (!running.get() || c == null) return;
        if (pcm == null || length <= 0) return;

        int nsam = c.frameSamples();
        if (nsam <= 0) return;

        // 合并到内部缓冲
        int needed = pcmBufferLen + length;
        if (pcmBuffer.length < needed) {
            short[] newBuf = new short[needed];
            System.arraycopy(pcmBuffer, 0, newBuf, 0, pcmBufferLen);
            pcmBuffer = newBuf;
        }
        System.arraycopy(pcm, 0, pcmBuffer, pcmBufferLen, length);
        pcmBufferLen = needed;

        // 逐帧编码
        while (pcmBufferLen >= nsam) {
            short[] frame = Arrays.copyOfRange(pcmBuffer, 0, nsam);
            byte[] encoded = c.encode(frame);
            if (encoded != null) {
                try {
                    sink.onEncodedFrame(c.wireId(), encoded);
                } catch (Exception e) {
                    Log.e(TAG, "onEncodedFrame 回调异常 (BLE 发送失败?)", e);
                }
            } else {
                sink.onEncodeError("encode 返回 null");
                break;
            }
            int remaining = pcmBufferLen - nsam;
            if (remaining < 0) {
                Log.e(TAG, "pcmBufferLen 异常: " + pcmBufferLen + " - " + nsam);
                pcmBufferLen = 0;
                break;
            }
            if (remaining > 0) {
                System.arraycopy(pcmBuffer, nsam, pcmBuffer, 0, remaining);
            }
            pcmBufferLen = remaining;
        }
    }
}
