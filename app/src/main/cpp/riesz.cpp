#include "riesz.h"
#include <cmath>
#include <algorithm>
#include <limits>

namespace es {

// ---------------------------------------------------------------------------
// Piramide
// ---------------------------------------------------------------------------

static const float kBinom[5] = {1.0f / 16, 4.0f / 16, 6.0f / 16, 4.0f / 16, 1.0f / 16};

static inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

void gaussianBlur5(const Plane& src, Plane& dst) {
    if (dst.w != src.w || dst.h != src.h) dst.alloc(src.w, src.h);
    static thread_local Plane tmp;
    if (tmp.w != src.w || tmp.h != src.h) tmp.alloc(src.w, src.h);

    // orizzontale
    for (int y = 0; y < src.h; ++y) {
        const float* row = &src.d[static_cast<size_t>(y) * src.w];
        float* trow = &tmp.d[static_cast<size_t>(y) * src.w];
        for (int x = 0; x < src.w; ++x) {
            float acc = 0.0f;
            for (int k = -2; k <= 2; ++k) {
                acc += kBinom[k + 2] * row[clampi(x + k, 0, src.w - 1)];
            }
            trow[x] = acc;
        }
    }
    // verticale
    for (int y = 0; y < src.h; ++y) {
        float* drow = &dst.d[static_cast<size_t>(y) * src.w];
        for (int x = 0; x < src.w; ++x) {
            float acc = 0.0f;
            for (int k = -2; k <= 2; ++k) {
                acc += kBinom[k + 2] * tmp.d[static_cast<size_t>(clampi(y + k, 0, src.h - 1)) * src.w + x];
            }
            drow[x] = acc;
        }
    }
}

void downsample(const Plane& src, Plane& dst) {
    Plane blurred;
    gaussianBlur5(src, blurred);
    const int nw = std::max(1, src.w / 2);
    const int nh = std::max(1, src.h / 2);
    dst.alloc(nw, nh);
    for (int y = 0; y < nh; ++y) {
        for (int x = 0; x < nw; ++x) {
            dst.at(x, y) = blurred.at(std::min(x * 2, src.w - 1), std::min(y * 2, src.h - 1));
        }
    }
}

void upsample(const Plane& src, Plane& dst, int w, int h) {
    Plane expanded;
    expanded.alloc(w, h);
    // inserimento con zeri + blur, guadagno 4
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            if ((x % 2) == 0 && (y % 2) == 0) {
                const int sx = std::min(x / 2, src.w - 1);
                const int sy = std::min(y / 2, src.h - 1);
                expanded.at(x, y) = src.at(sx, sy);
            }
        }
    }
    gaussianBlur5(expanded, dst);
    for (auto& v : dst.d) v *= 4.0f;
}

// ---------------------------------------------------------------------------
// Configurazione
// ---------------------------------------------------------------------------

// Butterworth digitale del 1o ordine, passa-basso, via trasformata bilineare
// con pre-warping. fc in Hz, fs in Hz.
static void butter1Lowpass(float fc, float fs, float& b0, float& b1, float& a1) {
    const float nyq = fs * 0.5f;
    float f = fc;
    if (f <= 0.0f) f = 1e-4f;
    if (f >= nyq * 0.999f) f = nyq * 0.999f;
    const float wa = std::tan(static_cast<float>(M_PI) * f / fs);
    const float d = 1.0f + wa;
    b0 = wa / d;
    b1 = wa / d;
    a1 = (wa - 1.0f) / d;
}

void RieszMagnifier::configure(int width, int height, float fps, const BandParams& p) {
    w_ = width; h_ = height;
    fps_ = (fps > 1.0f) ? fps : 30.0f;
    params_ = p;
    params_.levels = std::max(2, std::min(p.levels, 6));
    params_.maxLevelProcessed = std::min(params_.maxLevelProcessed, params_.levels - 1);

    gauss_.assign(static_cast<size_t>(params_.levels), Plane());
    lap_.assign(static_cast<size_t>(params_.levels), Plane());
    outLap_.assign(static_cast<size_t>(params_.levels), Plane());
    st_.assign(static_cast<size_t>(params_.levels), LevelState());

    butter1Lowpass(params_.fHigh, fps_, b0hi_, b1hi_, a1hi_);
    butter1Lowpass(params_.fLow,  fps_, b0lo_, b1lo_, a1lo_);

    configured_ = true;
}

void RieszMagnifier::setBand(float fLow, float fHigh, float alpha) {
    params_.fLow = fLow;
    params_.fHigh = fHigh;
    params_.alpha = alpha;
    butter1Lowpass(params_.fHigh, fps_, b0hi_, b1hi_, a1hi_);
    butter1Lowpass(params_.fLow,  fps_, b0lo_, b1lo_, a1lo_);
    reset();
}

