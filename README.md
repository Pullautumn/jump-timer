# 跳转计时器

页面 / App 跳转时，悬浮窗从 1 开始计时。

## 它做什么

```
你切换到另一个 App    →  悬浮窗立刻重置，显示 ⏱ 1 s
你在 App 内换页面      →  悬浮窗立刻重置，显示 ⏱ 1 s
弹出对话框            →  悬浮窗立刻重置，显示 ⏱ 1 s
```

## 原理

用 Android 无障碍服务（AccessibilityService）监听系统级的窗口变化事件：

```java
TYPE_WINDOW_STATE_CHANGED   // 窗口状态变化
TYPE_WINDOWS_CHANGED        // 窗口列表变化
```

事件里带 `packageName`（哪个 App）和 `className`（哪个页面），任一变化就判定为跳转。

## 为什么不用 Xposed / FPA 模块

| | FPA 模块 | 本方案（无障碍） |
|---|---|---|
| 监控范围 | 只能监控 patch 过的**单个** App | **所有 App** |
| 跨 App 跳转 | **完全看不到**（进程被切后台） | 能 |
| App 更新后 | 要重新 patch | 不受影响 |
| 签名问题 | 会触发校验 | 无 |

关键：FPA 模块注入在目标 App 的进程里，用户切到别的 App 后模块就"失明"了。

## 检测能力对比

| App 渲染方式 | 本方案 | FPA 模块 |
|---|---|---|
| 传统多 Activity | ✅ | ✅ |
| 单 Activity + Fragment | ❌ 只看到外壳 | ✅ |
| 单 Activity + WebView | ❌ 只看到外壳 | ✅ |
| Flutter / Compose | ❌ | ❌ |
| 跨 App 切换 | ✅ | ❌ |

## 构建

### 方式一：GitHub Actions（推荐，零配置）

1. 在 GitHub 建一个新仓库
2. 把本项目所有文件上传（网页拖拽即可）
3. 打开仓库的 **Actions** 标签页
4. 等 workflow 自动跑完（约 3 分钟）
5. 在 **Artifacts** 里下载 `JumpTimer-APK`

### 方式二：本地构建

需要 Android SDK（含 `platforms/android-34` 和 `build-tools/34.0.0`）。

Windows 下双击 `build.bat`，产物在 `dist/JumpTimer.apk`。

## 安装与使用

```
1. adb install -r dist/JumpTimer.apk
   或直接把 APK 传到手机点击安装

2. 打开 App，点「① 授予悬浮窗权限」

3. 点「② 开启无障碍服务」
   → 跳转到系统设置
   → 在「已下载的应用」里找到「跳转计时器」
   → 打开开关，确认授权

4. 回到桌面，悬浮窗出现，显示「⏱ 1 s 等待跳转」

5. 切换任意 App / 页面，秒数立刻重置为 1

6. 悬浮窗可按住拖动到任意位置
```

## 目录结构

```
jump_timer/
├── build.bat                          Windows 本地构建脚本
├── .github/workflows/build.yml        GitHub Actions 自动构建
├── README.md
└── src/
    ├── AndroidManifest.xml
    ├── com/timer/jump/
    │   ├── JumpDetectService.java     无障碍服务（核心）
    │   ├── TimerOverlay.java          悬浮窗 + 计时逻辑
    │   └── MainActivity.java          权限申请界面
    └── res/
        ├── values/strings.xml
        └── xml/accessibility_service_config.xml
```

## 可调参数

| 位置 | 参数 | 默认 | 说明 |
|---|---|---|---|
| `JumpDetectService` | `DEBOUNCE_MS` | 200 | 去抖间隔，防止一次跳转算多次 |
| `TimerOverlay` | `TICK_MS` | 100 | 刷新频率，100ms 更新一次秒数 |
| `TimerOverlay` | `lp.y` | 180dp | 悬浮窗初始纵向位置 |
| `TimerOverlay` | `0xC8000000` | — | 背景色（ARGB，前两位是透明度） |

## 常见问题

**Q: 秒数不动**
检查无障碍服务是否真的开启（设置 → 无障碍 → 已下载的应用）。部分机型需要把 App 加入「电池优化白名单」。

**Q: 小米/华为重启后失效**
MIUI / EMUI 会杀后台。到「设置 → 应用 → 跳转计时器 → 省电策略」改成「不限制」。

**Q: 某些 App 不触发**
Flutter 或 Jetpack Compose 写的 App，页面切换在同一个 Activity 内完成，不产生系统级窗口事件。这是无障碍方案的固有局限。

**Q: 输入法弹出也会计时**
已在 `isNoise()` 里过滤（`inputmethod`、`ime`、`systemui`、`permissioncontroller`）。如果还漏了，在那个方法里加规则即可。

## 隐私

本应用：

- 不联网（无 INTERNET 权限）
- 不收集任何数据
- 不上传任何内容
- 所有判断都在本机内存中完成

无障碍服务声明里已写明用途。
