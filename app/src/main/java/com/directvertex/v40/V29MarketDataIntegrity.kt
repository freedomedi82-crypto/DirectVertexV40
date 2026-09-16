package com.directvertex.v40

import kotlin.math.abs
import kotlin.math.sqrt

/** V29 market-specific data integrity and calibration gate. Analysis only. */
object V29MarketDataIntegrity {
    enum class Verdict { READY, USABLE, FRAGILE, BLOCKED }
    data class Report(
        val points:Int=0,
        val validPoints:Int=0,
        val duplicateTimestamps:Int=0,
        val invalidPrices:Int=0,
        val nonMonotonicTimestamps:Int=0,
        val medianIntervalMs:Long=0,
        val intervalCv:Double=0.0,
        val medianAbsReturn:Double=0.0,
        val p95AbsReturn:Double=0.0,
        val maxAbsReturn:Double=0.0,
        val jumpCount:Int=0,
        val suggestedExpirySteps:Int=3,
        val suggestedExpirySeconds:Int=60,
        val coverageScore:Double=0.0,
        val verdict:Verdict=Verdict.BLOCKED,
        val note:String="Belum diuji",
        val issues:List<String> = emptyList()
    )

    fun evaluate(points:List<ReplayPoint>, requestedExpirySeconds:Int=60):Report {
        if (points.isEmpty()) return Report(note="Belum ada data replay")
        val sorted=points.sortedBy { it.timestamp }
        var dup=0; var invalid=0; var nonMono=0
        for(i in 1 until points.size) {
            if(points[i].timestamp==points[i-1].timestamp) dup++
            if(points[i].timestamp<points[i-1].timestamp) nonMono++
        }
        sorted.forEach { if(!it.price.isFinite() || it.price<=0.0) invalid++ }
        val clean=sorted.filter { it.price.isFinite() && it.price>0.0 }
        if(clean.size<20) return Report(points.size,clean.size,dup,invalid,nonMono,note="Data valid < 20 titik; belum layak dikalibrasi",issues=listOf("DATA_TOO_SMALL"))
        val intervals=clean.zipWithNext().map { (a,b)->b.timestamp-a.timestamp }.filter { it>0 }
        val medianInterval=medianLong(intervals)
        val meanInterval=intervals.average()
        val sd=sqrt(intervals.map { (it-meanInterval)*(it-meanInterval) }.average())
        val cv=if(meanInterval>0)sd/meanInterval else Double.POSITIVE_INFINITY
        val returns=clean.zipWithNext().map { (a,b)->abs((b.price-a.price)/a.price) }.filter { it.isFinite() }
        val medRet=median(returns); val p95=percentile(returns,0.95); val maxRet=returns.maxOrNull()?:0.0
        val jumpThreshold=maxOf(medRet*8.0,p95*1.5,0.0005)
        val jumps=returns.count { it>jumpThreshold }
        val targetMs=requestedExpirySeconds.toLong().coerceAtLeast(1L)*1000L
        val steps=if(medianInterval>0) kotlin.math.round(targetMs.toDouble()/medianInterval.toDouble()).toInt().coerceIn(1,120) else 3
        val calibratedSeconds=((steps.toLong()*medianInterval)/1000L).toInt().coerceAtLeast(1)
        val continuity=when { cv<=0.25 -> 1.0; cv<=0.50 -> 0.8; cv<=1.0 -> 0.55; else -> 0.25 }
        val returnCount=if(returns.isEmpty()) 1.0 else returns.size.toDouble()
        val pointCount=if(points.isEmpty()) 1.0 else points.size.toDouble()
        val jumpPenalty=(jumps.toDouble()/returnCount).coerceAtMost(1.0)
        val uniqueness=1.0-((dup+nonMono).toDouble()/pointCount).coerceIn(0.0,1.0)
        val validity=clean.size.toDouble()/pointCount
        val coverage=((continuity*0.45)+(uniqueness*0.25)+(validity*0.20)+(1.0-jumpPenalty)*0.10).coerceIn(0.0,1.0)
        val issues=mutableListOf<String>()
        if(dup>0) issues += "DUPLICATE_TIMESTAMP=$dup"
        if(nonMono>0) issues += "NON_MONOTONIC_TIMESTAMP=$nonMono"
        if(invalid>0) issues += "INVALID_PRICE=$invalid"
        if(cv>0.75) issues += "IRREGULAR_SAMPLING_CV=${"%.2f".format(cv)}"
        if(jumpPenalty>0.03) issues += "EXCESSIVE_JUMPS=${"%.1f".format(jumpPenalty*100)}%"
        val verdict=when {
            invalid>0 || nonMono>0 || coverage<0.45 -> Verdict.BLOCKED
            clean.size>=100 && coverage>=0.80 && cv<=0.50 && jumpPenalty<=0.03 -> Verdict.READY
            clean.size>=50 && coverage>=0.65 -> Verdict.USABLE
            else -> Verdict.FRAGILE
        }
        val note="Sampling median ${medianInterval}ms • target ${requestedExpirySeconds}s → ${calibratedSeconds}s (${steps} steps) • coverage ${"%.0f".format(coverage*100)}%"
        return Report(points.size,clean.size,dup,invalid,nonMono,medianInterval,cv,medRet,p95,maxRet,jumps,steps,calibratedSeconds,coverage,verdict,note,issues)
    }
    private fun median(v:List<Double>):Double { if(v.isEmpty()) return 0.0; val s=v.sorted(); return s[s.size/2] }
    private fun medianLong(v:List<Long>):Long { if(v.isEmpty()) return 0L; val s=v.sorted(); return s[s.size/2] }
    private fun percentile(v:List<Double>,p:Double):Double { if(v.isEmpty()) return 0.0; val s=v.sorted(); val idx=((s.size-1)*p).coerceIn(0.0,(s.size-1).toDouble()); val lo=idx.toInt(); val hi=kotlin.math.ceil(idx).toInt(); return if(lo==hi)s[lo] else s[lo]+(s[hi]-s[lo])*(idx-lo) }
}
