package it.rs.eulerscope

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import it.rs.eulerscope.core.BandSetting
import it.rs.eulerscope.core.LieGame
import it.rs.eulerscope.core.Measure
import it.rs.eulerscope.core.Mode
import it.rs.eulerscope.ui.EulerScopeViewModel
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { AppRoot() }
            }
        }
    }
}

@Composable
private fun AppRoot(vm: EulerScopeViewModel = viewModel()) {
    val ctx = LocalContextSafe()
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    var showDiagnostics by remember { mutableStateOf(false) }

    if (!granted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Serve il permesso fotocamera.", Modifier.padding(24.dp))
        }
        return
    }

    DisposableEffect(Unit) {
        vm.start()
        onDispose { vm.stop() }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("EulerScope", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            Text(
                if (vm.fps > 0) "${vm.fps} fps" else "…",
                fontFamily = FontFamily.Monospace, fontSize = 13.sp
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { showDiagnostics = !showDiagnostics }) { Text("Info") }
        }

        vm.error?.let {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(it, Modifier.padding(10.dp), fontSize = 13.sp)
            }
        }

        if (showDiagnostics) {
            Card(Modifier.fillMaxWidth().weight(1f)) {
                Text(
                    vm.report?.summary() ?: "Nessuna fotocamera rilevata.",
                    Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp
                )
            }
            return@Column
        }

        ModeBar(vm.mode) { vm.selectMode(it) }

        Box(
            Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            vm.frame?.let { bmp ->
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Frame elaborato",
                    modifier = Modifier.fillMaxSize()
                )
                RoiOverlay(vm)
            } ?: Text("Avvio fotocamera…")
        }

        Text(vm.mode.hint, fontSize = 11.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))

        when (vm.mode) {
            Mode.VITALS -> VitalsPanel(vm)
            Mode.MAGNIFY, Mode.THERMAL_FLOW -> BandPanel(vm)
            Mode.STRUCTURES -> SpectrumPanel(vm)
            Mode.LIE_GAME -> GamePanel(vm)
        }
    }
}

@Composable
private fun LocalContextSafe() = androidx.compose.ui.platform.LocalContext.current

