package com.cpnt2.demo.audio;

import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * 神经编解码引擎（ONNX Runtime <b>Java 绑定</b>实现）。
 *
 * <h3>为什么不用同事的 libclaricodec.so？</h3>
 * 同事交付的 AAR 走的是 ONNX Runtime 的 <b>C API</b>（{@code claricodec_create} → {@code OrtGetApiBase}），
 * 在 arm64 真机上**一旦创建会话即永久卡死**（100% 单核、不返回、不崩溃），
 * 见 {@code claricodec-native/REAL_DEVICE_CRASH_REPORT.md}。而**同样**的模型经 ORT 的
 * **Java 绑定**加载/推理完全正常（同机同模型 A/B 实测通过）。因此本类直接用 Java 绑定驱动
 * enc/dec 两个 ONNX 图，彻底绕开有问题的 C API 原生库。
 *
 * <h3>模型 I/O（300bps，实测）</h3>
 * <pre>
 *   编码器: pcm[1,1280] + state[1,120161] → codes[1,2]      + valid[1] + state_out[1,120161]
 *   解码器: codes[1,2] + state[1,155169]   → audio[1,1280]  +           state_out[1,155169]
 * </pre>
 * 150bps 结构相同，仅 codes 维度为 1。所有张量均为 float32。
 * 张量名按 "pcm"/"codes"/"audio"/"valid"/"state" 关键字匹配（同时提供位置兜底），
 * 因此不依赖某个具体导出名的拼写。
 */
public class OrtNeuralEngine implements Closeable {

    private static final String TAG = "OrtNeuralEngine";

    private final OrtEnvironment env;
    private final OrtSession session;

    /** true: 编码器(pcm→codes)；false: 解码器(codes→pcm)。由图的输入名自动判定。 */
    private final boolean encoder;

    private final String signalInName;  // 编码: "pcm"  解码: "codes"
    private final String stateInName;   // "state"
    private final String mainOutName;   // 编码: "codes" 解码: "audio"
    private final String validOutName;  // 编码: "valid" 解码: null
    private final String stateOutName;  // "state_out"

    private final int chunkSamples;     // 1280（一帧 PCM 样本数）
    private final int codesPerFrame;    // 2 / 1
    private final long[] stateShape;    // [1, N]
    private final float[] state;        // 递推状态（扁平，长度 = N），初始全 0

    private volatile float lastValid = 0f;
    private final Object runLock = new Object();

    public OrtNeuralEngine(File model, int intraOpThreads) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();

        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(intraOpThreads);
        // 仅设置线程数（与已验证可用的 A/B 实验一致），其余用 ORT 默认优化等级。
        this.session = env.createSession(model.getAbsolutePath(), opts);

        // ---- 发现输入张量 ----
        Map<String, TensorInfo> ins = new HashMap<>();
        for (Map.Entry<String, NodeInfo> e : session.getInputInfo().entrySet()) {
            ins.put(e.getKey(), (TensorInfo) e.getValue().getInfo());
        }
        Map<String, TensorInfo> outs = new HashMap<>();
        for (Map.Entry<String, NodeInfo> e : session.getOutputInfo().entrySet()) {
            outs.put(e.getKey(), (TensorInfo) e.getValue().getInfo());
        }
        if (ins.isEmpty() || outs.isEmpty()) {
            throw new OrtException("模型无输入/输出: " + model.getName());
        }

        String pcm = findName(ins, "pcm");
        String codesIn = findName(ins, "code");
        String stateIn = findName(ins, "state");
        String audio = findName(outs, "audio");
        String codesOut = findName(outs, "code");
        String valid = findName(outs, "valid");
        String stateOut = findName(outs, "state");

        this.encoder = (pcm != null);

        // 输入：一个信号（pcm/codes）+ 一个 state；名字缺失时按位置兜底。
        this.signalInName = firstNonNull(pcm, codesIn, fallbackSignalInput(ins, stateIn));
        this.stateInName = firstNonNull(stateIn, fallbackOtherInput(ins, signalInName));
        // 输出：一个主输出（codes/audio）+ 可选的 valid + state_out。
        this.mainOutName = encoder
                ? firstNonNull(codesOut, audio, firstNonStateOutput(outs))
                : firstNonNull(audio, codesOut, firstNonStateOutput(outs));
        this.validOutName = valid;
        this.stateOutName = firstNonNull(stateOut, fallbackStateOutput(outs, mainOutName));

        if (signalInName == null || stateInName == null
                || mainOutName == null || stateOutName == null) {
            throw new OrtException("无法解析模型 I/O: "
                    + model.getName() + " in=" + ins.keySet() + " out=" + outs.keySet());
        }

        long[] sigShape = ins.get(signalInName).getShape();
        long[] stateInShape = ins.get(stateInName).getShape();
        long[] mainShape = outs.get(mainOutName).getShape();

        this.stateShape = new long[]{1, numel(stateInShape)};
        this.state = new float[(int) stateShape[1]];

        if (encoder) {
            this.chunkSamples = numel(sigShape);        // pcm 长度
            this.codesPerFrame = numel(mainShape);      // codes 个数
        } else {
            this.chunkSamples = numel(mainShape);       // audio 长度
            this.codesPerFrame = numel(sigShape);       // codes 个数
        }

