#include <jni.h>
#include <android/log.h>
#include <memory>
#include <mutex>
#include <vector>
#include <deque>
#include <cmath>
#include <algorithm>

#include "riesz.h"
#include "vitals.h"
#include "fft.h"

#define LOG_TAG "EulerScopeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    std::mutex mtx;
    es::RieszMagnifier mag;
    es::VitalsAnalyzer vitals;

    int w = 0, h = 0;
    float fps = 30.0f;

    std::vector<float> luma;      // ingresso [0..1]
    std::vector<float> magnified; // uscita
    std::vector<float> chromaU, chromaV;

    // ROI corrente, coordinate del frame elaborato
    int roiX0 = 0, roiY0 = 0, roiX1 = 0, roiY1 = 0;
    bool roiSet = false;

    // storico del segnale di movimento per lo spettro (modalita' strutture)
    std::deque<float> motionHistory;
    size_t motionCap = 2048;
};

std::unique_ptr<Engine> gEngine;

inline float clamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_it_rs_eulerscope_core_NativeEngine_nativeInit(
        JNIEnv*, jobject, jint w, jint h, jfloat fps, jint levels) {
    gEngine = std::make_unique<Engine>();
    std::lock_guard<std::mutex> lk(gEngine->mtx);

    gEngine->w = w;
    gEngine->h = h;
    gEngine->fps = fps;

    es::BandParams p;
    p.levels = levels;
    p.maxLevelProcessed = std::max(0, levels - 2);
    gEngine->mag.configure(w, h, fps, p);
    gEngine->vitals.configure(fps, 12.0f);

    const size_t n = static_cast<size_t>(w) * h;
    gEngine->luma.assign(n, 0.0f);
    gEngine->magnified.assign(n, 0.0f);
    gEngine->chromaU.assign(n / 4 + 1, 0.0f);
    gEngine->chromaV.assign(n / 4 + 1, 0.0f);
    gEngine->motionCap = static_cast<size_t>(fps * 30.0f);

    LOGI("init %dx%d @ %.2f fps, %d livelli", w, h, fps, levels);
}

JNIEXPORT void JNICALL
Java_it_rs_eulerscope_core_NativeEngine_nativeSetBand(
        JNIEnv*, jobject, jfloat fLow, jfloat fHigh, jfloat alpha) {
    if (!gEngine) return;
    std::lock_guard<std::mutex> lk(gEngine->mtx);
    gEngine->mag.setBand(fLow, fHigh, alpha);
    gEngine->motionHistory.clear();
}

JNIEXPORT void JNICALL
Java_it_rs_eulerscope_core_NativeEngine_nativeSetRoi(
        JNIEnv*, jobject, jint x0, jint y0, jint x1, jint y1) {
    if (!gEngine) return;
    std::lock_guard<std::mutex> lk(gEngine->mtx);
    gEngine->roiX0 = x0; gEngine->roiY0 = y0;
    gEngine->roiX1 = x1; gEngine->roiY1 = y1;
    gEngine->roiSet = (x1 > x0 && y1 > y0);
}

JNIEXPORT void JNICALL
Java_it_rs_eulerscope_core_NativeEngine_nativeReset(JNIEnv*, jobject) {
    if (!gEngine) return;
    std::lock_guard<std::mutex> lk(gEngine->mtx);
    gEngine->mag.reset();
    gEngine->vitals.reset();
    gEngine->motionHistory.clear();
}

JNIEXPORT void JNICALL
Java_it_rs_eulerscope_core_NativeEngine_nativeRelease(JNIEnv*, jobject) {
    gEngine.reset();
}

/**
 * Elabora un frame YUV_420_888.
 * yBuf/uBuf/vBuf sono DirectByteBuffer dalle Image.Plane di Camera2.
 * outArgb riceve il frame magnificato in ARGB_8888.
 * Ritorna true se il frame e' stato elaborato.
 */
