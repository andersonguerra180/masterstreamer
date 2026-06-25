package com.example.dsp

import kotlin.math.*

class StereoAnalyzer {
    var phaseCorrelation: Float = 0.0f
    var midLevelDb: Float = -120f
    var sideLevelDb: Float = -120f
    var stereoWidth: Float = 0.0f
    var sideDominance: String = "C" // "L" (Left), "R" (Right), "C" (Center)
    var monoFoldDownLossDb: Float = 0f

    // Exponential smoothing states
    private var smoothedCorrelation = 0f
    private var smoothedMidPower = 0f
    private var smoothedSidePower = 0f
    private var smoothedPowerL = 0f
    private var smoothedPowerR = 0f

    private val alpha = 0.12f // Fast but smooth ballistics typical of mastering consoles

    fun reset() {
        phaseCorrelation = 0f
        midLevelDb = -120f
        sideLevelDb = -120f
        stereoWidth = 0f
        sideDominance = "C"
        monoFoldDownLossDb = 0f
        smoothedCorrelation = 0f
        smoothedMidPower = 0f
        smoothedSidePower = 0f
        smoothedPowerL = 0f
        smoothedPowerR = 0f
    }

    fun process(left: FloatArray, right: FloatArray, size: Int) {
        if (size == 0) return

        var dotProduct = 0.0
        var normL = 0.0
        var normR = 0.0
        var sumMidSq = 0.0
        var sumSideSq = 0.0
        var sumLSq = 0.0
        var sumRSq = 0.0

        for (i in 0 until size) {
            val l = left[i].toDouble()
            val r = right[i].toDouble()

            dotProduct += l * r
            normL += l * l
            normR += r * r

            val mid = (l + r) * 0.5
            val side = (l - r) * 0.5

            sumMidSq += mid * mid
            sumSideSq += side * side
            sumLSq += l * l
            sumRSq += r * r
        }

        val frameCorrelation = if (normL > 1e-12 && normR > 1e-12) {
            (dotProduct / (sqrt(normL) * sqrt(normR))).toFloat()
        } else {
            0.0f
        }

        smoothedCorrelation = smoothedCorrelation * (1f - alpha) + frameCorrelation * alpha
        phaseCorrelation = smoothedCorrelation.coerceIn(-1.0f, 1.0f)

        val framePowerMid = (sumMidSq / size).toFloat()
        val framePowerSide = (sumSideSq / size).toFloat()
        val framePowerL = (sumLSq / size).toFloat()
        val framePowerR = (sumRSq / size).toFloat()

        smoothedMidPower = smoothedMidPower * (1f - alpha) + framePowerMid * alpha
        smoothedSidePower = smoothedSidePower * (1f - alpha) + framePowerSide * alpha
        smoothedPowerL = smoothedPowerL * (1f - alpha) + framePowerL * alpha
        smoothedPowerR = smoothedPowerR * (1f - alpha) + framePowerR * alpha

        val epsilon = 1e-9f
        midLevelDb = if (smoothedMidPower < epsilon) -120f else 10f * log10(smoothedMidPower)
        sideLevelDb = if (smoothedSidePower < epsilon) -120f else 10f * log10(smoothedSidePower)

        val midRms = sqrt(smoothedMidPower)
        val sideRms = sqrt(smoothedSidePower)
        val denom = midRms + sideRms
        stereoWidth = if (denom > 1e-6f) {
            (sideRms / denom) * 100f
        } else {
            0.0f
        }

        val lRms = sqrt(smoothedPowerL)
        val rRms = sqrt(smoothedPowerR)
        sideDominance = when {
            abs(lRms - rRms) < 1e-3f -> "C"
            lRms > rRms -> "L"
            else -> "R"
        }

        val stereoTotalPower = smoothedPowerL + smoothedPowerR
        val monoPower = smoothedMidPower * 4f // mid is (L+R)/2 so (L+R)^2 is 4*midSq
        
        monoFoldDownLossDb = if (stereoTotalPower > 1e-8f && monoPower > 1e-8f) {
            val loss = 10f * log10(monoPower / stereoTotalPower)
            loss.coerceAtMost(3.0f)
        } else {
            0.0f
        }
    }
}
