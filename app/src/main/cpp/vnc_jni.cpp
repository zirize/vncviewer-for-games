// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

#include <jni.h>
#include <android/log.h>
#include <turbojpeg.h>
#include <atomic>
#include <time.h>

#define LOG_TAG "VNC_JNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ── Instrumentation: separates libjpeg's own time from JNI overhead ──
static std::atomic<long long> g_ns_total(0);   // the whole native function
static std::atomic<long long> g_ns_tj(0);      // tjDecompress2 alone
static std::atomic<long long> g_ns_crit(0);    // waiting for GetPrimitiveArrayCritical
static std::atomic<long long> g_px(0);
static std::atomic<int> g_calls(0);

static inline long long now_ns() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

// 🔑 One decoder handle per thread, reused.
//    DecodeManager calls this from four threads at once so it cannot be shared, and calling
//    tjInitDecompress() per rect is itself a measurable cost.
static thread_local tjhandle g_tj = nullptr;

/*
 * Decodes a JPEG straight into the given rect position in the framebuffer.
 *
 * 🔴 The old function (decodeJpegToBitmap) had no x/y parameters at all and always drew at the
 *    bitmap's origin. That is why enabling JPEG broke the picture into fragments, and why
 *    qualityLevel had been pinned at -1 (no JPEG) - diagnosed 2026-09-15.
 *
 * fb is a Java int[] where one pixel is (A<<24)|(R<<16)|(G<<8)|B.
 * In little-endian memory the bytes are [B,G,R,A], which is exactly TJPF_BGRA.
 * (When decompressing into a *A format, turbojpeg guarantees the A byte is 0xFF.)
 */
extern "C"
JNIEXPORT jboolean JNICALL
Java_io_github_zirize_vncviewerforgames_JpegDecoder_decodeJpegToFramebuffer(
        JNIEnv *env, jobject thiz,
        jbyteArray jpeg_data, jint length,
        jintArray fb, jint fbWidth, jint fbHeight,
        jint x, jint y, jint w, jint h) {

    const long long t_enter = now_ns();
    if (!jpeg_data || !fb) { LOGE("null arg"); return JNI_FALSE; }
    if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > fbWidth || y + h > fbHeight) {
        LOGE("rect out of bounds: %d,%d %dx%d in %dx%d", x, y, w, h, fbWidth, fbHeight);
        return JNI_FALSE;
    }

    if (!g_tj) {
        g_tj = tjInitDecompress();
        if (!g_tj) { LOGE("tjInitDecompress failed: %s", tjGetErrorStr()); return JNI_FALSE; }
    }

    // ⚠️ No other JNI calls inside the critical section. tj* is not JNI, so it is fine.
    jbyte *jpegBuf = (jbyte *) env->GetPrimitiveArrayCritical(jpeg_data, nullptr);
    if (!jpegBuf) { LOGE("GetPrimitiveArrayCritical(jpeg) failed"); return JNI_FALSE; }

    int jw = 0, jh = 0, subsamp = 0, cs = 0;
    if (tjDecompressHeader3(g_tj, (unsigned char *) jpegBuf, (unsigned long) length,
                            &jw, &jh, &subsamp, &cs) < 0) {
        LOGE("tjDecompressHeader3: %s", tjGetErrorStr());
        env->ReleasePrimitiveArrayCritical(jpeg_data, jpegBuf, JNI_ABORT);
        return JNI_FALSE;
    }
    if (jw != w || jh != h) {
        LOGE("jpeg %dx%d != rect %dx%d", jw, jh, w, h);
        env->ReleasePrimitiveArrayCritical(jpeg_data, jpegBuf, JNI_ABORT);
        return JNI_FALSE;
    }

    const long long t_crit0 = now_ns();
    jint *fbPtr = (jint *) env->GetPrimitiveArrayCritical(fb, nullptr);
    const long long t_crit1 = now_ns();
    if (!fbPtr) {
        LOGE("GetPrimitiveArrayCritical(fb) failed");
        env->ReleasePrimitiveArrayCritical(jpeg_data, jpegBuf, JNI_ABORT);
        return JNI_FALSE;
    }

    unsigned char *dst = (unsigned char *) (fbPtr + (size_t) y * (size_t) fbWidth + (size_t) x);
    // ℹ️ [measured 2026-09-15] TJPF_BGRX (which has NEON) plus filling alpha separately was
    //    tried and made **no difference** at 196-212 ms/Mpx ⇒ colour conversion is not the
    //    bottleneck. So TJPF_BGRA is used, where alpha is guaranteed: simpler and safer.
    const long long t_tj0 = now_ns();
    int rc = tjDecompress2(g_tj, (unsigned char *) jpegBuf, (unsigned long) length,
                           dst, w, fbWidth * 4, h, TJPF_BGRA, TJFLAG_FASTDCT);
    const long long t_tj1 = now_ns();

    env->ReleasePrimitiveArrayCritical(fb, fbPtr, 0);
    env->ReleasePrimitiveArrayCritical(jpeg_data, jpegBuf, JNI_ABORT);

    if (rc < 0) { LOGE("tjDecompress2: %s", tjGetErrorStr()); return JNI_FALSE; }

    g_ns_total += now_ns() - t_enter;
    g_ns_tj    += t_tj1 - t_tj0;
    g_ns_crit  += t_crit1 - t_crit0;
    g_px       += (long long) w * h;
    if (++g_calls >= 1500) {
        long long tot = g_ns_total.exchange(0), tj = g_ns_tj.exchange(0);
        long long crit = g_ns_crit.exchange(0), px = g_px.exchange(0);
        g_calls = 0;
        double mpx = px / 1e6;
        if (mpx > 0)
            LOGI("JPEG x1500: total %.0fms/Mpx, tjDecompress2 %.0fms/Mpx, critical wait %.0fms/Mpx",
                 tot / 1e6 / mpx, tj / 1e6 / mpx, crit / 1e6 / mpx);
    }
    return JNI_TRUE;
}
