package com.airgesture

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.hypot

/**
 * MediaPipe HandLandmarker 封装。
 *
 * 默认走 CPU：GPU delegate 在部分机型上会"创建成功但不返回结果"，
 * 表现为完全检测不到手，所以默认关闭，需要时再打开。
 */
class HandTracker(
    private val context: Context,
    private val preferGpu: Boolean = false,
    private val onHand: (x: Float, y: Float, pinch: Float, nearEdge: Boolean) -> Unit,
    private val onIdle: () -> Unit
) {

    private val landmarker: HandLandmarker
    val delegateName: String

    init {
        if (preferGpu) {
            val gpu = runCatching { create(Delegate.GPU) }.getOrNull()
            if (gpu != null) {
                landmarker = gpu
                delegateName = "GPU"
            } else {
                landmarker = create(Delegate.CPU)
                delegateName = "CPU"
            }
        } else {
            landmarker = create(Delegate.CPU)
            delegateName = "CPU"
        }
    }

    private fun create(delegate: Delegate): HandLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(delegate)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.35f)
            .setMinHandPresenceConfidence(0.35f)
            .setMinTrackingConfidence(0.35f)
            .setResultListener { result, _ -> handle(result) }
            .setErrorListener { _ -> }
            .build()

        return HandLandmarker.createFromOptions(context, options)
    }

    fun detect(bitmap: Bitmap, timestampMs: Long) {
        landmarker.detectAsync(BitmapImageBuilder(bitmap).build(), timestampMs)
    }

    fun close() {
        runCatching { landmarker.close() }
    }

    private fun handle(result: HandLandmarkerResult) {
        val hand = result.landmarks().firstOrNull()
        if (hand == null || hand.size < 21) {
            onIdle()
            return
        }

        val tip = hand[8]      // 食指尖
        val thumb = hand[4]    // 拇指尖
        val wrist = hand[0]
        val mcp = hand[9]

        val palm = hypot(wrist.x() - mcp.x(), wrist.y() - mcp.y()).coerceAtLeast(1e-4f)
        val pinch = hypot(thumb.x() - tip.x(), thumb.y() - tip.y()) / palm

        val x = tip.x()
        val y = tip.y()
        // 贴边判定放宽到 5%/6%，避免手在画面偏上/偏下就完全没反应
        val nearEdge = x < 0.05f || x > 0.95f || y < 0.06f || y > 0.94f

        onHand(x, y, pinch, nearEdge)
    }

    private companion object {
        const val MODEL_ASSET = "hand_landmarker.task"
    }
}