        Log.i(TAG, "引擎就绪 role=" + getRole()
                + " chunk=" + chunkSamples + " codes=" + codesPerFrame
                + " state=" + state.length + " in=" + ins.keySet() + " out=" + outs.keySet());
    }

    // ---------------------------------------------------------------------
    // 推理
    // ---------------------------------------------------------------------

    /** 编码一帧：pcm[chunkSamples] → codesOut[codesPerFrame]。成功返回 codesOut，失败 null。 */
    public float[] encode(float[] pcm, float[] codesOut) {
        if (!encoder) {
            Log.e(TAG, "encode() 调用于解码器引擎");
            return null;
        }
        return run(pcm, codesOut, true);
    }

    /** 解码一帧：codes[codesPerFrame] → audioOut[chunkSamples]。成功返回 audioOut，失败 null。 */
    public float[] decode(float[] codes, float[] audioOut) {
        if (encoder) {
            Log.e(TAG, "decode() 调用于编码器引擎");
            return null;
        }
        return run(codes, audioOut, false);
    }

    private float[] run(float[] signal, float[] out, boolean isEncode) {
        synchronized (runLock) {
            OnnxTensor sigT = null, stateT = null;
            try {
                long[] sigShape = new long[]{1, isEncode ? chunkSamples : codesPerFrame};
                sigT = OnnxTensor.createTensor(env, FloatBuffer.wrap(signal), sigShape);
                stateT = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), stateShape);

                Map<String, OnnxTensor> feeds = new HashMap<>(4);
                feeds.put(signalInName, sigT);
                feeds.put(stateInName, stateT);

                try (OrtSession.Result res = session.run(feeds)) {
                    // 主输出
                    OnnxTensor mainT = (OnnxTensor) res.get(mainOutName).get();
                    FloatBuffer mb = mainT.getFloatBuffer();
                    int n = isEncode ? codesPerFrame : chunkSamples;
                    for (int i = 0; i < n; i++) out[i] = mb.get(i);

                    // 编码器 valid 标志
                    if (isEncode && validOutName != null) {
                        OnnxTensor vT = (OnnxTensor) res.get(validOutName).get();
                        lastValid = vT.getFloatBuffer().get(0);
                    }

                    // 回写递推状态
                    OnnxTensor soT = (OnnxTensor) res.get(stateOutName).get();
                    FloatBuffer sb = soT.getFloatBuffer();
                    for (int i = 0; i < state.length; i++) state[i] = sb.get(i);
                }
                return out;
            } catch (Throwable t) {
                Log.e(TAG, (isEncode ? "encode" : "decode") + " 失败", t);
                return null;
            } finally {
                if (sigT != null) try { sigT.close(); } catch (Throwable ignored) {}
                if (stateT != null) try { stateT.close(); } catch (Throwable ignored) {}
            }
        }
    }

    // ---------------------------------------------------------------------
    // 访问器（与同事 C API 引擎语义对齐，方便上层复用）
    // ---------------------------------------------------------------------

    public int getChunkSamples()      { return chunkSamples; }
    public int getCodesPerFrame()     { return codesPerFrame; }
    public int getStateSize()         { return state.length; }
    /** 2 = 编码器，3 = 解码器（仅用于日志）。 */
    public int getRole()              { return encoder ? 2 : 3; }
    public boolean isEncoder()        { return encoder; }
    public float getLastValid()       { return lastValid; }

    public void resetState() {
        synchronized (runLock) {
            Arrays.fill(state, 0f);
            lastValid = 0f;
        }
    }

    @Override
    public void close() {
        try { session.close(); } catch (Throwable ignored) {}
        // 环境 OrtEnvironment 是进程级单例，不在此关闭。
    }

    // ---------------------------------------------------------------------
    // 名字解析辅助
    // ---------------------------------------------------------------------

    private static String findName(Map<String, TensorInfo> m, String needle) {
        for (String k : m.keySet()) {
            if (k.toLowerCase().contains(needle)) return k;
        }
        return null;
    }

    private static String firstNonNull(String... names) {
        for (String n : names) if (n != null) return n;
        return null;
    }

    /** 信号输入兜底：非 state 的第一个输入。 */
    private static String fallbackSignalInput(Map<String, TensorInfo> ins, String stateIn) {
        for (String k : ins.keySet()) {
            if (!k.equals(stateIn)) return k;
        }
        return null;
    }

    /** 输入里"另一个"（非 signalInName）。 */
    private static String fallbackOtherInput(Map<String, TensorInfo> ins, String signalIn) {
        for (String k : ins.keySet()) {
            if (!k.equals(signalIn)) return k;
        }
        return null;
    }

    /** 非 state 的第一个输出（用作主输出兜底）。 */
    private static String firstNonStateOutput(Map<String, TensorInfo> outs) {
        for (String k : outs.keySet()) {
            if (!k.toLowerCase().contains("state")) return k;
        }
        return null;
    }

    /** 状态输出兜底：名字含 state 且不等于主输出的那个输出。 */
    private static String fallbackStateOutput(Map<String, TensorInfo> outs, String mainOut) {
        for (String k : outs.keySet()) {
            if (k.toLowerCase().contains("state") && !k.equals(mainOut)) return k;
        }
        return null;
    }

    private static int numel(long[] shape) {
        long n = 1;
        for (long s : shape) if (s > 0) n *= s;
        return (int) n;
    }
}
