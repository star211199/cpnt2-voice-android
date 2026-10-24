package com.cpnt2.demo.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 音频播放器 — 支持免提/听筒动态切换
 *
 * 免提模式：USAGE_MEDIA → 扬声器播放，音量大，媒体音量控制
 * 听筒模式：USAGE_VOICE_COMMUNICATION → 听筒播放，通话音量控制
 *
 * 切换模式时会暂停、重建 AudioTrack、恢复播放。
 */
public class AudioPlayer {

    private static final String TAG = "AudioPlayer";
    public static final int DEFAULT_SAMPLE_RATE = 8000;

    private final int sampleRate;
    private final int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
    private final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

    private AudioTrack audioTrack;
    private final Object trackLock = new Object();
    private Thread playThread;
    private final AtomicBoolean playing = new AtomicBoolean(false);
    /**
     * 播放队列：16 帧 ≈ 640ms (40ms/帧)。
     * 原 64 帧 (≈2.5s) 会导致 BLE 突发到达时积压大量音频，语音延迟高达数秒。
     * 队列满时丢弃最旧帧 (见 submit)，保证实时性优先于平滑性。
     */
    private final BlockingQueue<short[]> queue = new ArrayBlockingQueue<>(16);

    private volatile float volumeBoost = 2.0f;
    private volatile boolean speakerMode = true; // true=免提(扬声器), false=听筒

    public AudioPlayer(int sampleRate) {
        this(sampleRate, true);
    }

    public AudioPlayer(int sampleRate, boolean speakerMode) {
        this.sampleRate = sampleRate;
        this.speakerMode = speakerMode;
    }

    public int getSampleRate() { return sampleRate; }

    public void setVolumeBoost(float boost) {
        this.volumeBoost = Math.max(0.0f, Math.min(boost, 2.0f));
        Log.i(TAG, "音量增益设为: " + volumeBoost);
    }

    public float getVolumeBoost() { return volumeBoost; }

    /**
     * 切换免提/听筒模式 — 会重建 AudioTrack
     */
    public void setSpeakerMode(boolean speakerOn) {
        if (this.speakerMode == speakerOn) return;
        this.speakerMode = speakerOn;
        if (!playing.get()) return;
        // 播放中切换：先暂停，重建，再恢复
        synchronized (trackLock) {
            // 暂停当前 track
            AudioTrack oldTrack = audioTrack;
            if (oldTrack != null) {
                try { oldTrack.pause(); } catch (Exception ignored) {}
            }
            // 重建
            createAudioTrack();
            if (audioTrack != null) {
                try { audioTrack.play(); } catch (Exception ignored) {}
            }
            // 释放旧的
            if (oldTrack != null) {
                try { oldTrack.flush(); } catch (Exception ignored) {}
                try { oldTrack.stop(); } catch (Exception ignored) {}
                try { oldTrack.release(); } catch (Exception ignored) {}
            }
            Log.i(TAG, "speakerMode=" + speakerOn + " AudioTrack 已重建");
        }
    }

    private void createAudioTrack() {
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        if (minBuf <= 0) {
            Log.e(TAG, "AudioTrack.getMinBufferSize 失败: " + minBuf);
            return;
        }
        int bufSize = minBuf * 4;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int usage = speakerMode
                    ? AudioAttributes.USAGE_MEDIA           // 免提→扬声器
                    : AudioAttributes.USAGE_VOICE_COMMUNICATION; // 听筒
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(audioFormat)
                            .setChannelMask(channelConfig)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } else {
            int streamType = speakerMode
                    ? AudioManager.STREAM_MUSIC
                    : AudioManager.STREAM_VOICE_CALL;
            audioTrack = new AudioTrack(streamType,
                    sampleRate, channelConfig, audioFormat,
                    bufSize, AudioTrack.MODE_STREAM);
        }

        if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack 未初始化 (speakerMode=" + speakerMode + ")");
            audioTrack.release();
            audioTrack = null;
            return;
        }

        // 系统级别音量设到最大（软增益由 volumeBoost 控制）
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                audioTrack.setVolume(1.0f);
            }
        } catch (Exception ignored) {}
    }

    public void start() {
        if (playing.get()) return;
        createAudioTrack();
        if (audioTrack == null) return;

        try {
            audioTrack.play();
        } catch (Exception e) {
            Log.e(TAG, "audioTrack.play 失败: " + e.getMessage());
            return;
        }

        playing.set(true);
        playThread = new Thread(() -> {
            while (playing.get()) {
                try {
                    short[] pcm = queue.take();
                    int pcmLen = pcm.length;
                    float boost = volumeBoost;

                    if (boost > 1.0f) {
                        for (int i = 0; i < pcmLen; i++) {
                            int v = (int) (pcm[i] * boost);
                            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
                            else if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
                            pcm[i] = (short) v;
                        }
                    }

                    synchronized (trackLock) {
                        if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                            audioTrack.write(pcm, 0, pcmLen);
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "write 异常: " + e.getMessage());
                    break;
                }
            }
        }, "AudioPlayer-Play");
        playThread.start();
        Log.i(TAG, "播放启动 sampleRate=" + sampleRate
                + " speakerMode=" + speakerMode + " boost=" + volumeBoost);
    }

    public void stop() {
        if (!playing.compareAndSet(true, false)) return;
        queue.clear();
        if (playThread != null) {
            playThread.interrupt();
            try { playThread.join(500); } catch (InterruptedException ignored) {}
            playThread = null;
        }
        synchronized (trackLock) {
            if (audioTrack != null) {
                try { audioTrack.flush(); } catch (Exception ignored) {}
                try { audioTrack.stop(); } catch (Exception ignored) {}
                audioTrack.release();
                audioTrack = null;
            }
        }
        Log.i(TAG, "播放停止");
    }

    public void submit(short[] pcm) {
        if (playing.get() && pcm != null && pcm.length > 0) {
            while (!queue.offer(pcm)) {
                queue.poll(); // 背压：丢弃最旧的
            }
        }
    }
}
