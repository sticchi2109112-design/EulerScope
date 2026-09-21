package it.rs.eulerscope.core

import kotlin.math.abs
import kotlin.math.min

/**
 * Gioco da festa.
 *
 * Gli scostamenti misurati sono REALI: battito, respiro e movimento
 * vengono confrontati con una baseline acquisita dalla stessa persona.
 * Ciò che è finto è l'attribuzione di quegli scostamenti alla menzogna.
 *
 * Non esiste alcun correlato fisiologico specifico della menzogna: la
 * letteratura su poligrafo e rilevamento remoto è concorde su questo, e
 * il progetto europeo iBorderCtrl è il caso più documentato di fallimento
 * dell'approccio. L'etichetta "gioco" non è una formalità legale, è la
 * descrizione esatta di cosa fa questo schermo.
 */
class LieGame {

    data class Baseline(
        val hr: Float,
        val rr: Float,
        val samples: Int
    )

    data class Round(
        val hrDelta: Float,       // bpm rispetto alla baseline
        val rrDelta: Float,       // atti/min rispetto alla baseline
        val arousalScore: Float,  // 0..1, scostamento combinato normalizzato
        val quality: Float        // affidabilità delle misure usate
    )

    private val hrSamples = mutableListOf<Float>()
    private val rrSamples = mutableListOf<Float>()
    private var baseline: Baseline? = null

    val hasBaseline: Boolean get() = baseline != null
    val baselineProgress: Float get() = min(1f, hrSamples.size / MIN_BASELINE_SAMPLES.toFloat())

    fun feedBaseline(v: Vitals) {
        if (v.heartRate.valid && v.heartRate.quality > 0.3f) hrSamples.add(v.heartRate.value)
        if (v.respiration.valid && v.respiration.quality > 0.3f) rrSamples.add(v.respiration.value)
    }

    /** Ritorna true se la baseline è stata fissata. */
    fun sealBaseline(): Boolean {
        if (hrSamples.size < MIN_BASELINE_SAMPLES) return false
        baseline = Baseline(
            hr = hrSamples.average().toFloat(),
            rr = if (rrSamples.isNotEmpty()) rrSamples.average().toFloat() else Float.NaN,
            samples = hrSamples.size
        )
        return true
    }

    /** Valuta un turno. Null se non c'è baseline o le misure non sono valide. */
    fun evaluate(v: Vitals): Round? {
        val b = baseline ?: return null
        if (!v.heartRate.valid) return null

        val hrDelta = v.heartRate.value - b.hr
        val rrDelta = if (v.respiration.valid && !b.rr.isNaN())
            v.respiration.value - b.rr else 0f

        // normalizzazione su scostamenti tipici: 10 bpm e 4 atti/min
        val a = min(1f, abs(hrDelta) / 10f)
        val r = min(1f, abs(rrDelta) / 4f)
        val score = (0.7f * a + 0.3f * r).coerceIn(0f, 1f)

        val q = if (v.respiration.valid)
            (v.heartRate.quality + v.respiration.quality) / 2f
        else v.heartRate.quality

        return Round(hrDelta, rrDelta, score, q)
    }

    fun reset() {
        hrSamples.clear(); rrSamples.clear(); baseline = null
    }

    companion object {
        const val MIN_BASELINE_SAMPLES = 20
        const val DISCLAIMER =
            "Questo è un gioco. Gli scostamenti di battito e respiro mostrati " +
            "sono misurati davvero, ma non indicano se una persona stia mentendo: " +
            "nessun parametro fisiologico lo fa. Usalo per divertirti, non per " +
            "giudicare qualcuno."
    }
}
