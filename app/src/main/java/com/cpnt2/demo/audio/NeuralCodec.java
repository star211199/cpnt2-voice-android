package com.cpnt2.demo.audio;

import android.util.Log;

import java.io.File;

/**
 * ClariCodec 神经编解码适配层。
 *
 * 数据流（发送端）：
 *   PCM(short, 1280/80ms, 16kHz) → float[-1,1] → engine.encode() → codes(2×12bit)
 *   → 打包 12-bit MSB-first → 3 字节/帧
 * 数据流（接收端）：
 *   3 字节 → 解包出 codes → float → engine.decode() → float PCM → short PCM
 *
 * 关键约定：
 *   - 编码器 / 解码器是两个独立模型文件（各自携带递推 state），本类按角色只加载其中一个；
 *   - 前置 3 帧 warm-up：编码器 valid=0（但 codes 是真实的），解码器前 3 帧输出静音。
 *     上层**必须逐帧发送、不得跳帧**，否则两端 state 失步导致噪声；
 *   - 引擎由 {@link NeuralEngineProvider} 共享缓存，本类 close() 不真正释放引擎。
 */
public class NeuralCodec implements AudioCodec {

    private static final String TAG = "NeuralCodec";

    /** 每个 code 12 bit，码本 4096 */
    private static final int CODE_BITS = 12;

    private final String mode;
    private final byte wire;
    private final int sampleRate = 16000;
    private final boolean encoderSide;

    private OrtNeuralEngine engine;
    private int frameSamples = 1280;   // 兜底默认，实际从模型读取
    private int codesPerFrame = 2;

    public NeuralCodec(String mode, boolean encoderSide) {
        this.mode = mode;
        this.encoderSide = encoderSide;
        this.wire = CodecSpec.wireId(mode);

        File model = NeuralEngineProvider.modelFile(modelName(mode, encoderSide));
        this.engine = NeuralEngineProvider.getEngine(model);
        if (engine != null) {
            frameSamples = engine.getChunkSamples();
            codesPerFrame = engine.getCodesPerFrame();
        } else {
            Log.e(TAG, "神经引擎加载失败: " + NeuralEngineProvider.lastError());
        }
    }

    private static String modelName(String mode, boolean encoderSide) {
        String bps = CodecSpec.neuralBps(mode);
        if (bps == null) bps = "300";
        return "claricodec_" + bps + "bps_" + (encoderSide ? "enc" : "dec") + ".onnx";
    }

    @Override
    public boolean isReady() {
        return engine != null;
    }

    @Override
    public int frameSamples() {
        return frameSamples;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public byte wireId() {
        return wire;
    }

    @Override
    public byte[] encode(short[] pcmFrame) {
        if (engine == null || pcmFrame == null || pcmFrame.length < frameSamples) return null;
        // short → float [-1, 1]
        float[] in = new float[frameSamples];
        for (int i = 0; i < frameSamples; i++) {
            in[i] = pcmFrame[i] / 32768f;
        }
        float[] codes = new float[codesPerFrame];
        try {
            engine.encode(in, codes);
        } catch (Throwable t) {
            Log.e(TAG, "encode 异常", t);
            return null;
        }
        return pack(codes);
    }

    @Override
    public short[] decode(byte[] packedFrame) {
        if (engine == null || packedFrame == null) return null;
        int[] codes = unpack(packedFrame, codesPerFrame);
        float[] cf = new float[codesPerFrame];
        for (int i = 0; i < codesPerFrame; i++) {
            cf[i] = codes[i];
        }
        float[] audio = new float[frameSamples];
        try {
            engine.decode(cf, audio);
        } catch (Throwable t) {
            Log.e(TAG, "decode 异常", t);
            return null;
        }
        short[] out = new short[frameSamples];
        for (int i = 0; i < frameSamples; i++) {
            int v = Math.round(audio[i] * 32767f);
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
            else if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
            out[i] = (short) v;
        }
        return out;
    }

    @Override
    public void reset() {
        if (engine != null) {
            try { engine.resetState(); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void close() {
        // 引擎由 NeuralEngineProvider 共享管理，此处不释放；
        // 挂断时由 NeuralEngineProvider.releaseAll() 统一回收。
    }

    /** 将 codes 按 12-bit MSB-first 打包成字节串（300bps: 2 codes → 3 字节） */
    private static byte[] pack(float[] codes) {
        int n = codes.length;
        byte[] out = new byte[(n * CODE_BITS + 7) / 8];
        int bit = 0;
        for (float cf : codes) {
            int code = Math.round(cf);
            for (int i = CODE_BITS - 1; i >= 0; i--) {
                if ((code & (1 << i)) != 0) {
                    out[bit / 8] |= (1 << (7 - bit % 8));
                }
                bit++;
            }
        }
        return out;
    }

    /** 从字节串解包出 count 个 12-bit code */
    private static int[] unpack(byte[] packed, int count) {
        int[] codes = new int[count];
        int bit = 0;
        for (int i = 0; i < count; i++) {
            int code = 0;
            for (int b = 0; b < CODE_BITS; b++) {
                int byteIdx = bit / 8;
                int v = byteIdx < packed.length
                        ? ((packed[byteIdx] >> (7 - bit % 8)) & 1)
                        : 0;
                code = (code << 1) | v;
                bit++;
            }
            codes[i] = code;
        }
        return codes;
    }
}
