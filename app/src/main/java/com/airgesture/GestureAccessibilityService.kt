package com.airgesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent

/**
 * 只做一件事：把上层识别出的手势，注入成真实的屏幕操作。
 * 必须由用户在系统设置里手动开启（Android 不允许程序自动开启）。
 */
class GestureAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GestureAccessibilityService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** up = true 表示向上滑（下一页） */
    fun verticalSwipe(up: Boolean) {
        main.post {
            val dm: DisplayMetrics = resources.displayMetrics
            val x = dm.widthPixels / 2f
            val path = Path()
            if (up) {
                path.moveTo(x, dm.heightPixels * 0.78f)
                path.lineTo(x, dm.heightPixels * 0.22f)
            } else {
                path.moveTo(x, dm.heightPixels * 0.35f)
                path.lineTo(x, dm.heightPixels * 0.85f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 200L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            runCatching { dispatchGesture(gesture, null, null) }
        }
    }

    fun tapCenter() {
        main.post {
            val dm: DisplayMetrics = resources.displayMetrics
            val path = Path().apply { moveTo(dm.widthPixels / 2f, dm.heightPixels / 2f) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            runCatching { dispatchGesture(gesture, null, null) }
        }
    }
}
