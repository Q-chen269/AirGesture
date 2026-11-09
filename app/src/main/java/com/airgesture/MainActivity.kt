package com.airgesture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        askNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 120, 56, 56)
        }

        root.addView(TextView(this).apply {
            text = "隔空手势 · 翻页模式"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "上滑 → 下一页\n下滑 → 上一页\n捏合 → 点击 / 播放暂停\n\n不用碰屏幕，前置摄像头识别手势。"
            textSize = 16f
            setPadding(0, 36, 0, 36)
        })

        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 0, 0, 36)
        }
        root.addView(statusView)

        addButton(root, "① 授予相机权限") {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
        addButton(root, "② 授予悬浮窗权限") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        addButton(root, "③ 开启无障碍服务") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton(root, "④ 启动 / 停止") { toggleService() }

        root.addView(TextView(this).apply {
            text = "\n使用说明：\n" +
                "1. 小米/红米会二次校验无障碍，开关要开两次。\n" +
                "2. 去「应用管理 → 隔空手势」，打开自启动、省电策略设为无限制、最近任务上锁。\n" +
                "3. 手势在光线充足、手离镜头 30~80cm 时最稳。\n" +
                "4. 识别不准可在 SwipeRecognizer.kt 里调 SWIPE_THRESHOLD。"
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 48, 0, 0)
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun addButton(root: LinearLayout, label: String, onClick: () -> Unit) {
        val button = Button(this).apply {
            text = label
            setAllCaps(false)
            setOnClickListener { onClick() }
        }
        root.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
        )
    }

    private fun toggleService() {
        val intent = Intent(this, AirGestureService::class.java)
        if (AirGestureService.running) {
            stopService(intent)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                cameraPermission.launch(Manifest.permission.CAMERA)
                return
            }
            ContextCompat.startForegroundService(this, intent)
        }
        statusView.postDelayed({ refresh() }, 600)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refresh() {
        if (!::statusView.isInitialized) return
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val accessibility = GestureAccessibilityService.instance != null

        statusView.text = buildString {
            append(if (camera) "✅ 相机" else "❌ 相机")
            append("    ")
            append(if (overlay) "✅ 悬浮窗" else "❌ 悬浮窗")
            append("    ")
            append(if (accessibility) "✅ 无障碍" else "❌ 无障碍")
            append("\n当前状态：")
            append(if (AirGestureService.running) "运行中" else "未运行")
        }
    }
}
