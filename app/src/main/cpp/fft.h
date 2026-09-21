#pragma once
// FFT radix-2 iterativa + utilità spettrali.
// Nessuna dipendenza esterna: compila con il solo toolchain NDK.

#include <vector>
#include <complex>
#include <cmath>
#include <cstddef>
#include <algorithm>

namespace es {

inline size_t nextPow2(size_t n) {
    size_t p = 1;
    while (p < n) p <<= 1;
    return p;
}

// FFT in-place. data.size() deve essere potenza di 2.
inline void fft(std::vector<std::complex<float>>& a) {
    const size_t n = a.size();
    if (n < 2) return;

    // bit-reversal
    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }

    for (size_t len = 2; len <= n; len <<= 1) {
        const float ang = -2.0f * static_cast<float>(M_PI) / static_cast<float>(len);
        const std::complex<float> wl(std::cos(ang), std::sin(ang));
        for (size_t i = 0; i < n; i += len) {
            std::complex<float> w(1.0f, 0.0f);
            for (size_t k = 0; k < len / 2; ++k) {
                std::complex<float> u = a[i + k];
                std::complex<float> v = a[i + k + len / 2] * w;
                a[i + k] = u + v;
                a[i + k + len / 2] = u - v;
                w *= wl;
            }
        }
    }
}

// Densità spettrale di ampiezza a finestra di Hann.
// Ritorna i bin 0..N/2 (monolatero). freq(bin) = bin * fs / N.
inline std::vector<float> amplitudeSpectrum(const std::vector<float>& x, size_t fftSize) {
    std::vector<std::complex<float>> buf(fftSize, {0.0f, 0.0f});
    const size_t m = std::min(x.size(), fftSize);

    // rimozione media sul segmento effettivo
    float mean = 0.0f;
    for (size_t i = 0; i < m; ++i) mean += x[i];
    if (m > 0) mean /= static_cast<float>(m);

    float winSum = 0.0f;
    for (size_t i = 0; i < m; ++i) {
        const float w = 0.5f * (1.0f - std::cos(2.0f * static_cast<float>(M_PI) *
                                                static_cast<float>(i) / static_cast<float>(m - 1 ? m - 1 : 1)));
        winSum += w;
        buf[i] = {(x[i] - mean) * w, 0.0f};
    }
    if (winSum <= 0.0f) winSum = 1.0f;

    fft(buf);

    std::vector<float> mag(fftSize / 2 + 1);
    for (size_t k = 0; k <= fftSize / 2; ++k) {
        mag[k] = 2.0f * std::abs(buf[k]) / winSum;
    }
    return mag;
}

// Picco spettrale con interpolazione parabolica, limitato a [fLo, fHi].
// Ritorna false se la banda è vuota o l'energia è nulla.
struct SpectralPeak {
    float frequency = 0.0f;   // Hz
    float magnitude = 0.0f;
    float snr = 0.0f;         // energia (picco + 1a armonica) / energia residua
};

// halfWidth: semilarghezza in Hz della finestra considerata "segnale"
// attorno alla fondamentale e alla prima armonica.
//
// La metrica picco/mediana e' inadeguata: su rumore bianco il periodogramma
// ha distribuzione esponenziale e il massimo su N bin supera facilmente
// 2-3 volte la mediana, producendo falsi positivi. Il rapporto di energia
// di banda misura invece quanto lo spettro e' effettivamente concentrato,
// che e' la proprieta' che distingue un segnale periodico dal rumore.
inline bool findPeak(const std::vector<float>& mag, float fs, size_t fftSize,
                     float fLo, float fHi, SpectralPeak& out, float halfWidth = 0.15f) {
    if (mag.size() < 3) return false;
    const float binHz = fs / static_cast<float>(fftSize);
    size_t kLo = static_cast<size_t>(std::ceil(fLo / binHz));
    size_t kHi = static_cast<size_t>(std::floor(fHi / binHz));
    if (kLo < 1) kLo = 1;
    if (kHi >= mag.size() - 1) kHi = mag.size() - 2;
    if (kLo >= kHi) return false;

    size_t kBest = kLo;
    for (size_t k = kLo; k <= kHi; ++k) {
        if (mag[k] > mag[kBest]) kBest = k;
    }
    if (mag[kBest] <= 0.0f) return false;

    // interpolazione parabolica sui tre bin attorno al massimo
    const float y0 = mag[kBest - 1], y1 = mag[kBest], y2 = mag[kBest + 1];
    const float denom = (y0 - 2.0f * y1 + y2);
    float delta = 0.0f;
    if (std::fabs(denom) > 1e-12f) delta = 0.5f * (y0 - y2) / denom;
    if (delta > 1.0f || delta < -1.0f) delta = 0.0f;

    const float f0 = (static_cast<float>(kBest) + delta) * binHz;

    // energia concentrata attorno a f0 e a 2*f0, contro il resto della banda
    double pSig = 0.0, pTot = 0.0;
    for (size_t k = kLo; k <= kHi; ++k) {
        const float f = static_cast<float>(k) * binHz;
        const double p = static_cast<double>(mag[k]) * mag[k];
        pTot += p;
        if (std::fabs(f - f0) <= halfWidth || std::fabs(f - 2.0f * f0) <= halfWidth) {
            pSig += p;
        }
    }
    const double pNoise = pTot - pSig;

    out.frequency = f0;
    out.magnitude = y1;
    out.snr = (pNoise > 1e-15) ? static_cast<float>(pSig / pNoise) : 0.0f;
    return true;
}

} // namespace es
