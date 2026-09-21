#pragma once
// Estrazione parametri fisiologici da video.
//
// rPPG: algoritmo POS (Plane-Orthogonal-to-Skin),
//   Wang, den Brinker, Stuijk, de Haan, IEEE TBME 2017.
//   Scelto rispetto a CHROM e ICA perche' e' piu' robusto alle variazioni
//   di illuminazione e non richiede calibrazione del tono cutaneo.
//
// NESSUN VALORE E' SINTETIZZATO. Se il rapporto segnale-rumore e' sotto
// soglia, la misura viene marcata non valida e non viene restituito
// alcun numero plausibile al suo posto.

#include <vector>
#include <deque>
#include <cstddef>

namespace es {

struct Measurement {
    bool  valid = false;
    float value = 0.0f;     // unita' dipendente dal parametro
    float quality = 0.0f;   // 0..1, derivata dall'SNR spettrale
};

struct VitalsResult {
    Measurement heartRate;       // bpm
    Measurement respiration;     // atti/min
    Measurement tremor;          // Hz (picco dominante 2-14 Hz)
    Measurement rhythmIrregular; // value = coefficiente di variazione degli IBI
    float       bufferFill = 0;  // 0..1, quanto e' pieno il buffer di analisi
};

class VitalsAnalyzer {
public:
    void configure(float fps, float windowSeconds = 10.0f);
    void reset();

    // Medie R,G,B della ROI cutanea per il frame corrente.
    void pushSkinRgb(float r, float g, float b);

    // Segnale scalare di movimento (da RieszMagnifier::roiMotionSignal).
    void pushMotion(float m);

    VitalsResult analyze() const;

    bool configured() const { return fps_ > 0.0f; }

private:
    std::vector<float> posSignal() const;

    float fps_ = 0.0f;
    size_t cap_ = 0;
    std::deque<float> r_, g_, b_;
    std::deque<float> motion_;
};

} // namespace es
