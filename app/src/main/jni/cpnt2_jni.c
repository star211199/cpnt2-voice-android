/*---------------------------------------------------------------------------*\

  FILE........: cpnt2_jni.c
  AUTHOR......: CPNT Lab
  DATE CREATED: 2026-07-10

  CPNT2 (Codec2) Android JNI 包装层

  提供 Java/C 之间的桥接接口, 让 Android 应用可以调用 CPNT2 编解码库。
  功能:
    1. 初始化编解码器 (指定码率模式)
    2. 编码一帧语音 (PCM short[] -> byte[])
    3. 解码一帧比特流 (byte[] -> PCM short[])
    4. 获取帧参数 (采样数, 字节数, 比特数)
    5. 销毁编解码器
    6. 批量编解码 + 统计信息

\*---------------------------------------------------------------------------*/

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <android/log.h>

#include "codec2.h"

#define TAG "CPNT2_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* 码率模式表 */
typedef struct {
    int         mode;
    const char *name;
    int         sample_rate;
} mode_info_t;

#define NUM_MODES 9

static mode_info_t mode_table[NUM_MODES] = {
    { CODEC2_MODE_3200,   "3200",   8000 },
    { CODEC2_MODE_2400,   "2400",   8000 },
    { CODEC2_MODE_1600,   "1600",   8000 },
    { CODEC2_MODE_1400,   "1400",   8000 },
    { CODEC2_MODE_1300,   "1300",   8000 },
    { CODEC2_MODE_1200,   "1200",   8000 },
    { CODEC2_MODE_700C,   "700C",   8000 },
    { CODEC2_MODE_450,    "450",    8000 },
    { CODEC2_MODE_450PWB, "450PWB", 16000 },
};

/* 获取当前时间 (毫秒) */
static double get_time_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1000.0 + (double)ts.tv_nsec / 1000000.0;
}

/*=========================================================================*\
  JNI 接口函数
\*=========================================================================*/

/*
 * 获取支持的码率模式列表
 * 返回: String[] 模式名称数组
 */
JNIEXPORT jobjectArray JNICALL
Java_com_cpnt2_demo_Codec2Native_getSupportedModes(JNIEnv *env, jobject thiz)
{
    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, NUM_MODES, strClass, NULL);
    for (int i = 0; i < NUM_MODES; i++) {
        jstring str = (*env)->NewStringUTF(env, mode_table[i].name);
        (*env)->SetObjectArrayElement(env, result, i, str);
        (*env)->DeleteLocalRef(env, str);
    }
    return result;
}

/*
 * 根据模式名称获取模式索引
 */
static int get_mode_index(const char *name)
{
    for (int i = 0; i < NUM_MODES; i++) {
        if (strcmp(name, mode_table[i].name) == 0)
            return i;
    }
    return -1;
}

/*
 * 初始化编解码器
 * @param modeStr: 码率模式名称 ("3200", "2400", ..., "450PWB")
 * @return: 编解码器实例指针 (long)
 */
JNIEXPORT jlong JNICALL
Java_com_cpnt2_demo_Codec2Native_create(JNIEnv *env, jobject thiz, jstring modeStr)
{
    if (modeStr == NULL) {
        LOGE("create: modeStr 为 NULL");
        return 0;
    }
    const char *name = (*env)->GetStringUTFChars(env, modeStr, NULL);
    if (name == NULL) return 0;
    int idx = get_mode_index(name);
    if (idx < 0) {
        /* 注意: 必须先打日志再 Release, 否则 name 已释放 (use-after-free) */
        LOGE("未知码率模式: %s", name);
        (*env)->ReleaseStringUTFChars(env, modeStr, name);
        return 0;
    }
    (*env)->ReleaseStringUTFChars(env, modeStr, name);

    struct CODEC2 *codec2 = codec2_create(mode_table[idx].mode);
    if (codec2 == NULL) {
        LOGE("codec2_create 失败 (mode=%s)", mode_table[idx].name);
        return 0;
    }

    LOGI("编解码器创建成功 (mode=%s)", mode_table[idx].name);
    return (jlong)(intptr_t)codec2;
}

/*
 * 销毁编解码器
 * @param handle: 编解码器实例指针
 */
JNIEXPORT void JNICALL
Java_com_cpnt2_demo_Codec2Native_destroy(JNIEnv *env, jobject thiz, jlong handle)
{
    if (handle == 0) return;
    struct CODEC2 *codec2 = (struct CODEC2 *)(intptr_t)handle;
    codec2_destroy(codec2);
    LOGI("编解码器已销毁");
}

/*
 * 获取每帧采样数
 */
JNIEXPORT jint JNICALL
Java_com_cpnt2_demo_Codec2Native_samplesPerFrame(JNIEnv *env, jobject thiz, jlong handle)
{
    if (handle == 0) return 0;
    struct CODEC2 *codec2 = (struct CODEC2 *)(intptr_t)handle;
    return (jint)codec2_samples_per_frame(codec2);
}

