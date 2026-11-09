package com.airgesture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.Executors

/**
 * 前台服务：长期持有前置摄像头，逐帧交给 MediaPipe 识别，
 * 识别到手势后调用无障碍服务注入滑动/点击。
 */
class AirGestureService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "air_gesture"
        const val NOTI_ID = 1001

        @Volatile
        var running: Boolean = false
            private set
    }

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = SwipeRecognizer()

    private var cameraProvider: ProcessCameraProvider? = null
    private var tracker: HandTracker? = null
    private var hud: HudOverlay? = null
    private var bound = false

    override fun onCreate() {
        super.onCreate()
        running = true
        startForegroundCompat()
        hud = HudOverlay(this).also { it.show() }
    }

    // Service 没有 onStart()，生命周期入口是 onStartCommand
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!bound) startCamera()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        bound = false
        runCatching { tracker?.close() }
        runCatching { cameraProvider?.unbindAll() }
        runCatching { hud?.hide() }
        analyzerExecutor.shutdown()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTI_ID, notification)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                tracker?.close()
                tracker = HandTracker(
                    context = this,
                    onHand = { x, y, pinch -> onHand(x, y, pinch) },
                    onIdle = { hud?.setStatus("隔空手势：未检测到手") }
                )

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetResolution(Size(480, 640))
                    .build()

                analysis.setAnalyzer(analyzerExecutor) { proxy -> analyzeFrame(proxy) }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                bound = true
                hud?.setStatus("隔空手势：已启动，把手举到镜头前")
            } catch (e: Exception) {
                hud?.setStatus("启动失败：${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        try {
            tracker?.detect(proxy.toBitmapRotated(), SystemClock.uptimeMillis())
        } catch (_: Exception) {
            // 单帧失败直接跳过
        } finally {
            proxy.close()
        }
    }

    private fun onHand(x: Float, y: Float, pinch: Float) {
        val action = recognizer.feed(x, y, pinch, SystemClock.uptimeMillis())
        if (action == null) {
            hud?.setStatus("隔空手势：✋ 已识别")
            return
        }
        val service = GestureAccessibilityService.instance
        if (service == null) {
            hud?.setStatus("请先在设置里开启无障碍服务")
            return
        }
        when (action) {
            SwipeRecognizer.Action.NEXT -> {
                service.verticalSwipe(up = true)
                hud?.setStatus("↑ 下一页")
            }
            SwipeRecognizer.Action.PREV -> {
                service.verticalSwipe(up = false)
                hud?.setStatus("↓ 上一页")
            }
            SwipeRecognizer.Action.TAP -> {
                service.tapCenter()
                hud?.setStatus("● 点击")
            }
        }
    }

    private fun ImageProxy.toBitmapRotated(): Bitmap {
        val buffer = planes[0].buffer
        buffer.rewind()
        val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)

        val degrees = imageInfo.rotationDegrees
        if (degrees == 0) return raw
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "隔空手势", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("隔空手势运行中")
            .setContentText("上滑=下一页 · 下滑=上一页 · 捏合=点击")
            .setSmallIcon(R.drawable.ic_app)
            .setOngoing(true)
            .setContentIntent(intent)
            .build()
    }
}
