package com.directvertex.v40

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Research-only recorder for user-exported or manually captured Quotex OTC data.
 * It does not log in, scrape credentials, automate the Quotex terminal, or place orders.
 */
data class QuotexOTCResearchRow(
    val timestampMs: Long,
    val asset: String,
    val timeframeSeconds: Int,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val payoutPercent: Double? = null,
    val source: String = "USER_CAPTURE"
)

data class QuotexOTCTick(
    val timestampMs: Long,
    val asset: String,
    val price: Double,
    val source: String = "USER_CAPTURE"
)

data class QuotexOTCIntegrityReport(
    val rows: Int,
    val validRows: Int,
    val duplicateTimestamps: Int,
    val nonMonotonicTimestamps: Int,
    val invalidPrices: Int,
    val invalidOHLC: Int,
    val gaps: Int,
    val medianIntervalMs: Long,
    val sha256: String,
    val readyForCalibration: Boolean,
    val issues: List<String>
)

class QuotexOTCResearchRecorder {
    private val candles = mutableListOf<QuotexOTCResearchRow>()
    private val ticks = mutableListOf<QuotexOTCTick>()
    private var captureStartedAt: Long? = null
    private var captureStoppedAt: Long? = null

    val isCapturing: Boolean get() = captureStartedAt != null && captureStoppedAt == null

    fun startCapture(nowMs: Long = System.currentTimeMillis()) {
        captureStartedAt = nowMs
        captureStoppedAt = null
    }

    fun stopCapture(nowMs: Long = System.currentTimeMillis()) {
        captureStoppedAt = nowMs
    }

    fun clear() {
        candles.clear()
        ticks.clear()
        captureStartedAt = null
        captureStoppedAt = null
    }

    fun recordCandle(row: QuotexOTCResearchRow): Boolean {
        if (!row.asset.isNotBlank() || row.timestampMs <= 0L || row.timeframeSeconds <= 0) return false
        if (!row.open.isFinite() || !row.high.isFinite() || !row.low.isFinite() || !row.close.isFinite()) return false
        if (row.open <= 0.0 || row.high <= 0.0 || row.low <= 0.0 || row.close <= 0.0) return false
        if (row.high < maxOf(row.open, row.close) || row.low > minOf(row.open, row.close)) return false
        candles += row.copy(asset = row.asset.trim())
        return true
    }

    fun recordTick(tick: QuotexOTCTick): Boolean {
        if (!tick.asset.isNotBlank() || tick.timestampMs <= 0L || !tick.price.isFinite() || tick.price <= 0.0) return false
        ticks += tick.copy(asset = tick.asset.trim())
        return true
    }

    fun candleSnapshot(): List<QuotexOTCResearchRow> = candles.sortedBy { it.timestampMs }
    fun tickSnapshot(): List<QuotexOTCTick> = ticks.sortedBy { it.timestampMs }

