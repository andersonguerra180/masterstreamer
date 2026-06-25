package com.example.dsp

import kotlin.math.*
import java.util.Random

class SignalGenerator(val sampleRate: Double = 48000.0) {
    enum class SignalType {
        SINE_1KHZ, SINE_100HZ, PINK_NOISE, WHITE_NOISE, SINE_SWEEP, SILENCE
    }

    private var sineIndex = 0.0
    private val random = Random()
    private var sweepFreq = 20.0
    private var sweepDirection = 1.0

    // Voss-McCartney Pink Noise generator states
    private val pinkRows = DoubleArray(16)
    private var pinkRunningSum = 0.0
    private var pinkIndex = 0

    init {
        resetPink()
    }

    private fun resetPink() {
        pinkIndex = 0
        pinkRunningSum = 0.0
        for (i in pinkRows.indices) {
            val r = random.nextDouble() - 0.5
            pinkRows[i] = r
            pinkRunningSum += r
        }
    }

    fun fillBuffer(left: FloatArray, right: FloatArray, size: Int, type: SignalType, targetDb: Float = -20.0f) {
        val targetLinear = 10.0.pow(targetDb.toDouble() / 20.0)

        when (type) {
            SignalType.SILENCE -> {
                left.fill(0f)
                right.fill(0f)
            }
            SignalType.SINE_1KHZ -> generateSine(left, right, size, 1000.0, targetLinear)
            SignalType.SINE_100HZ -> generateSine(left, right, size, 100.0, targetLinear)
            SignalType.PINK_NOISE -> generatePinkNoise(left, right, size, targetLinear)
            SignalType.WHITE_NOISE -> generateWhiteNoise(left, right, size, targetLinear)
            SignalType.SINE_SWEEP -> generateSweep(left, right, size, targetLinear)
        }
    }

    private fun generateSine(left: FloatArray, right: FloatArray, size: Int, frequency: Double, amplitude: Double) {
        val step = 2.0 * PI * frequency / sampleRate
        for (i in 0 until size) {
            val s = sin(sineIndex) * amplitude
            left[i] = s.toFloat()
            right[i] = s.toFloat()

            sineIndex += step
            if (sineIndex >= 2.0 * PI) {
                sineIndex -= 2.0 * PI
            }
        }
    }

    private fun generateWhiteNoise(left: FloatArray, right: FloatArray, size: Int, amplitude: Double) {
        for (i in 0 until size) {
            val lVal = (random.nextFloat() * 2f - 1f) * amplitude.toFloat()
            val rVal = (random.nextFloat() * 2f - 1f) * amplitude.toFloat()
            left[i] = lVal
            right[i] = rVal
        }
    }

    private fun generatePinkNoise(left: FloatArray, right: FloatArray, size: Int, amplitude: Double) {
        for (i in 0 until size) {
            val lastIdx = pinkIndex
            pinkIndex = (pinkIndex + 1) and 15
            
            var diff = 0.0
            var bit = 0
            var temp = lastIdx xor pinkIndex
            while (temp != 0) {
                if (temp and 1 != 0) {
                    val r = random.nextDouble() - 0.5
                    diff += r - pinkRows[bit]
                    pinkRows[bit] = r
                }
                temp = temp shr 1
                bit++
            }
            
            pinkRunningSum += diff
            val white = random.nextDouble() - 0.5
            val pinkVal = (pinkRunningSum + white) * 0.12 * amplitude
            left[i] = pinkVal.toFloat()
            right[i] = pinkVal.toFloat()
        }
    }

    private fun generateSweep(left: FloatArray, right: FloatArray, size: Int, amplitude: Double) {
        for (i in 0 until size) {
            val s = sin(sineIndex) * amplitude
            left[i] = s.toFloat()
            right[i] = s.toFloat()

            // Sweep frequency progresses slowly back and forth between 20Hz and 20kHz
            sweepFreq += sweepDirection * (180.0 / sampleRate)
            if (sweepFreq >= 20000.0) {
                sweepFreq = 20000.0
                sweepDirection = -1.0
            } else if (sweepFreq <= 20.0) {
                sweepFreq = 20.0
                sweepDirection = 1.0
            }

            val step = 2.0 * PI * sweepFreq / sampleRate
            sineIndex += step
            if (sineIndex >= 2.0 * PI) {
                sineIndex -= 2.0 * PI
            }
        }
    }
}