void RieszMagnifier::reset() {
    for (auto& s : st_) s = LevelState();
    roiPhase_ = Plane();
    roiAmp_ = Plane();
}

// ---------------------------------------------------------------------------
// Elaborazione
// ---------------------------------------------------------------------------

void RieszMagnifier::buildPyramid(const float* in) {
    gauss_[0].alloc(w_, h_);
    std::copy(in, in + static_cast<size_t>(w_) * h_, gauss_[0].d.begin());

    for (int l = 1; l < params_.levels; ++l) {
        downsample(gauss_[l - 1], gauss_[l]);
    }
    for (int l = 0; l < params_.levels - 1; ++l) {
        Plane up;
        upsample(gauss_[l + 1], up, gauss_[l].w, gauss_[l].h);
        lap_[l].alloc(gauss_[l].w, gauss_[l].h);
        for (size_t i = 0; i < lap_[l].d.size(); ++i) {
            lap_[l].d[i] = gauss_[l].d[i] - up.d[i];
        }
    }
    // l'ultimo livello e' il residuo passa-basso, non amplificato
    lap_[params_.levels - 1] = gauss_[params_.levels - 1];
}

void RieszMagnifier::collapse(float* out) {
    Plane acc = outLap_[params_.levels - 1];
    for (int l = params_.levels - 2; l >= 0; --l) {
        Plane up;
        upsample(acc, up, outLap_[l].w, outLap_[l].h);
        acc.alloc(outLap_[l].w, outLap_[l].h);
        for (size_t i = 0; i < acc.d.size(); ++i) {
            acc.d[i] = outLap_[l].d[i] + up.d[i];
        }
    }
    std::copy(acc.d.begin(), acc.d.end(), out);
}

