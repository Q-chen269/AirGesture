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
 * 输出：
 *  - x/y      食指指尖(8)的归一化坐标（0~1）
 *  - pinch    拇指尖(4)与食指尖(8)距离 / 手掌尺寸（与远近无关，>0.8 张开，<0.38 捏合）
 *  - nearEdge 指尖是否贴边（准备离开/刚离开画面）
 */
class HandTracker(
    private val context: Context,
    private val onHand: (x: Float, y: Float, pinch: Float, nearEdge: Boolean) -> Unit,
    private val onIdle: () -> Unit
) {

    private val landmarker: HandLandmarker =
        runCatching { create(Delegate.GPU) }.getOrElse { create(Delegate.CPU) }

    private fun create(delegate: Delegate): HandLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(delegate)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
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

        // 用手掌尺寸做归一化，避免手离镜头远近导致阈值失效
        val palm = hypot(wrist.x() - mcp.x(), wrist.y() - mcp.y()).coerceAtLeast(1e-4f)
        val pinch = hypot(thumb.x() - tip.x(), thumb.y() - tip.y()) / palm

        val x = tip.x()
        val y = tip.y()
        val nearEdge = x < 0.07f || x > 0.93f || y < 0.08f || y > 0.92f

        onHand(x, y, pinch, nearEdge)
    }

    private companion object {
        const val MODEL_ASSET = "hand_landmarker.task"
    }
}
