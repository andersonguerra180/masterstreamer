package com.example.dsp

import kotlin.math.*

enum class FftMode {
    NORMAL, PRECISION, VINYL
}

class FftAnalyzer(var fftSize: Int = 4096) {
    enum class WindowType {
        HANN, BLACKMAN_HARRIS, FLAT_TOP
    }

    var windowType = WindowType.HANN
        set(value) {
            field = value
            recreateWindow()
        }

    var mode: FftMode = FftMode.NORMAL
        set(value) {
            field = value
            when (value) {
                FftMode.NORMAL -> {
                    setSize(8192)
                    decayFactor = 0.85f
                }
                FftMode.PRECISION -> {
                    setSize(16384)
                    decayFactor = 0.70f
                }
                FftMode.VINYL -> {
                    setSize(16384)
                    decayFactor = 0.75f
                }
            }
        }

    private var cosTable = FloatArray(0)
    private var sinTable = FloatArray(0)
    private var revTable = IntArray(0)
    private var windowCoeffs = FloatArray(0)

    private var windowSum = 1.0f

    private val inputHistory = FloatArray(16384)
    private var historyWriteIndex = 0

    var magnitudes = FloatArray(0)
    var logMagnitudes = FloatArray(100) // 100 logarithmic spectrum slices
    var octave31Bins = FloatArray(31) { -120f } // 31 1/3 octave bands
    