    fun integrityReport(): QuotexOTCIntegrityReport {
        val sorted = candleSnapshot()
        var duplicate = 0
        var nonMonotonic = 0
        var invalidPrices = 0
        var invalidOhlc = 0
        var gaps = 0
        val intervals = mutableListOf<Long>()

        for (i in sorted.indices) {
            val r = sorted[i]
            if (r.open <= 0 || r.high <= 0 || r.low <= 0 || r.close <= 0 ||
                !r.open.isFinite() || !r.high.isFinite() || !r.low.isFinite() || !r.close.isFinite()) invalidPrices++
            if (r.high < maxOf(r.open, r.close) || r.low > minOf(r.open, r.close)) invalidOhlc++
            if (i > 0) {
                val dt = r.timestampMs - sorted[i - 1].timestampMs
                if (dt == 0L) duplicate++
                if (dt < 0L) nonMonotonic++
                if (dt > 0L) {
                    intervals += dt
                    val expected = sorted[i - 1].timeframeSeconds * 1000L
                    if (expected > 0 && dt > expected * 2L) gaps++
                }
            }
        }
        val valid = sorted.count { r ->
            r.open > 0 && r.high > 0 && r.low > 0 && r.close > 0 &&
                r.open.isFinite() && r.high.isFinite() && r.low.isFinite() && r.close.isFinite() &&
                r.high >= maxOf(r.open, r.close) && r.low <= minOf(r.open, r.close)
        }
        val median = if (intervals.isEmpty()) 0L else intervals.sorted()[intervals.size / 2]
        val issues = buildList {
            if (sorted.size < 20) add("DATA_TOO_SMALL")
            if (duplicate > 0) add("DUPLICATE_TIMESTAMP=$duplicate")
            if (nonMonotonic > 0) add("NON_MONOTONIC_TIMESTAMP=$nonMonotonic")
            if (invalidPrices > 0) add("INVALID_PRICE=$invalidPrices")
            if (invalidOhlc > 0) add("INVALID_OHLC=$invalidOhlc")
            if (gaps > 0) add("GAPS=$gaps")
        }
        return QuotexOTCIntegrityReport(
            rows = sorted.size,
            validRows = valid,
            duplicateTimestamps = duplicate,
            nonMonotonicTimestamps = nonMonotonic,
            invalidPrices = invalidPrices,
            invalidOHLC = invalidOhlc,
            gaps = gaps,
            medianIntervalMs = median,
            sha256 = sha256(canonicalCsv()),
            readyForCalibration = sorted.size >= 20 && valid == sorted.size && duplicate == 0 && nonMonotonic == 0 && invalidOhlc == 0,
            issues = issues
        )
    }

    fun canonicalCsv(): String = buildString {
        append("timestamp_ms,asset,timeframe_seconds,open,high,low,close,payout_percent,source\n")
        candleSnapshot().forEach { r ->
            append(r.timestampMs).append(',')
            append(csv(r.asset)).append(',')
            append(r.timeframeSeconds).append(',')
            append(r.open).append(',').append(r.high).append(',').append(r.low).append(',').append(r.close).append(',')
            append(r.payoutPercent ?: "").append(',').append(csv(r.source)).append('\n')
        }
    }

    fun importCsv(csvText: String, replaceExisting: Boolean = true): Int {
        val lines = csvText.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return 0
        val header = lines.first().split(',').map { it.trim().lowercase(Locale.US) }
        val index = { name: String -> header.indexOf(name) }
        val required = listOf("timestamp_ms", "asset", "timeframe_seconds", "open", "high", "low", "close")
        if (required.any { index(it) < 0 }) return 0
        if (replaceExisting) candles.clear()
        var imported = 0
        lines.drop(1).forEach { line ->
            val cells = parseCsvLine(line)
            fun cell(name: String): String? = cells.getOrNull(index(name))?.trim()?.takeIf { it.isNotEmpty() }
            val row = QuotexOTCResearchRow(
                timestampMs = cell("timestamp_ms")?.toLongOrNull() ?: return@forEach,
                asset = cell("asset") ?: return@forEach,
                timeframeSeconds = cell("timeframe_seconds")?.toIntOrNull() ?: return@forEach,
                open = cell("open")?.toDoubleOrNull() ?: return@forEach,
                high = cell("high")?.toDoubleOrNull() ?: return@forEach,
                low = cell("low")?.toDoubleOrNull() ?: return@forEach,
                close = cell("close")?.toDoubleOrNull() ?: return@forEach,
                payoutPercent = cell("payout_percent")?.toDoubleOrNull(),
                source = cell("source") ?: "IMPORTED"
            )
            if (recordCandle(row)) imported++
        }
        return imported
    }

    fun captureSummary(): String {
        val start = captureStartedAt?.let { formatUtc(it) } ?: "-"
        val stop = captureStoppedAt?.let { formatUtc(it) } ?: "-"
        return "capture=$isCapturing; start=$start; stop=$stop; candles=${candles.size}; ticks=${ticks.size}"
    }

    private fun csv(value: String): String {
        if (!value.contains(',') && !value.contains('"') && !value.contains('\n')) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += current.toString(); current.setLength(0) }
                else -> current.append(c)
            }
            i++
        }
        out += current.toString()
        return out
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun formatUtc(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.format(Date(ms))
}
