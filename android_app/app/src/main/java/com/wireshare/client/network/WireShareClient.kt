package com.wireshare.client.network

import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.wireshare.client.data.model.ConnectionState
import com.wireshare.client.data.model.KeyboardEvent
import com.wireshare.client.data.model.MouseEvent
import com.wireshare.client.data.model.WsMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WireShareClient {

    private const val TAG = "WireShareClient"
    private val gson = Gson()
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(java.time.Duration.ofSeconds(15))
        .build()

    private var webSocket: WebSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var udpJob: Job? = null
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready to connect")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _mouseEvents = MutableSharedFlow<MouseEvent>(extraBufferCapacity = 64)
    val mouseEvents: SharedFlow<MouseEvent> = _mouseEvents.asSharedFlow()

    private val _keyboardEvents = MutableSharedFlow<KeyboardEvent>(extraBufferCapacity = 32)
    val keyboardEvents: SharedFlow<KeyboardEvent> = _keyboardEvents.asSharedFlow()

    private var lastServerIp: String = "192.168.100.11"
    private var lastWsPort: Int = 8765
    private var lastUdpPort: Int = 8766

    /** Read-only accessor so ScreenMirrorClient can connect to the same host. */
    fun currentServerIp(): String = lastServerIp

    /** Read-only accessor so ScreenMirrorClient can authenticate with the same PIN. */
    fun currentPin(): String = lastPin
    private var lastPin: String = ""
    private var shouldAutoReconnect: Boolean = false
    private var udpSeqNum: Long = 0

    fun connectAndPair(serverIp: String, wsPort: Int, udpPort: Int, pin: String) {
        lastServerIp = serverIp
        lastWsPort = wsPort
        lastUdpPort = udpPort
        lastPin = pin
        shouldAutoReconnect = true
        reconnectJob?.cancel()

        executeConnect()
    }

    private fun executeConnect() {
        stopUdpListener()
        _connectionState.value = ConnectionState.PAIRING
        _statusMessage.value = "Connecting to $lastServerIp:$lastWsPort..."

        val request = Request.Builder()
            .url("ws://$lastServerIp:$lastWsPort")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened. Sending AUTH_REQUEST...")
                val authPayload = """
                    {"type":"AUTH_REQUEST","client_name":"Android-${Build.MODEL}","pin":"$lastPin"}
                """.trimIndent()
                webSocket.send(authPayload)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received WS Message: $text")
                try {
                    val msg = gson.fromJson(text, WsMessage::class.java)
                    when (msg.type) {
                        "AUTH_RESPONSE" -> {
                            if (msg.success) {
                                _connectionState.value = ConnectionState.CONNECTED
                                _statusMessage.value = "Connected & Authenticated! (Press F8 or Alt+X on PC)"
                                startUdpListener(lastUdpPort)
                            } else {
                                shouldAutoReconnect = false
                                _connectionState.value = ConnectionState.DISCONNECTED
                                _statusMessage.value = "Pairing Failed: ${msg.message}"
                                webSocket.close(1000, "Auth failed")
                            }
                        }
                        "MODE_CHANGE" -> {
                            if (msg.active) {
                                _connectionState.value = ConnectionState.MODE_ACTIVE
                                _statusMessage.value = "📱 PHONE CONTROL ACTIVE (Cursor Visible & Stream Active)"
                            } else {
                                _connectionState.value = ConnectionState.CONNECTED
                                _statusMessage.value = "💻 PC DESKTOP MODE (Normal Windows Input)"
                            }
                        }
                        "KEYBOARD_EVENT" -> {
                            scope.launch {
                                _keyboardEvents.emit(
                                    KeyboardEvent(
                                        action = msg.action,
                                        keyCode = msg.keyCode,
                                        char = msg.char
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WS message: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                stopUdpListener()
                handleDisconnect("Disconnected: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                stopUdpListener()
                handleDisconnect("Connection Lost (${t.message})")
            }
        })
    }

    private fun handleDisconnect(reason: String) {
        if (shouldAutoReconnect) {
            _connectionState.value = ConnectionState.SCANNING
            _statusMessage.value = "Screen Off / Disconnected — Auto-reconnecting in 2s..."
            scheduleAutoReconnect()
        } else {
            _connectionState.value = ConnectionState.DISCONNECTED
            _statusMessage.value = reason
        }
    }

    private fun scheduleAutoReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(2000)
            if (shouldAutoReconnect) {
                Log.d(TAG, "Attempting automatic reconnection to $lastServerIp:$lastWsPort...")
                executeConnect()
            }
        }
    }

    private fun startUdpListener(udpPort: Int) {
        stopUdpListener()
        udpJob = scope.launch(Dispatchers.IO) {
            try {
                udpSocket = DatagramSocket(udpPort)
                udpSocket?.soTimeout = 2000
                val buffer = ByteArray(64)
                val packet = DatagramPacket(buffer, buffer.size)

                Log.d(TAG, "Started UDP Mouse Listener on port $udpPort")
                while (isActive && _connectionState.value != ConnectionState.DISCONNECTED) {
                    try {
                        udpSocket?.receive(packet)
                        if (packet.length >= 20) {
                            val mouseEvent = unpackMousePacket(packet.data)
                            if (mouseEvent != null) {
                                _mouseEvents.emit(mouseEvent)
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Keep loop alive
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "UDP listener error: ${e.message}")
            } finally {
                udpSocket?.close()
                udpSocket = null
            }
        }
    }

    // --- BIDIRECTIONAL KVM METHODS (Phone -> PC Control) ---

    fun sendRemotePcMouse(dx: Int, dy: Int, buttons: Int = 0, scrollY: Int = 0, scrollX: Int = 0) {
        if (lastServerIp.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                udpSeqNum = (udpSeqNum + 1) and 0xFFFFFFFFL
                val bb = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                bb.put('W'.code.toByte())
                bb.put('S'.code.toByte())
                bb.put(0x02.toByte()) // Packet Type 0x02 = REMOTE_PC_MOUSE
                bb.putInt(udpSeqNum.toInt())
                bb.putInt(dx)
                bb.putInt(dy)
                bb.put(buttons.toByte())
                bb.put(scrollY.toByte())
                bb.put(scrollX.toByte())
                bb.putShort(0)

                val bytes = bb.array()
                val addr = InetAddress.getByName(lastServerIp)
                val packet = DatagramPacket(bytes, bytes.size, addr, lastUdpPort)

                // Use existing UDP socket or create a temporary sender socket
                val senderSocket = udpSocket ?: DatagramSocket()
                senderSocket.send(packet)
                if (senderSocket != udpSocket) {
                    senderSocket.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending remote PC mouse UDP packet: ${e.message}")
            }
        }
    }

    fun sendRemotePcClick(buttonMask: Int) {
        sendRemotePcMouse(0, 0, buttons = buttonMask)
        scope.launch {
            delay(60)
            sendRemotePcMouse(0, 0, buttons = 0)
        }
    }

    fun sendRemotePcKeyboard(char: String = "", keyCode: String = "") {
        val json = """
            {"type":"REMOTE_KEYBOARD_EVENT","action":"KEY_DOWN","key_code":"$keyCode","char":"$char"}
        """.trimIndent()
        webSocket?.send(json)
    }

    /**
     * Like sendRemotePcKeyboard, but lets the caller specify KEY_DOWN vs
     * KEY_UP explicitly. Needed for the Feature 2 virtual key overlay
     * (Ctrl/Alt/Win/Shift) so those keys can be HELD while another key or
     * click happens (e.g. Ctrl+Click, Alt+Tab) instead of firing as an
     * instant press+release pulse like Enter/Backspace/Escape do.
     */
    fun sendRemotePcKeyboardAction(action: String, keyCode: String, char: String = "") {
        val json = """
            {"type":"REMOTE_KEYBOARD_EVENT","action":"$action","key_code":"$keyCode","char":"$char"}
        """.trimIndent()
        webSocket?.send(json)
    }

    // --- PHONE-SIDE TOGGLE KEY (PC <-> Phone control) ---

    /**
     * Ask the PC to flip between PC_DESKTOP and PHONE_CONTROL modes, without
     * needing to press Alt+X / F8 on the physical PC keyboard.
     */
    fun sendToggleModeRequest() {
        if (_connectionState.value == ConnectionState.DISCONNECTED) return
        val json = """{"type":"TOGGLE_MODE_REQUEST"}"""
        webSocket?.send(json)
    }

    private fun unpackMousePacket(data: ByteArray): MouseEvent? {
        try {
            val bb = ByteBuffer.wrap(data, 0, 20).order(ByteOrder.LITTLE_ENDIAN)
            val magic1 = bb.get(0).toInt().toChar()
            val magic2 = bb.get(1).toInt().toChar()
            val pType = bb.get(2).toInt() and 0xFF

            if (magic1 != 'W' || magic2 != 'S' || pType != 0x01) {
                return null
            }

            val seqNum = bb.getInt(3).toLong() and 0xFFFFFFFFL
            val dx = bb.getInt(7)
            val dy = bb.getInt(11)
            val buttons = bb.get(15).toInt() and 0xFF
            val scrollY = bb.get(16).toInt()
            val scrollX = bb.get(17).toInt()

            return MouseEvent(
                seqNum = seqNum,
                dx = dx,
                dy = dy,
                buttons = buttons,
                scrollY = scrollY,
                scrollX = scrollX
            )
        } catch (e: Exception) {
            return null
        }
    }

    fun stopUdpListener() {
        udpJob?.cancel()
        udpJob = null
        udpSocket?.close()
        udpSocket = null
    }

    fun disconnect() {
        shouldAutoReconnect = false
        reconnectJob?.cancel()
        stopUdpListener()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _statusMessage.value = "Disconnected"
    }
}
