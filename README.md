# 隔空手势 AirGesture

用**前置摄像头**识别手部，通过**无障碍服务**把动作变成真实的屏幕操作。
不碰屏幕就能翻视频、点页面。

## 两种模式（App 内可切换，切换后立即生效）

### 翻页模式
| 手势 | 动作 |
|---|---|
| 手/食指向上移动 | 向下滚动 → 下一个视频 |
| 手/食指向下移动 | 向上滚动 → 上一个视频 |
| 捏合（可选） | 点击屏幕中心 |

### 鼠标模式
| 手势 | 动作 |
|---|---|
| 移动手 | 屏幕上的绿色圆点光标跟着动 |
| 捏合一下 | 在光标位置点击 |

## 防误触设计

- **进入预热**：手刚被识别到的前几帧不响应，避免"出现的一瞬间"误触发
- **贴边不响应**：指尖进入画面边缘 7%/8% 区域（即将离开或刚离开）时，一律不做动作
- **捏合严格化**：必须「先张开 → 再捏住 → 保持约 0.32 秒且不移动」才算点击，移动中捏合直接作废
- **滑动需单调**：中途反向超过 45% 直接作废，必须一次性完成
- **动作冷却**：两次动作间隔至少 0.9 秒
- **点击可关闭**：App 里可一键关掉「捏合点击」，只保留滑动

## 降耗措施

- 推理限帧：约 11 fps（不是每帧都跑模型）
- 优先 **GPU delegate**，不支持时自动回落 CPU
- 采集分辨率 480×360
- **熄屏时跳过推理**

## 目录结构

```
AirGesture/
├── .github/workflows/build.yml        # GitHub Actions 云编译
├── app/src/main/java/com/airgesture/
│   ├── MainActivity.kt                # 权限引导 + 模式/灵敏度/点击开关
│   ├── AirGestureService.kt           # 前台服务：相机 + 调度 + 模式切换
│   ├── HandTracker.kt                 # MediaPipe 21 点，输出指尖/捏合比/贴边
│   ├── SwipeRecognizer.kt             # 手势状态机（纯逻辑）
│   ├── GestureAccessibilityService.kt # 注入滑动/点击
│   ├── CursorOverlay.kt               # 鼠标模式的绿色光标
│   ├── HudOverlay.kt                  # 顶部状态条
│   └── Prefs.kt                       # 设置存储
└── app/src/main/res/...
```

## 构建

### 方式一：GitHub Actions（推荐）

push 到 `main` 即自动构建，产物在 Actions 运行页底部 **Artifacts**。

模型文件（7.5MB）由 workflow 自动下载，不提交到仓库。

### 方式二：本地编译

需要 JDK 17 + Android SDK(platform 34 / build-tools 34) + Gradle 8.7。

```bash
# 1. 放好模型
mkdir -p app/src/main/assets
curl -L -o app/src/main/assets/hand_landmarker.task \
  https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task

# 2. 指向本地 SDK
echo "sdk.dir=/path/to/android/sdk" > local.properties

# 3. 编译
gradle :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 手机端配置

1. ① 相机权限 → ② 悬浮窗 → ③ 无障碍（**小米要开关两次**）→ ④ 启动
2. 保活：应用管理 → 隔空手势 → 自启动开、省电策略「无限制」、最近任务上锁
3. 顶部状态条会实时显示：`✋ 已识别` / `↑ 下一个` / `手在画面边缘，暂停响应`

## 调参位置

`SwipeRecognizer.kt` 底部：

```kotlin
const val SWIPE_DISTANCE = 0.13f   // 滑动距离阈值，越小越灵敏
const val MIN_SPEED = 0.00045f     // 最小速度（归一化位移/ms）
const val PINCH_OPEN = 0.80f       // 判定"手张开"
const val PINCH_CLOSE = 0.38f      // 判定"捏合"
const val TAP_HOLD_MS = 320L       // 捏住多久才算点击
const val WARMUP_FRAMES = 4        // 手出现后预热帧数
```

`AirGestureService.kt` 顶部：

```kotlin
private const val FRAME_INTERVAL_MS = 90L   // 推理间隔，越大越省电
private const val SMOOTH = 0.45f            // 坐标平滑，越小越稳但越迟钝
```

## 已知限制

- 相机常开，耗电发热仍明显高于普通 App
- 暗光识别率下降
- 手势作用于整个屏幕，不区分 App
- Android 不允许程序自动开启无障碍，必须用户手动开一次
