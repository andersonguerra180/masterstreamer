package com.example.dsp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

// ─────────────────────────────────────────────────────────────────────────────
//  AudioEngine
//
//  Responsabilidades:
//    - Orquestra a fonte de áudio (UDP / Generator / Silence)
//    - Roda o pipeline DSP sem alocações no loop de áudio
//    - Expõe MeterState via StateFlow para a UI (Compose)
//
//  Fontes de dados:
//    UDP mode  → consome blocos do UDPReceiver; se não há pacote, silence
//    Generator → SignalGenerator preenche os buffers
// ─────────────────────────────────────────────────────────────────────────────
class AudioEngine {

    // ── DSP components ────────────────────────────────────────────────────────
    val loudnessMeter   = LoudnessMeter(SAMPLE_RATE)
    val truePeakMeter   = TruePeakMeter()
    val fftAnalyzer     = FftAnalyzer(FFT_SIZE)
    val stereoAnalyzer  = StereoAnalyzer()
    val signalGenerator = SignalGenerator(SAMPLE_RATE)
    val bluetoothMonitor= BluetoothQualityMonitor()
    val udpReceiver     = UDPReceiver.sharedInstance

    // ── Config (escrita antes de start(), lida no thread de áudio) ────────────
    @Volatile var isUdpMode      = true
    @Volatile var isGeneratorMode= false
    @Volatile var generatorType  = SignalGenerator.SignalType.SINE_1KHZ
    @Volatile var generatorDb    = -20f
    @Volatile var rmsWindowMs    = 300

    // ── State ─────────────────────────────────────────────────────────────────
    private val _state = MutableStateFlow(MeterState())
    val state: StateFlow<MeterState> = _state

    // ── Thread control ────────────────────────────────────────────────────────
    private val running      = AtomicBoolean(false)
    private var workerThread: Thread? = null
    private var lastStatePublishTimeMs = 0L

    // ── Buffers pré-alocados — NUNCA alocados dentro do loop ─────────────────
    private val leftBuf      = FloatArray(BLOCK_SIZE)
    private val rightBuf     = FloatArray(BLOCK_SIZE)
    // Snapshots para o StateFlow (evita expor buffers mutáveis)
    private val leftSnap     = FloatArray(BLOCK_SIZE)
    private val rightSnap    = FloatArray(BLOCK_SIZE)

    // ── RMS Sliding window (exactly 300ms at 48000Hz = 14400 samples) ─────────
    private val rmsWindowL = RmsSlidingWindow(14400)
    private val rmsWindowR = RmsSlidingWindow(14400)

    // ── Realtime re-synthesis states (UDP Mode) ────────────────────────────────
    private var synthPhase = 0.0
    private var synthBeatTime = 0.0
    private val lfoPhases = FloatArray(12) { (Math.random() * 2.0 * Math.PI).toFloat() }
    
    // Pink noise states for Voss-McCartney filter
    private var b0L = 0f; private var b1L = 0f; private var b2L = 0f; private var b3L = 0f; private var b4L = 0f; private var b5L = 0f; private var b6L = 0f
    private var b0R = 0f; private var b1R = 0f; private var b2R = 0f; private var b3R = 0f; private var b4R = 0f; private var b5R = 0f; private var b6R = 0f

    private fun nextPinkL(): Float {
        val white = (Math.random().toFloat() * 2f - 1f)
        b0L = 0.99886f * b0L + white * 0.0555179f
        b1L = 0.99332f * b1L + white * 0.0750759f
        b2L = 0.96900f * b2L + white * 0.1538520f
        b3L = 0.86650f * b3L + white * 0.3104856f
        b4L = 0.55000f * b4L + white * 0.5329522f
        b5L = -0.7616f * b5L - white * 0.0168980f
        val pink = b0L + b1L + b2L + b3L + b4L + b5L + b6L + white * 0.5362f
        b6L = white * 0.115926f
        return pink * 0.11f
    }

