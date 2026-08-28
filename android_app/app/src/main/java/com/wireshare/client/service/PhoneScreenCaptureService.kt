package com.wireshare.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wireshare.client.network.PhoneScreenStreamer

/**
 * Feature 3 (phone -> PC mirror). Owns the whole MediaProjection capture
 * pipeline: VirtualDisplay -> ImageReader -> Bitmap -> JPEG -> WebSocket.
 *
 * Must run as a foreground service (Android 10+ requirement for
 * MediaProjection at all; Android 14+ additionally requires the
 * "mediaProjection" foregroundServiceType declared in the manifest, which is
 * done for this service). The ongoing notification is mandatory - Android
 * will not let screen capture run silently in the background.
 */
class PhoneScreenCaptureService : Service() {

    companion object {
        private const val TAG = "PhoneScreenCapture"
        const val ACTION_START = "com.wireshare.client.action.START_SCREEN_CAPTURE"
        const val ACTION_STOP = "com.wireshare.client.action.STOP_SCREEN_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_SERVER_IP = "extra_server_ip"
        const val EXTRA_PIN = "extra_pin"
        const val EXTRA_PORT = "extra_port"
        private const val NOTIF_CHANNEL_ID = "wireshare_screen_share"
        private const val NOTIF_ID = 42
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var streamer: PhoneScreenStreamer? = null

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val handlerThread = HandlerThread("WireShare-ScreenCapture").apply { start() }
    private val handler = Handler(handlerThread.looper)

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val ip = intent.getStringExtra(EXTRA_SERVER_IP)
                val pin = intent.getStringExtra(EXTRA_PIN) ?: ""
                val port = intent.getIntExtra(EXTRA_PORT, 8771)

                if (data == null || ip.isNullOrBlank() || resultCode == -1) {
                    Log.e(TAG, "Missing projection result or server info - stopping.")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(NOTIF_ID, buildNotification())
                startCapture(resultCode, data, ip, port, pin)
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("WireShare")
            .setContentText("Sharing your screen to PC")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Screen Sharing",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startCapture(resultCode: Int, data: Intent, ip: String, port: Int, pin: String) {
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped by system/user.")
                stopCapture()
                stopSelf()
            }
        }, handler)

        val metrics = resources.displayMetrics
        // Downscale capture resolution - full-res + JPEG per frame at native
        // resolution would be far too much data/CPU for a live stream.
        val scale = 0.6
        val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(2)
        val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(2)
        val density = metrics.densityDpi

        streamer = PhoneScreenStreamer(ip, port, pin).also { it.connect() }

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "WireSharePhoneScreen",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener

            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width

                val bitmap = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                val cropped = if (rowPadding == 0) {
                    bitmap
                } else {
                    val c = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                    bitmap.recycle()
                    c
                }

                streamer?.sendFrame(cropped)
                cropped.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Frame processing error: ${e.message}")
            } finally {
                image.close()
            }
        }, handler)

        Log.d(TAG, "Screen capture started (${width}x${height}) -> $ip:$port")
    }

    private fun stopCapture() {
        try {
            virtualDisplay?.release()
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            mediaProjection?.stop()
            streamer?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error during stopCapture: ${e.message}")
        } finally {
            virtualDisplay = null
            imageReader = null
            mediaProjection = null
            streamer = null
        }
    }

    override fun onDestroy() {
        stopCapture()
        handlerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