/*
 * 获取每帧压缩字节数
 */
JNIEXPORT jint JNICALL
Java_com_cpnt2_demo_Codec2Native_bytesPerFrame(JNIEnv *env, jobject thiz, jlong handle)
{
    if (handle == 0) return 0;
    struct CODEC2 *codec2 = (struct CODEC2 *)(intptr_t)handle;
    return (jint)codec2_bytes_per_frame(codec2);
}

/*
 * 获取每帧压缩比特数
 */
JNIEXPORT jint JNICALL
Java_com_cpnt2_demo_Codec2Native_bitsPerFrame(JNIEnv *env, jobject thiz, jlong handle)
{
    if (handle == 0) return 0;
    struct CODEC2 *codec2 = (struct CODEC2 *)(intptr_t)handle;
    return (jint)codec2_bits_per_frame(codec2);
}

/*
 * 获取采样率
 * @param modeStr: 码率模式名称
 * @return: 采样率 (8000 或 16000)
 */
JNIEXPORT jint JNICALL
Java_com_cpnt2_demo_Codec2Native_getSampleRate(JNIEnv *env, jobject thiz, jstring modeStr)
{
    if (modeStr == NULL) return 8000;
    const char *name = (*env)->GetStringUTFChars(env, modeStr, NULL);
    if (name == NULL) return 8000;
    int idx = get_mode_index(name);
    (*env)->ReleaseStringUTFChars(env, modeStr, name);
    if (idx < 0) return 8000;
    return (jint)mode_table[idx].sample_rate;
}

/*
 * 编码一帧语音
 * @param handle: 编解码器实例
 * @param speechIn: PCM 语音样本 (short[])
 * @return: 压缩字节 byte[]
 */
JNIEXPORT jbyteArray JNICALL
Java_com_cpnt2_demo_Codec2Native_encodeFrame(JNIEnv *env, jobject thiz,
                                              jlong handle, jshortArray speechIn)
{
    if (handle == 0) return NULL;
    struct CODEC2 *codec2 = (struct CODEC2 *)(intptr_t)handle;

    int nsam  = codec2_samples_per_frame(codec2);
    int nbyte = codec2_bytes_per_frame(codec2);

    /* 防御: 输入样本数必须够一整帧, 否则 codec2_encode 会越界读 */
    if ((*env)->GetArrayLength(env, speechIn) < nsam) {
        LOGE("encodeFrame: 输入样本数不足 (需要 %d)", nsam);
        return NULL;
    }

    jshort *speech = (*env)->GetShortArrayElements(env, speechIn, NULL);
    if (speech == NULL) return NULL;

    unsigned char *compressed = (unsigned char *)malloc(nbyte);
    if (compressed == NULL) {
        (*env)->ReleaseShortArrayElements(env, speechIn, speech, JNI_ABORT);
        return NULL;
    }

    codec2_encode(codec2, compressed, speech);

    jbyteArray result = (*env)->NewByteArray(env, nbyte);
    (*env)->SetByteArrayRegion(env, result, 0, nbyte, (jbyte *)compressed);

    free(compressed);
    (*env)->ReleaseShortArrayElements(env, speechIn, speech, JNI_ABORT);

    return result;
}

/*
 * 解码一帧比特流
 * @param handle: 编解码器实例
 * @param compressedIn: 压缩字节 byte[]
 * @return: PCM 语音样本 short[]
 */
JNIEXPORT jshortArray JNICALL
Java_com_cpnt2_demo_Codec2Native_decodeFrame(JNIEnv *env, jobject thiz,
                                              jlong handle, jbyteArray compressedIn)
{
    if (handle == 0) return NULL;
    struct CODEC2 *codec2 = (struct CODEC2 *)(intptr_t)handle;

    int nsam  = codec2_samples_per_frame(codec2);
    int nbyte = codec2_bytes_per_frame(codec2);

    jbyte *compressed = (*env)->GetByteArrayElements(env, compressedIn, NULL);
    if (compressed == NULL) return NULL;

    jsize inLen = (*env)->GetArrayLength(env, compressedIn);
    const unsigned char *bits_in = (const unsigned char *)compressed;
    unsigned char *padded = NULL;

    if (inLen < nbyte) {
        /* 防御: BLE 收到的帧可能被截断。codec2_decode 固定读取 nbyte 字节,
         * 直接传短帧会造成堆越界读, 这里补零到期望长度 */
        padded = (unsigned char *)calloc(1, nbyte);
        if (padded == NULL) {
            (*env)->ReleaseByteArrayElements(env, compressedIn, compressed, JNI_ABORT);
            return NULL;
        }
        memcpy(padded, compressed, inLen);
        bits_in = padded;
    }

    short *speech = (short *)malloc(nsam * sizeof(short));
    if (speech == NULL) {
        free(padded);
        (*env)->ReleaseByteArrayElements(env, compressedIn, compressed, JNI_ABORT);
        return NULL;
    }

    codec2_decode(codec2, speech, bits_in);

    jshortArray result = (*env)->NewShortArray(env, nsam);
    (*env)->SetShortArrayRegion(env, result, 0, nsam, speech);

    free(speech);
    free(padded);
    (*env)->ReleaseByteArrayElements(env, compressedIn, compressed, JNI_ABORT);

    return result;
}

