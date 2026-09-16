package com.directvertex.v40

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max

data class V15Frame(
    val chartY: Double,
    val ocrPrice: Double?,
    val greenRatio: Double,
    val redRatio: Double,
    val frameQuality: Double,
    val timestampMs: Long
)

data class V15VisionState(
    val stableY: Double,
    val stablePrice: Double?,
    val trend: String,
    val momentum: Double,
    val volatility: Double,
    val quality: Double,
    val acceptedFrames: Int,
    val rejectedFrames: Int
)

class V15VisionEngine(
    private val maxHistory: Int = 20,
    private val jumpLimit: Double = 0.12,
    private val minQuality: Double = 0.35
) {
    private val frames = ArrayDeque<V15Frame>()
    private var rejected = 0

    fun push(frame: V15Frame): V15VisionState {
        val q = frame.frameQuality.coerceIn(0.0, 1.0)
        val last = frames.lastOrNull()
        if (q < minQuality || (last != null && abs(frame.chartY - last.chartY) > jumpLimit)) {
            rejected++
            return state()
        }
        frames.addLast(frame.copy(frameQuality = q))
        while (frames.size > maxHistory) frames.removeFirst()
        return state()
    }

    fun reset() {
        frames.clear()
        rejected = 0
    }

    private fun state(): V15VisionState {
        if (frames.isEmpty()) return V15VisionState(0.0, null, "UNKNOWN", 0.0, 0.0, 0.0, 0, rejected)
        val list: List<V15Frame> = frames.toList()
        val weights = list.map { max(0.05, it.frameQuality) }
        val wsum: Double = weights.sum()
        val stableY: Double = list.zip(weights).sumOf { (f, w) -> f.chartY * w } / wsum
        val pw = list.filter { it.ocrPrice != null }
        val pweights = pw.map { max(0.05, it.frameQuality) }
        val stablePrice: Double? = if (pw.isEmpty()) null else pw.zip(pweights).sumOf { (f, w) -> f.ocrPrice!! * w } / pweights.sum()
        val half = max(1, list.size / 2)
        val a: Double = list.take(half).map { it.chartY }.average()
        val b: Double = list.takeLast(half).map { it.chartY }.average()
        val momentum: Double = a - b
        val mean: Double = list.map { it.chartY }.average()
        val variance: Double = list.map { (it.chartY - mean) * (it.chartY - mean) }.average()
        val volatility: Double = kotlin.math.sqrt(variance)
        val trend = when {
            momentum > 0.015 -> "UP"
            momentum < -0.015 -> "DOWN"
            else -> "RANGE"
        }
        val quality: Double = list.map { it.frameQuality }.average()
        return V15VisionState(stableY, stablePrice, trend, momentum, volatility, quality, list.size, rejected)
    }
}
