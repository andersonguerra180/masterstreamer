package com.example.dsp

import kotlin.math.*

class LoudnessMeter(val sampleRate: Double) {
    private val kFilter = KWeightingFilter(sampleRate)

    // Running window sizes in samples
    private val mWindowSamples = (0.400 * sampleRate).toInt()
    private val sWindowSamples = (3.000 * sampleRate).toInt()

    // Ring buffers for squared values of filtered signal. Allocating 3s size.
    private val lSquaredBuffer = FloatArray(sWindowSamples)
    private val rSquaredBuffer = FloatArray(sWindowSamples)
    private var writeIdx = 0

    // Running sums to avoid O(N) looping on every buffer update
    private var mRunningSumL = 0.0
    private var mRunningSumR = 0.0
    private var sRunningSumL = 0.0
    private var sRunningSumR = 0.0

    // Gated integration blocks: each block represents a 400ms chunk, overlapping by 75% (every 100ms)
    private val blockStrideSamples = (0.100 * sampleRate).toInt()
    private var strideCounter = 0
    private var blockAccumL = 0.0
    private var blockAccumR = 0.0
    private var blockAccumCount = 0

    // Historical blocks of average energy (z_i) for integrated loudness (BS.1770-4)
    private val integratedBlockEnergies = ArrayList<Double>()
    
    // Historical short-term values (3s) for LRA (Tech 3342)
    private val sLoudnessHistory = ArrayList<Double>()

    // Udp loudness history
    private val udpMomentaryEnergyHistory = ArrayList<Double>()
    private var udpStrideAccumulator = 0.0
    private var udpStrideCount = 0
    private var udpTimeAccumulatorMs = 0L

    // Outputs
    var momentaryLufs: Float = -70.0f
    var shortTermLufs: Float = -70.0f
    var integratedLufs: Float = -70.0f
    var lra: Float = 0.0f
    var truePeakMax: Float = -150.0f
    var truePeakHold: Float = -150.0f
    var clipCount: Int = 0

    fun reset() {
        kFilter.reset()
        lSquaredBuffer.fill(0f)
        rSquaredBuffer.fill(0f)
        writeIdx = 0
        mRunningSumL = 0.0
        mRunningSumR = 0.0
        sRunningSumL = 0.0
        sRunningSumR = 0.0
        strideCounter = 0
        blockAccumL = 0.0
        blockAccumR = 0.0
        blockAccumCount = 0
        integratedBlockEnergies.clear()
        sLoudnessHistory.clear()
        udpMomentaryEnergyHistory.clear()
        udpStrideAccumulator = 0.0
        udpStrideCount = 0
        udpTimeAccumulatorMs = 0L
        momentaryLufs = -70.0f
        shortTermLufs = -70.0f
        integratedLufs = -70.0f
        lra = 0.0f
        truePeakMax = -150.0f
        truePeakHold = -150.0f
        clipCount = 0
    }

    // Process a block of stereo samples (unfiltered, raw Float32 data)
    fun process(left: FloatArray, right: FloatArray, size: Int) {
        if (size == 0) return

        for (i in 0 until size) {
            val rawL = left[i].toDouble()
            val rawR = right[i].toDouble()

            // 1. K-Weighting Filter
            val (filtL, filtR) = kFilter.processStereo(rawL, rawR)

            val sqL = (filtL * filtL).toFloat()
            val sqR = (filtR * filtR).toFloat()

            // 2. Manage sliding windows via ring buffer
            val mOutIdx = (writeIdx - mWindowSamples + sWindowSamples) % sWindowSamples
            val mOutL = lSquaredBuffer[mOutIdx]
            val mOutR = rSquaredBuffer[mOutIdx]

            val sOutIdx = writeIdx
            val sOutL = lSquaredBuffer[sOutIdx]
            val sOutR = rSquaredBuffer[sOutIdx]

            // Save new squared sample in buffer
            lSquaredBuffer[writeIdx] = sqL
            rSquaredBuffer[writeIdx] = sqR

            // Update sliding sums
            mRunningSumL += sqL - mOutL
            mRunningSumR += sqR - mOutR

            sRunningSumL += sqL - sOutL
            sRunningSumR += sqR - sOutR

            // Increment write index
            writeIdx = (writeIdx + 1) % sWindowSamples

            // 3. Block accumulators for Integrated Gated Loudness
            blockAccumL += sqL
            blockAccumR += sqR
            blockAccumCount++
            strideCounter++

            // Every 100ms stride, we save the block energy
            if (strideCounter >= blockStrideSamples) {
                if (blockAccumCount > 0) {
                    val energyL = blockAccumL / blockAccumCount
                    val energyR = blockAccumR / blockAccumCount
                    val blockEnergy = energyL + energyR
                    // To prevent memory leaking, we cap historical blocks at 36000 (1 hour of audio)
                    if (integratedBlockEnergies.size > 36000) {
                        integratedBlockEnergies.removeAt(0)
                    }
                    integratedBlockEnergies.add(blockEnergy)
                }
                blockAccumL = 0.0
                blockAccumR = 0.0
                blockAccumCount = 0
                strideCounter = 0
            }
        }

        // Calculate Momentary & Short-term levels in LUFS
        momentaryLufs = energyToLufs(mRunningSumL / mWindowSamples + mRunningSumR / mWindowSamples)
        shortTermLufs = energyToLufs(sRunningSumL / sWindowSamples + sRunningSumR / sWindowSamples)

        // Keep track of Short-term loudness history for Loudness Range (LRA) calculation
        if (shortTermLufs > -70.0f) {
            sLoudnessHistory.add(shortTermLufs.toDouble())
            if (sLoudnessHistory.size > 6000) { // Limit LRA history (10 minutes of blocks)
                sLoudnessHistory.removeAt(0)
            }
        }

        // Calculate Integrated Loudness (dynamic gating BS.1770-4)
        calculateIntegratedLoudness()

        // Calculate LRA
        calculateLra()
    }

