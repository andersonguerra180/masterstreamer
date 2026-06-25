package com.seuapp.clarityreceiver

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ─────────────────────────────────────────────────────────────────────────────
//  MeterPacket — espelho EXATO do struct C++ do plugin
//  32 bytes, little-endian
//
//  Offset  Tipo      Campo
//  0       uint32    magic       (deve ser 0x4D455445 = "METE")
//  4       uint8     version     (deve ser 1)
//  5       uint8     numChannels (2)
//  6       uint16    sequence
//  8       float32   peakL       (dBFS)
//  12      float32   rmsL        (dBFS)
//  16      float32   lufsL       (LUFS)
//  20      float32   peakR       (dBFS)
//  24      float32   rmsR        (dBFS)
//  28      float32   lufsR       (LUFS)
// ─────────────────────────────────────────────────────────────────────────────
data class MeterPacket(
    val sequence:    Int,
    val peakL:       Float,   // dBFS  ex: -6.0
    val rmsL:        Float,   // dBFS  ex: -18.0
    val lufsL:       Float,   // LUFS  ex: -23.0
    val peakR:       Float,
    val rmsR:        Float,
    val lufsR:       Float,
    val numChannels: Int = 2
)

// ─────────────────────────────────────────────────────────────────────────────
//  MeterReceiver
//  Uso:
//    val receiver = MeterReceiver(port = 50005)
//    receiver.onPacket = { pkt -> /* atualiza seu UI aqui */ }
//    receiver.start()
//    // ...
//    receiver.stop()
// ─────────────────────────────────────────────────────────────────────────────
class MeterReceiver(
    private val port: Int = 50005
) {
    companion object {
        private const val TAG           = "MeterReceiver"
        private const val MAGIC         = 0x4D455445.toInt()   // "METE"
        private const val VERSION       = 1.toByte()
        private const val PACKET_SIZE   = 32
        private const val SILENCE_DB    = -120.0f
    }

    // Callback chamado na main thread com cada pacote válido recebido
    var onPacket:      ((MeterPacket) -> Unit)? = null

    // Callback chamado quando a conexão muda de estado
    var onConnected:   ((Boolean) -> Unit)?     = null

    // Callback para erros (opcional)
    var onError:       ((String) -> Unit)?      = null

    // ── Estado ────────────────────────────────────────────────────────────────
    private var socket:    DatagramSocket? = null
    private var job:       Job?            = null
    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastSeq    = -1
    private var lostPackets = 0

    @Volatile var isRunning = false
        private set

    // ── Início / parada ───────────────────────────────────────────────────────
    fun start() {
        if (isRunning) return
        isRunning = true
        lostPackets = 0
        lastSeq = -1

        job = scope.launch {
            try {
                // Bind em todas as interfaces (0.0.0.0) para receber broadcast e unicast
                socket = DatagramSocket(port).apply {
                    soTimeout    = 3000          // timeout de 3s → detecta desconexão
                    reuseAddress = true
                }

                Log.d(TAG, "Escutando na porta $port")
                notifyConnected(false) // ainda não recebeu nada

                val buf    = ByteArray(PACKET_SIZE)
                val dgram  = DatagramPacket(buf, buf.size)

                while (isActive && isRunning) {
                    try {
                        socket!!.receive(dgram)

                        if (dgram.length == PACKET_SIZE) {
                            parsePacket(buf)?.let { pkt ->
                                checkSequence(pkt.sequence)
                                withContext(Dispatchers.Main) {
                                    onPacket?.invoke(pkt)
                                }
                            }
                        } else {
                            Log.w(TAG, "Pacote com tamanho inesperado: ${dgram.length}")
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // 3s sem pacote → sinaliza desconexão
                        Log.w(TAG, "Timeout — plugin desconectado ou parado")
                        withContext(Dispatchers.Main) {
                            onConnected?.invoke(false)
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Erro no receiver: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError?.invoke(e.message ?: "Erro desconhecido")
                    onConnected?.invoke(false)
                }
            } finally {
                socket?.close()
                socket = null
                isRunning = false
                Log.d(TAG, "Receiver encerrado")
            }
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        socket?.close()
        socket = null
        notifyConnected(false)
    }

    // ── Parser do pacote ──────────────────────────────────────────────────────
    private fun parsePacket(raw: ByteArray): MeterPacket? {
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        val magic   = bb.int
        val version = bb.get()
        val nCh     = bb.get().toInt() and 0xFF
        val seq     = bb.short.toInt() and 0xFFFF

        // Valida assinatura
        if (magic != MAGIC) {
            Log.w(TAG, "Magic inválido: 0x${magic.toString(16)}")
            return null
        }
        if (version != VERSION) {
            Log.w(TAG, "Versão desconhecida: $version")
            return null
        }

        val peakL = bb.float
        val rmsL  = bb.float
        val lufsL = bb.float
        val peakR = bb.float
        val rmsR  = bb.float
        val lufsR = bb.float

        // Garante conectado na primeira recepção válida
        if (lastSeq == -1) {
            scope.launch(Dispatchers.Main) { onConnected?.invoke(true) }
        }

        return MeterPacket(
            sequence    = seq,
            peakL       = peakL.coerceIn(SILENCE_DB, 3.0f),
            rmsL        = rmsL .coerceIn(SILENCE_DB, 3.0f),
            lufsL       = lufsL.coerceIn(SILENCE_DB, 3.0f),
            peakR       = peakR.coerceIn(SILENCE_DB, 3.0f),
            rmsR        = rmsR .coerceIn(SILENCE_DB, 3.0f),
            lufsR       = lufsR.coerceIn(SILENCE_DB, 3.0f),
            numChannels = nCh
        )
    }

    // ── Detecção de pacotes perdidos ──────────────────────────────────────────
    private fun checkSequence(seq: Int) {
        if (lastSeq >= 0) {
            val expected = (lastSeq + 1) and 0xFFFF
            if (seq != expected) {
                val lost = ((seq - lastSeq - 1) and 0xFFFF)
                lostPackets += lost
                Log.w(TAG, "Pacotes perdidos: $lost (total: $lostPackets)")
            }
        }
        lastSeq = seq
    }

    private fun notifyConnected(state: Boolean) {
        scope.launch(Dispatchers.Main) { onConnected?.invoke(state) }
    }

    // ── Diagnóstico ───────────────────────────────────────────────────────────
    fun getLostPackets() = lostPackets
    fun getLastSequence() = lastSeq
}


// ─────────────────────────────────────────────────────────────────────────────
//  Exemplo de uso numa Activity/Fragment:
// ─────────────────────────────────────────────────────────────────────────────
/*

class MeterActivity : AppCompatActivity() {

    private val receiver = MeterReceiver(port = 50005)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        receiver.onConnected = { connected ->
            statusView.text = if (connected) "● CONECTADO" else "● AGUARDANDO..."
        }

        receiver.onPacket = { pkt ->
            // Atualiza suas barras de meter aqui.
            // Chamado na main thread, safe para UI.
            meterViewL.setPeak(pkt.peakL)
            meterViewL.setRms(pkt.rmsL)
            meterViewR.setPeak(pkt.peakR)
            meterViewR.setRms(pkt.rmsR)
            lufsLabel.text = "${pkt.lufsL.roundToInt()} LUFS"
        }

        receiver.onError = { msg ->
            Log.e("App", "Erro receiver: $msg")
        }
    }

    override fun onStart()  { super.onStart();  receiver.start() }
    override fun onStop()   { super.onStop();   receiver.stop()  }
}

*/
