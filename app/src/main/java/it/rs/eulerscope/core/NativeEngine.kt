package it.rs.eulerscope.core

import java.nio.ByteBuffer

/**
 * Ponte verso il motore nativo (libeulerscope.so).
 *
 * I nomi dei metodi devono restare allineati alle funzioni
 * Java_it_rs_eulerscope_core_NativeEngine_* in jni_bridge.cpp.
 */
object NativeEngine {

    init { System.loadLibrary("eulerscope") }

    external fun nativeInit(width: Int, height: Int, fps: Float, levels: Int)
    external fun nativeSetBand(fLow: Float, fHigh: Float, alpha: Float)
    external fun nativeSetRoi(x0: Int, y0: Int, x1: Int, y1: Int)
    external fun nativeReset()
    external fun nativeRelease()

    external fun nativeProcessFrame(
        yBuf: ByteBuffer, yStride: Int,
        uBuf: ByteBuffer, uStride: Int, uPixStride: Int,
        vBuf: ByteBuffer, vStride: Int, vPixStride: Int,
        outArgb: IntArray,
        magnifyEnabled: Boolean
    ): Boolean

    /** Vedi il commento su nativeGetVitals in jni_bridge.cpp per il layout. */
    external fun nativeGetVitals(): FloatArray

    external fun nativeGetMotionSpectrum(): FloatArray
}

/** Una misura che può essere assente. Assente non significa zero. */
data class Measure(val valid: Boolean, val value: Float, val quality: Float) {
    companion object {
        val ABSENT = Measure(false, 0f, 0f)
    }
}

data class Vitals(
    val heartRate: Measure = Measure.ABSENT,
    val respiration: Measure = Measure.ABSENT,
    val tremor: Measure = Measure.ABSENT,
    val rhythmIrregular: Measure = Measure.ABSENT,
    val bufferFill: Float = 0f
) {
    companion object {
        fun from(a: FloatArray): Vitals {
            if (a.size < 13) return Vitals()
            return Vitals(
                heartRate = Measure(a[0] == 1f, a[1], a[2]),
                respiration = Measure(a[3] == 1f, a[4], a[5]),
                tremor = Measure(a[6] == 1f, a[7], a[8]),
                rhythmIrregular = Measure(a[9] == 1f, a[10], a[11]),
                bufferFill = a[12]
            )
        }
    }

    /**
     * Indicatore aggregato di STABILITÀ, non di salute.
     * Esprime solo quanto le misure disponibili sono state coerenti,
     * e vale null finché non ci sono almeno due misure affidabili.
     * Deliberatamente non fonde i parametri in un punteggio clinico.
     */
    fun stability(): Float? {
        val q = listOf(heartRate, respiration, tremor)
            .filter { it.valid }
            .map { it.quality }
        if (q.size < 2) return null
        return q.average().toFloat()
    }
}
