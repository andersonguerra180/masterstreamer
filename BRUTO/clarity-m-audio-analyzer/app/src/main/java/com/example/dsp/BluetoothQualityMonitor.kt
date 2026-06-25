package com.example.dsp

import kotlin.math.*
import java.util.Random

class BluetoothQualityMonitor {
    enum class Codec(
        val displayName: String,
        val maxSampleRateKhz: Double,
        val maxBitrateKbps: Int,
        val nativeBitDepth: Int,
        val estimatedLatencyMs: Int,
        val hasPsychoacousticLimiting: Boolean
    ) {
        SBC("SBC (Low-Res)", 48.0, 328, 16, 220, true),
        AAC("AAC (HD Audio)", 44.1, 320, 16, 180, true),
        APTX("aptX (CD Quality)", 48.0, 352, 16, 120, false),
        APTX_HD("aptX HD (High-Res)", 48.0, 576, 24, 100, false),
        LDAC("LDAC (Sony Ultra-Res)", 96.0, 990, 24, 150, false),
        LHDC("LHDC (Ultra-Res)", 96.0, 900, 24, 120, false)
    }

    var activeCodec = Codec.LDAC
    var connectionStrength = 1.0f // 0.0 to 1.0
    var packetLossRate = 0.0f
    var clockDriftPpm = 2.4f
    
    private val random = Random()

    val estimatedBitrate: Int
        get() = (activeCodec.maxBitrateKbps * (0.4f + 0.6f * connectionStrength)).toInt()

    val actualBitDepth: Int
        get() = if (connectionStrength < 0.5f) {
            (activeCodec.nativeBitDepth - 4).coerceAtLeast(12)
        } else {
            activeCodec.nativeBitDepth
        }

    private var filterStateL = 0f
    private var filterStateR = 0f
    private var lossHoldSamples = 0

    fun reset() {
        connectionStrength = 1.0f
        packetLossRate = 0f
        clockDriftPpm = 2.4f
        filterStateL = 0f
        filterStateR = 0f
        lossHoldSamples = 0
    }

    fun applyCodecSimulation(left: FloatArray, right: FloatArray, size: Int) {
        val strength = connectionStrength
        val codec = activeCodec

        // 1. Roll-off emulation for SBC and low-strength AAC simulating frequency containment
        if (codec == Codec.SBC) {
            val factor = 0.65f - (strength * 0.25f)
            applyLowPassSimulation(left, right, size, factor)
        } else if (codec == Codec.AAC && strength < 0.6f) {
            applyLowPassSimulation(left, right, size, 0.25f)
        }

        // 2. Emulate Packet Loss Dropout Jitter
        if (strength < 0.95f) {
            packetLossRate = (1.0f - strength) * 0.05f // Max 5% packet loss
            applyPacketLossSim(left, right, size, packetLossRate)
        } else {
            packetLossRate = 0f
        }

        // 3. Emulate compression quantization background floor (noise addition)
        if (codec == Codec.SBC || (codec == Codec.AAC && strength < 0.5f)) {
            val noiseAmp = (1.0f - strength) * 0.0002f
            for (i in 0 until size) {
                val noise = (random.nextFloat() * 2f - 1f) * noiseAmp
                left[i] += noise
                right[i] += noise
            }
        }
    }

    private fun applyLowPassSimulation(left: FloatArray, right: FloatArray, size: Int, factor: Float) {
        for (i in 0 until size) {
            filterStateL = filterStateL * factor + left[i] * (1f - factor)
            filterStateR = filterStateR * factor + right[i] * (1f - factor)
            left[i] = filterStateL
            right[i] = filterStateR
        }
    }

    private fun applyPacketLossSim(left: FloatArray, right: FloatArray, size: Int, lossRate: Float) {
        for (i in 0 until size) {
            if (lossHoldSamples > 0) {
                left[i] = filterStateL
                right[i] = filterStateR
                lossHoldSamples--
            } else {
                if (random.nextFloat() < lossRate) {
                    lossHoldSamples = random.nextInt(40).coerceAtLeast(5)
                    left[i] = filterStateL
                    right[i] = filterStateR
                } else {
                    filterStateL = left[i]
                    filterStateR = right[i]
                }
            }
        }
    }
}
