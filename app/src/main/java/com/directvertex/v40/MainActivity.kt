package com.directvertex.v40

import android.Manifest
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(Manifest.permission.CAMERA)
        setContent { DirectVertexTheme { Dashboard() } }
    }
}

@Composable
private fun DirectVertexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(background = Color(0xFF080A0D), surface = Color(0xFF11151A)),
        content = content
    )
}

@Composable
private fun Dashboard() {
    val lifecycleOwner = LocalLifecycleOwner.current
    val calibrator = remember { PriceCalibrator() }
    val temporal = remember { TemporalEngine(12) }
    val candleBuilder = remember { VirtualCandleBuilder() }
    val signalEngine = remember { SignalEngine() }
    val v15 = remember { V15VisionEngine() }

    var latest by remember { mutableStateOf(SonarSnapshot()) }
    var signal by remember { mutableStateOf(SignalState()) }
    var running by remember { mutableStateOf(true) }
    var expirySeconds by remember { mutableIntStateOf(60) }
    var firstY by remember { mutableStateOf<Float?>(null) }
    var secondY by remember { mutableStateOf<Float?>(null) }
    var firstPrice by remember { mutableStateOf("") }
    var secondPrice by remember { mutableStateOf("") }
    var calibrated by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { temporal.clear(); candleBuilder.clear(); v15.reset() }
    }

    fun handleSnapshot(raw: SonarSnapshot) {
        if (!running) return
        val chartY = raw.chartY
        val price = if (chartY != null && calibrator.isReady()) calibrator.calibrate(chartY).price else null
        price?.let { candleBuilder.add(it) }
        val base = raw.copy(calibratedPrice = price, virtualCandle = candleBuilder.build())
        val vision = v15.push(
            V15Frame(
                chartY = (base.chartY ?: 0.5f).toDouble(),
                ocrPrice = base.ocrPrice.toDoubleOrNull(),
                greenRatio = base.greenRatio.toDouble(),
                redRatio = base.redRatio.toDouble(),
                frameQuality = ((base.greenRatio + base.redRatio) * 8.0 + if (base.chartY != null) 0.5 else 0.2).coerceIn(0.0, 1.0),
                timestampMs = base.updatedAt
            )
        )
        val stable = base.copy(chartY = if (vision.acceptedFrames > 0) vision.stableY.toFloat() else base.chartY)
        val enriched = temporal.push(stable)
        latest = enriched
        signal = signalEngine.evaluate(enriched)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF080A0D)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("DIRECT VERTEX V40", style = MaterialTheme.typography.headlineSmall)
        Text("Android Trading Signal Analysis", color = Color.LightGray)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF11151A))
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PreviewView(ctx).also { view ->
                                startCamera(ctx, lifecycleOwner, view, ::handleSnapshot)
                            }
                        }
                    )
                    Canvas(
                        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val y = (offset.y / size.height).coerceIn(0f, 1f)
                                if (calibrated) {
                                    if (firstY == null) firstY = y else secondY = y
                                }
                            }
                        }
                    ) {
                        firstY?.let { y -> drawLine(Color.Yellow, Offset(0f, y * size.height), Offset(size.width, y * size.height), strokeWidth = 3f) }
                        secondY?.let { y -> drawLine(Color.Cyan, Offset(0f, y * size.height), Offset(size.width, y * size.height), strokeWidth = 3f) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("SIGNAL", style = MaterialTheme.typography.titleMedium)
                    Text(signal.direction, style = MaterialTheme.typography.headlineSmall)
                    Text("Score ${signal.score}")
                    Text(signal.confidence)
                }
                Text(signal.reason, color = Color.LightGray)
                Text("OCR: ${latest.ocrPrice} • Bias: ${latest.virtualCandle.bias} • Samples: ${latest.sampleCount}", color = Color.Gray)
                Text("Price: ${latest.calibratedPrice?.let { "%.6f".format(it) } ?: "not calibrated"}", color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { running = !running }) { Text(if (running) "PAUSE" else "START") }
                    OutlinedButton(onClick = {
                        firstY = null; secondY = null; firstPrice = ""; secondPrice = ""; calibrated = false
                        calibrator.clear(); temporal.clear(); candleBuilder.clear(); v15.reset(); latest = SonarSnapshot(); signal = SignalState()
                    }) { Text("RESET") }
                }
            }
        }

        Card(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF11151A))) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PRICE CALIBRATION", style = MaterialTheme.typography.titleMedium)
                Text("Tap two chart positions, then enter their prices.", color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = firstPrice, onValueChange = { firstPrice = it }, modifier = Modifier.weight(1f), label = { Text("Price 1") }, singleLine = true)
                    OutlinedTextField(value = secondPrice, onValueChange = { secondPrice = it }, modifier = Modifier.weight(1f), label = { Text("Price 2") }, singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val p1 = firstPrice.toDoubleOrNull(); val p2 = secondPrice.toDoubleOrNull()
                        if (p1 != null && p2 != null && firstY != null && secondY != null && firstY != secondY) {
                            calibrator.setEntry(PriceAnchor(firstY!!, p1)); calibrator.setSecond(PriceAnchor(secondY!!, p2)); calibrated = calibrator.isReady()
                        }
                    }) { Text("APPLY") }
                    OutlinedButton(onClick = { firstY = null; secondY = null }) { Text("CLEAR Y") }
                }
                Text("Y1=${firstY?.let { "%.3f".format(it) } ?: "-"} • Y2=${secondY?.let { "%.3f".format(it) } ?: "-"} • ${if (calibrated) "READY" else "WAIT"}", color = Color.Gray)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Expiry")
            OutlinedButton(onClick = { expirySeconds = (expirySeconds - 5).coerceAtLeast(5) }) { Text("−") }
            Text("${expirySeconds}s", modifier = Modifier.width(55.dp))
            OutlinedButton(onClick = { expirySeconds = (expirySeconds + 5).coerceAtMost(300) }) { Text("+") }
        }

        QuotexOTCResearchPanel()

        Spacer(Modifier.height(2.dp))
        Text("CALL/PUT adalah sinyal analisis manual; aplikasi tidak mengeksekusi order otomatis.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
    }
}

private fun startCamera(context: Context, lifecycleOwner: LifecycleOwner, previewView: PreviewView, onSnapshot: (SonarSnapshot) -> Unit) {
    val future = ProcessCameraProvider.getInstance(context)
    val executor = Executors.newSingleThreadExecutor()
    future.addListener({
        val provider = future.get()
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also {
            it.setAnalyzer(executor, VisualSonarAnalyzer(Roi(), onSnapshot))
        }
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }, ContextCompat.getMainExecutor(context))
}
