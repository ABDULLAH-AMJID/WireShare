package com.wireshare.client.network

import android.graphics.Bitmap
import android.util.Log
import okhttp3.*
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream

/**
 * Feature 3 (phone -> PC screen mirror): connects to the PC's
 * phone_mirror_server.py (config.phone_mirror_port, default 8771) as a
 * WebSocket CLIENT and pushes JPEG-encoded frames captured by
 * PhoneScreenCaptureService. Same handshake/frame format as
 * ScreenMirrorClient's PC-side counterpart: {"type":"AUTH","pin":...} first,
 * then binary frames of [0x01 marker][JPEG bytes].
 */
class PhoneScreenStreamer(
    private val serverIp: String,
    private val port: Int,
    private val pin: String,
) {
    companion object {
        private const val TAG = "PhoneScreenStreamer"
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(java.time.Duration.ofSeconds(15))
        .build()
    private var webSocket: WebSocket? = null

    @Volatile private var isAuthed = false

    fun connect() {
        val request = Request.Builder()
            .url("ws://$serverIp:$port")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Connected to PC, sending AUTH")
                ws.send("""{"type":"AUTH","pin":"$pin"}""")
                isAuthed = true
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closed: $reason")
                isAuthed = false
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Failure: ${t.message}")
                isAuthed = false
            }
        })
    }

    /** Called from the capture thread (ImageReader callback) - not the main thread. */
    fun sendFrame(bitmap: Bitmap, jpegQuality: Int = 55) {
        if (!isAuthed) return
        try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)
            val jpeg = stream.toByteArray()
            val payload = ByteArray(jpeg.size + 1)
            payload[0] = 0x01
            System.arraycopy(jpeg, 0, payload, 1, jpeg.size)
            webSocket?.send(payload.toByteString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send frame: ${e.message}")
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Streamer closing")
        webSocket = null
        isAuthed = false
    }
}
