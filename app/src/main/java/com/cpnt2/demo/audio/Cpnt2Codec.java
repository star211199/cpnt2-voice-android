package com.cpnt2.demo.audio;

import android.util.Log;

import com.cpnt2.demo.Codec2Native;

/**
 * CPNT2（Codec2）编解码器的统一抽象实现。
 * 直接包装现有的 Codec2Native JNI 句柄，行为与改造前完全一致。
 */
public class Cpnt2Codec implements AudioCodec {

    private static final String TAG = "Cpnt2Codec";

    private final String mode;
    private final int nsam;
    private final int sampleRate;
    private final byte wire;
    private long handle = 0;

    public Cpnt2Codec(String mode) {
        this.mode = mode;
        this.handle = Codec2Native.createNative(mode);
        this.nsam = handle != 0 ? Codec2Native.samplesPerFrameNative(handle) : 0;
        this.sampleRate = BleCodecMode.sampleRate(mode);
        this.wire = BleCodecMode.toId(mode);
        if (handle == 0) {
            Log.e(TAG, "codec2_create 失败: " + mode);
        }
    }

    @Override
    public boolean isReady() {
        return handle != 0;
    }

    @Override
    public int frameSamples() {
        return nsam;
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
        if (handle == 0) return null;
        return Codec2Native.encodeFrameNative(handle, pcmFrame);
    }

    @Override
    public short[] decode(byte[] packedFrame) {
        if (handle == 0) return null;
        return Codec2Native.decodeFrameNative(handle, packedFrame);
    }

    @Override
    public void reset() {
        // CPNT2 无独立状态向量，切码率时由上层重建实例
    }

    @Override
    public void close() {
        if (handle != 0) {
            Codec2Native.destroyNative(handle);
            handle = 0;
        }
    }
}
