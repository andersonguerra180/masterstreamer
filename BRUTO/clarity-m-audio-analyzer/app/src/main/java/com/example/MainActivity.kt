package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dsp.*
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlin.math.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Always Full Screen Immersive Mode setup
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) { innerPadding ->
                    MasterClarityApp(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun MasterClarityApp(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    
    // Dialog state for UDP Configuration and Monitoring
    var showUdpConfigDialog by remember { mutableStateOf(false) }

    // Connect to State flows
    val state by viewModel.audioEngine.state.collectAsState()
    val isVinylModeActive by viewModel.isVinylModeActive.collectAsState()
    val selectedRpm by viewModel.selectedRpm.collectAsState()
    val isGeneratorActive by viewModel.isGeneratorActive.collectAsState()
    val userNotification by viewModel.userNotification.collectAsState()

    val isUdpActive by viewModel.isUdpActive.collectAsState()
    val isUdpConnected by viewModel.isUdpConnected.collectAsState()
    val udpLocalIp by viewModel.udpLocalIp.collectAsState()
    val udpRmsL by viewModel.udpRmsL.collectAsState()
    val udpRmsR by viewModel.udpRmsR.collectAsState()
    val isUdpModeEnabled by viewModel.isUdpModeEnabled.collectAsState()

    val udpPort by viewModel.udpPort.collectAsState()
    val udpTargetIpFilter by viewModel.udpTargetIpFilter.collectAsState()
    val udpPacketCount by viewModel.udpPacketCount.collectAsState()
    val udpPacketRate by viewModel.udpPacketRate.collectAsState()
    val udpLastSenderIp by viewModel.udpLastSenderIp.collectAsState()

    // 60-FPS calculated dynamic vinyl cutter metrics
    val vinylMetrics = computeVinylMetrics(state, selectedRpm)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // 1. HARDLOCK STATUS HEADER BAR (TC Electronic Professional Metrology Look)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Color.Black)
                    .border(BorderStroke(1.dp, Color(0xFF1E2129)))
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Brand and Mode Bullet
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (isGeneratorActive) Color(0xFFFF9800) else Color(0xFF0AF779))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "BUNKER ANALOG MASTERING METER",
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "STATE_v1.5",
                        color = Color.Gray,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Center button to open UDP configuration dialog
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isUdpConnected) Color(0x330AF779) else Color(0x11FFFFFF))
                        .border(1.dp, if (isUdpConnected) Color(0xFF0AF779) else Color(0xFF22262F), RoundedCornerShape(4.dp))
                        .clickable { showUdpConfigDialog = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Configurar UDP",
                        tint = if (isUdpConnected) Color(0xFF0AF779) else Color.Gray,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    val udpText = if (isUdpConnected) {
                        val senderIp = if (udpLastSenderIp?.isNotEmpty() == true) udpLastSenderIp else "192.168.18.5"
                        "UDP · $senderIp · $udpPort"
                    } else {
                        "UDP · AGUARDANDO · $udpPort"
                    }
                    Text(
                        text = udpText,
                        color = if (isUdpConnected) Color(0xFF0AF779) else Color.Gray,
                        fontSize = 5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Right Utility Quick-Actions
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Blinking Vinyl Button Toggle
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (isVinylModeActive) Color(0xFFFF3D00) else Color(0xFF1E2128))
                            .border(1.dp, if (isVinylModeActive) Color.White else Color(0xFF2B303C), RoundedCornerShape(3.dp))
                            .clickable { viewModel.toggleVinylMode() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("vinyl_mode_toggle_button")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (isVinylModeActive) Color.White else Color(0xFFFF3D00))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "VINYL MODE",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // 2. MAIN TRIPLE DASHBOARD COLUMNS (Side-by-side, fits 100% on screen, absolutely NO scrolling!)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                
                // Column 1: Metrological Panel Swap (LUFS-I, LUFS-S, RMS, Dynamic Range / Vinyl safe levels)
                Card(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight(),
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    border = BorderStroke(1.dp, Color(0xFF1E2129))
                ) {
                    if (isVinylModeActive) {
                        VinylMetricsPanel(
                            metrics = vinylMetrics,
                            selectedRpm = selectedRpm,
                            onRpmToggle = { viewModel.setRpm(if (selectedRpm == 33) 45 else 33) }
                        )
                    } else {
                        NormalMetricsPanel(state = state)
                    }
                }

                // Column 2: Spectrum Analyser (Clarity M Style)
                SpectrumAnalyserCanvas(
                    bins = state.octave31Bins,
                    isUdpActive = state.isUdpActive,
                    modifier = Modifier
                        .weight(1.6f)
                        .fillMaxHeight()
                )

                // Column 3: Goniometer vectorscope rendering (Clarity M Black Background)
                Card(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight(),
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    border = BorderStroke(1.dp, Color(0xFF1E2129))
                ) {
                    PremiumGoniometer(state = state)
                }

                // Column 4: VU Strip (fino, 48dp)
                VuStripVertical(
                    peakL = state.truePeakL,
                    peakR = state.truePeakR,
                    rmsL = state.rmsL,
                    rmsR = state.rmsR,
                    modifier = Modifier
                        .width(48.dp)
                        .fillMaxHeight()
                )
            }
        }

        // 3. UDP CONFIGURATION & MONITOR DIALOG (TC Electronic Professional styling with peak lights)
        if (showUdpConfigDialog) {
            Dialog(onDismissRequest = { showUdpConfigDialog = false }) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(16.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0F1115),
                    border = BorderStroke(1.dp, Color(0xFF1E2129))
                ) {
                    Column(
                        modifier = Modifier
                            .padding(18.dp)
                            .fillMaxWidth()
                    ) {
                        // Header info
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = Color(0xFF00C9FD),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "CONEXÃO UDP JUCE VST3",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            IconButton(
                                onClick = { showUdpConfigDialog = false },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Explanatory guidance text block
                        Text(
                            text = "Abra seu plugin de envio UDP VST3 no DAW de sua preferência (Reaper, Pro Tools, etc) e configure para transmitir o áudio para o IP e porta locais exibidos abaixo.",
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Receiver Location Local IP Block
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF07080A), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF1B2330), RoundedCornerShape(4.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "IP DO CELULAR (RECEPTOR):",
                                    color = Color.Gray,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = udpLocalIp,
                                    color = Color(0xFF0AF779),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isUdpConnected) Color(0x330AF779) else Color(0x11FFFFFF))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (isUdpConnected) "CONECTADO" else "AGUARDANDO DAW...",
                                    color = if (isUdpConnected) Color(0xFF0AF779) else Color(0xFF00C9FD),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Parametros editáveis
                        var tempPort by remember { mutableStateOf(udpPort.toString()) }
                        var tempIpFilter by remember { mutableStateOf(udpTargetIpFilter) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "PORTA UDP LISTEN:",
                                    color = Color.Gray,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = tempPort,
                                    onValueChange = { tempPort = it.filter { char -> char.isDigit() } },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF00C9FD),
                                        unfocusedBorderColor = Color(0xFF22262F),
                                        focusedContainerColor = Color(0xFF07080A),
                                        unfocusedContainerColor = Color(0xFF07080A)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Column(modifier = Modifier.weight(1.5f)) {
                                Text(
                                    text = "FILTRAR TRANSMISSOR (IP OPCIONAL):",
                                    color = Color.Gray,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = tempIpFilter,
                                    onValueChange = { tempIpFilter = it },
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    placeholder = {
                                        Text(
                                            "Ex: 192.168.0.10",
                                            color = Color.DarkGray,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF00C9FD),
                                        unfocusedBorderColor = Color(0xFF22262F),
                                        focusedContainerColor = Color(0xFF07080A),
                                        unfocusedContainerColor = Color(0xFF07080A)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // MONITOR REAL TIME TELEMETRIES
                        Text(
                            text = "MONITORAMENTO DO SINAL",
                            color = Color.Gray,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF07080A), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF1B2330), RoundedCornerShape(4.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("SENDER ADDR:", color = Color.Gray, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                                Text(udpLastSenderIp, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("TOTAL DE PACOTES:", color = Color.Gray, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                                Text(udpPacketCount.toString(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("TAXA DE ENVIO (PPS):", color = Color.Gray, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                                Text(String.format(java.util.Locale.US, "%.1f", udpPacketRate) + " pps", color = Color(0xFF0AF779), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }

                            if (isUdpConnected) {
                                Spacer(modifier = Modifier.height(8.dp))
                                // Peak visual indicator inside dialog
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(12.dp)
                                        .background(Color.Black)
                                        .padding(2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val levelL = ((udpRmsL + 60f) / 60f).coerceIn(0f, 1f)
                                    val levelR = ((udpRmsR + 60f) / 60f).coerceIn(0f, 1f)

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .background(Color(0x33FFFFFF))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(levelL)
                                                .fillMaxHeight()
                                                .background(Color(0xFF0AF779))
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .background(Color(0x33FFFFFF))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(levelR)
                                                .fillMaxHeight()
                                                .background(Color(0xFF0AF779))
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Action buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    viewModel.stopUdpReceiver()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF2D55),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.weight(1.0f),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("PARAR SOCKET", fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }

                            Button(
                                onClick = {
                                    val p = tempPort.toIntOrNull() ?: 50005
                                    viewModel.configureUdp(p, tempIpFilter)
                                    viewModel.startUdpReceiver()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF0AF779),
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.weight(1.2f),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("SALVAR & INICIAR", fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        // 4. FLOATING NOTIFICATION SLIDE OVERLAY (e.g., USB connected)
        AnimatedVisibility(
            visible = userNotification != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
        ) {
            userNotification?.let { msg ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xEE090A0C))
                        .border(1.dp, Color(0xFF0AF779), RoundedCornerShape(4.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(modifier = Modifier.size(6.dp).background(Color(0xFF0AF779), RoundedCornerShape(3.dp)))
                        Text(text = msg, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------
// SOLID SUB-METROPOLIS OVERLAYS & CANVAS DRAWINGS
// ----------------------------------------------------

@Composable
fun NormalMetricsPanel(state: MeterState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "LOUDNESS",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        
        // LUFS Integrated
        MetricCard(
            title = "LUFS-I",
            value = if (state.integratedLufs <= -70f) "-INF" else String.format("%.1f", state.integratedLufs),
            unit = "LUFS",
            color = if (state.integratedLufs > -14f) Color(0xFF0AF779) else Color(0xFF00C9FD),
            modifier = Modifier.weight(1f)
        )
        
        // LUFS ShortTerm
        MetricCard(
            title = "LUFS-S",
            value = if (state.shortTermLufs <= -70f) "-INF" else String.format("%.1f", state.shortTermLufs),
            unit = "LUFS",
            color = if (state.shortTermLufs > -14f) Color(0xFF0AF779) else Color(0xFF00C9FD),
            modifier = Modifier.weight(1f)
        )
        
        // RMS LEVEL CARD
        RmsLevelCard(
            rmsL = state.rmsL,
            rmsR = state.rmsR,
            modifier = Modifier.weight(1f)
        )

        // DYNAMIC RANGE CARD
        DynamicRangeCard(
            crestFactor = state.crestFactor,
            modifier = Modifier.weight(1f)
        )
    }
}

fun getCombinedRms(rmsL: Float, rmsR: Float): Float {
    if (rmsL <= -120f && rmsR <= -120f) return -120f
    if (rmsL <= -120f) return rmsR
    if (rmsR <= -120f) return rmsL
    val pL = Math.pow(10.0, rmsL.toDouble() / 10.0)
    val pR = Math.pow(10.0, rmsR.toDouble() / 10.0)
    val avgP = (pL + pR) / 2.0
    return (10.0 * Math.log10(avgP)).toFloat()
}

@Composable
fun RmsLevelCard(rmsL: Float, rmsR: Float, modifier: Modifier = Modifier) {
    val combinedRms = getCombinedRms(rmsL, rmsR)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        border = BorderStroke(1.dp, Color(0xFF1E2129))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = "RMS LEVEL",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "L+R BLENDED",
                    color = Color.DarkGray,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            
            Text(
                text = if (combinedRms <= -120f) "-INF" else String.format("%.1f dBFS", combinedRms),
                color = if (combinedRms > -3f) Color(0xFFFF1E42) else Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun DynamicRangeCard(crestFactor: Float, modifier: Modifier = Modifier) {
    val color = when {
        crestFactor > 14f -> Color(0xFF0AF779)
        crestFactor >= 8f -> Color(0xFFF7C30A)
        else -> Color(0xFFFF1E42)
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        border = BorderStroke(1.dp, Color(0xFF1E2129))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = "DYNAMIC RANGE",
                    color = Color.Gray,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "CREST FACTOR",
                    color = Color.DarkGray,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = String.format("%.1f", crestFactor),
                    color = color,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "dB",
                    color = Color.Gray,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        border = BorderStroke(1.dp, Color(0xFF1E2129))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Color.Gray,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = value,
                    color = color,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = unit,
                    color = Color.Gray,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun VinylMetricsPanel(
    metrics: VinylMetrics,
    selectedRpm: Int,
    onRpmToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "VINYL CUTTER METRICAS",
                color = Color(0xFFFF9800),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF22262F))
                    .clickable { onRpmToggle() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "$selectedRpm RPM",
                    color = Color(0xFFFF9800),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // LOW-END SAFE
        VinylItemCard(
            title = "LOW-END SAFE (<120Hz)",
            status = metrics.lowEndStatus,
            color = when (metrics.lowEndStatus) {
                "SAFE" -> Color(0xFF0AF779)
                "WARNING" -> Color(0xFFF7C30A)
                else -> Color(0xFFFF1E42)
            },
            detail = "Corr: ${String.format("%.2f", metrics.lowEndCorr)}",
            modifier = Modifier.weight(1f)
        )

        // VERTICAL MODULATION
        VinylItemProgressCard(
            title = "VERTICAL MODULATION",
            percentage = metrics.verticalPercentage,
            color = if (metrics.verticalPercentage < 35f) Color(0xFF0AF779) else if (metrics.verticalPercentage <= 65f) Color(0xFFF7C30A) else Color(0xFFFF1E42),
            modifier = Modifier.weight(1f)
        )

        // HF CUT RISK & SIDE LENGTH
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            VinylItemHalfCard(
                title = "HF CUT RISK",
                status = metrics.hfCutRisk,
                color = when (metrics.hfCutRisk) {
                    "LOW" -> Color(0xFF0AF779)
                    "MEDIUM" -> Color(0xFFF7C30A)
                    else -> Color(0xFFFF1E42)
                },
                modifier = Modifier.weight(1f)
            )
            
            VinylItemHalfCard(
                title = "SIDE LENGTH",
                status = metrics.sideLengthStatus,
                color = when (metrics.sideLengthStatus) {
                    "SAFE" -> Color(0xFF0AF779)
                    "TIGHT" -> Color(0xFFF7C30A)
                    else -> Color(0xFFFF1E42)
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun VinylItemCard(
    title: String,
    status: String,
    color: Color,
    detail: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        border = BorderStroke(1.dp, Color(0xFF1E2129))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                color = Color.Gray,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = status,
                    color = color,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = detail,
                    color = Color.Gray,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun VinylItemProgressCard(
    title: String,
    percentage: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        border = BorderStroke(1.dp, Color(0xFF1E2129))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = Color.Gray,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = String.format("%.1f%%", percentage),
                    color = color,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            
            val fraction = (percentage / 100f).coerceIn(0f, 1f)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(1.dp))
            ) {
                drawRect(color = Color(0xFF1B1E24))
                drawRect(color = color, size = size.copy(width = size.width * fraction))
            }
        }
    }
}

@Composable
fun VinylItemHalfCard(
    title: String,
    status: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        border = BorderStroke(1.dp, Color(0xFF1E2129))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                color = Color.Gray,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = status,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ----------------------------------------------------
// CANVAS ACCURATE SPECTRUM ANALYSER & VU STRIP
// ----------------------------------------------------

@Composable
fun SpectrumAnalyserCanvas(
    bins: FloatArray,
    isUdpActive: Boolean,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val ticker = rememberInfiniteTransition(label = "SpectrumAnim")
    val dummy by ticker.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dummy"
    )

    // Ballistics states for exactly 31 bands
    val visualBins = remember { FloatArray(31) { -60f } }
    val peakHoldBins = remember { FloatArray(31) { -60f } }
    val peakHoldTimes = remember { LongArray(31) { 0L } }
    var lastTime by remember { mutableStateOf(0L) }
    var accumulatedTime by remember { mutableStateOf(0f) }

    // Auto-range tracking states (smooth animation)
    var maxPeakTracker by remember { mutableStateOf(-40f) }
    var animatedCeiling by remember { mutableStateOf(0f) }

    val mono7sp = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 7.sp,
        color = Color(0xFF555B6A),
        fontWeight = FontWeight.Bold
    )

    val gridLabelStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 6.5.sp,
        color = Color(0xFF333842),
        fontWeight = FontWeight.Normal
    )

    Card(
        modifier = modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        border = BorderStroke(1.dp, Color(0xFF1E2129))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            // Header with responsive info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SPECTRUM RTA (1/3 OCT)",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = if (isUdpActive) "UDP REALTIME RTA" else "AUTO-RANGE ACTIVE",
                    color = if (isUdpActive) Color(0xFF00FF41).copy(alpha = 0.5f) else Color(0xFF00E5FF).copy(alpha = 0.4f),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = 6.dp)
                )
            }

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) {
                // Read dummy to enforce recomposition every frame
                val d = dummy

                // Elapsed time
                val now = System.currentTimeMillis()
                val elapsed = if (lastTime == 0L) 0.01667f else ((now - lastTime) / 1000f).coerceIn(0.001f, 0.2f)
                lastTime = now

                // Accumulate time for fixed 60Hz physics step (0.01667s)
                accumulatedTime += elapsed
                val fixedDt = 0.01667f

                if (isUdpActive) {
                    for (i in 0 until 31) {
                        visualBins[i] = bins.getOrElse(i) { -120f }
                        peakHoldBins[i] = bins.getOrElse(i) { -120f }
                    }
                } else {
                    while (accumulatedTime >= fixedDt) {
                        accumulatedTime -= fixedDt

                        // Auto-range tracking: find max of 31 bands
                        var currentFrameMax = -120f
                        for (i in 0 until 31) {
                            val rawVal = bins.getOrElse(i) { -120f }
                            if (rawVal > currentFrameMax) {
                                currentFrameMax = rawVal
                            }
                        }

                        // Smooth decay for tracker
                        if (currentFrameMax > maxPeakTracker) {
                            maxPeakTracker = maxPeakTracker * 0.7f + currentFrameMax * 0.3f
                        } else {
                            maxPeakTracker = max(-120f, maxPeakTracker - 2.5f * fixedDt)
                        }

                        // Determine target ceiling and floor
                        val targetCeiling = when {
                            maxPeakTracker > -6f -> 10f
                            maxPeakTracker > -16f -> 0f
                            maxPeakTracker > -28f -> -10f
                            maxPeakTracker > -40f -> -20f
                            else -> -30f
                        }

                        // Animate ceiling (lag filter)
                        animatedCeiling += (targetCeiling - animatedCeiling) * 0.1f
                        val floorVal = animatedCeiling - 60f // always 60 dB dynamic range

                        // Update visual levels and peak holds for exactly 31 bands
                        for (i in 0 until 31) {
                            val rawDb = bins.getOrElse(i) { -120f }
                            val targetDb = rawDb.coerceIn(floorVal, animatedCeiling)

                            // 1. Attack/Decay for bars
                            if (targetDb > visualBins[i]) {
                                visualBins[i] = targetDb // Instantaneous attack
                            } else {
                                visualBins[i] = max(floorVal, visualBins[i] - 25f * fixedDt) // Organic decay at 25 dB/s
                            }

                            // 2. Peak hold
                            if (targetDb > peakHoldBins[i]) {
                                peakHoldBins[i] = targetDb
                                peakHoldTimes[i] = now
                            } else {
                                if (now - peakHoldTimes[i] > 2000L) {
                                    peakHoldBins[i] = max(floorVal, peakHoldBins[i] - 12f * fixedDt) // slow decay after 2s
                                }
                            }

                            // Force peak hold to at least match bar level
                            if (visualBins[i] > peakHoldBins[i]) {
                                peakHoldBins[i] = visualBins[i]
                            }
                        }
                    }
                }

                val currentCeiling = if (isUdpActive) 0f else animatedCeiling
                val currentFloor = if (isUdpActive) -60f else (animatedCeiling - 60f)

                // Dimensions
                val dbLabelWidth = 24.dp.toPx()
                val freqLabelHeight = 12.dp.toPx()
                val paddingRight = 4.dp.toPx()
                val paddingTop = 8.dp.toPx()

                val chartLeftX = dbLabelWidth
                val chartRightX = size.width - paddingRight
                val chartWidth = chartRightX - chartLeftX
                val chartBottomY = size.height - freqLabelHeight
                val chartTopY = paddingTop
                val chartHeight = chartBottomY - chartTopY

                if (chartWidth <= 0f || chartHeight <= 0f) return@Canvas

                // Background plate (Hardware look #000000)
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(chartLeftX, chartTopY),
                    size = Size(chartWidth, chartHeight)
                )

                // 1. Draw Grid Lines
                // Horizontal dB ticks (very dark gray)
                val startMult = Math.ceil(currentFloor.toDouble() / 10.0).toInt() * 10
                val endMult = Math.floor(currentCeiling.toDouble() / 10.0).toInt() * 10
                for (dbTick in startMult..endMult step 10) {
                    val db = dbTick.toFloat()
                    val yRel = ((db - currentFloor) / (currentCeiling - currentFloor)).toFloat()
                    val y = chartBottomY - yRel * chartHeight
                    
                    drawLine(
                        color = Color(0xFF13161F),
                        start = Offset(chartLeftX, y),
                        end = Offset(chartRightX, y),
                        strokeWidth = 1f
                    )
                    
                    // DB Labels (Aligned left)
                    val textLayoutResult = textMeasurer.measure(
                        text = if (db.toInt() == 0) "0" else db.toInt().toString(),
                        style = gridLabelStyle
                    )
                    drawText(
                        textLayoutResult = textLayoutResult,
                        topLeft = Offset(2.dp.toPx(), y - textLayoutResult.size.height / 2f)
                    )
                }

                // 31 frequencies mapping center coordinates
                val barGap = 1.5.dp.toPx()
                val totalGaps = barGap * 30
                val barWidth = (chartWidth - totalGaps) / 31

                // Sparse Vertical grid lines separating regions:
                // We show physical separator lines at indices 11.5 and 23.5
                val sepIndices = listOf(11.5f, 23.5f)
                sepIndices.forEach { sIdx ->
                    val x = chartLeftX + sIdx * (barWidth + barGap) - (barGap / 2f)
                    drawLine(
                        color = Color(0xFF1F2432).copy(alpha = 0.7f),
                        start = Offset(x, chartTopY),
                        end = Offset(x, chartBottomY),
                        strokeWidth = 1f.dp.toPx()
                    )
                }

                // Label center frequencies Map
                val labelMap = mapOf(
                    4 to "50",
                    7 to "100",
                    10 to "200",
                    14 to "500",
                    17 to "1k",
                    20 to "2k",
                    24 to "5k",
                    27 to "10k",
                    30 to "20k"
                )

                // 2. Draw 31 Bands and their Peak Holds
                for (i in 0 until 31) {
                    val xL = chartLeftX + i * (barWidth + barGap)
                    
                    // Calculate bar height relative to current animated range
                    val yRel = (visualBins[i] - currentFloor) / (currentCeiling - currentFloor)
                    val topY = chartBottomY - yRel.coerceIn(0f, 1f) * chartHeight
                    val barHeight = chartBottomY - topY

                    // Draw solid Cyan flat bar
                    if (barHeight > 0f) {
                        drawRect(
                            color = Color(0xFF00E5FF),
                            topLeft = Offset(xL, topY),
                            size = Size(barWidth, barHeight)
                        )
                    }

                    // Draw Freq Numeric Label if mapped for index `i`
                    labelMap[i]?.let { label ->
                        val xCenter = xL + (barWidth / 2f)
                        val textLayoutResult = textMeasurer.measure(text = label, style = mono7sp)
                        drawText(
                            textLayoutResult = textLayoutResult,
                            topLeft = Offset(xCenter - textLayoutResult.size.width / 2f, chartBottomY + 2.dp.toPx())
                        )
                    }

                    // Draw Peak Hold Line (fine horizontal line: 1.5dp height)
                    val pkYRel = (peakHoldBins[i] - currentFloor) / (currentCeiling - currentFloor)
                    val pkY = chartBottomY - pkYRel.coerceIn(0f, 1f) * chartHeight
                    
                    drawLine(
                        color = Color.White,
                        start = Offset(xL, pkY),
                        end = Offset(xL + barWidth, pkY),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }
            }
        }
    }
}

@Composable
fun VuStripVertical(
    peakL: Float,
    peakR: Float,
    rmsL: Float,
    rmsR: Float,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    
    // Smooth decay States
    var visualPeakL by remember { mutableStateOf(-60f) }
    var visualPeakR by remember { mutableStateOf(-60f) }
    
    var holdPeakL by remember { mutableStateOf(-60f) }
    var holdPeakR by remember { mutableStateOf(-60f) }
    
    var holdTimeL by remember { mutableStateOf(0L) }
    var holdTimeR by remember { mutableStateOf(0L) }
    
    var clipTimeL by remember { mutableStateOf(0L) }
    var clipTimeR by remember { mutableStateOf(0L) }
    
    var lastUpdateTime by remember { mutableStateOf(0L) }
    
    val ticker = rememberInfiniteTransition(label = "VuAnim")
    val dummy by ticker.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dummy"
    )
    
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        border = BorderStroke(1.dp, Color(0xFF1E2129))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 4.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // "VU" text at top
            Text(
                text = "VU",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            
            // Peak hold numeric values
            val peakLToShow = if (peakL <= -60f) "-60" else String.format("%.0f", holdPeakL)
            val peakRToShow = if (peakR <= -60f) "-60" else String.format("%.0f", holdPeakR)
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Text(
                    text = peakLToShow,
                    color = if (holdPeakL >= 0f) Color(0xFFFF1E42) else Color.White.copy(alpha = 0.8f),
                    fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = peakRToShow,
                    color = if (holdPeakR >= 0f) Color(0xFFFF1E42) else Color.White.copy(alpha = 0.8f),
                    fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Clip Dots Indicator Row
            val now = System.currentTimeMillis()
            if (peakL >= 0f) clipTimeL = now
            if (peakR >= 0f) clipTimeR = now
            
            val isClipL = (now - clipTimeL) < 3000L
            val isClipR = (now - clipTimeR) < 3000L
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Left clip indicator
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (isClipL) Color(0xFFFF1E42) else Color(0xFF221111))
                        .border(0.5.dp, if (isClipL) Color(0xFFFF8C00) else Color.Transparent, RoundedCornerShape(3.dp))
                )
                // Right clip indicator
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (isClipR) Color(0xFFFF1E42) else Color(0xFF221111))
                        .border(0.5.dp, if (isClipR) Color(0xFFFF8C00) else Color.Transparent, RoundedCornerShape(3.dp))
                )
            }
            
            // Main Canvas
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                val d = dummy // force redrawing
                
                // Keep peak and RMS levels inside correct boundaries
                val currentPeakL = peakL.coerceIn(-60f, 3f)
                val currentPeakR = peakR.coerceIn(-60f, 3f)
                val currentRmsL = rmsL.coerceIn(-60f, 3f)
                val currentRmsR = rmsR.coerceIn(-60f, 3f)
                
                val timeNow = System.currentTimeMillis()
                val dt = if (lastUpdateTime == 0L) 0.016f else ((timeNow - lastUpdateTime) / 1000f).coerceIn(0.001f, 0.1f)
                lastUpdateTime = timeNow
                
                // Keep the visual bars representing the ultra-fast RMS from the network, with absolutely zero decay, smoothing, or lowpass filters.
                visualPeakL = currentRmsL
                visualPeakR = currentRmsR
                
                // 2. Process Peak Holds Decay (2s hold, then 20 dB/s decay)
                if (currentPeakL > holdPeakL) {
                    holdPeakL = currentPeakL
                    holdTimeL = timeNow
                } else {
                    if (timeNow - holdTimeL > 2000L) {
                        holdPeakL = max(-60f, holdPeakL - 20f * dt)
                    }
                }
                
                if (currentPeakR > holdPeakR) {
                    holdPeakR = currentPeakR
                    holdTimeR = timeNow
                } else {
                    if (timeNow - holdTimeR > 2000L) {
                        holdPeakR = max(-60f, holdPeakR - 20f * dt)
                    }
                }
                
                // Draw Columns
                val totalX = size.width
                val totalH = size.height
                val barW = (totalX - 4.dp.toPx()) / 2f
                
                // Background boxes
                // Left
                drawRect(
                    color = Color(0xFF0F1115),
                    topLeft = Offset(0f, 0f),
                    size = Size(barW, totalH)
                )
                // Right
                drawRect(
                    color = Color(0xFF0F1115),
                    topLeft = Offset(barW + 4.dp.toPx(), 0f),
                    size = Size(barW, totalH)
                )
                
                // Map levels (-60 to +3 dBFS -> 63 dB range)
                val levelL = ((visualPeakL + 60f) / 63f).coerceIn(0f, 1f)
                val levelR = ((visualPeakR + 60f) / 63f).coerceIn(0f, 1f)
                
                val fillHL = totalH * levelL
                val fillHR = totalH * levelR
                
                val gradientBrush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0xFFFF1E42), // +3 dB range
                        0.05f to Color(0xFFFF1E42), // 0 to 3 dB
                        0.14f to Color(0xFFFF8C00), // -6 to 0 dB
                        0.33f to Color(0xFFF7C30A), // -18 to -6 dB
                        1.0f to Color(0xFF0AF779)   // -60 to -18 dB
                    ),
                    startY = 0f,
                    endY = totalH
                )
                
                // Draw L channel filled bar
                drawRect(
                    brush = gradientBrush,
                    topLeft = Offset(0f, totalH - fillHL),
                    size = Size(barW, fillHL)
                )
                
                // Draw R channel filled bar
                drawRect(
                    brush = gradientBrush,
                    topLeft = Offset(barW + 4.dp.toPx(), totalH - fillHR),
                    size = Size(barW, fillHR)
                )
                
                // Draw Peak Hold Horizontal lines (2dp height)
                val holdY_L = totalH - (((holdPeakL + 60f) / 63f).coerceIn(0f, 1f) * totalH)
                val holdY_R = totalH - (((holdPeakR + 60f) / 63f).coerceIn(0f, 1f) * totalH)
                
                drawLine(
                    color = Color.White.copy(alpha = 0.9f),
                    start = Offset(0f, holdY_L),
                    end = Offset(barW, holdY_L),
                    strokeWidth = 2.dp.toPx()
                )
                
                drawLine(
                    color = Color.White.copy(alpha = 0.9f),
                    start = Offset(barW + 4.dp.toPx(), holdY_R),
                    end = Offset(totalX, holdY_R),
                    strokeWidth = 2.dp.toPx()
                )
            }
            
            // Channel Labels at the very bottom
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Text("L", color = Color.Gray, fontSize = 7.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("R", color = Color.Gray, fontSize = 7.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ----------------------------------------------------
// PHOSPHOR GONIOMETER VECTORSCOPE COMPOSABLE
// ----------------------------------------------------

@Composable
fun PremiumGoniometer(state: MeterState) {
    // History list of point lists for CRT phosphor persistence trail
    val history = remember { ArrayDeque<List<Offset>>() }
    val lastBufHash = remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "SCOPE",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.Start)
        )
        
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize().testTag("goniometer_canvas")) {
                val midX = size.width / 2
                val midY = size.height / 2
                val radius = min(size.width, size.height) * 0.44f
                
                // 1. Draw Grid diopter lines style TC Clarity (cinza muito escuro #0F1014, sem circulos)
                val gridColor = Color(0xFF0F1014)
                val gridStroke = 1.dp.toPx()
                
                // M/Vertical line
                drawLine(
                    color = gridColor,
                    start = Offset(midX, midY - radius),
                    end = Offset(midX, midY + radius),
                    strokeWidth = gridStroke
                )
                // S/Horizontal line
                drawLine(
                    color = gridColor,
                    start = Offset(midX - radius, midY),
                    end = Offset(midX + radius, midY),
                    strokeWidth = gridStroke
                )
                // Diagonal Left (45 degrees, L channel)
                drawLine(
                    color = gridColor,
                    start = Offset(midX - radius * 0.7071f, midY + radius * 0.7071f),
                    end = Offset(midX + radius * 0.7071f, midY - radius * 0.7071f),
                    strokeWidth = gridStroke
                )
                // Diagonal Right (135 degrees, R channel)
                drawLine(
                    color = gridColor,
                    start = Offset(midX - radius * 0.7071f, midY - radius * 0.7071f),
                    end = Offset(midX + radius * 0.7071f, midY + radius * 0.7071f),
                    strokeWidth = gridStroke
                )
                
                // 2. Compute current XY Lissajous Phosphor Samples Plot
                val currentPoints = ArrayList<Offset>(64)
                if (state.isUdpActive) {
                    val scope = state.scopeData
                    val currentHash = scope.contentHashCode()
                    if (currentHash != lastBufHash.value) {
                        lastBufHash.value = currentHash
                    }
                    for (i in 0 until 64) {
                        val l = scope.getOrElse(i * 2) { 0f }
                        val r = scope.getOrElse(i * 2 + 1) { 0f }
                        
                        // Pure Left mapped directly to X axis, pure Right mapped directly to Y axis (raw L/R vectorscope for checking phase)
                        val x = l
                        val y = r
                        
                        val drawX = midX + x * radius * 1.235f
                        val drawY = midY - y * radius * 1.235f
                        currentPoints.add(Offset(drawX, drawY))
                    }
                    // Clear history to prevent any residue when UDP mode is active
                    history.clear()
                } else {
                    val bufL = state.bufferSamplesL
                    val bufR = state.bufferSamplesR
                    val currentHash = bufL.contentHashCode()
                    
                    if (currentHash != lastBufHash.value) {
                        lastBufHash.value = currentHash
                        val offlinePoints = ArrayList<Offset>(bufL.size / 2)
                        for (i in bufL.indices step 2) {
                            if (i >= bufL.size || i >= bufR.size) break
                            val l = bufL[i]
                            val r = bufR[i]
                            
                            // Pure Left mapped directly to X axis, pure Right mapped directly to Y axis (raw L/R vectorscope for checking phase)
                            val x = l
                            val y = r
                            
                            val drawX = midX + x * radius * 1.235f
                            val drawY = midY - y * radius * 1.235f
                            offlinePoints.add(Offset(drawX, drawY))
                        }
                        history.addLast(offlinePoints)
                        while (history.size > 8) {
                            history.removeFirst()
                        }
                    }
                }
                
                // 3. Draw historics trails with screen-blend and decaying alpha
                if (state.isUdpActive) {
                    // Draw ONLY the current points to completely resolve pan leakage/residue
                    if (currentPoints.size > 1) {
                        val path = Path()
                        path.moveTo(currentPoints[0].x, currentPoints[0].y)
                        for (pIdx in 1 until currentPoints.size) {
                            path.lineTo(currentPoints[pIdx].x, currentPoints[pIdx].y)
                        }
                        drawPath(
                            path = path,
                            color = Color(0xFF00FF41),
                            style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                } else {
                    history.forEachIndexed { index, points ->
                        val alpha = ((index + 1) / history.size.toFloat()) * 0.85f
                        if (points.size > 1) {
                            val path = Path()
                            path.moveTo(points[0].x, points[0].y)
                            for (pIdx in 1 until points.size) {
                                path.lineTo(points[pIdx].x, points[pIdx].y)
                            }
                            drawPath(
                                path = path,
                                color = Color(0xFF00FF41).copy(alpha = alpha),
                                style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round),
                                blendMode = BlendMode.Screen
                            )
                        }
                    }
                }
            }
            
            // MS Cohesion status label
            val label = when {
                state.phaseCorrelation < -0.1f -> "PHASE REVERSED"
                state.stereoWidth > 75f -> "EXCESSIVE WIDTH"
                else -> "COHERENT M/S"
            }
            val labelColor = if (state.phaseCorrelation >= 0f) Color(0xFF00C9FD) else Color(0xFFFF1E42)
            
            Text(
                text = label,
                color = labelColor,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color(0xFF0C0D0F).copy(alpha = 0.8f), RoundedCornerShape(2.dp))
                    .border(1.dp, Color(0xFF1E2129), RoundedCornerShape(2.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

// ----------------------------------------------------
// 60-FPS REAL-TIME VINYL METRICS CO-DETERMINING
// ----------------------------------------------------

@Composable
fun computeVinylMetrics(state: MeterState, selectedRpm: Int): VinylMetrics {
    val left = state.bufferSamplesL
    val right = state.bufferSamplesR
    val N = left.size.coerceAtLeast(1)

    var sumSqMid = 0f
    var sumSqSide = 0f
    var dot120 = 0f
    var energyL120 = 0f
    var energyR120 = 0f

    val sampleRate = state.activeSampleRateHz.toDouble().coerceAtLeast(44100.0)
    val lpfL = remember(sampleRate) { Lpf(120.0, sampleRate) }
    val lpfR = remember(sampleRate) { Lpf(120.0, sampleRate) }

    lpfL.reset()
    lpfR.reset()

    for (i in 0 until N) {
        val sL = left[i]
        val sR = right[i]

        val m = (sL + sR) / 2.0f
        val s = (sL - sR) / 2.0f
        sumSqMid += m * m
        sumSqSide += s * s

        val fL = lpfL.process(sL.toDouble()).toFloat()
        val fR = lpfR.process(sR.toDouble()).toFloat()
        dot120 += fL * fR
        energyL120 += fL * fL
        energyR120 += fR * fR
    }

    val rmsMid = sqrt(sumSqMid / N)
    val rmsSide = sqrt(sumSqSide / N)

    // Vertical Movement ratio Side RMS / Mid RMS
    val verticalPercentage = if (rmsMid > 1e-6f) (rmsSide / rmsMid) * 100f else 0f

    // 120Hz Low End limit correlation value
    val lowEndCorr = if (energyL120 * energyR120 > 1e-12f) (dot120 / sqrt(energyL120 * energyR120)).coerceIn(-1.0f, 1.0f) else 1.0f
    val lowEndStatus = when {
        lowEndCorr >= 0.5f -> "SAFE"
        lowEndCorr >= 0.1f -> "WARNING"
        else -> "DANGER"
    }

    // High Frequency Cut Risk (examine energy above 12 kHz in the 100 spectrum slices, index 90..99)
    var hfSum = 0f
    for (idx in 90..99) {
        val db = state.logSpectrumBins[idx]
        hfSum += 10f.pow(db / 20f)
    }
    val hfRmsDb = 20f * log10((hfSum / 10f).coerceAtLeast(1e-6f))
    val hfCutRisk = when {
        hfRmsDb > -30f -> "HIGH"
        hfRmsDb > -45f -> "MEDIUM"
        else -> "LOW"
    }

    // Side Length Estimator
    val baseMins = if (selectedRpm == 33) 22.0 else 15.0
    val lFactor = ((state.shortTermLufs + 14f).coerceAtLeast(0f) * 0.7f)
    val bFactor = ((state.subBassDb + 30f).coerceAtLeast(0f) * 0.3f)
    val wFactor = (state.stereoWidth * 0.05f)
    val estMins = (baseMins - lFactor - bFactor - wFactor).coerceIn(10.0, baseMins)
    val sideLengthStatus = when {
        estMins > (baseMins - 2.0) -> "SAFE"
        estMins > (baseMins - 5.0) -> "TIGHT"
        else -> "TOO LONG"
    }

    return VinylMetrics(
        lowEndCorr = lowEndCorr,
        lowEndStatus = lowEndStatus,
        verticalPercentage = verticalPercentage,
        hfCutRisk = hfCutRisk,
        sideLengthStatus = sideLengthStatus,
        estimatedMins = estMins
    )
}

data class VinylMetrics(
    val lowEndCorr: Float,
    val lowEndStatus: String,
    val verticalPercentage: Float,
    val hfCutRisk: String,
    val sideLengthStatus: String,
    val estimatedMins: Double
)

class Lpf(fc: Double, fs: Double) {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    init {
        val w0 = 2.0 * Math.PI * fc / fs
        val alpha = Math.sin(w0) / (2.0 * (1.0 / Math.sqrt(2.0)))
        val cosW0 = Math.cos(w0)
        val a0 = 1.0 + alpha
        b0 = ((1.0 - cosW0) / 2.0) / a0
        b1 = (1.0 - cosW0) / a0
        b2 = ((1.0 - cosW0) / 2.0) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha) / a0
    }
    fun process(sample: Double): Double {
        val out = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = sample; y2 = y1; y1 = out
        return out
    }
    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }
}

