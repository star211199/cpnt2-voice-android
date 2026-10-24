package com.cpnt2.demo.audio;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 神经编解码引擎（ONNX）的共享缓存与模型定位。
 *
 * 为什么需要它：
 *   - 一个模型加载会把约 0.6GB 权重映射进内存，重复加载代价极高；
 *   - 一次通话里编码端与解码端各自需要长期持有同一个引擎；
 *   - 切换码率/挂断时希望有统一的释放入口。
 *
 * 模型放置位置（按优先级）：
 *   1) App 外部私有目录  <external-files>/models/      ← adb push 最方便
 *   2) App 内部私有目录  <files>/models/
 */
public final class NeuralEngineProvider {

    private static final String TAG = "NeuralEngine";

    /** intra-op 线程数（ONNX 每帧前向） */
    private static final int THREADS = 2;

    private static volatile File modelsDir;
    private static volatile String lastError = null;

    private static final Map<String, OrtNeuralEngine> engines = new ConcurrentHashMap<>();
    private static final Object lock = new Object();

    private NeuralEngineProvider() {}

    /** 建议在 Activity.onCreate 早期调用一次 */
    public static void init(Context ctx) {
        if (modelsDir != null) return;
        Context app = ctx.getApplicationContext();
        File internal = new File(app.getFilesDir(), "models");
        File external = null;
        try {
            File e = app.getExternalFilesDir(null);
            if (e != null) external = new File(e, "models");
        } catch (Exception ignored) {}
        // 优先内部存储：外部存储(/sdcard, FUSE)上 mmap 大型 ONNX 权重在部分机型上会异常；
        // 作者示例亦从内部 filesDir 读取模型。
        modelsDir = containsModel(internal) ? internal : (external != null ? external : internal);
        Log.i(TAG, "模型目录: " + modelsDir.getAbsolutePath()
                + " (internal=" + containsModel(internal)
                + ", external=" + (external != null && containsModel(external)) + ")");
    }

    private static boolean containsModel(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] fs = dir.listFiles();
        if (fs == null) return false;
        for (File f : fs) {
            if (f.getName().endsWith(".onnx")) return true;
        }
        return false;
    }

    public static File modelsDir() {
        return modelsDir;
    }

    public static File modelFile(String fileName) {
        return new File(modelsDir, fileName);
    }

    /** 指定神经码率的 enc+dec 模型是否都在 */
    public static boolean hasBps(String bps) {
        if (modelsDir == null) return false;
        return new File(modelsDir, "claricodec_" + bps + "bps_enc.onnx").exists()
                && new File(modelsDir, "claricodec_" + bps + "bps_dec.onnx").exists();
    }

    public static String lastError() {
        return lastError;
    }

    /**
     * 获取（或按需创建）某个模型文件对应的引擎。返回 null 表示加载失败。
     */
    public static OrtNeuralEngine getEngine(File model) {
        final String key = model.getAbsolutePath();
        OrtNeuralEngine cached = engines.get(key);
        if (cached != null) return cached;
        synchronized (lock) {
            cached = engines.get(key);
            if (cached != null) return cached;
            if (!model.exists()) {
                lastError = "模型不存在: " + key;
                Log.e(TAG, lastError);
                return null;
            }
            long t0 = System.currentTimeMillis();
            try {
                OrtNeuralEngine e = new OrtNeuralEngine(model, THREADS);
                engines.put(key, e);
                Log.i(TAG, "加载模型 " + model.getName()
                        + " 用时 " + (System.currentTimeMillis() - t0) + "ms"
                        + " role=" + e.getRole()
                        + " chunk=" + e.getChunkSamples()
                        + " codes=" + e.getCodesPerFrame()
                        + " state=" + e.getStateSize());
                return e;
            } catch (Throwable t) {
                lastError = "加载失败 " + model.getName() + ": " + t.getMessage();
                Log.e(TAG, lastError, t);
                return null;
            }
        }
    }

    /** 释放所有已缓存引擎（挂断时调用，回收约 1.2GB 内存） */
    public static void releaseAll() {
        synchronized (lock) {
            for (OrtNeuralEngine e : engines.values()) {
                try { e.close(); } catch (Throwable ignored) {}
            }
            engines.clear();
        }
        Log.i(TAG, "已释放所有神经引擎");
    }
}
