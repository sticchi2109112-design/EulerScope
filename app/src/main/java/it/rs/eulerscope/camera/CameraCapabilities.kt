package it.rs.eulerscope.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Size

/**
 * Interroga il dispositivo invece di assumerne le capacità.
 *
 * Motivo: molti produttori, Oppo incluso, espongono le modalità slow-motion
 * nella propria app camera tramite API private. La presenza di "1080p 240fps"
 * nella scheda tecnica non garantisce che Camera2 la offra. Qui si legge
 * cosa il dispositivo dichiara davvero.
 */
data class HighSpeedOption(val size: Size, val fpsRange: Range<Int>)

data class CameraReport(
    val cameraId: String,
    val facing: String,
    val hardwareLevel: String,
    val supportsManualSensor: Boolean,
    val supportsManualPostProcessing: Boolean,
    val supportsHighSpeed: Boolean,
    val highSpeedOptions: List<HighSpeedOption>,
    val yuvSizes: List<Size>,
    val maxYuvFpsRange: Range<Int>?,
    val opticalStabilizationModes: List<Int>,
    val canDisableOis: Boolean,
    val videoStabilizationModes: List<Int>,
    val isoRange: Range<Int>?,
    val exposureTimeRangeNs: Range<Long>?,
    val minFocusDistance: Float?
) {
    /** Riassunto leggibile, mostrato nella schermata diagnostica. */
    fun summary(): String = buildString {
        appendLine("Camera $cameraId ($facing)")
        appendLine("Livello hardware: $hardwareLevel")
        appendLine("Sensore manuale: ${yesNo(supportsManualSensor)}")
        appendLine("Post-processing manuale (AWB off): ${yesNo(supportsManualPostProcessing)}")
        appendLine("OIS disattivabile: ${yesNo(canDisableOis)}")
        appendLine()
        if (supportsHighSpeed && highSpeedOptions.isNotEmpty()) {
            appendLine("Alta velocità disponibile via Camera2:")
            highSpeedOptions.forEach {
                appendLine("  ${it.size.width}x${it.size.height} @ ${it.fpsRange.upper} fps")
            }
            appendLine("  (solo registrazione su file: le sessioni high-speed")
            appendLine("   non accettano ImageReader come destinazione)")
        } else {
            appendLine("Alta velocità NON esposta via Camera2.")
            appendLine("Le modalità slow-motion dell'app di sistema usano")
            appendLine("API private e non sono raggiungibili da qui.")
        }
        appendLine()
        appendLine("Live (YUV_420_888): max ${maxYuvFpsRange?.upper ?: "?"} fps")
        isoRange?.let { appendLine("ISO: ${it.lower}-${it.upper}") }
        exposureTimeRangeNs?.let {
            appendLine("Esposizione: ${it.lower / 1000}µs - ${it.upper / 1_000_000}ms")
        }
    }

    private fun yesNo(b: Boolean) = if (b) "sì" else "no"
}

object CameraCapabilities {

    fun probeAll(context: Context): List<CameraReport> {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return mgr.cameraIdList.mapNotNull { id ->
            runCatching { probe(mgr, id) }.getOrNull()
        }
    }

    /** La camera posteriore è quella da usare: sensore migliore e OIS controllabile. */
    fun preferredBack(context: Context): CameraReport? =
        probeAll(context).firstOrNull { it.facing == "posteriore" }

    private fun probe(mgr: CameraManager, id: String): CameraReport {
        val c = mgr.getCameraCharacteristics(id)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val highSpeedSupported = caps.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO
        )

        val hsOptions = mutableListOf<HighSpeedOption>()
        if (highSpeedSupported && map != null) {
            map.highSpeedVideoSizes?.forEach { size ->
                map.getHighSpeedVideoFpsRangesFor(size)?.forEach { range ->
                    hsOptions.add(HighSpeedOption(size, range))
                }
            }
        }

        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.toList() ?: emptyList()
        val vis = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.toList() ?: emptyList()

        return CameraReport(
            cameraId = id,
            facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "posteriore"
                CameraCharacteristics.LENS_FACING_FRONT -> "frontale"
                else -> "esterna"
            },
            hardwareLevel = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                else -> "sconosciuto"
            },
            supportsManualSensor = caps.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
            ),
            supportsManualPostProcessing = caps.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
            ),
            supportsHighSpeed = highSpeedSupported,
            highSpeedOptions = hsOptions.sortedByDescending { it.fpsRange.upper },
            yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList(),
            maxYuvFpsRange = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.maxByOrNull { it.upper },
            opticalStabilizationModes = ois,
            canDisableOis = ois.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF),
            videoStabilizationModes = vis,
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureTimeRangeNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        )
    }

    /**
     * Sceglie la risoluzione di analisi. La magnificazione euleriana non
     * beneficia di risoluzioni alte: il costo cresce quadraticamente mentre
     * il segnale di fase no. 640x480 o inferiore è il compromesso corretto.
     */
    fun chooseAnalysisSize(report: CameraReport, target: Int = 640): Size {
        if (report.yuvSizes.isEmpty()) return Size(640, 480)
        return report.yuvSizes
            .filter { it.width <= target && it.width >= 240 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: report.yuvSizes.minByOrNull { it.width.toLong() * it.height }!!
    }
}
