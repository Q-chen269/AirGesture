package com.airgesture

import kotlin.math.abs

/**
 * 纯逻辑：把连续的手心坐标序列判定成「上滑 / 下滑 / 捏合」。
 * 不依赖 Android，方便单独调试。
 */
class SwipeRecognizer {

    enum class Action { NEXT, PREV, TAP }

    /** 灵敏度：越大越容易触发，建议 0.5 ~ 2.0 */
    var sensitivity: Float = 1.0f

    private data class Sample(val x: Float, val y: Float, val t: Long)

    private val samples = ArrayDeque<Sample>()
    private var pinching = false
    private var lastActionAt = 0L

    fun feed(x: Float, y: Float, pinchDistance: Float, now: Long): Action? {
        // 动作冷却，避免一次挥手触发多次
        if (now - lastActionAt < COOLDOWN_MS) {
            push(x, y, now)
            return null
        }

        // 捏合：从张开到合拢的瞬间算一次点击
        val isPinch = pinchDistance < PINCH_THRESHOLD * sensitivity
        if (isPinch && !pinching) {
            pinching = true
            lastActionAt = now
            samples.clear()
            return Action.TAP
        }
        if (!isPinch) pinching = false

        push(x, y, now)

        val first = samples.firstOrNull() ?: return null
        val dy = y - first.y
        val dx = x - first.x
        val span = now - first.t
        val threshold = SWIPE_THRESHOLD / sensitivity

        val vertical = abs(dy) > threshold && abs(dy) > abs(dx) * 1.5f
        if (vertical && span in MIN_SPAN_MS..MAX_SPAN_MS) {
            lastActionAt = now
            samples.clear()
            return if (dy < 0) Action.NEXT else Action.PREV
        }
        return null
    }

    fun reset() {
        samples.clear()
        pinching = false
    }

    private fun push(x: Float, y: Float, now: Long) {
        samples.addLast(Sample(x, y, now))
        while (samples.size > 2 && now - samples.first().t > WINDOW_MS) {
            samples.removeFirst()
        }
    }

    private companion object {
        const val COOLDOWN_MS = 700L
        const val WINDOW_MS = 800L
        const val MIN_SPAN_MS = 120L
        const val MAX_SPAN_MS = 600L
        const val SWIPE_THRESHOLD = 0.12f
        const val PINCH_THRESHOLD = 0.06f
    }
}
