package it.rs.eulerscope.ui

import android.app.Application
import android.graphics.Bitmap
import android.media.Image
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.rs.eulerscope.camera.CameraCapabilities
import it.rs.eulerscope.camera.CameraReport
import it.rs.eulerscope.camera.LiveCameraController
import it.rs.eulerscope.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class EulerScopeViewModel(app: Application) : AndroidViewModel(app) {

    // ---- stato osservato dalla UI ----
    var report by mutableStateOf<CameraReport?>(null); private set
    var mode by mutableStateOf(Mode.VITALS); private set
    var band by mutableStateOf(BandSetting(Mode.VITALS.fLow, Mode.VITALS.fHigh, Mode.VITALS.alpha)); private set
    var magnifyEnabled by mutableStateOf(true); private set
    var frame by mutableStateOf<Bitmap?>(null); private set
    var vitals by mutableStateOf(Vitals()); private set
    var spectrum by mutableStateOf<FloatArray>(FloatArray(0)); private set
    var spectrumBinHz by mutableStateOf(0f); private set
    var fps by mutableStateOf(0f); private set
    var error by mutableStateOf<String?>(null); private set
    var streaming by mutableStateOf(false); private set

    /** ROI normalizzata 0..1 sul frame di analisi. */
    var roi by mutableStateOf(RectF01(0.32f, 0.22f, 0.68f, 0.58f)); private set

    val game = LieGame()
    var gameRound by mutableStateOf<LieGame.Round?>(null); private set
    var gameBaselineReady by mutableStateOf(false); private set
    private var collectingBaseline = false

    private var camera: LiveCameraController? = null
    private var argb: IntArray = IntArray(0)
    private var bmp: Bitmap? = null
    private var analysisSize = Size(640, 480)
    private var engineReady = AtomicBoolean(false)
    private var frameCounter = 0
    private var lastConfiguredFps = 0f

    init {
        report = CameraCapabilities.preferredBack(app)
        report?.let { analysisSize = CameraCapabilities.chooseAnalysisSize(it) }
    }

    // ---- controllo ----

    fun selectMode(m: Mode) {
        mode = m
        band = BandSetting(m.fLow, m.fHigh, m.alpha)
        applyBand()
        NativeEngine.nativeReset()
        vitals = Vitals()
        gameRound = null
    }

    fun updateBand(newBand: BandSetting) {
        band = if (fps > 1f) newBand.clampedTo(fps) else newBand
        applyBand()
    }

    fun toggleMagnify() {
        magnifyEnabled = !magnifyEnabled
    }

    fun setRoi(r: RectF01) {
        roi = r
        if (engineReady.get()) {
            NativeEngine.nativeSetRoi(
                (r.left * analysisSize.width).toInt(),
                (r.top * analysisSize.height).toInt(),
                (r.right * analysisSize.width).toInt(),
                (r.bottom * analysisSize.height).toInt()
            )
        }
    }

    private fun applyBand() {
        if (engineReady.get()) NativeEngine.nativeSetBand(band.fLow, band.fHigh, band.alpha)
    }

    fun start() {
        val rep = report ?: run { error = "Nessuna fotocamera posteriore trovata."; return }
        if (streaming) return
        error = null
        streaming = true

        camera = LiveCameraController(
            getApplication(),
            onFrame = ::handleFrame,
            onError = { e -> error = e; streaming = false }
        ).also { it.start(rep, analysisSize) }
    }

    fun stop() {
        camera?.stop(); camera = null
        streaming = false
        if (engineReady.getAndSet(false)) NativeEngine.nativeRelease()
        lastConfiguredFps = 0f
    }

    // ---- gioco ----

    fun startBaseline() {
        game.reset()
        collectingBaseline = true
        gameBaselineReady = false
        gameRound = null
    }

    fun sealBaseline() {
        collectingBaseline = false
        gameBaselineReady = game.sealBaseline()
        if (!gameBaselineReady) {
            error = "Baseline non acquisita: segnale insufficiente. Migliora luce e stabilità."
        }
    }

    fun resetGame() {
        game.reset(); gameBaselineReady = false; gameRound = null; collectingBaseline = false
    }

    // ---- elaborazione frame ----

    private fun handleFrame(image: Image, measuredFps: Double) {
        val f = measuredFps.toFloat()
        if (f < 5f) return   // ancora in stima

        // (ri)configurazione quando l'fps reale cambia in modo significativo
        if (!engineReady.get() || kotlin.math.abs(f - lastConfiguredFps) > 1.5f) {
            NativeEngine.nativeInit(analysisSize.width, analysisSize.height, f, 4)
            NativeEngine.nativeSetBand(band.fLow, band.fHigh, band.alpha)
            setRoiInternal()
            argb = IntArray(analysisSize.width * analysisSize.height)
            bmp = Bitmap.createBitmap(
                analysisSize.width, analysisSize.height, Bitmap.Config.ARGB_8888
            )
            engineReady.set(true)
            lastConfiguredFps = f
        }

        val planes = image.planes
        if (planes.size < 3) return

        val ok = NativeEngine.nativeProcessFrame(
            planes[0].buffer, planes[0].rowStride,
            planes[1].buffer, planes[1].rowStride, planes[1].pixelStride,
            planes[2].buffer, planes[2].rowStride, planes[2].pixelStride,
            argb, magnifyEnabled
        )
        if (!ok) return

        val b = bmp ?: return
        b.setPixels(argb, 0, analysisSize.width, 0, 0, analysisSize.width, analysisSize.height)

        frameCounter++
        // la UI non ha bisogno di aggiornarsi a ogni frame
        val pushUi = frameCounter % 2 == 0
        val pushMetrics = frameCounter % 15 == 0

        viewModelScope.launch(Dispatchers.Main) {
            fps = (f * 10).roundToInt() / 10f
            if (pushUi) frame = b
            if (pushMetrics) {
                val v = Vitals.from(NativeEngine.nativeGetVitals())
                vitals = v

                if (mode == Mode.LIE_GAME) {
                    if (collectingBaseline) game.feedBaseline(v)
                    else if (gameBaselineReady) gameRound = game.evaluate(v)
                }

                if (mode == Mode.STRUCTURES) {
                    val s = withContext(Dispatchers.Default) {
                        NativeEngine.nativeGetMotionSpectrum()
                    }
                    if (s.size > 1) {
                        spectrum = s.copyOfRange(0, s.size - 1)
                        spectrumBinHz = s[s.size - 1]
                    }
                }
            }
        }
    }

    private fun setRoiInternal() {
        NativeEngine.nativeSetRoi(
            (roi.left * analysisSize.width).toInt(),
            (roi.top * analysisSize.height).toInt(),
            (roi.right * analysisSize.width).toInt(),
            (roi.bottom * analysisSize.height).toInt()
        )
    }

    override fun onCleared() {
        stop(); super.onCleared()
    }
}

data class RectF01(val left: Float, val top: Float, val right: Float, val bottom: Float)
