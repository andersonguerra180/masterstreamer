package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dsp.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val audioEngine = AudioEngine()
    val snapshotManager = SnapshotManager(application)
    val masteringValidation = MasteringValidation()

    val activeProfile = MutableStateFlow(MasteringProfile.SPOTIFY)
    val selectedRmsWindowMs = MutableStateFlow(300)
    val selectedFftSize = MutableStateFlow(8192)
    val selectedFftMode = MutableStateFlow(FftMode.NORMAL)
    val selectedFftWindow = MutableStateFlow(FftAnalyzer.WindowType.HANN)
    
    val isFullscreen = MutableStateFlow(false)
    val isVinylModeActive = MutableStateFlow(false)
    val selectedRpm = MutableStateFlow(33)
    val isGeneratorActive = MutableStateFlow(true)
    val selectedCodecOption = MutableStateFlow(BluetoothQualityMonitor.Codec.LDAC)
    val connectionStrengthOption = MutableStateFlow(1.0f)
    
    val isUdpModeEnabled = MutableStateFlow(true)
    val isUdpActive = audioEngine.udpReceiver.isActiveFlow
    val isUdpConnected = audioEngine.udpReceiver.isConnectedFlow
    val udpLocalIp = audioEngine.udpReceiver.localIpFlow
    val udpRmsL = audioEngine.udpReceiver.rmsLFlow
    val udpRmsR = audioEngine.udpReceiver.rmsRFlow

    val udpPort = MutableStateFlow(audioEngine.udpReceiver.port)
    val udpTargetIpFilter = MutableStateFlow(audioEngine.udpReceiver.targetIpFilter)
    
    val udpPacketCount = audioEngine.udpReceiver.packetCountFlow
    val udpPacketRate = audioEngine.udpReceiver.packetRateFlow
    val udpLastSenderIp = audioEngine.udpReceiver.lastSenderIpFlow
    
    val activeAlerts = MutableStateFlow<List<ValidationAlert>>(emptyList())
    val userNotification = MutableStateFlow<String?>(null)

    init {
        // Set UDP reception as default, disabling tone generator initially
        isGeneratorActive.value = false
        audioEngine.isGeneratorMode = false
        audioEngine.isUdpMode = true

        audioEngine.start(application)
        audioEngine.fftAnalyzer.mode = FftMode.NORMAL
        
        // Start UDP receiver instantly on launch
        startUdpReceiver()

        // Listen to the real-time DSP output matching EBU parameters
        viewModelScope.launch {
            audioEngine.state.collect { meterState ->
                // Append historical metrics to sliding log
                snapshotManager.logMetricHistory(meterState)
                
                // Assert live validation parameters against the active profile
                activeAlerts.value = masteringValidation.validate(meterState, activeProfile.value)
            }
        }
    }

    fun startUdpReceiver() {
        val app = getApplication<Application>()
        audioEngine.udpReceiver.startReceiver(app)
        enableUdpSource(true)
    }

    fun stopUdpReceiver() {
        audioEngine.udpReceiver.stopReceiver()
        enableUdpSource(false)
    }

    private fun enableUdpSource(enable: Boolean) {
        isUdpModeEnabled.value = true
        audioEngine.isUdpMode = true
        isGeneratorActive.value = false
        audioEngine.isGeneratorMode = false
        if (enable) {
            notifyUser("RECEPTOR UDP ATIVO NA PORTA ${udpPort.value}!")
        } else {
            notifyUser("RECEPTOR UDP DESATIVADO.")
        }
    }

    fun configureUdp(port: Int, ipFilter: String) {
        val wasRunning = isUdpActive.value
        if (wasRunning) {
            audioEngine.udpReceiver.stopReceiver()
        }
        
        audioEngine.udpReceiver.port = port
        audioEngine.udpReceiver.targetIpFilter = ipFilter
        udpPort.value = port
        udpTargetIpFilter.value = ipFilter
        
        if (wasRunning) {
            val app = getApplication<Application>()
            audioEngine.udpReceiver.startReceiver(app)
        }
        notifyUser("UDP Configurado! Porta: $port, Filtro IP: ${if (ipFilter.trim().isNotEmpty()) ipFilter.trim() else "Nenhum"}")
    }

    fun toggleSignalSource() {
        // Force generator to remain false and keep UDP mode active
        isGeneratorActive.value = false
        audioEngine.isGeneratorMode = false
        audioEngine.isUdpMode = true
        isUdpModeEnabled.value = true
    }

    fun setRmsWindow(ms: Int) {
        selectedRmsWindowMs.value = ms
        audioEngine.rmsWindowMs = ms
    }

    fun setFftSize(size: Int) {
        selectedFftSize.value = size
        audioEngine.fftAnalyzer.setSize(size)
    }

    fun setFftMode(mode: FftMode) {
        selectedFftMode.value = mode
        if (!isVinylModeActive.value) {
            audioEngine.fftAnalyzer.mode = mode
        }
    }

    fun toggleVinylMode() {
        val next = !isVinylModeActive.value
        isVinylModeActive.value = next
        if (next) {
            audioEngine.fftAnalyzer.mode = FftMode.VINYL
        } else {
            audioEngine.fftAnalyzer.mode = selectedFftMode.value
        }
    }

    fun setRpm(rpm: Int) {
        selectedRpm.value = rpm
    }

    fun setFftWindow(window: FftAnalyzer.WindowType) {
        selectedFftWindow.value = window
        audioEngine.fftAnalyzer.windowType = window
    }

    fun updateCodec(codec: BluetoothQualityMonitor.Codec) {
        selectedCodecOption.value = codec
        audioEngine.bluetoothMonitor.activeCodec = codec
    }

    fun updateConnectionStrength(strength: Float) {
        connectionStrengthOption.value = strength
        audioEngine.bluetoothMonitor.connectionStrength = strength
    }

    fun changeProfile(profile: MasteringProfile) {
        activeProfile.value = profile
        // Force re-validation immediately
        activeAlerts.value = masteringValidation.validate(audioEngine.state.value, profile)
    }

    fun takeSnapshot(state: MeterState, name: String) {
        snapshotManager.takeSnapshot(state, name)
        notifyUser("Snapshot '$name' cadastrado com sucesso!")
    }

    fun deleteSnapshot(id: String) {
        snapshotManager.deleteSnapshot(id)
    }

    fun setAsReference(snapshot: AudioSnapshot?) {
        snapshotManager.setAsReference(snapshot)
        if (snapshot != null) {
            notifyUser(" locked como Referência A/B: ${snapshot.name}")
        } else {
            notifyUser("Referência A/B desmarcada.")
        }
    }

    fun exportHistory() {
        val path = snapshotManager.exportHistoryToCsv()
        if (path != null) {
            notifyUser("Exportado com sucesso: cache/clarity_audio_meter_history.csv")
        } else {
            notifyUser("Falha ao exportar, histórico vazio.")
        }
    }

    fun clearHistory() {
        snapshotManager.clearHistory()
        notifyUser("Histórico de métricas do medidor resetado.")
    }

    private fun notifyUser(msg: String) {
        viewModelScope.launch {
            userNotification.value = msg
            delay(3500)
            if (userNotification.value == msg) {
                userNotification.value = null
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.stop()
    }
}
