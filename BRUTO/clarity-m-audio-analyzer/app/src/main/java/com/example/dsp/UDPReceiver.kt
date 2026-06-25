package com.example.dsp

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

class UDPReceiver {

    companion object {
        val sharedInstance = UDPReceiver()
        private const val TAG = "UDPReceiver"
        private const val MAGIC = 0x4D455445.toInt()
        private const val PACKET_SIZE = 288
        private const val SILENCE = -120f
    }

    // ── Configuração ──────────────────────────────────────────────────────────
    var port: Int = 50005
    var targetIpFilter: String = ""

    // ── StateFlows expostos ao ViewModel ──────────────────────────────────────
    private val _isActiveFlow     = MutableStateFlow(false)
    private val _isConnectedFlow  = MutableStateFlow(false)
    private val _localIpFlow      = MutableStateFlow("---.---.---.---")
    private val _rmsLFlow         = MutableStateFlow(SILENCE)
    private val _rmsRFlow         = MutableStateFlow(SILENCE)
    private val _packetCountFlow  = MutableStateFlow(0)
    private val _packetRateFlow   = MutableStateFlow(0f)
    private val _lastSenderIpFlow = MutableStateFlow("---")

    val isActiveFlow:     StateFlow<Boolean> = _isActiveFlow
    val isConnectedFlow:  StateFlow<Boolean> = _isConnectedFlow
    val localIpFlow:      StateFlow<String>  = _localIpFlow
    val rmsLFlow:         StateFlow<Float>   = _rmsLFlow
    val rmsRFlow:         StateFlow<Float>   = _rmsRFlow
    val packetCountFlow:  StateFlow<Int>     = _packetCountFlow
    val packetRateFlow:   StateFlow<Float>   = _packetRateFlow
    val lastSenderIpFlow: StateFlow<String>  = _lastSenderIpFlow

    // ── Valores em tempo real para o AudioEngine ──────────────────────────────
    @Volatile var hasRealTimePacket = false
    @Volatile var lastPeakL = SILENCE
    @Volatile var lastPeakR = SILENCE
    @Volatile var lastRmsL  = SILENCE
    @Volatile var lastRmsR  = SILENCE
    @Volatile var lastLufsL = SILENCE
    @Volatile var lastLufsR = SILENCE
    @Volatile var lastLufsShortTerm = SILENCE
    @Volatile var lastLufsMomentary = SILENCE
    @Volatile var lastSpectrumL = FloatArray(31) { SILENCE }
    @Volatile var lastSpectrumR = FloatArray(31) { SILENCE }
    @Volatile var lastScopeData = FloatArray(128) { 0f }

    // ── Fila de blocos de áudio (para AudioEngine) ────────────────────────────
    val blockQueue = java.util.concurrent.LinkedBlockingQueue<AudioBlock>(8)

    data class AudioBlock(val left: FloatArray, val right: FloatArray)

    // ── Internos ──────────────────────────────────────────────────────────────
    private var socket: DatagramSocket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var multicastLock: WifiManager.MulticastLock? = null
    private var phaseIndex = 0.0

    private var packetCount = 0
    private var lastRateCheck = System.currentTimeMillis()
    private var packetsInWindow = 0

