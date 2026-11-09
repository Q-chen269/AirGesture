package com.airgesture

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 屏幕顶部的小状态条，告诉用户"手有没有被识别到"。
 * 需要「显示在其他应用上层」权限，没授权就静默不显示。
 */
class HudOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null

    fun show() {
        if (view != null) return
        if (!android.provider.Settings.canDrawOverlays(context)) return

        val tv = TextView(context).apply {
            text = "隔空手势：等待识别"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(0xCC1B5E20.toInt())
            setPadding(32, 16, 32, 16)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        runCatching {
            wm.addView(tv, lp)
            view = tv
        }
    }

    fun setStatus(text: String) {
        view?.post { view?.text = text }
    }

    fun hide() {
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null
    }
}