/*
 * 批量编解码: 对整段 PCM 数据进行编解码, 返回统计信息
 *
 * @param modeStr: 码率模式名称
 * @param pcmData: 原始 PCM 语音数据 (short[])
 * @return: long[] { 原始字节数, 压缩字节数, 帧数, 编码耗时ms, 解码耗时ms }
 */
JNIEXPORT jlongArray JNICALL
Java_com_cpnt2_demo_Codec2Native_processAll(JNIEnv *env, jobject thiz,
                                             jstring modeStr, jshortArray pcmData)
{
    if (modeStr == NULL) {
        LOGE("processAll: modeStr 为 NULL");
        return NULL;
    }
    const char *name = (*env)->GetStringUTFChars(env, modeStr, NULL);
    if (name == NULL) return NULL;
    int idx = get_mode_index(name);
    if (idx < 0) {
        /* 注意: 必须先打日志再 Release, 否则 name 已释放 (use-after-free) */
        LOGE("未知码率模式: %s", name);
        (*env)->ReleaseStringUTFChars(env, modeStr, name);
        return NULL;
    }
    (*env)->ReleaseStringUTFChars(env, modeStr, name);

    struct CODEC2 *codec2 = codec2_create(mode_table[idx].mode);
    if (codec2 == NULL) {
        LOGE("codec2_create 失败");
        return NULL;
    }

    int nsam  = codec2_samples_per_frame(codec2);
    int nbyte = codec2_bytes_per_frame(codec2);

    jsize total_samples = (*env)->GetArrayLength(env, pcmData);
    int frame_count = total_samples / nsam;

    jshort *pcm = (*env)->GetShortArrayElements(env, pcmData, NULL);
    if (pcm == NULL) {
        codec2_destroy(codec2);
        return NULL;
    }

    unsigned char *compressed = (unsigned char *)malloc(nbyte);
    short *speech_buf = (short *)malloc(nsam * sizeof(short));
    if (!compressed || !speech_buf) {
        if (compressed) free(compressed);
        if (speech_buf) free(speech_buf);
        (*env)->ReleaseShortArrayElements(env, pcmData, pcm, JNI_ABORT);
        codec2_destroy(codec2);
        return NULL;
    }

    double enc_total = 0, dec_total = 0;
    long total_compressed_bytes = 0;

    for (int i = 0; i < frame_count; i++) {
        memcpy(speech_buf, pcm + i * nsam, nsam * sizeof(short));

        double t1 = get_time_ms();
        codec2_encode(codec2, compressed, speech_buf);
        double t2 = get_time_ms();
        enc_total += (t2 - t1);

        codec2_decode(codec2, speech_buf, compressed);
        double t3 = get_time_ms();
        dec_total += (t3 - t2);

        total_compressed_bytes += nbyte;
    }

    free(compressed);
    free(speech_buf);
    (*env)->ReleaseShortArrayElements(env, pcmData, pcm, JNI_ABORT);
    codec2_destroy(codec2);

    /* 返回统计信息 */
    jlong stats[5];
    stats[0] = (jlong)total_samples * 2;    /* 原始字节数 */
    stats[1] = (jlong)total_compressed_bytes; /* 压缩字节数 */
    stats[2] = (jlong)frame_count;            /* 帧数 */
    stats[3] = (jlong)(enc_total + 0.5);      /* 编码耗时 (ms) */
    stats[4] = (jlong)(dec_total + 0.5);      /* 解码耗时 (ms) */

    jlongArray result = (*env)->NewLongArray(env, 5);
    (*env)->SetLongArrayRegion(env, result, 0, 5, stats);

    LOGI("模式 %s: %d 帧, 压缩比 %.1f:1, 编码 %.1fms, 解码 %.1fms",
         mode_table[idx].name, frame_count,
         (double)(total_samples * 2) / (total_compressed_bytes > 0 ? total_compressed_bytes : 1),
         enc_total, dec_total);

    return result;
}

/*
 * 获取 CPNT2 版本信息
 */
JNIEXPORT jstring JNICALL
Java_com_cpnt2_demo_Codec2Native_getVersion(JNIEnv *env, jobject thiz)
{
    return (*env)->NewStringUTF(env, "CPNT2 (Codec2) 1.1.0 - Android NDK Build");
}
