package com.example.dsp

data class MeterState(
    val momentaryLufs: Float = -70f,
    val shortTermLufs: Float = -70f,
    val integratedLufs: Float = -70f,
    val lra: Float = 0f,
    val truePeakL: Float = -150f,
    val truePeakR: Float = -150f,
    val truePeakMax: Float = -150f,
    val truePeakHold: Float = -150f,
    val rmsL: Float = -120f,
    val rmsR: Float = -120f,
    val rmsSum: Float = -120f,
    val crestFactor: Float = 0f,
    val phaseCorrelation: Float = 0f,
    val midLevelDb: Float = -120f,
    val sideLevelDb: Float = -120f,
    val stereoWidth: Float = 0f,
    val sideDominance: String = "C",
    val monoFoldDownLossDb: Float = 0f,
    val activeCodecName: String = "LDAC",
    val activeSampleRateHz: Int = 48000,
    val activeBitDepth: Int = 24,
    val estimatedBitrateKbps: Int = 990,
    val bluetoothLatencyMs: Int = 150,
    val packetLossRate: Float = 0f,
    val subBassDb: Float = -120f,
    val harshnessDb: Float = -120f,
    val sibilanceDb: Float = -120f,
    val resonanceFreq: Float = 0f,
    val resonanceDb: Float = -120f,
    val clipCount: Int = 0,
    val bufferSamplesL: FloatArray = FloatArray(512),
    val bufferSamplesR: FloatArray = FloatArray(512),
    val logSpectrumBins: FloatArray = FloatArray(100) { -120f },
    val octave31Bins: FloatArray = FloatArray(31) { -120f },
    val isUdpActive: Boolean = false,
    val scopeData: FloatArray = FloatArray(128) { 0f }
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MeterState

        if (momentaryLufs != other.momentaryLufs) return false
        if (shortTermLufs != other.shortTermLufs) return false
        if (integratedLufs != other.integratedLufs) return false
        if (lra != other.lra) return false
        if (truePeakL != other.truePeakL) return false
        if (truePeakR != other.truePeakR) return false
        if (truePeakMax != other.truePeakMax) return false
        if (truePeakHold != other.truePeakHold) return false
        if (rmsL != other.rmsL) return false
        if (rmsR != other.rmsR) return false
        if (rmsSum != other.rmsSum) return false
        if (crestFactor != other.crestFactor) return false
        if (phaseCorrelation != other.phaseCorrelation) return false
        if (midLevelDb != other.midLevelDb) return false
        if (sideLevelDb != other.sideLevelDb) return false
        if (stereoWidth != other.stereoWidth) return false
        if (sideDominance != other.sideDominance) return false
        if (monoFoldDownLossDb != other.monoFoldDownLossDb) return false
        if (activeCodecName != other.activeCodecName) return false
        if (activeSampleRateHz != other.activeSampleRateHz) return false
        if (activeBitDepth != other.activeBitDepth) return false
        if (estimatedBitrateKbps != other.estimatedBitrateKbps) return false
        if (bluetoothLatencyMs != other.bluetoothLatencyMs) return false
        if (packetLossRate != other.packetLossRate) return false
        if (subBassDb != other.subBassDb) return false
        if (harshnessDb != other.harshnessDb) return false
        if (sibilanceDb != other.sibilanceDb) return false
        if (resonanceFreq != other.resonanceFreq) return false
        if (resonanceDb != other.resonanceDb) return false
        if (clipCount != other.clipCount) return false
        if (!bufferSamplesL.contentEquals(other.bufferSamplesL)) return false
        if (!bufferSamplesR.contentEquals(other.bufferSamplesR)) return false
        if (!logSpectrumBins.contentEquals(other.logSpectrumBins)) return false
        if (!octave31Bins.contentEquals(other.octave31Bins)) return false
        if (isUdpActive != other.isUdpActive) return false
        if (!scopeData.contentEquals(other.scopeData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = momentaryLufs.hashCode()
        result = 31 * result + shortTermLufs.hashCode()
        result = 31 * result + integratedLufs.hashCode()
        result = 31 * result + lra.hashCode()
        result = 31 * result + truePeakL.hashCode()
        result = 31 * result + truePeakR.hashCode()
        result = 31 * result + truePeakMax.hashCode()
        result = 31 * result + truePeakHold.hashCode()
        result = 31 * result + rmsL.hashCode()
        result = 31 * result + rmsR.hashCode()
        result = 31 * result + rmsSum.hashCode()
        result = 31 * result + crestFactor.hashCode()
        result = 31 * result + phaseCorrelation.hashCode()
        result = 31 * result + midLevelDb.hashCode()
        result = 31 * result + sideLevelDb.hashCode()
        result = 31 * result + stereoWidth.hashCode()
        result = 31 * result + sideDominance.hashCode()
        result = 31 * result + monoFoldDownLossDb.hashCode()
        result = 31 * result + activeCodecName.hashCode()
        result = 31 * result + activeSampleRateHz
        result = 31 * result + activeBitDepth
        result = 31 * result + estimatedBitrateKbps
        result = 31 * result + bluetoothLatencyMs
        result = 31 * result + packetLossRate.hashCode()
        result = 31 * result + subBassDb.hashCode()
        result = 31 * result + harshnessDb.hashCode()
        result = 31 * result + sibilanceDb.hashCode()
        result = 31 * result + resonanceFreq.hashCode()
        result = 31 * result + resonanceDb.hashCode()
        result = 31 * result + clipCount
        result = 31 * result + bufferSamplesL.contentHashCode()
        result = 31 * result + bufferSamplesR.contentHashCode()
        result = 31 * result + logSpectrumBins.contentHashCode()
        result = 31 * result + octave31Bins.contentHashCode()
        result = 31 * result + isUdpActive.hashCode()
        result = 31 * result + scopeData.contentHashCode()
        return result
    }
}
