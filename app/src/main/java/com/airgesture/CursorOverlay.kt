package com.airgesture

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 鼠标模式下的虚拟光标（一个半透明圆点）。
 * 悬浮窗本身不接收触摸，所以不会挡住下面的 App。
 */
class CursorOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var radiusPx = 0

    fun show() {
        if (view != null) return
        if (!Settings.canDrawOverlays(context)) return

        val density = context.resources.displayMetrics.density
        val size = (44 * density).toInt()
        radiusPx = size / 2

        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC00E676.toInt())
                setStroke((2 * density).toInt(), 0xFFFFFFFF.toInt())
            }
        }

        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 600
        }

        runCatching {
            wm.addView(dot, lp)
            view = dot
            params = lp
        }
    }

    fun move(centerX: Float, centerY: Float) {
        val v = view ?: return
        val p = params ?: return
        p.x = (centerX - radiusPx).toInt()
        p.y = (centerY - radiusPx).toInt()
        runCatching { wm.updateViewLayout(v, p) }
    }

    fun hide() {
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null
        params = null
    }
}
