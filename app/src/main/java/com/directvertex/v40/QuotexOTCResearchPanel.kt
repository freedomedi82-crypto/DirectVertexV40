package com.directvertex.v40

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun QuotexOTCResearchPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val recorder = remember { QuotexOTCResearchRecorder() }
    var report by remember { mutableStateOf(recorder.integrityReport()) }
    var message by remember { mutableStateOf("Belum ada dataset") }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("File tidak dapat dibaca")
            }.onSuccess { csv ->
                val count = recorder.importCsv(csv, replaceExisting = true)
                report = recorder.integrityReport()
                message = "Imported $count candle rows"
            }.onFailure { error ->
                message = "Import gagal: ${error.message ?: "unknown error"}"
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(recorder.canonicalCsv()) }
                    ?: error("File tidak dapat ditulis")
            }.onSuccess {
                message = "CSV tersimpan"
            }.onFailure { error ->
                message = "Export gagal: ${error.message ?: "unknown error"}"
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("QUOTEX OTC RESEARCH", style = MaterialTheme.typography.titleMedium)
            Text("Research-only • import/capture data • no auto trading", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Button(onClick = { importLauncher.launch(arrayOf("text/csv", "text/plain", "application/octet-stream")) }) {
                    Text("IMPORT CSV")
                }
                OutlinedButton(onClick = { exportLauncher.launch("quotex_otc_research.csv") }) {
                    Text("EXPORT")
                }
                OutlinedButton(onClick = {
                    if (recorder.isCapturing) recorder.stopCapture() else recorder.startCapture()
                    message = if (recorder.isCapturing) "Capture ON" else "Capture OFF"
                }) {
                    Text(if (recorder.isCapturing) "STOP" else "CAPTURE")
                }
            }
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Rows: ${report.rows} • Valid: ${report.validRows} • Gaps: ${report.gaps}")
            Text("Duplicates: ${report.duplicateTimestamps} • Non-monotonic: ${report.nonMonotonicTimestamps}")
            Text("Median interval: ${report.medianIntervalMs} ms")
            Text("SHA-256: ${report.sha256.take(16)}…")
            Text(if (report.readyForCalibration) "DATA STATUS: READY FOR CALIBRATION" else "DATA STATUS: WAIT / FIX DATA")
            if (report.issues.isNotEmpty()) Text("Issues: ${report.issues.joinToString()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Schema: timestamp_ms, asset, timeframe_seconds, open, high, low, close, payout_percent, source", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
