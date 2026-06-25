package com.example.dsp

import kotlin.math.*

class TruePeakMeter {
    // 4x oversampling FIR polyphase filter.
    // 48 coefficient taps overall, which gives 12 taps per phase.
    private val filterLength = 48
    private val phases = 4
    private val tapsPerPhase = filterLength / phases
    
    private val coeffs = Array(phases) { FloatArray(tapsPerPhase) }
    private val bufferL = FloatArray(tapsPerPhase)
    private val bufferR = FloatArray(tapsPerPhase)
    private var bufferIdx = 0

    var truePeakL: Float = -150f
    var truePeakR: Float = -150f
    var truePeakMax: Float = -150f
    var truePeakHold: Float = -150f
    var clipCount: Int = 0

    init {
        // Pre-compute Kaiser-windowed sinc interpolation coefficients for 4 phases
        val numTaps = filterLength
        val alpha = 8.0 // Kaiser window parameter modeling high stop-band attenuation
        val doubleCoeffs = DoubleArray(numTaps)
        val center = (numTaps - 1) / 2.0

        for (i in 0 until numTaps) {
            val t = i - center
            val sincVal = if (t == 0.0) 1.0 else sin(PI * t / 4.0) / (PI * t / 4.0)
            val x = 2.0 * i / (numTaps - 1) - 1.0
            val bVal = besselI0(alpha * sqrt(1.0 - x * x)) / besselI0(alpha)
            doubleCoeffs[i] = sincVal * bVal
        }

        // Distribute coefficients into polyphase branches and normalize gain per phase
        for (phase in 0 until phases) {
            var phaseSum = 0.0
            for (t in 0 until tapsPerPhase) {
                phaseSum += doubleCoeffs[t * phases + phase]
            }
            for (t in 0 until tapsPerPhase) {
                coeffs[phase][t] = (doubleCoeffs[t * phases + phase] / phaseSum).toFloat()
            }
        }
    }

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var temp = 1.0
        for (i in 1..25) {
            temp *= (x / (2.0 * i))
            val term = temp * temp
            sum += term
            if (term < sum * 1e-15) break
        }
        return sum
    }

    fun reset() {
        bufferL.fill(0f)
        bufferR.fill(0f)
        bufferIdx = 0
        truePeakL = -150f
        truePeakR = -150f
        truePeakMax = -150f
        truePeakHold = -150f
        clipCount = 0
    }

    fun process(left: FloatArray, right: FloatArray, size: Int): Pair<Float, Float> {
        if (size == 0) return Pair(-150f, -150f)

        var localMaxL = 0f
        var localMaxR = 0f

        for (i in 0 until size) {
            val sampleL = left[i]
            val sampleR = right[i]

            // Save inside circle buffer
            bufferL[bufferIdx] = sampleL
            bufferR[bufferIdx] = sampleR

            // Process polyphase branches for 4x oversampling
            for (phase in 0 until phases) {
                var interpL = 0f
                var interpR = 0f
                
                for (tap in 0 until tapsPerPhase) {
                    val idx = (bufferIdx - tap + tapsPerPhase) % tapsPerPhase
                    val c = coeffs[phase][tap]
                    interpL += bufferL[idx] * c
                    interpR += bufferR[idx] * c
                }

                val absL = abs(interpL)
                val absR = abs(interpR)
                if (absL > localMaxL) localMaxL = absL
                if (absR > localMaxR) localMaxR = absR
            }

            bufferIdx = (bufferIdx + 1) % tapsPerPhase
        }

        val epsilon = 1e-8f
        val dbL = if (localMaxL < epsilon) -150f else 20f * log10(localMaxL)
        val dbR = if (localMaxR < epsilon) -150f else 20f * log10(localMaxR)

        this.truePeakL = dbL
        this.truePeakR = dbR
        
        val maxFramePeak = max(dbL, dbR)
        if (maxFramePeak > this.truePeakMax) {
            this.truePeakMax = maxFramePeak
        }
        if (maxFramePeak > this.truePeakHold) {
            this.truePeakHold = maxFramePeak
        }

        // Clip count exceeds 0 dBTP
        if (dbL >= 0.0f || dbR >= 0.0f) {
            clipCount++
        }

        return Pair(dbL, dbR)
    }
}