void RieszMagnifier::process(const float* in, float* out) {
    if (!configured_) return;

    buildPyramid(in);
    outLap_ = lap_;   // i livelli non elaborati passano inalterati

    for (int l = 0; l <= params_.maxLevelProcessed && l < params_.levels - 1; ++l) {
        const Plane& L = lap_[l];
        const int lw = L.w, lh = L.h;
        if (lw < 8 || lh < 8) continue;

        // --- trasformata di Riesz: approssimazione FIR [0.5, 0, -0.5] ---
        Plane R1, R2;
        R1.alloc(lw, lh);
        R2.alloc(lw, lh);
        for (int y = 0; y < lh; ++y) {
            for (int x = 0; x < lw; ++x) {
                const int xm = clampi(x - 1, 0, lw - 1), xp = clampi(x + 1, 0, lw - 1);
                const int ym = clampi(y - 1, 0, lh - 1), yp = clampi(y + 1, 0, lh - 1);
                R1.at(x, y) = 0.5f * (L.at(xp, y) - L.at(xm, y));
                R2.at(x, y) = 0.5f * (L.at(x, yp) - L.at(x, ym));
            }
        }

        LevelState& S = st_[l];
        if (!S.initialised) {
            S.prevLap = L; S.prevR1 = R1; S.prevR2 = R2;
            S.phaseCos.alloc(lw, lh); S.phaseSin.alloc(lw, lh);
            S.lp1Cos.alloc(lw, lh);   S.lp1Sin.alloc(lw, lh);
            S.lp2Cos.alloc(lw, lh);   S.lp2Sin.alloc(lw, lh);
            S.initialised = true;
            continue;   // il primo frame non produce differenza di fase
        }

        Plane fCos, fSin, amp;
        fCos.alloc(lw, lh); fSin.alloc(lw, lh); amp.alloc(lw, lh);

        const size_t n = static_cast<size_t>(lw) * lh;
        for (size_t i = 0; i < n; ++i) {
            const float a  = L.d[i],  r1  = R1.d[i],  r2  = R2.d[i];
            const float pa = S.prevLap.d[i], pr1 = S.prevR1.d[i], pr2 = S.prevR2.d[i];

            // --- differenza di fase quaternionica ---
            // q = (a, r1, r2), q_prev = (pa, pr1, pr2)
            // q_diff = q * conj(q_prev) / |q_prev|^2
            const float qr = a * pa + r1 * pr1 + r2 * pr2;
            const float qi = r1 * pa - a * pr1;
            const float qj = r2 * pa - a * pr2;

            const float tangential = std::sqrt(qi * qi + qj * qj);
            const float norm = std::sqrt(qr * qr + qi * qi + qj * qj);

            float dPhiCos = 0.0f, dPhiSin = 0.0f;
            if (norm > 1e-9f && tangential > 1e-9f) {
                const float phi = std::atan2(tangential, qr);   // [0, pi]
                dPhiCos = phi * (qi / tangential);
                dPhiSin = phi * (qj / tangential);
            }

            // accumulo temporale della fase
            S.phaseCos.d[i] += dPhiCos;
            S.phaseSin.d[i] += dPhiSin;

            // --- filtro passa-banda: differenza di due passa-basso ---
            const float xC = S.phaseCos.d[i], xS = S.phaseSin.d[i];

            const float y1C = b0hi_ * xC + b1hi_ * dPhiCos - a1hi_ * S.lp1Cos.d[i];
            const float y1S = b0hi_ * xS + b1hi_ * dPhiSin - a1hi_ * S.lp1Sin.d[i];
            const float y2C = b0lo_ * xC + b1lo_ * dPhiCos - a1lo_ * S.lp2Cos.d[i];
            const float y2S = b0lo_ * xS + b1lo_ * dPhiSin - a1lo_ * S.lp2Sin.d[i];

            S.lp1Cos.d[i] = y1C; S.lp1Sin.d[i] = y1S;
            S.lp2Cos.d[i] = y2C; S.lp2Sin.d[i] = y2S;

            fCos.d[i] = y1C - y2C;
            fSin.d[i] = y1S - y2S;

            amp.d[i] = std::sqrt(a * a + r1 * r1 + r2 * r2);
        }

        // --- denoising spaziale pesato sull'ampiezza ---
        // blur(phase * A^2) / blur(A^2): sopprime la fase dove il segnale
        // e' debole, cioe' dove domina il rumore del sensore.
        Plane wC, wS, wA;
        wC.alloc(lw, lh); wS.alloc(lw, lh); wA.alloc(lw, lh);
        for (size_t i = 0; i < n; ++i) {
            const float a2 = amp.d[i] * amp.d[i];
            wC.d[i] = fCos.d[i] * a2;
            wS.d[i] = fSin.d[i] * a2;
            wA.d[i] = a2;
        }
        Plane bC, bS, bA;
        gaussianBlur5(wC, bC);
        gaussianBlur5(wS, bS);
        gaussianBlur5(wA, bA);

        // --- shift di fase ---
        // segnale monogenico: I = A cos(phi), R_theta = A sin(phi)
        // I' = A cos(phi + D) = I cos(D) - R_theta sin(D)
        for (size_t i = 0; i < n; ++i) {
            const float denom = bA.d[i] + 1e-6f;
            const float pC = bC.d[i] / denom;
            const float pS = bS.d[i] / denom;

            const float mag = std::sqrt(pC * pC + pS * pS);
            if (mag < 1e-9f) { outLap_[l].d[i] = L.d[i]; continue; }

            const float cosT = pC / mag;
            const float sinT = pS / mag;
            const float D = params_.alpha * mag;

            const float rTheta = R1.d[i] * cosT + R2.d[i] * sinT;
            outLap_[l].d[i] = L.d[i] * std::cos(D) - rTheta * std::sin(D);
        }

        if (l == 1 || (params_.maxLevelProcessed == 0 && l == 0)) {
            roiPhase_.alloc(lw, lh);
            roiAmp_.alloc(lw, lh);
            for (size_t i = 0; i < n; ++i) {
                const float denom = bA.d[i] + 1e-6f;
                const float pC = bC.d[i] / denom, pS = bS.d[i] / denom;
                roiPhase_.d[i] = std::sqrt(pC * pC + pS * pS);
                roiAmp_.d[i] = amp.d[i];
            }
        }

        S.prevLap = L; S.prevR1 = R1; S.prevR2 = R2;
    }

    collapse(out);
}

float RieszMagnifier::roiMotionSignal(int x0, int y0, int x1, int y1) const {
    if (roiPhase_.empty()) return std::numeric_limits<float>::quiet_NaN();

    // la ROI arriva in coordinate del frame intero: riscalata al livello
    const float sx = static_cast<float>(roiPhase_.w) / static_cast<float>(w_);
    const float sy = static_cast<float>(roiPhase_.h) / static_cast<float>(h_);

    int a = clampi(static_cast<int>(x0 * sx), 0, roiPhase_.w - 1);
    int b = clampi(static_cast<int>(y0 * sy), 0, roiPhase_.h - 1);
    int c = clampi(static_cast<int>(x1 * sx), 0, roiPhase_.w - 1);
    int d = clampi(static_cast<int>(y1 * sy), 0, roiPhase_.h - 1);
    if (c <= a || d <= b) return std::numeric_limits<float>::quiet_NaN();

    double num = 0.0, den = 0.0;
    for (int y = b; y <= d; ++y) {
        for (int x = a; x <= c; ++x) {
            const float w = roiAmp_.at(x, y) * roiAmp_.at(x, y);
            num += static_cast<double>(roiPhase_.at(x, y)) * w;
            den += w;
        }
    }
    if (den < 1e-9) return std::numeric_limits<float>::quiet_NaN();
    return static_cast<float>(num / den);
}

} // namespace es
