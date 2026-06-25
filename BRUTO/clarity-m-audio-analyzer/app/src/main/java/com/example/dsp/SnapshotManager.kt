package com.example.dsp

import android.content.Context
import java.io.File
import java.io.FileWriter

data class AudioSnapshot(
    val id: String,
    val name: String,
    val timestamp: Long,
    val momentary: Float,
    val shortTerm: Float,
    val integrated: Float,
    val lra: Float,
    val truePeak: Float,
    val width: Float,
    val codec: String
)

data class HistoricLoudnessLog(
    val timestamp: Long,
    val momentary: Float,
    val shortTerm: Float,
    val integrated: Float,
    val truePeak: Float
)

class SnapshotManager(private val context: Context) {
    private val _snapshots = mutableListOf<AudioSnapshot>()
    val snapshots: List<AudioSnapshot> get() = _snapshots

    private val _historicLogs = mutableListOf<HistoricLoudnessLog>()
    val historicLogs: List<HistoricLoudnessLog> get() = _historicLogs

    var referenceSnapshot: AudioSnapshot? = null
    var isAbComparing = false

    init {
        // Pre-initialize with some reference preset snapshots matching standards for testing
        _snapshots.add(
            AudioSnapshot(
                id = "ref_spotify",
                name = "Spotify Mastering Limit",
                timestamp = System.currentTimeMillis() - 600000,
                momentary = -13.8f,
                shortTerm = -14.1f,
                integrated = -14.0f,
                lra = 5.6f,
                truePeak = -1.0f,
                width = 38.0f,
                codec = "LDAC"
            )
        )
        _snapshots.add(
            AudioSnapshot(
                id = "ref_ebu",
                name = "Broadcast Reference EBU",
                timestamp = System.currentTimeMillis() - 300000,
                momentary = -23.1f,
                shortTerm = -22.9f,
                integrated = -23.0f,
                lra = 8.4f,
                truePeak = -2.0f,
                width = 41.5f,
                codec = "aptX HD"
            )
        )
    }

    fun takeSnapshot(state: MeterState, name: String): AudioSnapshot {
        val snapshot = AudioSnapshot(
            id = System.currentTimeMillis().toString(),
            name = name,
            timestamp = System.currentTimeMillis(),
            momentary = state.momentaryLufs,
            shortTerm = state.shortTermLufs,
            integrated = state.integratedLufs,
            lra = state.lra,
            truePeak = state.truePeakMax,
            width = state.stereoWidth,
            codec = state.activeCodecName
        )
        _snapshots.add(snapshot)
        return snapshot
    }

    fun deleteSnapshot(id: String) {
        _snapshots.removeAll { it.id == id }
        if (referenceSnapshot?.id == id) {
            referenceSnapshot = null
            isAbComparing = false
        }
    }

    fun setAsReference(snapshot: AudioSnapshot?) {
        referenceSnapshot = snapshot
        isAbComparing = snapshot != null
    }

    fun logMetricHistory(state: MeterState) {
        if (_historicLogs.size > 1500) { // Limit history elements counts roughly
            _historicLogs.removeAt(0)
        }
        _historicLogs.add(
            HistoricLoudnessLog(
                timestamp = System.currentTimeMillis(),
                momentary = state.momentaryLufs,
                shortTerm = state.shortTermLufs,
                integrated = state.integratedLufs,
                truePeak = state.truePeakMax
            )
        )
    }

    fun clearHistory() {
        _historicLogs.clear()
    }

    fun exportHistoryToCsv(): String? {
        if (_historicLogs.isEmpty()) return null
        
        return try {
            val file = File(context.cacheDir, "clarity_audio_meter_history.csv")
            val writer = FileWriter(file)
            writer.write("Timestamp,Momentary LUFS,ShortTerm LUFS,Integrated LUFS,TruePeak dBTP\n")
            for (log in _historicLogs) {
                writer.write("${log.timestamp},${log.momentary},${log.shortTerm},${log.integrated},${log.truePeak}\n")
            }
            writer.close()
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
