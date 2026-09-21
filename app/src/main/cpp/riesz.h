#pragma once
// Magnificazione euleriana basata sulla fase, con piramide di Riesz.
// Riferimento: Wadhwa, Rubinstein, Durand, Freeman,
// "Riesz Pyramids for Fast Phase-Based Video Magnification", ICCP 2014.
//
// Rispetto all'EVM lineare (Wu 2012) la versione a fase è molto più
// robusta al rumore; rispetto alla piramide complessa orientabile
// (Wadhwa 2013) è circa 4x più veloce, al prezzo di una gestione
// peggiore dei movimenti di grande ampiezza.

#include <vector>
#include <cstdint>
#include <cstddef>

namespace es {

struct Plane {
    int w = 0, h = 0;
    std::vector<float> d;

    void alloc(int width, int height) {
        w = width; h = height;
        d.assign(static_cast<size_t>(w) * static_cast<size_t>(h), 0.0f);
    }
    inline float& at(int x, int y) { return d[static_cast<size_t>(y) * w + x]; }
    inline const float& at(int x, int y) const { return d[static_cast<size_t>(y) * w + x]; }
    inline bool empty() const { return d.empty(); }
};

// Stato temporale per un singolo livello della piramide.
struct LevelState {
    Plane prevLap, prevR1, prevR2;      // frame precedente
    Plane phaseCos, phaseSin;           // fase quaternionica accumulata
    Plane lp1Cos, lp1Sin;               // lowpass IIR, taglio alto
    Plane lp2Cos, lp2Sin;               // lowpass IIR, taglio basso
    bool initialised = false;
};

struct BandParams {
    float fLow  = 0.8f;   // Hz
    float fHigh = 3.0f;   // Hz
    float alpha = 20.0f;  // guadagno di fase
    int   levels = 4;     // livelli di piramide
    int   maxLevelProcessed = 3; // livelli effettivamente amplificati
};

class RieszMagnifier {
public:
    RieszMagnifier() = default;

    // fps: frequenza di campionamento reale, ricavata dai timestamp del sensore.
    void configure(int width, int height, float fps, const BandParams& p);
    void setBand(float fLow, float fHigh, float alpha);
    void reset();

    // in: luma float [0..1], dimensione width*height.
    // out: luma float magnificato, stessa dimensione. Puo' coincidere con in.
    void process(const float* in, float* out);

    // Segnale scalare di movimento nella ROI (media pesata della fase
    // filtrata, pesata sull'ampiezza). Aggiornato a ogni process().
    // Ritorna NaN se la ROI non e' valida o non ci sono ancora dati.
    float roiMotionSignal(int x0, int y0, int x1, int y1) const;

    bool ready() const { return configured_; }
    int  width()  const { return w_; }
    int  height() const { return h_; }

private:
    void buildPyramid(const float* in);
    void collapse(float* out);

    int w_ = 0, h_ = 0;
    float fps_ = 30.0f;
    BandParams params_;
    bool configured_ = false;

    // coefficienti IIR butterworth 1o ordine
    float b0hi_ = 0, b1hi_ = 0, a1hi_ = 0;
    float b0lo_ = 0, b1lo_ = 0, a1lo_ = 0;

    std::vector<Plane> gauss_;   // livelli gaussiani
    std::vector<Plane> lap_;     // livelli laplaciani
    std::vector<Plane> outLap_;  // laplaciani dopo shift di fase
    std::vector<LevelState> st_;

    // fase filtrata dell'ultimo frame, livello 1 (usato per la ROI)
    Plane roiPhase_, roiAmp_;
};

// ---- utilità di piramide, esposte per i test ----
void downsample(const Plane& src, Plane& dst);
void upsample(const Plane& src, Plane& dst, int w, int h);
void gaussianBlur5(const Plane& src, Plane& dst);

} // namespace es
