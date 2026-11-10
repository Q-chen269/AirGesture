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
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var modePaging: Button
    private lateinit var modePointer: Button
    private lateinit var sensLabel: TextView

    private val prefs by lazy { Prefs.get(this) }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        updateModeButtons()
        askNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 110, 56, 56)
        }

        root.addView(TextView(this).apply {
            text = "隔空手势"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })

        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 24, 0, 12)
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

        // ---------------- 设置 ----------------
        root.addView(section("手势模式"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modePaging = Button(this).apply {
            text = "翻页模式"
            setAllCaps(false)
            setOnClickListener { setMode(Prefs.MODE_PAGING) }
        }
        modePointer = Button(this).apply {
            text = "鼠标模式"
            setAllCaps(false)
            setOnClickListener { setMode(Prefs.MODE_POINTER) }
        }
        row.addView(
            modePaging,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = 12 }
        )
        row.addView(
            modePointer,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(row)

        root.addView(hint("翻页：手向上移 = 下一个，向下移 = 上一个。\n" +
            "鼠标：移动手 = 移动光标，捏合一下 = 点击光标处。\n" +
            "切换后立即生效，不用重启服务。"))

        root.addView(section("灵敏度"))
        sensLabel = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 4)
        }
        root.addView(sensLabel)
        val initial = prefs.getFloat(Prefs.KEY_SENS, 1.0f)
        updateSensLabel(initial)
        root.addView(SeekBar(this).apply {
            max = 130
            progress = (((initial - 0.5f) * 100f).toInt()).coerceIn(0, 130)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    val v = 0.5f + value / 100f
                    updateSensLabel(v)
                    if (fromUser) prefs.edit().putFloat(Prefs.KEY_SENS, v).apply()
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })

        root.addView(section("点击手势"))
        root.addView(CheckBox(this).apply {
            text = "启用捏合点击"
            isChecked = prefs.getBoolean(Prefs.KEY_TAP, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(Prefs.KEY_TAP, checked).apply()
            }
        })
        root.addView(hint("捏合点击需要：先张手 → 再捏住 → 保持约 0.3 秒不动。\n" +
            "如果还是容易误触（比如进直播间），把这项关掉即可。"))

        root.addView(section("使用提醒"))
        root.addView(hint(
            "1. 小米/红米的「无障碍」有二次校验，开关要开两次。\n" +
                "2. 应用管理 → 隔空手势：打开自启动、省电策略设为「无限制」、最近任务上锁。\n" +
                "3. 光线充足、手离镜头 30~80cm、手掌正对镜头时最稳。\n" +
                "4. 手离开画面或贴到画面边缘时不会触发任何动作。"
        ))

        return ScrollView(this).apply { addView(root) }
    }

    private fun section(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 48, 0, 12)
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#777777"))
        setPadding(0, 12, 0, 12)
    }

    private fun addButton(root: LinearLayout, label: String, onClick: () -> Unit) {
        root.addView(
            Button(this).apply {
                text = label
                setAllCaps(false)
                setOnClickListener { onClick() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        )
    }

    private fun setMode(mode: Int) {
        prefs.edit().putInt(Prefs.KEY_MODE, mode).apply()
        updateModeButtons()
    }

    private fun updateModeButtons() {
        if (!::modePaging.isInitialized) return
        val mode = prefs.getInt(Prefs.KEY_MODE, Prefs.MODE_PAGING)
        modePaging.alpha = if (mode == Prefs.MODE_PAGING) 1f else 0.45f
        modePointer.alpha = if (mode == Prefs.MODE_POINTER) 1f else 0.45f
    }

    private fun updateSensLabel(value: Float) {
        sensLabel.text = String.format(Locale.US, "灵敏度 %.1f（越大越容易触发）", value)
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
        val mode = if (prefs.getInt(Prefs.KEY_MODE, Prefs.MODE_PAGING) == Prefs.MODE_POINTER)
            "鼠标模式" else "翻页模式"

        statusView.text = buildString {
            append(if (camera) "✅ 相机" else "❌ 相机")
            append("   ")
            append(if (overlay) "✅ 悬浮窗" else "❌ 悬浮窗")
            append("   ")
            append(if (accessibility) "✅ 无障碍" else "❌ 无障碍")
            append("\n当前：$mode · ")
            append(if (AirGestureService.running) "运行中" else "未运行")
        }
        updateModeButtons()
    }
}
