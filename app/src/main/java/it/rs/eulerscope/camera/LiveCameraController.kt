package it.rs.eulerscope.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Acquisizione live in YUV_420_888.
 *
 * Tutti i controlli automatici che introdurrebbero oscillazioni
 * indistinguibili da vibrazioni reali vengono disattivati:
 *  - AE off con ISO ed esposizione fissi
 *  - AWB off
 *  - AF off a distanza fissa
 *  - OIS off (compensa proprio i micromovimenti da misurare)
 *  - EIS off
 *
 * L'fps non viene assunto dal range richiesto: si ricava dai timestamp
 * reali del sensore, perché l'errore sull'fps si propaga direttamente
 * sull'asse delle frequenze.
 */
class LiveCameraController(
    private val context: Context,
    private val onFrame: (Image, Double) -> Unit,   // image, fps stimato
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "LiveCamera"
        private const val FPS_WINDOW = 30
    }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val running = AtomicBoolean(false)

    private val timestamps = ArrayDeque<Long>()
    @Volatile var measuredFps: Double = 0.0; private set

    var analysisSize: Size = Size(640, 480); private set

    @SuppressLint("MissingPermission")
    fun start(report: CameraReport, requestedSize: Size) {
        if (running.getAndSet(true)) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            running.set(false)
            onError("Permesso fotocamera non concesso.")
            return
        }

        analysisSize = requestedSize
        thread = HandlerThread("EulerScopeCam").also { it.start() }
        handler = Handler(thread!!.looper)

        reader = ImageReader.newInstance(
            analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, 4
        ).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    updateFps(img.timestamp)
                    onFrame(img, measuredFps)
                } catch (t: Throwable) {
                    Log.e(TAG, "errore elaborazione frame", t)
                } finally {
                    img.close()
                }
            }, handler)
        }

        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            mgr.openCamera(report.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    createSession(camera, report)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); device = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); device = null
                    running.set(false)
                    onError("Errore apertura camera (codice $error).")
                }
            }, handler)
        } catch (e: CameraAccessException) {
            running.set(false)
            onError("Accesso negato alla camera: ${e.message}")
        }
    }

    private fun createSession(camera: CameraDevice, report: CameraReport) {
        val surface = reader?.surface ?: return
        @Suppress("DEPRECATION")
        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        s.setRepeatingRequest(buildRequest(camera, surface, report), null, handler)
                    } catch (e: CameraAccessException) {
                        onError("Impossibile avviare lo stream: ${e.message}")
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    onError("Configurazione della sessione fallita.")
                }
            },
            handler
        )
    }

    private fun buildRequest(
        camera: CameraDevice, surface: android.view.Surface, report: CameraReport
    ): CaptureRequest {
        val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(surface)

        // fps massimo disponibile
        report.maxYuvFpsRange?.let {
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
        }

        // --- esposizione manuale ---
        if (report.supportsManualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            val fps = report.maxYuvFpsRange?.upper ?: 30
            // esposizione pari a metà del periodo di frame: compromesso tra
            // luce raccolta e motion blur, che attenuerebbe il segnale di fase
            val targetNs = (1_000_000_000L / fps) / 2
            report.exposureTimeRangeNs?.let { r ->
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetNs.coerceIn(r.lower, r.upper))
            }
            report.isoRange?.let { r ->
                // ISO moderato: alzarlo aumenta il rumore del sensore, che è
                // il fondo contro cui si misura il segnale
                b.set(CaptureRequest.SENSOR_SENSITIVITY, (r.lower * 4).coerceIn(r.lower, r.upper))
            }
        } else {
            // senza sensore manuale si blocca almeno l'esposizione automatica
            b.set(CaptureRequest.CONTROL_AE_LOCK, true)
        }

        // --- bilanciamento del bianco ---
        if (report.supportsManualPostProcessing) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        } else {
            b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }

        // --- messa a fuoco fissa ---
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        report.minFocusDistance?.let {
            if (it > 0f) b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f) // infinito
        }

        // --- stabilizzazioni disattivate ---
        if (report.canDisableOis) {
            b.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }
        b.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )

        // niente riduzione rumore o nitidezza: alterano il contenuto di fase
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)

        return b.build()
    }

    /** fps reale dai timestamp del sensore, su finestra scorrevole. */
    private fun updateFps(timestampNs: Long) {
        synchronized(timestamps) {
            timestamps.addLast(timestampNs)
            while (timestamps.size > FPS_WINDOW) timestamps.removeFirst()
            if (timestamps.size >= 5) {
                val span = timestamps.last() - timestamps.first()
                if (span > 0) {
                    measuredFps = (timestamps.size - 1) * 1_000_000_000.0 / span
                }
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null; device = null; reader = null
        thread?.quitSafely(); thread = null; handler = null
        synchronized(timestamps) { timestamps.clear() }
        measuredFps = 0.0
    }
}
