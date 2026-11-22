/* JNI shim for the RNNoise denoiser (xiph/rnnoise).
 *
 * RNNoise works on 48 kHz, 480-sample frames and expects samples in the 16-bit integer range, not -1..1
 * (its own demo reads shorts into floats without scaling; denoise.c treats a frame as silence when the
 * summed band energy drops under 0.04, which only makes sense at that scale). The Kotlin side keeps the
 * project convention of floats in -1..1, so this file does the conversion both ways.
 *
 * Plain C on purpose: the APK ships no libc++_shared.so, so a C++ translation unit would add a runtime
 * dependency the device cannot resolve. Nothing here needs C++.
 *
 * The state must be fed every frame in order: no skipping, no repeating. The caller owns the framing
 * (see Denoise.kt) and only hands over complete frames.
 */
#include <jni.h>
#include "rnnoise.h"

#define FRAME 480
#define SCALE 32768.0f

JNIEXPORT jlong JNICALL
Java_com_voicestudio_phone_Denoise_nativeCreate(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return (jlong) (intptr_t) rnnoise_create(NULL);
}

/* Denoises one 480-sample frame in place; returns the voice-activity probability (0..1) of that frame. */
JNIEXPORT jfloat JNICALL
Java_com_voicestudio_phone_Denoise_nativeProcess(JNIEnv *env, jclass cls, jlong handle, jfloatArray frame) {
    (void) cls;
    DenoiseState *st = (DenoiseState *) (intptr_t) handle;
    jfloat *buf;
    float x[FRAME];
    float vad;
    int i;
    if (st == NULL || frame == NULL) return 0.0f;
    if ((*env)->GetArrayLength(env, frame) != FRAME) return 0.0f;
    buf = (*env)->GetFloatArrayElements(env, frame, NULL);
    if (buf == NULL) return 0.0f;
    for (i = 0; i < FRAME; i++) x[i] = buf[i] * SCALE;
    vad = rnnoise_process_frame(st, x, x);
    for (i = 0; i < FRAME; i++) {
        /* Denoised output can overshoot a little; keep it inside the range the rest of the chain expects. */
        float v = x[i] / SCALE;
        buf[i] = v > 1.0f ? 1.0f : (v < -1.0f ? -1.0f : v);
    }
    (*env)->ReleaseFloatArrayElements(env, frame, buf, 0);
    return vad;
}

JNIEXPORT jint JNICALL
Java_com_voicestudio_phone_Denoise_nativeFrameSize(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return rnnoise_get_frame_size();
}

JNIEXPORT void JNICALL
Java_com_voicestudio_phone_Denoise_nativeDestroy(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    if (handle != 0) rnnoise_destroy((DenoiseState *) (intptr_t) handle);
}