@Composable
private fun ModeBar(current: Mode, onSelect: (Mode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScrollCompat(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Mode.entries.forEach { m ->
            FilterChip(
                selected = m == current,
                onClick = { onSelect(m) },
                label = { Text(m.label, fontSize = 12.sp) }
            )
        }
    }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier =
    this.then(androidx.compose.foundation.horizontalScroll(rememberScrollState()))

@Composable
private fun RoiOverlay(vm: EulerScopeViewModel) {
    Canvas(Modifier.fillMaxSize()) {
        val r = vm.roi
        drawRect(
            color = Color(0xFF4CD9C0),
            topLeft = Offset(r.left * size.width, r.top * size.height),
            size = androidx.compose.ui.geometry.Size(
                (r.right - r.left) * size.width,
                (r.bottom - r.top) * size.height
            ),
            style = Stroke(width = 2f)
        )
    }
}

// ---------------------------------------------------------------- Vitali

@Composable
private fun VitalsPanel(vm: EulerScopeViewModel) {
    val v = vm.vitals
    Column {
        LinearProgressIndicator(
            progress = { v.bufferFill.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Buffer di analisi ${(v.bufferFill * 100).roundToInt()}%",
            fontSize = 10.sp, color = Color.Gray
        )
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MeasureCard("Battito", v.heartRate, "bpm", Modifier.weight(1f))
            MeasureCard("Respiro", v.respiration, "/min", Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MeasureCard("Tremore", v.tremor, "Hz", Modifier.weight(1f))
            IrregularityCard(v.rhythmIrregular, Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))
        val s = v.stability()
        Text(
            if (s == null) "Stabilità: dati insufficienti"
            else "Stabilità delle misure: ${(s * 100).roundToInt()}%",
            fontSize = 12.sp
        )
        Text(
            "Non per uso diagnostico. Nessun valore mostrato quando il segnale " +
            "non è affidabile: l'assenza di un numero significa assenza di dato.",
            fontSize = 10.sp, color = Color.Gray
        )
    }
}

@Composable
private fun MeasureCard(title: String, m: Measure, unit: String, mod: Modifier = Modifier) {
    Card(mod) {
        Column(Modifier.padding(10.dp)) {
            Text(title, fontSize = 11.sp, color = Color.Gray)
            if (m.valid) {
                Text(
                    "${"%.1f".format(m.value)} $unit",
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold
                )
                QualityBar(m.quality)
            } else {
                Text("—", fontSize = 20.sp, color = Color.Gray)
                Text("segnale insufficiente", fontSize = 9.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun IrregularityCard(m: Measure, mod: Modifier = Modifier) {
    Card(mod) {
        Column(Modifier.padding(10.dp)) {
            Text("Regolarità ritmo", fontSize = 11.sp, color = Color.Gray)
            if (!m.valid) {
                Text("—", fontSize = 20.sp, color = Color.Gray)
                Text("segnale insufficiente", fontSize = 9.sp, color = Color.Gray)
            } else {
                // Coefficiente di variazione degli intervalli tra battiti.
                // Soglia indicativa, NON diagnostica.
                val irregular = m.value > 0.15f
                Text(
                    if (irregular) "irregolare" else "regolare",
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                )
                Text("CV ${"%.2f".format(m.value)}", fontSize = 10.sp, color = Color.Gray)
                QualityBar(m.quality)
            }
        }
    }
}

@Composable
private fun QualityBar(q: Float) {
    LinearProgressIndicator(
        progress = { q.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 4.dp)
    )
}

// ---------------------------------------------------------------- Banda

@Composable
private fun BandPanel(vm: EulerScopeViewModel) {
    val b = vm.band
    val nyq = if (vm.fps > 1f) vm.fps / 2f else 15f
    Column {
        SliderRow("Freq. min", b.fLow, 0.05f, nyq * 0.85f, "Hz") {
            vm.updateBand(BandSetting(it, b.fHigh, b.alpha))
        }
        SliderRow("Freq. max", b.fHigh, 0.1f, nyq * 0.9f, "Hz") {
            vm.updateBand(BandSetting(b.fLow, it, b.alpha))
        }
        SliderRow("Guadagno", b.alpha, 1f, 80f, "x") {
            vm.updateBand(BandSetting(b.fLow, b.fHigh, it))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = vm.magnifyEnabled, onCheckedChange = { vm.toggleMagnify() })
            Spacer(Modifier.width(8.dp))
            Text("Magnificazione attiva", fontSize = 12.sp)
        }
        Text(
            "A guadagni alti compaiono artefatti anche senza movimento reale: " +
            "confronta sempre con magnificazione disattivata.",
            fontSize = 10.sp, color = Color.Gray
        )
    }
}

@Composable
private fun SliderRow(
    label: String, value: Float, min: Float, max: Float, unit: String,
    onChange: (Float) -> Unit
) {
    Column {
        Text("$label  ${"%.2f".format(value)} $unit", fontSize = 11.sp)
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onChange,
            valueRange = min..max
        )
    }
}

// ---------------------------------------------------------------- Spettro

@Composable
private fun SpectrumPanel(vm: EulerScopeViewModel) {
    val s = vm.spectrum
    Column {
        if (s.isEmpty()) {
            Text("Raccolta campioni in corso…", fontSize = 12.sp, color = Color.Gray)
        } else {
            val maxHz = 30f
            val maxBin = ((maxHz / vm.spectrumBinHz).toInt()).coerceIn(2, s.size - 1)
            var peakBin = 1
            for (i in 1..maxBin) if (s[i] > s[peakBin]) peakBin = i
            val peakHz = peakBin * vm.spectrumBinHz

            Canvas(Modifier.fillMaxWidth().height(90.dp).background(Color(0xFF101418))) {
                val maxVal = (1..maxBin).maxOf { s[it] }.coerceAtLeast(1e-9f)
                val dx = size.width / maxBin
                for (i in 1..maxBin) {
                    val hgt = (s[i] / maxVal) * size.height
                    drawRect(
                        color = if (i == peakBin) Color(0xFFFFB347) else Color(0xFF4CD9C0),
                        topLeft = Offset((i - 1) * dx, size.height - hgt),
                        size = androidx.compose.ui.geometry.Size(dx, hgt)
                    )
                }
            }
            Text(
                "Picco a ${"%.2f".format(peakHz)} Hz  (${"%.0f".format(peakHz * 60)} cicli/min)",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
            Text(
                "Risoluzione ${"%.3f".format(vm.spectrumBinHz)} Hz/bin · " +
                "limite di Nyquist ${"%.1f".format(vm.fps / 2)} Hz",
                fontSize = 10.sp, color = Color.Gray
            )
        }
    }
}

// ---------------------------------------------------------------- Gioco

@Composable
private fun GamePanel(vm: EulerScopeViewModel) {
    Column {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2E12))) {
            Text(LieGame.DISCLAIMER, Modifier.padding(10.dp), fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))

        if (!vm.gameBaselineReady) {
            Text(
                "1. Fai stare la persona ferma e tranquilla.\n" +
                "2. Avvia la baseline e attendi il completamento.",
                fontSize = 12.sp
            )
            LinearProgressIndicator(
                progress = { vm.game.baselineProgress },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.startBaseline() }) { Text("Avvia baseline") }
                Button(
                    onClick = { vm.sealBaseline() },
                    enabled = vm.game.baselineProgress >= 1f
                ) { Text("Fissa baseline") }
            }
        } else {
            val r = vm.gameRound
            if (r == null) {
                Text("In attesa di una misura valida…", fontSize = 12.sp, color = Color.Gray)
            } else {
                Text(
                    "Attivazione ${(r.arousalScore * 100).roundToInt()}%",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    "Battito ${if (r.hrDelta >= 0) "+" else ""}${"%.1f".format(r.hrDelta)} bpm · " +
                    "respiro ${if (r.rrDelta >= 0) "+" else ""}${"%.1f".format(r.rrDelta)} /min",
                    fontSize = 12.sp
                )
                QualityBar(r.quality)
                Text(
                    "Scostamenti reali rispetto alla baseline. Cosa li causi " +
                    "— emozione, sorpresa, risata, niente — il telefono non lo sa.",
                    fontSize = 10.sp, color = Color.Gray
                )
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { vm.resetGame() }) { Text("Nuova partita") }
        }
    }
}
