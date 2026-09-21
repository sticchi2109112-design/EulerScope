package it.rs.eulerscope.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer

/**
 * Percorso ad alta velocità.
 *
 * Una CameraConstrainedHighSpeedCaptureSession accetta al massimo due
 * superfici, e solo di tipo SurfaceView o MediaRecorder/MediaCodec.
 * Un ImageReader non è ammesso. Di conseguenza l'analisi a 120/240 fps
 * non può essere fatta dal vivo: si registra su file e si elabora dopo.
 */
class HighSpeedRecorder(
    private val context: Context,
    private val onError: (String) -> Unit
) {
    companion object { private const val TAG = "HighSpeedRec" }

    private var device: CameraDevice? = null
    private var session: CameraConstrainedHighSpeedCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var outputFile: File? = null

    @SuppressLint("MissingPermission")
    fun start(report: CameraReport, option: HighSpeedOption, onStarted: (File) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError("Permesso fotocamera non concesso."); return
        }
        if (!report.supportsHighSpeed) {
            onError("Questo dispositivo non espone sessioni ad alta velocità via Camera2.")
            return
        }

        thread = HandlerThread("EulerScopeHS").also { it.start() }
        handler = Handler(thread!!.looper)

        val file = File(context.cacheDir, "hs_${System.currentTimeMillis()}.mp4")
        outputFile = file

        recorder = buildRecorder(file, option).also { it.prepare() }

        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        mgr.openCamera(report.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                configure(camera, option, file, onStarted)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); device = null }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close(); device = null
                onError("Errore camera ad alta velocità (codice $error).")
            }
        }, handler)
    }

    private fun buildRecorder(file: File, option: HighSpeedOption): MediaRecorder {
        @Suppress("DEPRECATION")
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context)
                else MediaRecorder()
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(file.absolutePath)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(option.size.width, option.size.height)
        r.setVideoFrameRate(option.fpsRange.upper)
        // bitrate generoso: la compressione introduce artefatti di blocco che
        // il filtro di fase interpreta come movimento
        r.setVideoEncodingBitRate(
            (option.size.width * option.size.height * option.fpsRange.upper * 0.25).toInt()
        )
        // il file viene salvato con il frame rate di cattura, non rallentato:
        // serve la scala temporale reale per l'analisi in frequenza
        r.setCaptureRate(option.fpsRange.upper.toDouble())
        return r
    }

    private fun configure(
        camera: CameraDevice, option: HighSpeedOption, file: File, onStarted: (File) -> Unit
    ) {
        val surface = recorder?.surface ?: return
        @Suppress("DEPRECATION")
        camera.createConstrainedHighSpeedCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    val hs = s as CameraConstrainedHighSpeedCaptureSession
                    session = hs
                    val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, option.fpsRange)
                        set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                        set(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                        )
                        set(
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
                        )
                    }.build()
                    hs.setRepeatingBurst(hs.createHighSpeedRequestList(b), null, handler)
                    recorder?.start()
                    onStarted(file)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    onError("Sessione ad alta velocità rifiutata dal dispositivo.")
                }
            },
            handler
        )
    }

    /** Ferma la registrazione e restituisce il file, o null in caso di errore. */
    fun stop(): File? {
        runCatching { session?.stopRepeating() }
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        recorder = null; session = null; device = null
        thread?.quitSafely(); thread = null; handler = null
        return outputFile?.takeIf { it.exists() && it.length() > 0 }
    }
}

/**
 * Decodifica un file video e invia ogni frame al motore nativo.
 * Usato sia per i video registrati ad alta velocità, sia per file
 * importati dalla galleria.
 */
object VideoFileProcessor {

    data class Info(val width: Int, val height: Int, val fps: Float, val frameCount: Int)

    fun probe(path: String): Info? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(path)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    val w = f.getInteger(MediaFormat.KEY_WIDTH)
                    val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    val fps = if (f.containsKey(MediaFormat.KEY_FRAME_RATE))
                        f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() else 30f
                    val dur = if (f.containsKey(MediaFormat.KEY_DURATION))
                        f.getLong(MediaFormat.KEY_DURATION) else 0L
                    val count = ((dur / 1_000_000.0) * fps).toInt()
                    return Info(w, h, fps, count)
                }
            }
            null
        } catch (t: Throwable) {
            Log.e("VideoFileProcessor", "probe fallita", t); null
        } finally { ex.release() }
    }

    /**
     * Decodifica in YUV_420_888 tramite ImageReader e chiama [onFrame]
     * per ogni frame, in ordine di presentazione.
     * [onFrame] riceve l'Image e l'indice; deve consumarla in modo sincrono.
     */
    fun decode(
        path: String,
        maxWidth: Int,
        onFrame: (Image, Int) -> Unit,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        val info = probe(path) ?: return false
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var reader: ImageReader? = null
        val thread = HandlerThread("VideoDecode").apply { start() }

        return try {
            ex.setDataSource(path)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    track = i; format = f; break
                }
            }
            if (track < 0 || format == null) return false
            ex.selectTrack(track)

            reader = ImageReader.newInstance(
                info.width, info.height, ImageFormat.YUV_420_888, 4
            )

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, reader.surface, null, 0)
            codec.start()

            val bufInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var frameIndex = 0

            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf: ByteBuffer = codec.getInputBuffer(inIdx)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufInfo, 10_000)
                if (outIdx >= 0) {
                    val render = bufInfo.size > 0
                    codec.releaseOutputBuffer(outIdx, render)
                    if (render) {
                        val img = reader.acquireNextImage()
                        if (img != null) {
                            try { onFrame(img, frameIndex) } finally { img.close() }
                            frameIndex++
                            if (info.frameCount > 0) {
                                onProgress(frameIndex.toFloat() / info.frameCount)
                            }
                        }
                    }
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            true
        } catch (t: Throwable) {
            Log.e("VideoFileProcessor", "decodifica fallita", t); false
        } finally {
            runCatching { codec?.stop(); codec?.release() }
            runCatching { reader?.close() }
            ex.release()
            thread.quitSafely()
        }
    }
}