    private fun nextPinkR(): Float {
        val white = (Math.random().toFloat() * 2f - 1f)
        b0R = 0.99886f * b0R + white * 0.0555179f
        b1R = 0.99332f * b1R + white * 0.0750759f
        b2R = 0.96900f * b2R + white * 0.1538520f
        b3R = 0.86650f * b3R + white * 0.3104856f
        b4R = 0.55000f * b4R + white * 0.5329522f
        b5R = -0.7616f * b5R - white * 0.0168980f
        val pink = b0R + b1R + b2R + b3R + b4R + b5R + b6R + white * 0.5362f
        b6R = white * 0.115926f
        return pink * 0.11f
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────────
    fun start(ctx: Context) {
        if (running.getAndSet(true)) return

        resetDsp()

        workerThread = Thread(::audioLoop, "ClarityAudioDspThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { udpReceiver.stopReceiver() }
        workerThread?.let {
            it.interrupt()
            runCatching { it.join(500) }
        }
        workerThread = null
    }

    fun resetHoldPeak() {
        truePeakMeter.truePeakHold = -150f
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Audio loop — roda no workerThread
    //  Regra absoluta: ZERO alocações aqui.
    // ─────────────────────────────────────────────────────────────────────────
    private fun audioLoop() {
        var lastLoopTimeMs = System.currentTimeMillis()
        while (running.get()) {
            val t0 = System.currentTimeMillis()
            val dtMs = (t0 - lastLoopTimeMs).coerceAtLeast(1L)
            lastLoopTimeMs = t0

            // 1. Preenche buffers conforme a fonte ativa
            fillBuffers()

            // 2. Bluetooth codec simulation (modifica buffers in-place)
            bluetoothMonitor.applyCodecSimulation(leftBuf, rightBuf, BLOCK_SIZE)

            // 3. DSP pipeline
            val isUdpWithPacket = isUdpMode && udpReceiver.hasRealTimePacket
            val (tpL, tpR) = if (isUdpWithPacket) {
                // Pre-calculated LUFS are read directly from UDP packet
                truePeakMeter.process(leftBuf, rightBuf, BLOCK_SIZE)
            } else {
                loudnessMeter.process(leftBuf, rightBuf, BLOCK_SIZE)
                truePeakMeter.process(leftBuf, rightBuf, BLOCK_SIZE)
            }
            fftAnalyzer.analyze(leftBuf, BLOCK_SIZE)
            stereoAnalyzer.process(leftBuf, rightBuf, BLOCK_SIZE)
            updateRms()

            // 4. Métricas finais: UDP tem prioridade se ativo e com dados
            val udp = udpMetricsOrNull()
            val metrics = buildMetrics(tpL, tpR, udp)

            // 5. Snapshot dos buffers (cópia única por ciclo para o StateFlow)
            leftBuf.copyInto(leftSnap)
            rightBuf.copyInto(rightSnap)

            // 6. Publica estado (limitado a max ~33 FPS para otimização da UI em Compose)
            val now = System.currentTimeMillis()
            if (now - lastStatePublishTimeMs >= 30L) {
                lastStatePublishTimeMs = now
                _state.value = buildState(metrics)
            }

            // 7. Throttle: alvo ~10ms por bloco (512 @ 48kHz ≈ 10.67ms)
            val sleep = LOOP_TARGET_MS - (System.currentTimeMillis() - t0)
            if (sleep > 0) runCatching { Thread.sleep(sleep) }
                .onFailure { if (it is InterruptedException) return }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  fillBuffers — fonte de áudio
    // ─────────────────────────────────────────────────────────────────────────
    private fun fillBuffers() {
        when {
            isGeneratorMode -> signalGenerator.fillBuffer(
                leftBuf, rightBuf, BLOCK_SIZE, generatorType, generatorDb
            )
            isUdpMode -> {
                generateUdpSyntheticBuffer()
            }
            else -> {
                leftBuf.fill(0f)
                rightBuf.fill(0f)
            }
        }
    }

    private fun generateUdpSyntheticBuffer() {
        val lastRmsL = udpReceiver.lastRmsL
        val lastRmsR = udpReceiver.lastRmsR
        
        val ampL = 10f.pow(lastRmsL / 20f).coerceIn(0f, 2f)
        val ampR = 10f.pow(lastRmsR / 20f).coerceIn(0f, 2f)

        // If very quiet, fill with silence to keep low floor quiet
        if (lastRmsL < -100f && lastRmsR < -100f) {
            leftBuf.fill(0f)
            rightBuf.fill(0f)
            return
        }

        val lufsL = udpReceiver.lastLufsL
        val lufsR = udpReceiver.lastLufsR
        val peakL = udpReceiver.lastPeakL
        val peakR = udpReceiver.lastPeakR

        val correlationCoeff = if (ampL > 0f && ampR > 0f) {
            val diff = abs(lufsL - lufsR)
            (1.0 - (diff / 30.0).coerceIn(0.0, 1.0)).toFloat()
        } else {
            1.0f
        }

        // Crest factor tracking to generate transient/rhythm pulses
        val crestL = (peakL - lastRmsL).coerceIn(0f, 30f)
        val crestR = (peakR - lastRmsR).coerceIn(0f, 30f)
        val crestAvg = (crestL + crestR) * 0.5f
        
        // High crest factor means lots of percussive/transient energy, low means compact pad
        val percStrength = ((crestAvg - 8.0f) / 10f).coerceIn(0f, 1f)

        val sampleRate = SAMPLE_RATE
        val dt = 1.0 / sampleRate

        // Modulator speeds
        val lfoSpeed = floatArrayOf(0.08f, 0.13f, 0.19f, 0.27f, 0.38f, 0.49f, 0.61f, 0.77f, 0.93f, 1.15f, 1.48f, 1.92f)
        val synthFreqs = floatArrayOf(
            45f, 90f, 160f, 280f, 440f, 750f, 1200f, 2400f, 4500f, 7500f, 12000f, 17000f
        )

        for (i in 0 until BLOCK_SIZE) {
            // Rhythmic time advance
            synthBeatTime += dt
            if (synthBeatTime >= 2.0) synthBeatTime -= 2.0

            // Percussive envelopes
            val kickPhase = (synthBeatTime * 2.1) % 1.0 // ~126 BPM
            val kickEnv = exp(-9.0 * kickPhase).toFloat()

            val snarePhase = (synthBeatTime * 1.05 + 0.4) % 1.0
            val snareEnv = exp(-11.0 * snarePhase).toFloat()

            val hatPhase = (synthBeatTime * 4.2) % 1.0
            val hatEnv = exp(-16.0 * hatPhase).toFloat()

            // Update LFOs
            for (b in 0 until 12) {
                lfoPhases[b] = (lfoPhases[b] + lfoSpeed[b] * 2.0 * Math.PI * dt).toFloat()
                if (lfoPhases[b] >= 2.0 * Math.PI) {
                    lfoPhases[b] -= (2.0 * Math.PI).toFloat()
                }
            }

            var sumL = 0f
            var sumR = 0f

            for (b in 0 until 12) {
                var bandWeight = 0.3f + 0.7f * sin(lfoPhases[b])
                
                // Overlay rhythmic envelopes under percussive strength
                if (percStrength > 0.05f) {
                    val percMod = when (b) {
                        0 -> kickEnv // Sub-bass pulse
                        1 -> 0.7f * kickEnv + 0.3f * snareEnv
                        2 -> 0.4f * kickEnv + 0.6f * snareEnv
                        3, 4, 5 -> snareEnv // Snare/Mid pulse
                        8, 9 -> 0.3f * snareEnv + 0.7f * hatEnv
                        10, 11 -> hatEnv // High-hat tick
                        else -> 1f
                    }
                    bandWeight = bandWeight * (1f - percStrength) + (bandWeight * percMod * percStrength)
                }

                // Low-end remains narrower, mids/highs conform to correlation
                val bandCorrelation = if (b < 2) {
                    (correlationCoeff * 0.3f + 0.7f).coerceIn(-1f, 1f)
                } else {
                    correlationCoeff
                }
                
                val phaseOffset = 1.25f * (1f - bandCorrelation)
                
                // Direct sine wave phase
                val phaseL = 2.0 * Math.PI * synthFreqs[b] * synthPhase
                val phaseR = phaseL + phaseOffset

                sumL += sin(phaseL).toFloat() * bandWeight
                sumR += sin(phaseR).toFloat() * bandWeight
            }

            // Smooth undulating noise-floor (high-frequency friction)
            val noiseUndulation = 0.6f + 0.4f * sin(synthPhase * 4.0).toFloat()
            val noiseL = nextPinkL() * 0.16f * noiseUndulation
            val noiseR = nextPinkR() * 0.16f * noiseUndulation

            val sinePartL = sumL * 0.14f
            val sinePartR = sumR * 0.14f

            val rawL = (sinePartL + noiseL) * ampL
            val rawR = (sinePartR + noiseR) * ampR

            leftBuf[i] = rawL.coerceIn(-2f, 2f)
            rightBuf[i] = rawR.coerceIn(-2f, 2f)

            synthPhase += dt
            if (synthPhase >= 10.0) synthPhase -= 10.0 // prevent division precision loss
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  updateRms — exponential smoothing, sem alocações
    // ─────────────────────────────────────────────────────────────────────────
    private fun updateRms() {
        rmsWindowL.addBlock(leftBuf, BLOCK_SIZE)
        rmsWindowR.addBlock(rightBuf, BLOCK_SIZE)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Peak instantâneo — sem lambda/maxOf (zero alocação)
    // ─────────────────────────────────────────────────────────────────────────
    private fun peakLinear(buf: FloatArray): Float {
        var max = 0f
        for (i in 0 until BLOCK_SIZE) {
            val v = abs(buf[i])
            if (v > max) max = v
        }
        return max
    }

    private fun linToDb(linear: Float) =
        if (linear < 1e-7f) -120f else (20f * log10(linear)).coerceAtLeast(-120f)

    // ─────────────────────────────────────────────────────────────────────────
    //  UDP metrics — retorna null se UDP inativo ou sem dados recentes
    // ─────────────────────────────────────────────────────────────────────────
    private data class UdpMetrics(
        val peakL: Float, val peakR: Float,
        val rmsL:  Float, val rmsR:  Float,
        val lufsL: Float, val lufsR: Float
    )

    private fun udpMetricsOrNull(): UdpMetrics? {
        if (!isUdpMode || !udpReceiver.hasRealTimePacket) return null
        return UdpMetrics(
            peakL = udpReceiver.lastPeakL, peakR = udpReceiver.lastPeakR,
            rmsL  = udpReceiver.lastRmsL,  rmsR  = udpReceiver.lastRmsR,
            lufsL = udpReceiver.lastLufsL, lufsR = udpReceiver.lastLufsR
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  buildMetrics — valores finais (UDP > DSP local)
    // ─────────────────────────────────────────────────────────────────────────
    private class FinalMetrics(
        val peakL: Float, val peakR: Float,
        val rmsL:  Float, val rmsR:  Float,
        val rmsSum: Float, val crestFactor: Float,
        val lufs:  Float,
        val tpMax: Float, val tpHold: Float,
        val tpL: Float, val tpR: Float
    )

    private fun buildMetrics(tpL: Float, tpR: Float, udp: UdpMetrics?): FinalMetrics {
        val peakL: Float
        val peakR: Float
        val rmsL:  Float
        val rmsR:  Float
        val lufs:  Float
        val tpMax: Float

        if (udp != null) {
            peakL = udp.peakL
            peakR = udp.peakR
            rmsL  = udp.rmsL
            rmsR  = udp.rmsR
            lufs  = udpReceiver.lastLufsMomentary
            tpMax = max(udp.peakL, udp.peakR)

            // Atualiza hold/max do truePeakMeter via UDP
            if (tpMax > truePeakMeter.truePeakHold) truePeakMeter.truePeakHold = tpMax
            if (tpMax > truePeakMeter.truePeakMax)  truePeakMeter.truePeakMax  = tpMax
        } else {
            peakL = linToDb(peakLinear(leftBuf))
            peakR = linToDb(peakLinear(rightBuf))
            rmsL  = linToDb(rmsWindowL.getCurrentRms())
            rmsR  = linToDb(rmsWindowR.getCurrentRms())
            lufs  = loudnessMeter.momentaryLufs
            tpMax = max(tpL, tpR)
        }

        // RMS sum (power average L+R)
        val rmsLinL  = 10f.pow(rmsL / 10f)
        val rmsLinR  = 10f.pow(rmsR / 10f)
        val rmsSum   = (10f * log10((rmsLinL + rmsLinR) * 0.5f)).coerceAtLeast(-120f)

        val crest    = (tpMax - max(rmsL, rmsR)).coerceIn(0f, 30f)

        return FinalMetrics(peakL, peakR, rmsL, rmsR, rmsSum, crest, lufs,
                            tpMax, truePeakMeter.truePeakHold, tpL, tpR)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  buildState — único ponto de construção do MeterState
    // ─────────────────────────────────────────────────────────────────────────
    private fun buildState(m: FinalMetrics): MeterState {
        val (resFreq, resDb) = fftAnalyzer.detectResonances()
        val isUdpWithPacket = isUdpMode && udpReceiver.hasRealTimePacket
        return MeterState(
            momentaryLufs        = if (isUdpWithPacket) udpReceiver.lastLufsMomentary else m.lufs,
            shortTermLufs        = if (isUdpWithPacket) udpReceiver.lastLufsMomentary else loudnessMeter.shortTermLufs,
            integratedLufs       = if (isUdpWithPacket) udpReceiver.lastLufsShortTerm else loudnessMeter.integratedLufs,
            lra                  = loudnessMeter.lra,
            truePeakL            = if (isUdpWithPacket) m.peakL else m.tpL,
            truePeakR            = if (isUdpWithPacket) m.peakR else m.tpR,
            truePeakMax          = truePeakMeter.truePeakMax,
            truePeakHold         = m.tpHold,
            rmsL                 = m.rmsL,
            rmsR                 = m.rmsR,
            rmsSum               = m.rmsSum,
            crestFactor          = m.crestFactor,
            phaseCorrelation     = stereoAnalyzer.phaseCorrelation,
            midLevelDb           = stereoAnalyzer.midLevelDb,
            sideLevelDb          = stereoAnalyzer.sideLevelDb,
            stereoWidth          = stereoAnalyzer.stereoWidth,
            sideDominance        = stereoAnalyzer.sideDominance,
            monoFoldDownLossDb   = stereoAnalyzer.monoFoldDownLossDb,
            activeCodecName      = bluetoothMonitor.activeCodec.name,
            activeSampleRateHz   = SAMPLE_RATE.toInt(),
            activeBitDepth       = bluetoothMonitor.actualBitDepth,
            estimatedBitrateKbps = bluetoothMonitor.estimatedBitrate,
            bluetoothLatencyMs   = bluetoothMonitor.activeCodec.estimatedLatencyMs,
            packetLossRate       = bluetoothMonitor.packetLossRate,
            subBassDb            = fftAnalyzer.getSubBassEnergy(),
            harshnessDb          = fftAnalyzer.getHarshnessZoneEnergy(),
            sibilanceDb          = fftAnalyzer.getSibilanceZoneEnergy(),
            resonanceFreq        = resFreq,
            resonanceDb          = resDb,
            clipCount            = truePeakMeter.clipCount,
            bufferSamplesL       = leftSnap,   // snapshot pré-alocado, não clone()
            bufferSamplesR       = rightSnap,
            logSpectrumBins      = fftAnalyzer.logMagnitudes,
            octave31Bins         = if (isUdpWithPacket) {
                FloatArray(31) { i ->
                    val sourceIndex = i - 3
                    if (sourceIndex >= 0) {
                        max(udpReceiver.lastSpectrumL.getOrElse(sourceIndex) { -120f }, udpReceiver.lastSpectrumR.getOrElse(sourceIndex) { -120f })
                    } else {
                        -120f
                    }
                }
            } else {
                fftAnalyzer.octave31Bins
            },
            isUdpActive          = isUdpWithPacket,
            scopeData            = if (isUdpWithPacket) udpReceiver.lastScopeData else FloatArray(128) { 0f }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private fun resetDsp() {
        loudnessMeter.reset()
        truePeakMeter.reset()
        fftAnalyzer.setSize(FFT_SIZE)
        stereoAnalyzer.reset()
        bluetoothMonitor.reset()
        rmsWindowL.reset()
        rmsWindowR.reset()
    }

    companion object {
        const val SAMPLE_RATE    = 48000.0
        const val BLOCK_SIZE     = 512
        const val FFT_SIZE       = 4096
        const val LOOP_TARGET_MS = 10L
    }

    private class RmsSlidingWindow(val size: Int) {
        private val buffer = FloatArray(size)
        private var writeIndex = 0
        private var sumOfSquares = 0.0
        private var blockCount = 0

        fun addBlock(samples: FloatArray, blockSize: Int) {
            for (i in 0 until blockSize) {
                val sq = (samples[i] * samples[i]).toDouble()
                val oldSq = buffer[writeIndex].toDouble()
                buffer[writeIndex] = sq.toFloat()
                sumOfSquares += sq - oldSq
                writeIndex++
                if (writeIndex >= size) writeIndex = 0
            }
            
            blockCount++
            if (blockCount >= 50) {
                blockCount = 0
                var exactSum = 0.0
                for (i in 0 until size) {
                    exactSum += buffer[i].toDouble()
                }
                sumOfSquares = exactSum
            }
        }

        fun getCurrentRms(): Float {
            val mean = (sumOfSquares / size).coerceAtLeast(0.0)
            return sqrt(mean).toFloat()
        }

        fun reset() {
            buffer.fill(0f)
            writeIndex = 0
            sumOfSquares = 0.0
            blockCount = 0
        }
    }
}
