package com.cpnt2.demo.audio;

/**
 * 编解码器统一抽象。
 *
 * 一次通话两端各持有一个编码器与一个解码器（双向）；本接口同时暴露 encode/decode，
 * 由具体实现（CPNT2 / 神经）提供。上层管线（EncoderPipeline 只调 encode，
 * DecoderPipeline 只调 decode）按角色使用。
 *
 * 约定：一帧长度固定为 {@link #frameSamples()} 个 16-bit PCM 样本、采样率 {@link #sampleRate()}。
 * 编码输出为可直接放进 BLE 包 payload 的字节；解码输入为对端发来的同长度字节。
 */
public interface AudioCodec {

    /** 编解码器是否已成功建立（native 句柄 / 模型已就绪） */
    boolean isReady();

    /** 每帧 PCM 样本数（CPNT2: 320/640；神经: 1280） */
    int frameSamples();

    /** PCM 采样率（CPNT2: 8000/16000；神经: 16000） */
    int sampleRate();

    /** 线上 codecId（写入 BLE 包头，供对端识别模式） */
    byte wireId();

    /**
     * 编码一帧 PCM。
     * @param pcmFrame 恰好 {@link #frameSamples()} 个样本
     * @return 编码后的字节（可直接作为 BLE payload），失败返回 null
     */
    byte[] encode(short[] pcmFrame);

    /**
     * 解码一帧字节。
     * @param packedFrame 对端发来的编码字节
     * @return 解码出的 PCM（{@link #frameSamples()} 个样本），失败返回 null
     */
    short[] decode(byte[] packedFrame);

    /** 重置内部状态（神经编解码器用于开启新码流） */
    void reset();

    /** 释放资源 */
    void close();
}
