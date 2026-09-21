#include "vitals.h"
#include "fft.h"
#include <cmath>
#include <algorithm>
#include <numeric>

namespace es {

// Soglie di SNR spettrale sotto le quali la misura e' rifiutata.
// Ricavate empiricamente: sotto 2.0 il picco non si distingue dal fondo.
// Con la metrica a rapporto di energia, il rumore a banda larga si attesta
// su valori attorno a 0.25-0.35 (frazione geometrica della finestra di
// segnale rispetto alla banda). Le soglie sono fissate ben sopra quel
// fondo, verificate su rumore pseudocasuale.
static constexpr float kMinSnrHr      = 1.20f;
static constexpr float kMinSnrResp    = 1.00f;
static constexpr float kMinSnrTremor  = 1.50f;

static float snrToQuality(float snr, float minSnr) {
    if (snr <= minSnr) return 0.0f;
    const float q = (snr - minSnr) / (minSnr * 4.0f);
    return std::min(1.0f, q);
}

void VitalsAnalyzer::configure(float fps, float windowSeconds) {
    fps_ = (fps > 1.0f) ? fps : 30.0f;
    cap_ = static_cast<size_t>(fps_ * windowSeconds);
    if (cap_ < 64) cap_ = 64;
    reset();
}

void VitalsAnalyzer::reset() {
    r_.clear(); g_.clear(); b_.clear(); motion_.clear();
}

void VitalsAnalyzer::pushSkinRgb(float r, float g, float b) {
    if (cap_ == 0) return;
    r_.push_back(r); g_.push_back(g); b_.push_back(b);
    while (r_.size() > cap_) { r_.pop_front(); g_.pop_front(); b_.pop_front(); }
}

void VitalsAnalyzer::pushMotion(float m) {
    if (cap_ == 0) return;
    if (std::isnan(m)) return;
    motion_.push_back(m);
    while (motion_.size() > cap_) motion_.pop_front();
}

// --- POS -------------------------------------------------------------------
// Finestra scorrevole di l = 1.6*fps campioni, proiezione su un piano
// ortogonale al tono cutaneo, combinazione adattiva, overlap-add.
std::vector<float> VitalsAnalyzer::posSignal() const {
    const size_t N = r_.size();
    const size_t l = static_cast<size_t>(1.6f * fps_);
    if (N < l + 2 || l < 8) return {};

    std::vector<float> H(N, 0.0f);

    for (size_t n = l; n <= N; ++n) {
        const size_t m = n - l;

        // media temporale della finestra
        float mr = 0, mg = 0, mb = 0;
        for (size_t i = m; i < n; ++i) { mr += r_[i]; mg += g_[i]; mb += b_[i]; }
        mr /= l; mg /= l; mb /= l;
        if (mr < 1e-6f || mg < 1e-6f || mb < 1e-6f) continue;

        // normalizzazione temporale
        std::vector<float> s1(l), s2(l);
        for (size_t i = 0; i < l; ++i) {
            const float cr = r_[m + i] / mr;
            const float cg = g_[m + i] / mg;
            const float cb = b_[m + i] / mb;
            s1[i] = cg - cb;                 // proiezione 1
            s2[i] = cg + cb - 2.0f * cr;     // proiezione 2
        }

        // deviazioni standard
        auto stddev = [&](const std::vector<float>& v) {
            const float mu = std::accumulate(v.begin(), v.end(), 0.0f) / v.size();
            float acc = 0.0f;
            for (float x : v) acc += (x - mu) * (x - mu);
            return std::sqrt(acc / v.size());
        };
        const float sd1 = stddev(s1);
        const float sd2 = stddev(s2);
        const float alpha = (sd2 > 1e-9f) ? (sd1 / sd2) : 0.0f;

        // combinazione e rimozione della media
        std::vector<float> h(l);
        float hm = 0.0f;
        for (size_t i = 0; i < l; ++i) { h[i] = s1[i] + alpha * s2[i]; hm += h[i]; }
        hm /= l;

        // overlap-add
        for (size_t i = 0; i < l; ++i) H[m + i] += (h[i] - hm);
    }
    return H;
}

// --- rilevazione picchi per gli intervalli inter-battito ---------------------
static std::vector<size_t> detectPeaks(const std::vector<float>& x, size_t minDist) {
    std::vector<size_t> peaks;
    if (x.size() < 3) return peaks;

    const float mu = std::accumulate(x.begin(), x.end(), 0.0f) / x.size();
    float sd = 0.0f;
    for (float v : x) sd += (v - mu) * (v - mu);
    sd = std::sqrt(sd / x.size());
    const float thr = mu + 0.3f * sd;

    for (size_t i = 1; i + 1 < x.size(); ++i) {
        if (x[i] > thr && x[i] >= x[i - 1] && x[i] > x[i + 1]) {
            if (!peaks.empty() && i - peaks.back() < minDist) {
                if (x[i] > x[peaks.back()]) peaks.back() = i;
            } else {
                peaks.push_back(i);
            }
        }
    }
    return peaks;
}

VitalsResult VitalsAnalyzer::analyze() const {
    VitalsResult out;
    if (fps_ <= 0.0f || cap_ == 0) return out;
    out.bufferFill = static_cast<float>(r_.size()) / static_cast<float>(cap_);

    // ---------------- Frequenza cardiaca ----------------
    const std::vector<float> pulse = posSignal();
    if (pulse.size() >= static_cast<size_t>(fps_ * 4.0f)) {
        const size_t nfft = nextPow2(pulse.size() * 2);
        const auto mag = amplitudeSpectrum(pulse, nfft);
        SpectralPeak pk;
        // 0.7-3.5 Hz = 42-210 bpm
        if (findPeak(mag, fps_, nfft, 0.7f, 3.5f, pk, 0.15f) && pk.snr >= kMinSnrHr) {
            out.heartRate.valid = true;
            out.heartRate.value = pk.frequency * 60.0f;
            out.heartRate.quality = snrToQuality(pk.snr, kMinSnrHr);
        }

        // ---------------- Irregolarita' del ritmo ----------------
        // Coefficiente di variazione degli intervalli tra picchi.
        // NON e' una diagnosi: segnala solo che il ritmo rilevato non e'
        // regolare, condizione che ha molte cause incluse artefatti.
        if (out.heartRate.valid && out.heartRate.quality > 0.4f) {
            const size_t minDist = static_cast<size_t>(fps_ * 0.35f);
            const auto peaks = detectPeaks(pulse, minDist);
            if (peaks.size() >= 6) {
                std::vector<float> ibi;
                ibi.reserve(peaks.size() - 1);
                for (size_t i = 1; i < peaks.size(); ++i) {
                    ibi.push_back(static_cast<float>(peaks[i] - peaks[i - 1]) / fps_);
                }
                const float mu = std::accumulate(ibi.begin(), ibi.end(), 0.0f) / ibi.size();
                if (mu > 1e-6f) {
                    float sd = 0.0f;
                    for (float v : ibi) sd += (v - mu) * (v - mu);
                    sd = std::sqrt(sd / ibi.size());
                    out.rhythmIrregular.valid = true;
                    out.rhythmIrregular.value = sd / mu;
                    out.rhythmIrregular.quality = out.heartRate.quality;
                }
            }
        }
    }

    // ---------------- Respirazione ----------------
    // Dal movimento amplificato nella ROI toracica o, in mancanza,
    // dalla modulazione in ampiezza del segnale rPPG.
    if (motion_.size() >= static_cast<size_t>(fps_ * 8.0f)) {
        std::vector<float> m(motion_.begin(), motion_.end());
        const size_t nfft = nextPow2(m.size() * 2);
        const auto mag = amplitudeSpectrum(m, nfft);
        SpectralPeak pk;
        // 0.1-0.6 Hz = 6-36 atti/min
        if (findPeak(mag, fps_, nfft, 0.1f, 0.6f, pk, 0.05f) && pk.snr >= kMinSnrResp) {
            out.respiration.valid = true;
            out.respiration.value = pk.frequency * 60.0f;
            out.respiration.quality = snrToQuality(pk.snr, kMinSnrResp);
        }

        // ---------------- Tremore ----------------
        // 2-14 Hz. Richiede fps >= 30 per Nyquist sulla banda posturale.
        if (fps_ >= 30.0f) {
            SpectralPeak tp;
            const float fHi = std::min(14.0f, fps_ * 0.45f);
            if (findPeak(mag, fps_, nfft, 2.0f, fHi, tp, 0.4f) && tp.snr >= kMinSnrTremor) {
                out.tremor.valid = true;
                out.tremor.value = tp.frequency;
                out.tremor.quality = snrToQuality(tp.snr, kMinSnrTremor);
            }
        }
    }

    return out;
}

} // namespace es