    val standard31OctaveFreqs = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0,
        1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0
    )
    
    // Smooth decay between updates
    var decayFactor = 0.90f
    
    init {
        setupFft()
    }

    fun setSize(size: Int) {
        if (size == fftSize && cosTable.isNotEmpty()) return
        fftSize = size
        setupFft()
    }

    private fun setupFft() {
        val n = fftSize
        cosTable = FloatArray(n / 2)
        sinTable = FloatArray(n / 2)
        for (i in 0 until n / 2) {
            val angle = -2.0 * PI * i / n
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }

        // Precompute bit reversal table
        revTable = IntArray(n)
        val bits = java.lang.Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) {
            var rev = 0
            var temp = i
            for (j in 0 until bits) {
                rev = (rev shl 1) or (temp and 1)
                temp = temp shr 1
            }
            revTable[i] = rev
        }

        recreateWindow()
        magnitudes = FloatArray(n / 2)
    }

    private fun recreateWindow() {
        val n = fftSize
        windowCoeffs = FloatArray(n)
        windowSum = 0.0f
        
        when (windowType) {
            WindowType.HANN -> {
                for (i in 0 until n) {
                    val w = 0.5f * (1f - cos(2f * PI.toFloat() * i / (n - 1)))
                    windowCoeffs[i] = w
                    windowSum += w
                }
            }
            WindowType.BLACKMAN_HARRIS -> {
                val a0 = 0.35875f
                val a1 = 0.48829f
                val a2 = 0.14128f
                val a3 = 0.01168f
                for (i in 0 until n) {
                    val angle = 2f * PI.toFloat() * i / (n - 1)
                    val w = a0 - a1 * cos(angle) + a2 * cos(2f * angle) - a3 * cos(3f * angle)
                    windowCoeffs[i] = w
                    windowSum += w
                }
            }
            WindowType.FLAT_TOP -> {
                val a0 = 0.21557895f
                val a1 = 0.41663158f
                val a2 = 0.277263158f
                val a3 = 0.083578947f
                val a4 = 0.006947368f
                for (i in 0 until n) {
                    val angle = 2f * PI.toFloat() * i / (n - 1)
                    val w = a0 - a1 * cos(angle) + a2 * cos(2f * angle) - a3 * cos(3f * angle) + a4 * cos(4f * angle)
                    windowCoeffs[i] = w
                    windowSum += w
                }
            }
        }
    }

    // Direct Radix-2 Cooley-Tukey FFT implementation
    fun analyze(samples: FloatArray, size: Int) {
        // Feed new samples to target circular history buffer
        for (i in 0 until size) {
            inputHistory[historyWriteIndex] = samples[i]
            historyWriteIndex = (historyWriteIndex + 1) % inputHistory.size
        }

        val n = fftSize
        val re = FloatArray(n)
        val im = FloatArray(n)

        val startIndex = (historyWriteIndex - n + inputHistory.size) % inputHistory.size
        // Apply window function and copy to real part from sliding window
        for (i in 0 until n) {
            val histIdx = (startIndex + i) % inputHistory.size
            re[i] = inputHistory[histIdx] * windowCoeffs[i]
            im[i] = 0.0f
        }

        // Cooley-Tukey inplace processing
        // 1. Bit-reversal reordering
        for (i in 0 until n) {
            val rev = revTable[i]
            if (i < rev) {
                var temp = re[i]
                re[i] = re[rev]
                re[rev] = temp

                temp = im[i]
                im[i] = im[rev]
                im[rev] = temp
            }
        }

        // 2. Butterfly stages
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val step = n / len
            for (i in 0 until n step len) {
                for (j in 0 until halfLen) {
                    val k = i + j
                    val m = k + halfLen
                    val tr = re[m] * cosTable[j * step] - im[m] * sinTable[j * step]
                    val ti = re[m] * sinTable[j * step] + im[m] * cosTable[j * step]
                    
                    re[m] = re[k] - tr
                    im[m] = im[k] - ti
                    re[k] += tr
                    im[k] += ti
                }
            }
            len = len shl 1
        }

        // 3. Compute half-spectrum magnitudes with exponential decay smoothing
        val norm = 2.0f / windowSum
        val decay = decayFactor
        val minMagnitude = 1e-7f

        for (i in 0 until n / 2) {
            val r = re[i] * norm
            val mVal = im[i] * norm
            var mag = sqrt(r * r + mVal * mVal)
            if (mag < minMagnitude) mag = minMagnitude

            // Smooth decay
            val oldMag = magnitudes[i]
            val newMag = if (mag > oldMag) {
                mag
            } else {
                oldMag * decay + mag * (1f - decay)
            }
            magnitudes[i] = newMag
        }

        // Interpolate into logarithmic buckets
        interpolateLogarithmic(100)
        compute31OctaveBands()
    }

    private fun compute31OctaveBands() {
        val fs = 48000.0
        val n = fftSize
        val k = 3.0 // 1/3 Octave Standard
        
        for (b in 0 until 31) {
            val f = standard31OctaveFreqs[b]
            
            // 1/3 octave band bounds
            val fStart = f * 2.0.pow(-1.0 / (2.0 * k))
            val fEnd = f * 2.0.pow(1.0 / (2.0 * k))
            
            val binStart = (fStart * n / fs).toInt().coerceIn(0, n / 2 - 1)
            val binEnd = (fEnd * n / fs).toInt().coerceIn(0, n / 2 - 1)
            
            val mag = if (binEnd > binStart) {
                var sumSq = 0f
                for (i in binStart..binEnd) {
                    val m = magnitudes[i]
                    sumSq += m * m
                }
                sqrt(sumSq / (binEnd - binStart + 1))
            } else {
                val binIdxDouble = f * n / fs
                val binIdx = binIdxDouble.toInt().coerceIn(0, n / 2 - 2)
                val frac = (binIdxDouble - binIdx).toFloat()
                magnitudes[binIdx] * (1f - frac) + magnitudes[binIdx + 1] * frac
            }
            
            var db = 20f * log10(mag.coerceAtLeast(1e-12f))
            if (db < -120f) db = -120f
            octave31Bins[b] = db
        }
    }

    private fun interpolateLogarithmic(numBuckets: Int) {
        if (logMagnitudes.size != numBuckets) {
            logMagnitudes = FloatArray(numBuckets) { -120f }
        }

        val fs = 48000.0 // Standard design rate
        val n = fftSize

        val k = when (mode) {
            FftMode.NORMAL -> 12.0
            FftMode.PRECISION, FftMode.VINYL -> 24.0
        }

        val fMin = 20.0
        val fMax = if (mode == FftMode.VINYL) 16000.0 else 20000.0

        for (b in 0 until numBuckets) {
            val ratio = b.toDouble() / (numBuckets - 1)
            
            val f = if (mode == FftMode.VINYL) {
                getVinylFrequency(ratio)
            } else {
                fMin * (fMax / fMin).pow(ratio)
            }

            // Apply 1/k octave band smoothing
            val fStart = f * 2.0.pow(-1.0 / (2.0 * k))
            val fEnd = f * 2.0.pow(1.0 / (2.0 * k))

            val binStart = (fStart * n / fs).toInt().coerceIn(0, n / 2 - 1)
            val binEnd = (fEnd * n / fs).toInt().coerceIn(0, n / 2 - 1)

            val mag = if (binEnd > binStart) {
                var sumSq = 0f
                for (i in binStart..binEnd) {
                    val m = magnitudes[i]
                    sumSq += m * m
                }
                sqrt(sumSq / (binEnd - binStart + 1))
            } else {
                // fallback to interpolation if bin span is too narrow
                val binIdxDouble = f * n / fs
                val binIdx = binIdxDouble.toInt().coerceIn(0, n / 2 - 2)
                val frac = (binIdxDouble - binIdx).toFloat()
                magnitudes[binIdx] * (1f - frac) + magnitudes[binIdx + 1] * frac
            }

            var db = 20f * log10(mag.coerceAtLeast(1e-12f))
            if (db < -120f) db = -120f
            logMagnitudes[b] = db
        }
    }

    private fun getVinylFrequency(r: Double): Double {
        return when {
            r < 0.1 -> {
                val t = r / 0.1
                20.0 * (40.0 / 20.0).pow(t)
            }
            r < 0.4 -> {
                val t = (r - 0.1) / 0.3
                40.0 * (300.0 / 40.0).pow(t)
            }
            r < 0.6 -> {
                val t = (r - 0.4) / 0.2
                300.0 * (4000.0 / 300.0).pow(t)
            }
            r < 0.9 -> {
                val t = (r - 0.6) / 0.3
                4000.0 * (12000.0 / 4000.0).pow(t)
            }
            else -> {
                val t = (r - 0.9) / 0.1
                12000.0 * (16000.0 / 12000.0).pow(t)
            }
        }
    }

    fun getSubBassEnergy(): Float {
        val currentMagnitudes = magnitudes
        val currentFftSize = currentMagnitudes.size * 2
        if (currentMagnitudes.isEmpty()) return -120f
        
        val binMin = (20.0 * currentFftSize / 48000.0).toInt().coerceIn(0, currentMagnitudes.size - 1)
        val binMax = (60.0 * currentFftSize / 48000.0).toInt().coerceIn(binMin, currentMagnitudes.size - 1)
        var sum = 0f
        for (i in binMin..binMax) {
            sum += currentMagnitudes[i]
        }
        val db = 20f * log10(sum / (binMax - binMin + 1).coerceAtLeast(1))
        return if (db < -120f) -120f else db
    }

    fun getHarshnessZoneEnergy(): Float {
        val currentMagnitudes = magnitudes
        val currentFftSize = currentMagnitudes.size * 2
        if (currentMagnitudes.isEmpty()) return -120f
        
        val binMin = (2000.0 * currentFftSize / 48000.0).toInt().coerceIn(0, currentMagnitudes.size - 1)
        val binMax = (5000.0 * currentFftSize / 48000.0).toInt().coerceIn(binMin, currentMagnitudes.size - 1)
        var sum = 0f
        for (i in binMin..binMax) {
            sum += currentMagnitudes[i]
        }
        val db = 20f * log10(sum / (binMax - binMin + 1).coerceAtLeast(1))
        return if (db < -120f) -120f else db
    }

    fun getSibilanceZoneEnergy(): Float {
        val currentMagnitudes = magnitudes
        val currentFftSize = currentMagnitudes.size * 2
        if (currentMagnitudes.isEmpty()) return -120f
        
        val binMin = (5000.0 * currentFftSize / 48000.0).toInt().coerceIn(0, currentMagnitudes.size - 1)
        val binMax = (10000.0 * currentFftSize / 48000.0).toInt().coerceIn(binMin, currentMagnitudes.size - 1)
        var sum = 0f
        for (i in binMin..binMax) {
            sum += currentMagnitudes[i]
        }
        val db = 20f * log10(sum / (binMax - binMin + 1).coerceAtLeast(1))
        return if (db < -120f) -120f else db
    }

    fun detectResonances(): Pair<Float, Float> {
        val fs = 48000.0
        var maxPeakVal = 0f
        var maxPeakFreq = 0f
        val currentMagnitudes = magnitudes
        val sizeLimit = currentMagnitudes.size
        
        if (sizeLimit > 40) {
            for (i in 20 until sizeLimit - 20) {
                val centerVal = currentMagnitudes[i]
                if (centerVal < 0.001f) continue

                var backgroundSum = 0f
                for (j in -10..10) {
                    if (j != 0) {
                        val idx = i + j
                        if (idx in 0 until sizeLimit) {
                            backgroundSum += currentMagnitudes[idx]
                        }
                    }
                }
                val localAverage = backgroundSum / 20f
                
                if (centerVal > localAverage * 2.8f) { 
                    val freq = (i * fs / (sizeLimit * 2)).toFloat()
                    if (centerVal > maxPeakVal) {
                        maxPeakVal = centerVal
                        maxPeakFreq = freq
                    }
                }
            }
        }
        val db = if (maxPeakVal <= 1e-7f) -120f else 20f * log10(maxPeakVal)
        return Pair(maxPeakFreq, db)
    }
}
