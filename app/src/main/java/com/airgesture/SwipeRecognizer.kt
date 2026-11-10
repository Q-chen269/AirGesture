package com.airgesture

import kotlin.math.abs
import kotlin.math.max

/**
 * 手势状态机（纯逻辑，不依赖 Android）。
 *
 * 设计要点：
 * 1) 手刚进入画面要预热若干帧，避免"识别到的一瞬间"误触发；
 * 2) 手在画面边缘（准备离开/刚离开）时不做任何动作；
 * 3) 捏合必须满足：先张开 → 再合拢 → 保持不动若干毫秒，避免一动就点；
 * 4) 滑动要求单调位移 + 速度足够，中途反向直接作废。
 */
class SwipeRecognizer {

    enum class Action { NEXT, PREV, TAP }

    var sensitivity: Float = 1.0f
    var tapEnabled: Boolean = true
    var swipeEnabled: Boolean = true

    private data class Sample(val x: Float, val y: Float, val t: Long)

    private val samples = ArrayDeque<Sample>()

    private var present = false
    private var framesSinceAppear = 0
    private var lastSeenAt = 0L
    private var lastActionAt = 0L

    private var pinchArmed = false
    private var pinchStartAt = 0L
    private var pinchStartX = 0f
    private var pinchStartY = 0f

    /** 手离开画面时调用（每帧没检测到手都会调） */
    fun onHandLost() {
        present = false
        framesSinceAppear = 0
        samples.clear()
        pinchArmed = false
        pinchStartAt = 0L
    }

    fun reset() = onHandLost()

    fun feed(x: Float, y: Float, pinch: Float, now: Long): Action? {
        // 手刚出现：预热，不响应
        if (!present) {
            present = true
            framesSinceAppear = 0
            samples.clear()
            pinchArmed = false
            pinchStartAt = 0L
            lastSeenAt = now
            return null
        }

        // 中途丢帧太久，按"重新出现"处理
        if (now - lastSeenAt > GAP_RESET_MS) {
            framesSinceAppear = 0
            samples.clear()
            pinchArmed = false
            pinchStartAt = 0L
        }
        lastSeenAt = now
        framesSinceAppear++
        if (framesSinceAppear <= WARMUP_FRAMES) return null

        val sens = sensitivity.coerceIn(0.5f, 1.8f)
        val coolingDown = now - lastActionAt < COOLDOWN_MS

        // ---------------- 捏合点击 ----------------
        if (tapEnabled) {
            val opened = pinch > PINCH_OPEN
            val closed = pinch < PINCH_CLOSE * sens

            if (opened) {
                pinchArmed = true
                pinchStartAt = 0L
            } else if (closed && pinchArmed) {
                if (pinchStartAt == 0L) {
                    pinchStartAt = now
                    pinchStartX = x
                    pinchStartY = y
                } else {
                    val moved = abs(x - pinchStartX) + abs(y - pinchStartY)
                    val held = now - pinchStartAt
                    if (moved > TAP_MAX_MOVE) {
                        // 捏着还在移动 → 不算点击
                        pinchStartAt = 0L
                        pinchArmed = false
                    } else if (held >= TAP_HOLD_MS && !coolingDown) {
                        lastActionAt = now
                        pinchArmed = false
                        pinchStartAt = 0L
                        samples.clear()
                        return Action.TAP
                    }
                }
                // 捏着的时候不判滑动
                push(x, y, now)
                return null
            } else if (!closed) {
                pinchStartAt = 0L
            }
        }

        // ---------------- 上下滑动 ----------------
        if (!swipeEnabled || coolingDown) {
            push(x, y, now)
            return null
        }

        push(x, y, now)
        if (samples.size < 3) return null

        val first = samples.first()
        val last = samples.last()
        val span = last.t - first.t
        if (span < MIN_SPAN_MS || span > MAX_SPAN_MS) return null

        val dy = last.y - first.y
        val dx = last.x - first.x
        val threshold = SWIPE_DISTANCE / sens

        if (abs(dy) < threshold) return null
        if (abs(dy) < abs(dx) * 1.6f) return null
        if (abs(dy) / span < MIN_SPEED / sens) return null
        if (!isMonotonic(first.y, dy)) return null

        lastActionAt = now
        samples.clear()
        return if (dy < 0) Action.NEXT else Action.PREV
    }

    /** 位移过程中不能有明显反向 */
    private fun isMonotonic(baseY: Float, dy: Float): Boolean {
        val sign = if (dy < 0) -1f else 1f
        var worstBack = 0f
        for (s in samples) {
            val d = (s.y - baseY) * sign
            if (d < 0f) worstBack = max(worstBack, -d)
        }
        return worstBack < abs(dy) * 0.45f
    }

    private fun push(x: Float, y: Float, now: Long) {
        samples.addLast(Sample(x, y, now))
        while (samples.size > 2 && now - samples.first().t > WINDOW_MS) {
            samples.removeFirst()
        }
    }

    private companion object {
        const val COOLDOWN_MS = 900L
        const val WINDOW_MS = 450L
        const val MIN_SPAN_MS = 120L
        const val MAX_SPAN_MS = 500L
        const val SWIPE_DISTANCE = 0.13f
        const val MIN_SPEED = 0.00045f
        const val PINCH_OPEN = 0.80f
        const val PINCH_CLOSE = 0.38f
        const val TAP_HOLD_MS = 320L
        const val TAP_MAX_MOVE = 0.035f
        const val WARMUP_FRAMES = 4
        const val GAP_RESET_MS = 200L
    }
}
