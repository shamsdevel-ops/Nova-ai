package com.nova.ai.service

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.*
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        connectWebSocket()
        setupVirtualDisplay()
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "NovaScreenCapture"
        val channel = NotificationChannel(channelId, "Nova Screen Service", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Nova AI Active")
            .setContentText("Screen awareness is streaming...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        startForeground(101, notification)
    }

    private fun connectWebSocket() {
        val request = Request.Builder().url("ws://10.0.2.2:8000/ws/stream").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
            }
        })
    }

    private fun setupVirtualDisplay() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels / 2
        val height = metrics.heightPixels / 2
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            processAndStreamFrame(bitmap)
        }, null)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "NovaCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun processAndStreamFrame(bitmap: Bitmap) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
        val byteArray = stream.toByteArray()
        webSocket?.send(byteArray.toByteString())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        webSocket?.close(1000, "Service stopped")
        super.onDestroy()
    }
}