    // ── Iniciar ───────────────────────────────────────────────────────────────
    fun startReceiver(context: Context?) {
        if (_isActiveFlow.value) return

        // Adquire MulticastLock para receber broadcasts Wi-Fi
        if (context != null) {
            try {
                val wm = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wm.createMulticastLock("ClarityUDPLock").apply {
                    setReferenceCounted(true)
                    acquire()
                }
                Log.d(TAG, "MulticastLock adquirido")
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível adquirir MulticastLock: ${e.message}")
            }
        }

        // Detecta IP local
        if (context != null) {
            try {
                val wm = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ip = wm.connectionInfo.ipAddress
                val ipStr = "%d.%d.%d.%d".format(
                    ip and 0xff, ip shr 8 and 0xff,
                    ip shr 16 and 0xff, ip shr 24 and 0xff
                )
                _localIpFlow.value = ipStr
            } catch (e: Exception) {
                _localIpFlow.value = "desconhecido"
            }
        }

        _isActiveFlow.value = true
        hasRealTimePacket = false
        packetCount = 0
        packetsInWindow = 0
        _packetCountFlow.value = 0
        _packetRateFlow.value = 0f
        _isConnectedFlow.value = false

        job = scope.launch {
            try {
                socket = DatagramSocket(port).apply {
                    soTimeout = 3000
                    reuseAddress = true
                    broadcast = true
                }
                Log.d(TAG, "Socket aberto na porta $port")

                val buf   = ByteArray(PACKET_SIZE)
                val dgram = DatagramPacket(buf, buf.size)

                while (isActive && _isActiveFlow.value) {
                    try {
                        socket!!.receive(dgram)

                        if (dgram.length == PACKET_SIZE) {
                            val senderIp = dgram.address.hostAddress ?: "?"

                            // Filtra por IP se configurado
                            if (targetIpFilter.isNotBlank() &&
                                senderIp != targetIpFilter.trim()) continue

                            parsePacket(buf, senderIp)
                        }

                    } catch (e: java.net.SocketTimeoutException) {
                        // Timeout = sem sinal, mas continua escutando
                        withContext(Dispatchers.Main) {
                            _isConnectedFlow.value = false
                            hasRealTimePacket = false
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Erro no socket: ${e.message}")
            } finally {
                socket?.close()
                socket = null
                withContext(Dispatchers.Main) {
                    _isActiveFlow.value    = false
                    _isConnectedFlow.value = false
                    hasRealTimePacket      = false
                }
            }
        }
    }

    // ── Parar ─────────────────────────────────────────────────────────────────
    fun stopReceiver() {
        job?.cancel()
        socket?.close()
        socket = null
        _isActiveFlow.value    = false
        _isConnectedFlow.value = false
        hasRealTimePacket      = false

        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao liberar MulticastLock: ${e.message}")
        }
        multicastLock = null

        Log.d(TAG, "Receiver parado")
    }

    // ── Parser ────────────────────────────────────────────────────────────────
    private suspend fun parsePacket(raw: ByteArray, senderIp: String) {
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        val magic   = bb.int
        val version = bb.get()
        val numChannels = bb.get()            // Offset 5
        val sequence = bb.short               // Offset 6

        if (magic != MAGIC) return            // Descarta ruído de rede

        val peakL = bb.float.coerceIn(-120f, 10f) // offset 8
        val rmsL  = bb.float.coerceIn(-120f, 10f) // offset 12
        val lufsShortTerm = bb.float.coerceIn(-120f, 10f) // offset 16
        val peakR = bb.float.coerceIn(-120f, 10f) // offset 20
        val rmsR  = bb.float.coerceIn(-120f, 10f) // offset 24
        val lufsMomentary = bb.float.coerceIn(-120f, 10f) // offset 28

        val specL = FloatArray(31)
        for (i in 0 until 31) {
            specL[i] = bb.float.coerceIn(-120f, 10f)
        }

        val specR = FloatArray(31)
        for (i in 0 until 31) {
            specR[i] = bb.float.coerceIn(-120f, 10f)
        }

        val scopeX = bb.float // offset 280
        val scopeY = bb.float // offset 284

        // Shift and update the 128-float scope data history (64 pairs)
        val newScope = lastScopeData.clone()
        System.arraycopy(newScope, 2, newScope, 0, 126)
        newScope[126] = scopeX
        newScope[127] = scopeY

        // Atualiza valores voláteis (lidos pelo AudioEngine no audio thread)
        lastPeakL = peakL
        lastPeakR = peakR
        lastRmsL  = rmsL
        lastRmsR  = rmsR
        lastLufsL = lufsShortTerm
        lastLufsR = lufsMomentary
        lastLufsShortTerm = lufsShortTerm
        lastLufsMomentary = lufsMomentary
        lastSpectrumL = specL
        lastSpectrumR = specR
        lastScopeData = newScope
        hasRealTimePacket = true

        // Atualiza contadores e taxa
        packetCount++
        packetsInWindow++
        val now = System.currentTimeMillis()
        val elapsed = now - lastRateCheck
        if (elapsed >= 1000L) {
            val rate = packetsInWindow * 1000f / elapsed
            lastRateCheck   = now
            packetsInWindow = 0
            withContext(Dispatchers.Main) {
                _packetRateFlow.value  = rate
                _packetCountFlow.value = packetCount
                _lastSenderIpFlow.value = senderIp
                _isConnectedFlow.value  = true
                _rmsLFlow.value = rmsL
                _rmsRFlow.value = rmsR
            }
        }
    }
}
