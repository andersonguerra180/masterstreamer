package com.example.dsp

import kotlin.math.*

enum class MasteringProfile(
    val displayName: String,
    val targetLoudnessLufs: Float,
    val targetPeakDb: Float,
    val minCrestFactor: Float,
    val codecLimit: Float // Allowed bit depth degradation threshold
) {
    SPOTIFY("Spotify Streaming", -14.0f, -1.0f, 6.0f, 16.0f),
    APPLE_MUSIC("Apple Music Spatial", -16.0f, -1.0f, 7.0f, 16.0f),
    YOUTUBE("YouTube Compression", -14.0f, -1.0f, 6.0f, 16.0f),
    BROADCAST_EBU("Broadcast (EBU R128)", -23.0f, -2.0f, 8.0f, 16.0f),
    CLUB("Club System PA", -9.0f, -0.2f, 4.5f, 12.0f),
    VINYL_PREVIEW("Vinyl Preview Cut", -12.0f, -2.5f, 9.0f, 12.0f)
}

data class ValidationAlert(
    val level: AlertLevel,
    val category: String,
    val message: String
)

enum class AlertLevel {
    OK, WARNING, DANGER
}

class MasteringValidation {
    fun validate(state: MeterState, profile: MasteringProfile): List<ValidationAlert> {
        val alerts = mutableListOf<ValidationAlert>()

        // 1. Gated Loudness analysis
        val lufsDiff = state.integratedLufs - profile.targetLoudnessLufs
        if (abs(lufsDiff) > 1.5f) {
            val level = if (abs(lufsDiff) > 4.5f) AlertLevel.DANGER else AlertLevel.WARNING
            val hint = if (lufsDiff > 0) "atenuar" else "reforçar"
            alerts.add(ValidationAlert(
                level, 
                "Gated Loudness", 
                "Loudness integrado (${format(state.integratedLufs)} LUFS) diverge do padrão (${profile.targetLoudnessLufs} LUFS). Reduza/reforce em ${format(abs(lufsDiff))} LU para evitar penalização de ganho."
            ))
        } else {
            alerts.add(ValidationAlert(
                AlertLevel.OK, 
                "Gated Loudness", 
                "Loudness integrado (${format(state.integratedLufs)} LUFS) ideal para o padrão ${profile.displayName}."
            ))
        }

        // 2. Headroom / Clipping checks (Intersample overshoot)
        if (state.truePeakMax >= profile.targetPeakDb) {
            alerts.add(ValidationAlert(
                AlertLevel.DANGER, 
                "Intersample Peaks", 
                "True Peak (${format(state.truePeakMax)} dBTP) atinge ou supera o teto (${profile.targetPeakDb} dBTP). Risco iminente de distorção na conversão D/A."
            ))
        } else if (state.truePeakMax >= profile.targetPeakDb - 0.5f) {
            alerts.add(ValidationAlert(
                AlertLevel.WARNING, 
                "Intersample Peaks", 
                "True Peak (${format(state.truePeakMax)} dBTP) próximo ao teto recomendado de ${profile.targetPeakDb} dBTP. Reduza a saída do limiter."
            ))
        } else {
            alerts.add(ValidationAlert(
                AlertLevel.OK, 
                "Intersample Peaks", 
                "Headroom térmico de pico seguro (${format(state.truePeakMax)} dBTP)."
            ))
        }

        // 3. Dynamic squash assessment (Crest factor / Limiters)
        if (state.crestFactor < profile.minCrestFactor) {
            alerts.add(ValidationAlert(
                AlertLevel.DANGER, 
                "Dynamic Range", 
                "Fator de Cresta muito reduzido (${format(state.crestFactor)} dB). Limitação excessiva destrói impacto dos transientes de graves e bateria."
            ))
        } else if (state.crestFactor < profile.minCrestFactor + 1.5f) {
            alerts.add(ValidationAlert(
                AlertLevel.WARNING, 
                "Dynamic Range", 
                "Compressão densa (${format(state.crestFactor)} dB). Monitore perda de ar estrutural nos transientes."
            ))
        } else {
            alerts.add(ValidationAlert(
                AlertLevel.OK, 
                "Dynamic Range", 
                "Dinâmica e transientes bem representados (${format(state.crestFactor)} dB)."
            ))
        }

        // 4. Mono safety (Phase)
        if (state.phaseCorrelation < 0.05f) {
            val level = if (state.phaseCorrelation < -0.15f) AlertLevel.DANGER else AlertLevel.WARNING
            alerts.add(ValidationAlert(
                level, 
                "Mono Compatibility", 
                "Diferença de fase profunda (${format(state.phaseCorrelation)}). Risco crítico de perda acústica (cancelamento) ao converter áudio em sistemas mono."
            ))
        } else {
            alerts.add(ValidationAlert(
                AlertLevel.OK, 
                "Mono Compatibility", 
                "Fase em coerência saudável (${format(state.phaseCorrelation)}). Totalmente estável para transmissões mono."
            ))
        }

        // 5. Codec Degradation Analysis
        if (state.packetLossRate > 0.01f) {
            alerts.add(ValidationAlert(
                AlertLevel.DANGER, 
                "Codec Degradation", 
                "Perda de pacotes detectada (${format(state.packetLossRate * 100f)}%). Distorção digital perceptível no monitoramento Bluetooth."
            ))
        } else if (state.activeCodecName == "SBC") {
            alerts.add(ValidationAlert(
                AlertLevel.WARNING, 
                "Codec Degradation", 
                "Codec SBC em uso. Transmissão sofre compressão destrutiva acima de 16kHz. Use codecs Hi-Res para monitoração metrológica de masterização."
            ))
        }

        return alerts
    }

    private fun format(value: Float): String {
        return String.format("%.2f", value)
    }
}
