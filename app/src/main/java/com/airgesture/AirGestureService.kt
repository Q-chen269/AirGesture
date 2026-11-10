package com.airgesture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.PowerManager
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
 * 前台服务：长期持有前置摄像头，逐帧交给 MediaPipe 识别。
 *
 * 降耗措施：
 *  - 限制推理帧率（约 11fps），不是每帧都跑模型
 *  - 优先用 GPU delegate，失败回落 CPU
 *  - 熄屏时直接跳过推理
 *  - 采集分辨率 480x360
 */
class AirGestureService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "air_gesture"
        const val NOTI_ID = 1001

        private const val FRAME_INTERVAL_MS = 90L
        private const val SMOOTH = 0.45f

        @Volatile
        var running: Boolean = false
            private set
    }

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = SwipeRecognizer()

    private var cameraProvider: ProcessCameraProvider? = null
    private var tracker: HandTracker? = null
    private var hud: HudOverlay? = null
    private var cursor: CursorOverlay? = null
    private var powerManager: PowerManager? = null

    private var bound = false
    private var lastDetectAt = 0L
    private var lastErrorAt = 0L
    private var frameCount = 0L
    private var detectionCount = 0L

    private var mode = Prefs.MODE_PAGING
    private var hasPose = false
    private var smoothX = 0.5f
    private var smoothY = 0.5f
    private var cursorX = 0f
    private var cursorY = 0f

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val p = Prefs.get(this)
        when (key) {
            Prefs.KEY_MODE -> {
                mode = p.getInt(Prefs.KEY_MODE, Prefs.MODE_PAGING)
                applyMode()
            }
            Prefs.KEY_SENS -> recognizer.sensitivity = p.getFloat(Prefs.KEY_SENS, 1.0f)
            Prefs.KEY_TAP -> recognizer.tapEnabled = p.getBoolean(Prefs.KEY_TAP, true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        val p = Prefs.get(this)
        mode = p.getInt(Prefs.KEY_MODE, Prefs.MODE_PAGING)
        recognizer.sensitivity = p.getFloat(Prefs.KEY_SENS, 1.0f)
        recognizer.tapEnabled = p.getBoolean(Prefs.KEY_TAP, true)

        startForegroundCompat()
        hud = HudOverlay(this).also { it.show() }
        cursor = CursorOverlay(this)
        applyMode()
        p.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!bound) startCamera()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        bound = false
        runCatching { Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefListener) }
        runCatching { tracker?.close() }
        runCatching { cameraProvider?.unbindAll() }
        runCatching { hud?.hide() }
        runCatching { cursor?.hide() }
        analyzerExecutor.shutdown()
        super.onDestroy()
    }

    private fun applyMode() {
        if (mode == Prefs.MODE_POINTER) {
            recognizer.swipeEnabled = false
            cursor?.show()
            hud?.setStatus("鼠标模式：移动手 = 光标，捏合 = 点击")
        } else {
            recognizer.swipeEnabled = true
            cursor?.hide()
            hud?.setStatus("翻页模式：手上移 = 下一个，下移 = 上一个")
        }
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
                    preferGpu = false,
                    onHand = { x, y, pinch, edge -> onHand(x, y, pinch, edge) },
                    onIdle = { onIdle() }
                )

                val useCase = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetResolution(Size(480, 640))
                    .build()

                useCase.setAnalyzer(analyzerExecutor) { proxy -> analyzeFrame(proxy) }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, useCase)
                bound = true
                hud?.setStatus("已启动（${tracker?.delegateName}），把手放进画面")
            } catch (e: Exception) {
                hud?.setStatus("启动失败：${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        try {
            val now = SystemClock.uptimeMillis()
            // 限帧 + 熄屏不推理，降发热
            if (now - lastDetectAt < FRAME_INTERVAL_MS) return
            if (powerManager?.isInteractive == false) return
            lastDetectAt = now
            frameCount++
            tracker?.detect(proxy.toBitmapRotated(), now)
        } catch (e: Exception) {
            val now = SystemClock.uptimeMillis()
            if (now - lastErrorAt > 3000) {
                lastErrorAt = now
                hud?.setStatus("取帧失败：${e.javaClass.simpleName}")
            }
        } finally {
            proxy.close()
        }
    }

    private fun onHand(x: Float, y: Float, pinch: Float, nearEdge: Boolean) {
        detectionCount++
        // 贴边 = 手要走/刚走，不做任何动作
        if (nearEdge) {
            recognizer.onHandLost()
            hasPose = false
            hud?.setStatus("手在画面边缘，暂停响应")
            return
        }

        if (!hasPose) {
            smoothX = x
            smoothY = y
            hasPose = true
        } else {
            smoothX += (x - smoothX) * SMOOTH
            smoothY += (y - smoothY) * SMOOTH
        }

        if (mode == Prefs.MODE_POINTER) updateCursor()

        val action = recognizer.feed(smoothX, smoothY, pinch, SystemClock.uptimeMillis())
        if (action == null) {
            hud?.setStatus(
                if (mode == Prefs.MODE_POINTER) "鼠标模式 ✋" else "✋ 已识别"
            )
            return
        }

        val service = GestureAccessibilityService.instance
        if (service == null) {
            hud?.setStatus("请先开启无障碍服务")
            return
        }

        when (action) {
            SwipeRecognizer.Action.NEXT -> {
                service.verticalSwipe(up = true)
                hud?.setStatus("↑ 下一个")
            }
            SwipeRecognizer.Action.PREV -> {
                service.verticalSwipe(up = false)
                hud?.setStatus("↓ 上一个")
            }
            SwipeRecognizer.Action.TAP -> {
                if (mode == Prefs.MODE_POINTER) {
                    service.tapAt(cursorX, cursorY)
                    hud?.setStatus("● 点击")
                } else {
                    service.tapCenter()
                    hud?.setStatus("● 点击")
                }
            }
        }
    }

    private fun onIdle() {
        recognizer.onHandLost()
        hasPose = false
        hud?.setStatus("未检测到手（帧$frameCount/手$detectionCount）")
    }

    private fun updateCursor() {
        val dm = resources.displayMetrics
        // 前置摄像头左右是镜像的，x 取反才符合直觉
        cursorX = (1f - smoothX) * dm.widthPixels
        cursorY = smoothY * dm.heightPixels
        cursor?.move(cursorX, cursorY)
    }

    private fun ImageProxy.toBitmapRotated(): Bitmap {
        val plane = planes[0]
        val buffer = plane.buffer
        buffer.rewind()

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        // 有些机型 buffer 每行尾部有对齐填充，直接拷会花屏/越界，这里按真实行宽处理
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        val raw = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)

        val cropped = if (paddedWidth != width) {
            Bitmap.createBitmap(raw, 0, 0, width, height)
        } else {
            raw
        }

        val degrees = imageInfo.rotationDegrees
        if (degrees == 0) return cropped
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
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
            .setContentText("翻页：上下移动手 · 鼠标：移动手+捏合点击")
            .setSmallIcon(R.drawable.ic_app)
            .setOngoing(true)
            .setContentIntent(intent)
            .build()
    }
}
