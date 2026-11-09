# 隔空手势 · 翻页版（AirGesture）

用**前置摄像头**识别手部动作，通过**无障碍服务**把动作变成真实滑动/点击。
不用碰屏幕，就能刷短视频、翻阅读页。

## 手势映射

| 手势 | 动作 |
|---|---|
| 手上移（上滑） | 向下滑动页面 → 下一个视频 / 下一页 |
| 手下移（下滑） | 向上滑动页面 → 上一个视频 / 上一页 |
| 拇指 + 食指捏合 | 点击屏幕中心（播放/暂停） |

识别效果最好的条件：光线充足、手离镜头 **30~80cm**、手掌正对镜头、背景不要太乱。

## 技术栈

- Kotlin + CameraX（前置摄像头取流）
- MediaPipe **HandLandmarker**（21 个手部关键点，端侧推理，不上传画面）
- AccessibilityService + `dispatchGesture`（注入滑动/点击）
- 前台服务 + 悬浮状态条

## 目录结构

```
AirGesture/
├── .github/workflows/build.yml        # 云编译出 APK
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── res/
│   │   ├── xml/accessibility_service_config.xml
│   │   ├── values/strings.xml, themes.xml
│   │   └── drawable/ic_app.xml
│   └── java/com/airgesture/
│       ├── MainActivity.kt             # 权限引导页
│       ├── AirGestureService.kt        # 前台服务：相机 + 识别
│       ├── HandTracker.kt              # MediaPipe 封装
│       ├── SwipeRecognizer.kt          # 手势判定（纯逻辑）
│       ├── GestureAccessibilityService.kt  # 注入手势
│       └── HudOverlay.kt               # 顶部状态条
```

## 方式一：GitHub Actions 云编译（推荐，不用装 Android Studio）

1. 在 GitHub 新建一个**空仓库**（Private 也可以）
2. 把 `AirGesture` 目录里的所有文件推上去：
   ```bash
   cd AirGesture
   git init
   git add .
   git commit -m "init"
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/<仓库名>.git
   git push -u origin main
   ```
3. 打开仓库 → **Actions** 标签 → 等 `Build APK` 跑完（约 3~6 分钟）
4. 点进这次运行 → 页面底部 **Artifacts** → 下载 `AirGesture-debug.zip`
5. 解压得到 `app-debug.apk`，传到手机安装

> 模型文件（约 7MB）由 workflow 自动下载，不需要提交到仓库。

## 方式二：本机 Android Studio

1. 装 Android Studio（自带 JDK + SDK）
2. 先从 [这里](https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task) 下载模型，放到：
   `app/src/main/assets/hand_landmarker.task`
3. Android Studio 打开 `AirGesture` 目录，等待 Gradle 同步
4. Run 或 Build → Build APK(s)

## 手机上的配置步骤

1. 安装后打开 App，按 ①②③④ 顺序点
2. **① 相机权限**：允许
3. **② 悬浮窗权限**：允许（用来显示顶部状态条，不给也能跑，只是没提示）
4. **③ 无障碍**：跳到系统设置 → 已下载的应用 → **隔空手势** → 打开
   - 小米/红米会提示风险，选「确定」
   - **小米经常需要开关两次**，开完回来看是否还是开启状态
5. **④ 启动**：回到 App 点一下，顶部出现「隔空手势：已启动」
6. 打开抖音/小说/任意 App，把手举到前置摄像头前试手势

### 小米/红米必做的保活

- 设置 → 应用管理 → 隔空手势 → **自启动** 打开
- 同页 → **省电策略 → 无限制**
- 最近任务 → 给 App **上锁**
- 失效时重新打开 App 点一次「启动」

## 调参

所有阈值都在 `SwipeRecognizer.kt` 底部：

```kotlin
const val SWIPE_THRESHOLD = 0.12f   // 变大 = 更难触发，变小 = 更灵敏
const val PINCH_THRESHOLD = 0.06f   // 捏合判定距离
const val COOLDOWN_MS = 700L        // 两次动作最小间隔
```

`HandTracker.kt` 里可以调检测置信度和手数量。

## 已知限制

- 相机常开，**耗电和发热明显**，建议只在需要时启动
- 暗光环境识别率会明显下降
- 手势是"整屏滑动"，不区分具体 App，所以任何 App 都会响应（包括桌面）
- Android 不允许程序自动开无障碍，必须用户手动开一次

## 许可证

个人使用。请在遵守各 App 用户协议的前提下使用。