    private fun energyToLufs(energy: Double): Float {
        if (energy <= 1e-12) return -70.0f
        val lufs = -0.691 + 10.0 * log10(energy)
        return max(-70.0, lufs).toFloat()
    }

    private fun calculateIntegratedLoudness() {
        if (integratedBlockEnergies.isEmpty()) {
            integratedLufs = -70.0f
            return
        }

        // Absolute gate (-70 LUFS corresponds to energy threshold of 10.0.pow((-70.0 + 0.691)/10.0))
        val absEnergyThreshold = 10.0.pow((-70.0 + 0.691) / 10.0)
        val absGatedEnergies = integratedBlockEnergies.filter { it >= absEnergyThreshold }

        if (absGatedEnergies.isEmpty()) {
            integratedLufs = -70.0f
            return
        }

        // Relative gate: -10 LU below average energy of absGatedEnergies
        val avgAbsEnergy = absGatedEnergies.average()
        val relEnergyThreshold = avgAbsEnergy * 0.1 // -10 LU is scaling factor of 0.1 (10^(-10/10) = 0.1)

        val finalGatedEnergies = absGatedEnergies.filter { it >= relEnergyThreshold }

        if (finalGatedEnergies.isEmpty()) {
            integratedLufs = energyToLufs(avgAbsEnergy)
            return
        }

        integratedLufs = energyToLufs(finalGatedEnergies.average())
    }

    private fun calculateLra() {
        if (sLoudnessHistory.size < 20) {
            lra = 0.0f
            return
        }

        // Tech 3342 Loudness Range:
        // 1. Absolute threshold of -70 LUFS on 3-second blocks
        val absGated = sLoudnessHistory.filter { it >= -70.0 }
        if (absGated.isEmpty()) {
            lra = 0.0f
            return
        }

        // 2. Relative threshold of -20 LU below average energy of blocks exceeding -70 LUFS
        val absGatedEnergies = absGated.map { 10.0.pow((it + 0.691) / 10.0) }
        val avgAbsEnergy = absGatedEnergies.average()
        val relEnergyThreshold = avgAbsEnergy * 0.01 // -20 LU is scaling factor of 0.01 (10^(-20/10) = 0.01)
        val relDbThreshold = -0.691 + 10.0 * log10(relEnergyThreshold)

        val finalGated = absGated.filter { it >= relDbThreshold }
        if (finalGated.size < 10) {
            lra = 0.0f
            return
        }

        // 3. Compute 10th and 95th percentiles of the gated loudness values
        val sorted = finalGated.sorted()
        val idx10 = (0.10 * (sorted.size - 1)).toInt()
        val idx95 = (0.95 * (sorted.size - 1)).toInt()

        lra = (sorted[idx95] - sorted[idx10]).toFloat()
        if (lra < 0f) lra = 0f
    }

    fun processUdpLoudness(lufsL: Float, lufsR: Float, dtMs: Long) {
        val mValue = ((lufsL + lufsR) * 0.5f).coerceIn(-120f, 10f)
        momentaryLufs = mValue

        val energy = 10.0.pow((mValue + 0.691) / 10.0)
        
        udpMomentaryEnergyHistory.add(energy)
        if (udpMomentaryEnergyHistory.size > 300) {
            udpMomentaryEnergyHistory.removeAt(0)
        }
        
        if (udpMomentaryEnergyHistory.isNotEmpty()) {
            shortTermLufs = energyToLufs(udpMomentaryEnergyHistory.average())
        } else {
            shortTermLufs = -70.0f
        }

        udpStrideAccumulator += energy
        udpStrideCount++
        udpTimeAccumulatorMs += dtMs

        if (udpTimeAccumulatorMs >= 100L) {
            val avgEnergy = if (udpStrideCount > 0) udpStrideAccumulator / udpStrideCount else energy
            
            if (integratedBlockEnergies.size > 36000) {
                integratedBlockEnergies.removeAt(0)
            }
            integratedBlockEnergies.add(avgEnergy)
            calculateIntegratedLoudness()

            if (shortTermLufs > -70.0f) {
                sLoudnessHistory.add(shortTermLufs.toDouble())
                if (sLoudnessHistory.size > 6000) {
                    sLoudnessHistory.removeAt(0)
                }
            }
            calculateLra()

            udpStrideAccumulator = 0.0
            udpStrideCount = 0
            udpTimeAccumulatorMs = 0L
        }
    }
}
