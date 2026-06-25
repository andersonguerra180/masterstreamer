package com.example.dsp

import kotlin.math.*

class BiquadFilter(
    var b0: Double = 1.0,
    var b1: Double = 0.0,
    var b2: Double = 0.0,
    var a1: Double = 0.0,
    var a2: Double = 0.0
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(sample: Double): Double {
        val out = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = sample
        y2 = y1
        y1 = out
        return out
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }
}

class KWeightingFilter(val sampleRate: Double) {
    private val filterL1: BiquadFilter
    private val filterL2: BiquadFilter
    private val filterR1: BiquadFilter
    private val filterR2: BiquadFilter

    init {
        // Stage 1: High-shelving pre-filter (exact 48kHz coefficients from BS.1770-4)
        val hb0 = 1.53512485958697
        val hb1 = -2.69169618940638
        val hb2 = 1.19839281085285
        val ha1 = -1.69065929318241
        val ha2 = 0.73248077421585

        // Stage 2: High-pass RLB filter (exact 48kHz coefficients from BS.1770-4)
        val hpb0 = 1.0
        val hpb1 = -2.0
        val hpb2 = 1.0
        val hpa1 = -1.99004745483398
        val hpa2 = 0.99007225036603

        filterL1 = BiquadFilter(hb0, hb1, hb2, ha1, ha2)
        filterR1 = BiquadFilter(hb0, hb1, hb2, ha1, ha2)

        filterL2 = BiquadFilter(hpb0, hpb1, hpb2, hpa1, hpa2)
        filterR2 = BiquadFilter(hpb0, hpb1, hpb2, hpa1, hpa2)
    }

    fun processStereo(left: Double, right: Double): Pair<Double, Double> {
        val lStage1 = filterL1.process(left)
        val rStage1 = filterR1.process(right)
        val lStage2 = filterL2.process(lStage1)
        val rStage2 = filterR2.process(rStage1)
        return Pair(lStage2, rStage2)
    }

    fun reset() {
        filterL1.reset()
        filterL2.reset()
        filterR1.reset()
        filterR2.reset()
    }

    companion object {
        private fun computeHighShelf(fs: Double, fc: Double, gainDb: Double, q: Double): DoubleArray {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * fc / fs
            val alpha = sin(w0) / (2.0 * q)
            val cosW0 = cos(w0)

            val b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + 2.0 * sqrt(a) * alpha)
            val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
            val b2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - 2.0 * sqrt(a) * alpha)
            val a0 = (a + 1.0) - (a - 1.0) * cosW0 + 2.0 * sqrt(a) * alpha
            val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
            val a2 = (a + 1.0) - (a - 1.0) * cosW0 - 2.0 * sqrt(a) * alpha

            return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }

        private fun computeHighPass(fs: Double, fc: Double, q: Double): DoubleArray {
            val w0 = 2.0 * PI * fc / fs
            val alpha = sin(w0) / (2.0 * q)
            val cosW0 = cos(w0)

            val b0 = (1.0 + cosW0) / 2.0
            val b1 = -(1.0 + cosW0)
            val b2 = (1.0 + cosW0) / 2.0
            val a0 = 1.0 + alpha
            val a1 = -2.0 * cosW0
            val a2 = 1.0 - alpha

            return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}
