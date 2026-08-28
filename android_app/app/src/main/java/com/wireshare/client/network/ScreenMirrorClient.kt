package com.wireshare.client.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString

/**
 * Feature 2 (full PC screen mirror): a small, independent WebSocket client
 * separate from WireShareClient's control channel. Connects to the PC's
 * screen_mirror.py server (config.mirror_port on the PC side, default 8770),
 * authenticates with the same PIN used for the main connection, and decodes
 * incoming binary frames (1-byte marker + JPEG bytes) into Bitmaps.
 *
 * Mouse/keyboard control while viewing the mirror still goes over the
 * existing WireShareClient control channel (sendRemotePcMouse / Click /
 * Keyboard) - this class is purely for receiving the video.
 */
object ScreenMirrorClient {
    private const val TAG = "ScreenMirrorClient"

    private val client = OkHttpClient.Builder()
        .pingInterval(java.time.Duration.ofSeconds(15))
        .build()
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _latestFrame = MutableStateFlow<Bitmap?>(null)
    val latestFrame: StateFlow<Bitmap?> = _latestFrame

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    fun connect(serverIp: String, mirrorPort: Int, pin: String) {
        disconnect()
        _errorMessage.value = null

        val request = Request.Builder()
            .url("ws://$serverIp:$mirrorPort")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Mirror socket open, sending AUTH")
                ws.send("""{"type":"AUTH","pin":"$pin"}""")
                _isConnected.value = true
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // Frame format: byte[0] == 0x01 marker, remainder is raw JPEG.
                val data = bytes.toByteArray()
                if (data.isEmpty() || data[0] != 0x01.toByte()) return
                try {
                    val jpeg = data.copyOfRange(1, data.size)
                    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    if (bmp != null) {
                        _latestFrame.value = bmp
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode mirror frame: ${e.message}")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Mirror socket closed: $reason")
                _isConnected.value = false
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Mirror socket failure: ${t.message}")
                _isConnected.value = false
                _errorMessage.value = t.message ?: "Connection failed"
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "Client closing")
        webSocket = null
        _isConnected.value = false
        _latestFrame.value = null
    }
}
