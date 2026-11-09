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
 * 包一层 MediaPipe HandLandmarker，输出手心中心点和「拇指-食指」距离。
 */
class HandTracker(
    context: Context,
    private val onHand: (x: Float, y: Float, pinch: Float) -> Unit,
    private val onIdle: () -> Unit
) {

    private val landmarker: HandLandmarker

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(Delegate.CPU)
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

        landmarker = HandLandmarker.createFromOptions(context, options)
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
        // 0 = 手腕，9 = 中指根部；取中点当手心
        val wrist = hand[0]
        val mcp = hand[9]
        val cx = (wrist.x() + mcp.x()) / 2f
        val cy = (wrist.y() + mcp.y()) / 2f

        // 4 = 拇指尖，8 = 食指尖
        val thumb = hand[4]
        val index = hand[8]
        val pinch = hypot(thumb.x() - index.x(), thumb.y() - index.y())

        onHand(cx, cy, pinch)
    }

    private companion object {
        const val MODEL_ASSET = "hand_landmarker.task"
    }
}
