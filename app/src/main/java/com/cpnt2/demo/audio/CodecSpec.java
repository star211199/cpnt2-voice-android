package com.cpnt2.demo.audio;

/**
 * 编解码"模式"的统一规格表。
 *
 * 现有 App 支持两套完全不同的编解码内核：
 *   - 传统 DSP：CPNT2（Codec2），模式名 "3200" ~ "450PWB"，采样率 8k/16k，PCM↔码流
 *   - 神经编解码：ClariCodec（ONNX），模式名 NEURAL300 / NEURAL150，采样率恒为 16k，
 *     PCM(frame 1280/80ms) → codes(每帧 1~2 个 12-bit) → 打包成码流
 *
 * 为了让上层（EncoderPipeline / DecoderPipeline / CallActivity）统一处理，
 * 这里把「内部模式名 ↔ 线上 codecId ↔ 采样率 ↔ 显示标签」集中起来。
 *
 * 线上 codecId 沿用 BleProtocol 的单字节编码：
 *   CPNT2 用 1..9（与 BleCodecMode 一致）；神经复用 100/101（与 CPNT2 不冲突）。
 */
public final class CodecSpec {

    private CodecSpec() {}

    // ===== 神经编解码（ClariCodec）内部模式名 =====
    public static final String NEURAL_300 = "NEURAL300"; // 300 bps，2 codes/帧
    public static final String NEURAL_150 = "NEURAL150"; // 150 bps，1 code/帧

    // ===== 神经模式的线上 codecId（避开 CPNT2 的 1..9） =====
    public static final byte WIRE_NEURAL_300 = 100;
    public static final byte WIRE_NEURAL_150 = 101;

    /** 是否为神经编解码模式 */
    public static boolean isNeural(String mode) {
        return NEURAL_300.equals(mode) || NEURAL_150.equals(mode);
    }

    /** 神经模式的 bps 字符串（"300"/"150"），非神经返回 null */
    public static String neuralBps(String mode) {
        if (NEURAL_300.equals(mode)) return "300";
        if (NEURAL_150.equals(mode)) return "150";
        return null;
    }

    /** 采样率：神经恒为 16k（帧长 1280/80ms）；CPNT2 沿用原映射 */
    public static int sampleRate(String mode) {
        if (isNeural(mode)) return 16000;
        return BleCodecMode.sampleRate(mode);
    }

    /** 内部模式名 → 线上 codecId */
    public static byte wireId(String mode) {
        if (NEURAL_300.equals(mode)) return WIRE_NEURAL_300;
        if (NEURAL_150.equals(mode)) return WIRE_NEURAL_150;
        return BleCodecMode.toId(mode);
    }

    /** 线上 codecId → 内部模式名 */
    public static String fromWire(int id) {
        int u = id & 0xFF;
        if (u == (WIRE_NEURAL_300 & 0xFF)) return NEURAL_300;
        if (u == (WIRE_NEURAL_150 & 0xFF)) return NEURAL_150;
        return BleCodecMode.toName((byte) id);
    }

    /** UI 显示标签 */
    public static String label(String mode) {
        if (NEURAL_300.equals(mode)) return "神经300";
        if (NEURAL_150.equals(mode)) return "神经150";
        return mode;
    }
}