JNIEXPORT jboolean JNICALL
Java_it_rs_eulerscope_core_NativeEngine_nativeProcessFrame(
        JNIEnv* env, jobject,
        jobject yBuf, jint yStride,
        jobject uBuf, jint uStride, jint uPixStride,
        jobject vBuf, jint vStride, jint vPixStride,
        jintArray outArgb, jboolean magnifyEnabled) {

    if (!gEngine) return JNI_FALSE;
    std::lock_guard<std::mutex> lk(gEngine->mtx);

    auto* y = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuf));
    auto* u = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuf));
    auto* v = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuf));
    if (!y || !u || !v) {
        LOGE("buffer non diretti: impossibile accedere ai piani");
        return JNI_FALSE;
    }

    const int W = gEngine->w, H = gEngine->h;

    // --- luma in float ---
    for (int j = 0; j < H; ++j) {
        const uint8_t* row = y + static_cast<size_t>(j) * yStride;
        float* dst = &gEngine->luma[static_cast<size_t>(j) * W];
        for (int i = 0; i < W; ++i) dst[i] = static_cast<float>(row[i]) * (1.0f / 255.0f);
    }

    // --- medie RGB sulla ROI, per rPPG ---
    if (gEngine->roiSet) {
        double sr = 0, sg = 0, sb = 0;
        long cnt = 0;
        const int x0 = std::max(0, gEngine->roiX0), y0 = std::max(0, gEngine->roiY0);
        const int x1 = std::min(W - 1, gEngine->roiX1), y1 = std::min(H - 1, gEngine->roiY1);
        for (int j = y0; j <= y1; ++j) {
            for (int i = x0; i <= x1; ++i) {
                const float Y = static_cast<float>(y[static_cast<size_t>(j) * yStride + i]);
                const int cj = j / 2, ci = i / 2;
                const float U = static_cast<float>(u[static_cast<size_t>(cj) * uStride + ci * uPixStride]) - 128.0f;
                const float V = static_cast<float>(v[static_cast<size_t>(cj) * vStride + ci * vPixStride]) - 128.0f;
                sr += Y + 1.402f * V;
                sg += Y - 0.344136f * U - 0.714136f * V;
                sb += Y + 1.772f * U;
                ++cnt;
            }
        }
        if (cnt > 0) {
            gEngine->vitals.pushSkinRgb(static_cast<float>(sr / cnt),
                                        static_cast<float>(sg / cnt),
                                        static_cast<float>(sb / cnt));
        }
    }

    // --- magnificazione ---
    if (magnifyEnabled) {
        gEngine->mag.process(gEngine->luma.data(), gEngine->magnified.data());
    } else {
        gEngine->magnified = gEngine->luma;
    }

    // --- segnale di movimento della ROI ---
    if (gEngine->roiSet) {
        const float m = gEngine->mag.roiMotionSignal(
                gEngine->roiX0, gEngine->roiY0, gEngine->roiX1, gEngine->roiY1);
        if (!std::isnan(m)) {
            gEngine->vitals.pushMotion(m);
            gEngine->motionHistory.push_back(m);
            while (gEngine->motionHistory.size() > gEngine->motionCap)
                gEngine->motionHistory.pop_front();
        }
    }

    // --- ricomposizione YUV -> ARGB con luma magnificata ---
    jint* out = env->GetIntArrayElements(outArgb, nullptr);
    if (!out) return JNI_FALSE;

    for (int j = 0; j < H; ++j) {
        const int cj = j / 2;
        for (int i = 0; i < W; ++i) {
            const int ci = i / 2;
            const float U = static_cast<float>(u[static_cast<size_t>(cj) * uStride + ci * uPixStride]) - 128.0f;
            const float V = static_cast<float>(v[static_cast<size_t>(cj) * vStride + ci * vPixStride]) - 128.0f;
            const float Y = clamp01(gEngine->magnified[static_cast<size_t>(j) * W + i]) * 255.0f;

            const int r = static_cast<int>(Y + 1.402f * V);
            const int g = static_cast<int>(Y - 0.344136f * U - 0.714136f * V);
            const int b = static_cast<int>(Y + 1.772f * U);

            out[static_cast<size_t>(j) * W + i] =
                    (0xFF << 24) |
                    (std::clamp(r, 0, 255) << 16) |
                    (std::clamp(g, 0, 255) << 8) |
                    std::clamp(b, 0, 255);
        }
    }
    env->ReleaseIntArrayElements(outArgb, out, 0);
    return JNI_TRUE;
}

/**
 * Parametri vitali correnti.
 * Layout float[13]:
 *  0 hrValid 1 hrBpm 2 hrQuality
 *  3 rrValid 4 rrBpm 5 rrQuality
 *  6 trValid 7 trHz  8 trQuality
 *  9 irValid 10 irCv 11 irQuality
 * 12 bufferFill
 * I campi *Valid sono 0.0 o 1.0. Quando un campo e' 0.0 il valore
 * adiacente non deve essere mostrato: non e' una stima, e' assenza di dato.
 */
JNIEXPORT jfloatArray JNICALL
Java_it_rs_eulerscope_core_NativeEngine_nativeGetVitals(JNIEnv* env, jobject) {
    jfloatArray arr = env->NewFloatArray(13);
    if (!gEngine) return arr;

    std::lock_guard<std::mutex> lk(gEngine->mtx);
    const es::VitalsResult v = gEngine->vitals.analyze();

    float buf[13] = {
            v.heartRate.valid ? 1.f : 0.f, v.heartRate.value, v.heartRate.quality,
            v.respiration.valid ? 1.f : 0.f, v.respiration.value, v.respiration.quality,
            v.tremor.valid ? 1.f : 0.f, v.tremor.value, v.tremor.quality,
            v.rhythmIrregular.valid ? 1.f : 0.f, v.rhythmIrregular.value, v.rhythmIrregular.quality,
            v.bufferFill
    };
    env->SetFloatArrayRegion(arr, 0, 13, buf);
    return arr;
}

/**
 * Spettro del segnale di movimento della ROI (modalita' strutture/macchine).
 * Ritorna float[nBins+1] dove l'ultimo elemento e' la risoluzione in Hz
 * per bin. Array vuoto se non ci sono abbastanza campioni.
 */
JNIEXPORT jfloatArray JNICALL
Java_it_rs_eulerscope_core_NativeEngine_nativeGetMotionSpectrum(JNIEnv* env, jobject) {
    if (!gEngine) return env->NewFloatArray(0);
    std::lock_guard<std::mutex> lk(gEngine->mtx);

    if (gEngine->motionHistory.size() < 64) return env->NewFloatArray(0);

    std::vector<float> x(gEngine->motionHistory.begin(), gEngine->motionHistory.end());
    const size_t nfft = es::nextPow2(x.size());
    const auto mag = es::amplitudeSpectrum(x, nfft);

    const int n = static_cast<int>(mag.size());
    jfloatArray arr = env->NewFloatArray(n + 1);
    std::vector<float> outv(mag);
    outv.push_back(gEngine->fps / static_cast<float>(nfft));
    env->SetFloatArrayRegion(arr, 0, n + 1, outv.data());
    return arr;
}

} // extern "C"
