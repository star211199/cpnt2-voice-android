package com.cpnt2.demo;

/**
 * CPNT2 (Codec2) JNI 接口
 *
 * 提供 Java 层调用 CPNT2 原生编解码库的接口。
 * 对应的 C 实现在 jni/cpnt2_jni.c
 */
public class Codec2Native {

    static {
        System.loadLibrary("cpnt2_jni");
    }

    /**
     * 获取支持的码率模式列表
     * @return 模式名称数组 ("3200", "2400", ..., "450PWB")
     */
    public native String[] getSupportedModes();

    /**
     * 创建编解码器实例
     * @param modeStr 码率模式名称 (如 "1300")
     * @return 编解码器句柄 (0 表示失败)
     */
    public native long create(String modeStr);

    /**
     * 销毁编解码器实例
     * @param handle 编解码器句柄
     */
    public native void destroy(long handle);

    /**
     * 获取每帧采样数
     */
    public native int samplesPerFrame(long handle);

    /**
     * 获取每帧压缩字节数
     */
    public native int bytesPerFrame(long handle);

    /**
     * 获取每帧压缩比特数
     */
    public native int bitsPerFrame(long handle);

    /**
     * 获取指定模式的采样率
     * @param modeStr 码率模式名称
     * @return 采样率 (8000 或 16000)
     */
    public native int getSampleRate(String modeStr);

    /**
     * 编码一帧语音
     * @param handle 编解码器句柄
     * @param speechIn PCM 语音样本 (short[])
     * @return 压缩字节 (byte[])
     */
    public native byte[] encodeFrame(long handle, short[] speechIn);

    /**
     * 解码一帧比特流
     * @param handle 编解码器句柄
     * @param compressedIn 压缩字节 (byte[])
     * @return PCM 语音样本 (short[])
     */
    public native short[] decodeFrame(long handle, byte[] compressedIn);

    /**
     * 批量编解码: 对整段 PCM 数据进行编解码并返回统计信息
     *
     * @param modeStr 码率模式名称
     * @param pcmData 原始 PCM 语音数据
     * @return 统计信息 [原始字节数, 压缩字节数, 帧数, 编码耗时ms, 解码耗时ms]
     */
    public native long[] processAll(String modeStr, short[] pcmData);

    /**
     * 获取版本信息
     */
    public native String getVersion();

    /**
     * 根据模式名获取每帧比特数 (纯静态映射, 不需 JNI 调用)
     * 取值与 C 层 codec2_bits_per_frame() 保持一致
     */
    public static int bitsPerFrameFromName(String name) {
        switch (name) {
            case "3200":   return 64;
            case "2400":   return 48;
            case "1600":   return 64;
            case "1400":   return 56;
            case "1300":   return 52;
            case "1200":   return 48;
            case "700C":   return 28;
            case "450":    return 18;
            case "450PWB": return 18;   // 640 样本/帧 @16kHz (25fps), 18*25 = 450 bps
            default:       return 52;
        }
    }

    // ========== 便捷方法 ==========

    /**
     * 计算压缩比
     */
    public static double calcCompressionRatio(long[] stats) {
        if (stats == null || stats.length < 2 || stats[1] == 0) return 0;
        return (double) stats[0] / stats[1];
    }

    /**
     * 计算码率 (bits per second)
     */
    public static double calcBitrate(long[] stats, int sampleRate) {
        if (stats == null || stats.length < 3 || stats[2] == 0) return 0;
        // stats[1] = 压缩字节数, stats[2] = 帧数
        // 每帧时长 = samplesPerFrame / sampleRate
        // 但这里我们没有 samplesPerFrame, 用近似:
        // 总时长 = stats[0] / 2 / sampleRate (原始字节数 / 2 / 采样率)
        double duration = (double) stats[0] / 2.0 / sampleRate;
        if (duration == 0) return 0;
        return (stats[1] * 8.0) / duration;
    }

    // ========== 给音频管线用的 static 风格方法 (通过单例调用, 减少每次创建 Java 包装对象的开销) ==========

    private static final Codec2Native INSTANCE = new Codec2Native();

    /** 创建 codec2 实例 (供 EncoderPipeline/DecoderPipeline 使用) */
    public static long createNative(String modeStr) {
        return INSTANCE.create(modeStr);
    }

    /** 销毁 codec2 实例 */
    public static void destroyNative(long handle) {
        INSTANCE.destroy(handle);
    }

    /** 获取每帧采样数 (static 调用) */
    public static int samplesPerFrameNative(long handle) {
        return INSTANCE.samplesPerFrame(handle);
    }

    /** 编码一帧 (static) */
    public static byte[] encodeFrameNative(long handle, short[] speechIn) {
        return INSTANCE.encodeFrame(handle, speechIn);
    }

    /** 解码一帧 (static) */
    public static short[] decodeFrameNative(long handle, byte[] compressedIn) {
        return INSTANCE.decodeFrame(handle, compressedIn);
    }

    // ========== 帧率计算 ==========

    /** 每模式帧率缓存 (mode -> fps) */
    private static final java.util.Map<String, Integer> frameRateCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 计算指定模式每秒的帧数 = 采样率 / 每帧采样数。
     * 通过真实 codec 实例查询，避免硬编码错误
     * (例如 450PWB 是 640 样本/帧 @ 16kHz，帧率 25fps 而非 50fps)。
     */
    public static int frameRateForMode(String mode) {
        if (mode == null) return 25;
        Integer cached = frameRateCache.get(mode);
        if (cached != null) return cached;
        int fps = 25;
        long h = createNative(mode);
        if (h != 0) {
            int spf = samplesPerFrameNative(h);
            int sr = INSTANCE.getSampleRate(mode);
            destroyNative(h);
            if (spf > 0) {
                fps = Math.max(1, Math.round((float) sr / spf));
            }
        }
        frameRateCache.put(mode, fps);
        return fps;
    }
}
