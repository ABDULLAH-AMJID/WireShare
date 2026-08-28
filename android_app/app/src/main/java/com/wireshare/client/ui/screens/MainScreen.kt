package com.wireshare.client.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.wireshare.client.data.model.ConnectionState
import com.wireshare.client.data.model.ServerInfo
import com.wireshare.client.network.DiscoveryClient
import com.wireshare.client.network.WireShareClient
import com.wireshare.client.service.PhoneScreenCaptureService
import com.wireshare.client.service.WireShareAccessibilityService
import com.wireshare.client.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val discoveryClient = remember { DiscoveryClient() }

    val connectionState by WireShareClient.connectionState.collectAsState()
    val statusMessage by WireShareClient.statusMessage.collectAsState()

    var serverIp by remember { mutableStateOf("192.168.100.11") }
    var pinCode by remember { mutableStateOf("") }
    var wsPort by remember { mutableStateOf(8765) }
    var udpPort by remember { mutableStateOf(8766) }
    var isScanning by remember { mutableStateOf(false) }
    var discoveredServer by remember { mutableStateOf<ServerInfo?>(null) }
    var sensitivity by remember { mutableStateOf(1.2f) }
    var selectedTab by remember { mutableStateOf(0) } // 0 = PC->Phone, 1 = Phone->PC
    var isSharingScreenToPc by remember { mutableStateOf(false) }

    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, PhoneScreenCaptureService::class.java).apply {
                action = PhoneScreenCaptureService.ACTION_START
                putExtra(PhoneScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(PhoneScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(PhoneScreenCaptureService.EXTRA_SERVER_IP, WireShareClient.currentServerIp())
                putExtra(PhoneScreenCaptureService.EXTRA_PIN, WireShareClient.currentPin())
                putExtra(PhoneScreenCaptureService.EXTRA_PORT, 8771)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            isSharingScreenToPc = true
        }
    }

    val isAccessibilityReady = WireShareAccessibilityService.instance != null

    Scaffold(
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "WireShare v2.0 Client",
                                color = PrimaryBlue,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                            Text(
                                text = "Bidirectional KVM & Remote Desktop Studio",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = DarkBackground
                    )
                )
// Bidirectional Mode Selector Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = SurfaceDark,
                    contentColor = PrimaryBlue
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("📱 PC -> Phone Mode", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("💻 Phone -> PC Studio", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("🖥️ Screen Mirror", fontWeight = FontWeight.Bold) }
                    )
                }
            }
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        if (selectedTab == 1) {
// Tab 1: Android Controlling PC (Joystick, Mouse Buttons, Scroll, Soft Keyboard)
            Box(modifier = Modifier.padding(innerPadding)) {
                PcControllerScreen()
            }
        } else if (selectedTab == 2) {
// Tab 2 (Feature 2): full PC screen mirrored to the phone, gesture-controlled.
// Only meaningful once connected - the mirror channel authenticates with the
// same PIN as the main connection.
            Box(modifier = Modifier.padding(innerPadding)) {
                if (connectionState == ConnectionState.DISCONNECTED) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Connect to your PC on the first tab before opening Screen Mirror.",
                            color = TextSecondary,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                } else {
                    ScreenMirrorScreen()
                }
            }
        } else {
// Tab 0: PC Controlling Android
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
// 1. Accessibility Service Card
                AccessibilityStatusCard(
                    isReady = isAccessibilityReady,
                    onOpenSettings = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                )

// 2. Wi-Fi Auto-Discovery Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Auto-Discover PC on Wi-Fi",
                                color = PrimaryBlue,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        isScanning = true
                                        val result = discoveryClient.discoverServer()
                                        isScanning = false
                                        if (result != null) {
                                            discoveredServer = result
                                            serverIp = result.ip
                                            wsPort = result.wsPort
                                            udpPort = result.udpPort
                                        }
                                    }
                                },
                                enabled = !isScanning
                            ) {
                                Text(if (isScanning) "Scanning..." else "Scan Wi-Fi")
                            }
                        }

                        if (discoveredServer != null) {
                            Text(
                                text = "Found Server: ${discoveredServer!!.hostname} (${discoveredServer!!.ip})",
                                color = AccentGreen,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

// 3. Pairing & Connection Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "PC Connection & Security PIN",
                            color = SecondaryPurple,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )

                        OutlinedTextField(
                            value = serverIp,
                            onValueChange = { serverIp = it },
                            label = { Text("PC Server IPv4 Address") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = pinCode,
                            onValueChange = { pinCode = it },
                            label = { Text("6-Digit Pairing PIN (From PC GUI)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (connectionState == ConnectionState.DISCONNECTED) {
                                Button(
                                    onClick = {
                                        if (pinCode.isNotEmpty()) {
                                            WireShareClient.connectAndPair(
                                                serverIp = serverIp,
                                                wsPort = wsPort,
                                                udpPort = udpPort,
                                                pin = pinCode
                                            )
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                                ) {
                                    Text("Connect & Pair", color = DarkBackground, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Button(
                                    onClick = { WireShareClient.disconnect() },
                                    colors = ButtonDefaults.buttonColors(containerColor = WarningAmber)
                                ) {
                                    Text("Disconnect", color = DarkBackground, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

// 4. Sensitivity Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Mouse Cursor Sensitivity: ${String.format("%.1f", sensitivity)}x",
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                        Slider(
                            value = sensitivity,
                            onValueChange = {
                                sensitivity = it
                                WireShareAccessibilityService.instance?.sensitivity = it
                            },
                            valueRange = 0.5f..3.0f,
                            steps = 24
                        )
                    }
                }

// 5. Phone-Side Toggle Key (switch control between PC and Phone without touching the PC)
                if (connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.MODE_ACTIVE) {
                    val isPhoneActive = connectionState == ConnectionState.MODE_ACTIVE
                    Button(
                        onClick = { WireShareClient.sendToggleModeRequest() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPhoneActive) SecondaryPurple else PrimaryBlue
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (isPhoneActive) "🔀 Switch Back to PC Control" else "🔀 Switch to Phone Control",
                            color = DarkBackground,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

// 6. Feature 3: Phone -> PC screen share (reverse of the mirror tab)
                if (connectionState != ConnectionState.DISCONNECTED) {
                    Button(
                        onClick = {
                            if (isSharingScreenToPc) {
                                val stopIntent = Intent(context, PhoneScreenCaptureService::class.java).apply {
                                    action = PhoneScreenCaptureService.ACTION_STOP
                                }
                                context.startService(stopIntent)
                                isSharingScreenToPc = false
                            } else {
                                screenCaptureLauncher.launch(
                                    mediaProjectionManager.createScreenCaptureIntent()
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSharingScreenToPc) Color(0xFFEF4444) else AccentGreen
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (isSharingScreenToPc) "⏹ Stop Sharing My Screen" else "📤 Share My Screen to PC",
                            color = DarkBackground,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

// 7. Connection Status Indicator
                StatusBox(connectionState = connectionState, message = statusMessage)
            }
        }
    }
}

@Composable
fun AccessibilityStatusCard(isReady: Boolean, onOpenSettings: () -> Unit) {
    val borderColor = if (isReady) AccentGreen else WarningAmber
    val bgColor = if (isReady) Color(0xFF064E3B) else Color(0xFF451A03)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, shape = RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isReady) "🟢 Accessibility Service Ready" else "⚠️ Accessibility Service Disabled",
                color = if (isReady) AccentGreen else WarningAmber,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                text = if (isReady) "Floating cursor & input injection active." else "Required to show mouse pointer and type text.",
                color = TextPrimary,
                fontSize = 12.sp
            )
        }

        if (!isReady) {
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(containerColor = WarningAmber)
            ) {
                Text("Enable", color = DarkBackground, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun StatusBox(connectionState: ConnectionState, message: String) {
    val (color, label) = when (connectionState) {
        ConnectionState.DISCONNECTED -> Color(0xFFEF4444) to "DISCONNECTED"
        ConnectionState.SCANNING -> PrimaryBlue to "SCANNING WI-FI"
        ConnectionState.PAIRING -> SecondaryPurple to "PAIRING"
        ConnectionState.CONNECTED -> AccentGreen to "CONNECTED - DESKTOP MODE"
        ConnectionState.MODE_ACTIVE -> PrimaryBlue to "ACTIVE - PHONE CONTROL"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(color, shape = RoundedCornerShape(50))
                )
                Text(
                    text = "Status: $label",
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
            Text(
                text = message,
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
    }
}
