package com.cpnt2.demo.audio;

/**
 * 音频测试源 - 用于在没麦克风权限或多设备演示时替代
 *
 * 三种模式：
 *   - MIC:      真实麦克风
 *   - SINE:     合成女声 (基频 + 谐波) 模拟语音
 *   - MUSIC:    合成音乐 (主旋律 + 和声)
 */
public class TestSource {

    public static final int SOURCE_MIC = 0;
    public static final int SOURCE_SINE = 1;
    public static final int SOURCE_MUSIC = 2;

    public static final String[] SOURCE_NAMES = { "mic", "sine", "music" };

    public static String nameOf(int source) {
        switch (source) {
            case SOURCE_MIC:   return "mic";
            case SOURCE_SINE:  return "sine";
            case SOURCE_MUSIC: return "music";
            default: return "mic";
        }
    }

    public static int parse(String s) {
        if (s == null) return SOURCE_MIC;
        switch (s) {
            case "mic":   return SOURCE_MIC;
            case "sine":  return SOURCE_SINE;
            case "music": return SOURCE_MUSIC;
            default: return SOURCE_MIC;
        }
    }

    /**
     * 生成测试 PCM 数据 (16bit signed mono)
     * @param source    信号源
     * @param sampleRate 采样率
     * @param sampleCount 样本数
     * @param phase      连续相位状态 (传入时为 0，每次调用后更新)
     * @return           PCM 数组
     */
    public static short[] generate(int source, int sampleRate, int sampleCount, double[] phase) {
        short[] out = new short[sampleCount];
        switch (source) {
            case SOURCE_SINE: {
                // 模拟女声: 200Hz 基频 + 谐波
                double f0 = 220.0;
                for (int i = 0; i < sampleCount; i++) {
                    double t = (phase[0] + i) / sampleRate;
                    double s = 0.45 * Math.sin(2 * Math.PI * f0 * t)
                             + 0.30 * Math.sin(2 * Math.PI * f0 * 2 * t)
                             + 0.20 * Math.sin(2 * Math.PI * f0 * 3 * t)
                             + 0.10 * Math.sin(2 * Math.PI * f0 * 4 * t);
                    out[i] = (short) (s * 12000);
                }
                phase[0] += sampleCount;
                break;
            }
            case SOURCE_MUSIC: {
                // 模拟音乐: 旋律 + 低音
                double f0 = 440.0; // A4
                for (int i = 0; i < sampleCount; i++) {
                    double t = (phase[0] + i) / sampleRate;
                    double s = 0.40 * Math.sin(2 * Math.PI * f0 * t)
                             + 0.25 * Math.sin(2 * Math.PI * f0 * 1.5 * t)  // 五度
                             + 0.20 * Math.sin(2 * Math.PI * 110.0 * t);    // 低音 A2
                    out[i] = (short) (s * 14000);
                }
                phase[0] += sampleCount;
                break;
            }
            default: // MIC - 不生成
                for (int i = 0; i < sampleCount; i++) out[i] = 0;
                break;
        }
        return out;
    }
}
