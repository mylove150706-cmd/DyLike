# 设计文档：MPV FSR 画质增强开关

**日期**：2026-07-19
**分支**：`spike/super-resolution-anime4k`（将基于此分支切 `feat/mpv-super-resolution`）
**状态**：✅ 设计已批准，待转实施计划
**前置 spike**：`docs/superpowers/specs/2026-07-20-mpv-shader-super-resolution-spike.md`

## 背景

用户希望「开启开关后对低分辨率视频进行超分，并以增强后的画质播放」。

经过 spike 验证（详见前置 spike 文档），确认：
- abdallahmehiz/mpv-android-lib:0.1.12 的 user shader hook 链路是通的（染色 shader 实测画面变黄）
- **LUMA / CHROMA hook 生效**，POSTKERNEL / RGB hook 在本 AAR 上不触发
- 之前 spike 报告"shader 完全无效"是误判，真实原因是 Anime4K 不适合实拍、adaptive-sharpen 挂了不生效的 POSTKERNEL

mpv 的渲染目标由 `android-surface-size` 决定，默认等于 SurfaceView 物理像素（手机屏幕分辨率 1256×2672），所以「指定输出到 1080p/2K/4K」这个原需求在 Android 上没有意义——mpv 已经按屏幕最高分辨率渲染，shader 在那个分辨率上工作。

## 目标

提供一个**单一开关**，开启后用 FSR 着色器链（EASU + RCAS）提升低分辨率视频的视觉清晰度。开关位置、语义、shader 选择都已与用户确认。

## 非目标

- ❌ 不实现 1080p/2K/4K 分档（mpv 已按 SurfaceView 分辨率渲染，分档无意义）
- ❌ 不在 ExoPlayer 上做超分（架构成本太高，仅 MPV 内核）
- ❌ 不做「按源视频分辨率智能判断」——开关一开，所有 MPV 播放的视频都走 shader（高分辨率视频走 EASU 是近似 passthrough，无负面影响）
- ❌ 不做离线神经超分（Real-ESRGAN ncnn 等是另一条路线，本设计不覆盖）

## 用户界面

**位置**：`LabSettingsFragment`（实验室设置页），紧贴 `swLabMpvSequentialRead` 下方新增一行。

| 文案 | 内容 |
|---|---|
| 标题 | MPV 画质增强 |
| 副标题 | 使用 FSR 着色器提升低分辨率视频清晰度（仅 MPV 内核） |

放在「实验室」分组的原因：跟现有的 `labMpvSequentialRead`、`labMpvSpecialRender` 同属「MPV 实验/高级」语义分组，受众一致。

**切换反馈**：切换开关时同时做两件事：
1. 写 SP（保证下次 MPV 实例初始化时自动应用）
2. 发广播触发当前 Activity 的 MPV 实例立即应用（pause 状态下也会强制 seek 重渲染）

这样切换是「立即生效」而非「下次视频生效」。发广播而非直接调用，是因为 `LabSettingsFragment` 拿不到当前播放器实例——通过 Activity 的 BroadcastReceiver 间接路由。

> 注：mpv 实例在 App 进程内可被复用（`idle=yes` 模式），shader 一旦挂上会跨视频持续。即使不立即生效，下一个视频也会自然继承。

## 数据层（SharedPreferences）

### 新增键

`labMpvSuperResolution`，类型 `Boolean`，默认 `false`。

复用现有 `SPManager.boolean()` 委托模式（持久化键 = 属性名字符串），与 `labMpvSequentialRead` 完全一致。

### 跨模块契约

`lib-player/player-mpv` 不能依赖 `dy-player`，所以 `MpvMediaPlayer` 会用字符串字面量 `"labMpvSuperResolution"` 读取，并在源码注释里明确「改名需同步两边」。这与 `labMpvSequentialRead` 的现有契约模式相同（见 `SpUtil.kt` 175–182 行注释、`MpvMediaPlayer.kt` 131–139 行 SP 常量）。

### 备份/恢复

在 `BackupSettingsFragment` 现有 lab 键列表里加一行，与 `labMpvSequentialRead` 同组。

## Shader 选择

### Shader 列表

**单个文件 `FSR.glsl`**，包含两个 LUMA hook：

| Hook | 作用 | 行号 |
|---|---|---|
| LUMA hook #1（EASU） | 边缘自适应升采样，FSR 核心；把 LUMA plane 放大并 SAVE 到 EASUTEX | line 34 |
| LUMA hook #2（RCAS） | 对比度自适应锐化；读 EASUTEX 输出最终结果 | line 363 |

来源：[agyild FSR gist](https://gist.github.com/agyild/82219c545228d70c5604f865ce0b0ce5) v1.0.2（AMD FSR 1.0.2 的 mpv 端口，单文件版本）。

### 关键约束

来自 spike 验证：**必须挂 LUMA hook**（POSTKERNEL/RGB 在本 AAR 上不触发）。`FSR.glsl` 两个 pass 都是 LUMA hook，无需修改。

### 存放与加载

- **存放**：`lib-player/player-mpv/src/main/assets/shaders/FSR.glsl`（单文件）
- **运行时拷贝**：`filesDir/shaders/FSR.glsl`（复用现有拷贝机制）
- **加载命令**：`mpv.command("change-list", "glsl-shaders", "add", path)`（已验证有效，单文件一次 add，mpv 内部会自动展开两个 hook）
- **清空命令**：`mpv.command("set", "glsl-shaders", "")`

### 清理 spike 残留

删除以下 spike 文件（不再是产品代码的一部分）：
- `assets/shaders/spike_red_tint.glsl`
- `assets/shaders/Anime4K_Clamp_Highlights.glsl`
- `assets/shaders/Anime4K_Restore_CNN_VL.glsl`
- `assets/shaders/Anime4K_Upscale_CNN_x2_VL.glsl`
- `assets/shaders/FSRCNNX_x2_8-0-4-1.glsl`
- `assets/shaders/adaptive-sharpen.glsl`

## MpvMediaPlayer 集成

### 新增 API

```kotlin
// lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt

companion object {
    // ... 现有常量
    private const val SP_KEY_LAB_MPV_SUPER_RESOLUTION = "labMpvSuperResolution"
    private const val SP_LAB_MPV_SUPER_RESOLUTION_DEFAULT = false
}

private val superResolutionShaderNames = listOf("FSR.glsl")

/**
 * 画质增强开关。开启时挂 EASU+RCAS 到 LUMA hook，关闭时清空。
 * 可在播放中调用（pause 状态下也会强制 seek 重渲染当前帧）。
 *
 * 跨模块契约：[labMpvSuperResolution] 这个 SP 键由 dy-player 的 SpUtil 定义，
 * MpvMediaPlayer 通过字符串字面量读取，改名需同步两边。
 */
fun setSuperResolutionEnabled(enabled: Boolean) {
    if (!isMpvInitialized || isNativeDestroyed) return
    try {
        if (enabled) {
            val paths = ensureShadersCopied(superResolutionShaderNames)
            if (paths.isEmpty()) {
                L.e("Super-resolution: shader files missing, cannot enable")
                return
            }
            for (path in paths) {
                mpv.command("change-list", "glsl-shaders", "add", path)
            }
            L.d("Super-resolution: enabled (${paths.size} shaders)")
        } else {
            mpv.command("set", "glsl-shaders", "")
            L.d("Super-resolution: disabled")
        }
        forceRerender()
    } catch (e: Exception) {
        L.e("setSuperResolutionEnabled failed: ${e.message}")
    }
}

/**
 * 初始化时按 SP 决定要不要默认挂上。保证切到下个视频/重启 App 仍生效。
 */
private fun applySuperResolutionOnInit() {
    val sp = PreferenceManager.getDefaultSharedPreferences(appContext)
    val enabled = sp.getBoolean(
        SP_KEY_LAB_MPV_SUPER_RESOLUTION,
        SP_LAB_MPV_SUPER_RESOLUTION_DEFAULT
    )
    if (enabled) setSuperResolutionEnabled(true)
}

/** 从 assets 拷贝 shader 到 filesDir，返回绝对路径列表。 */
private fun ensureShadersCopied(names: List<String>): List<String> {
    val outDir = java.io.File(appContext.filesDir, "shaders")
    if (!outDir.exists()) outDir.mkdirs()
    val paths = ArrayList<String>()
    for (name in names) {
        val out = java.io.File(outDir, name)
        if (!out.exists() || out.length() == 0L) {
            appContext.assets.open("shaders/$name").use { i ->
                java.io.FileOutputStream(out).use { o -> i.copyTo(o) }
            }
        }
        if (out.exists() && out.length() > 0L) paths.add(out.absolutePath)
    }
    return paths
}

/** 在 pause 状态下强制 mpv 重渲染当前帧（seek 到当前位置）。 */
private fun forceRerender() {
    try {
        val pos = mpv.getPropertyDouble("time-pos") ?: return
        mpv.command("seek", pos.toString(), "absolute", "exact")
    } catch (_: Exception) {}
}
```

### 调用时机

1. **初始化时**：在 `initialize()` 把现有 `applySpikeAnime4KShaders()` 调用替换为 `applySuperResolutionOnInit()`。这样开关打开后所有视频都生效——mpv 实例在进程内复用，shader 自动跨视频持续。
2. **运行时切换**：通过广播接收器（见下节）。

### 删除 spike 代码

从 `MpvMediaPlayer.kt` 删除：
- `applySpikeAnime4KShaders()`
- `spikeShaderPaths` 字段
- `spikeTakeScreenshot()` / `spikeClearShaders()` / `spikeReloadShaders()` / `spikeForceRerender()`

把有用的逻辑（shader 拷贝、强制重渲染）正式化为产品函数。

## 运行时切换：广播接收器

由于 `LabSettingsFragment` 拿不到当前播放器实例，运行时切换通过广播路由到 Activity。

### 改造现有 spike 广播为产品级

`LongVideoActivity.kt` 和 `ShortVideoActivity.kt` 各有一个 spike 广播接收器。改造为：

**Action 常量**（`LongVideoActivity.companion`）：
```kotlin
const val ACTION_SUPER_RESOLUTION_ON = "me.lingci.dy.player.SUPER_RES_ON"
const val ACTION_SUPER_RESOLUTION_OFF = "me.lingci.dy.player.SUPER_RES_OFF"
```

**接收器逻辑**：
```kotlin
val superResReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val mpv = videoView.getCurrentPlayer() as? MpvMediaPlayer ?: return
        when (intent?.action) {
            ACTION_SUPER_RESOLUTION_ON -> mpv.setSuperResolutionEnabled(true)
            ACTION_SUPER_RESOLUTION_OFF -> mpv.setSuperResolutionEnabled(false)
        }
    }
}
```

**注册/反注册**：
- `onResume()` 里 `registerReceiver(superResReceiver, filter, RECEIVER_EXPORTED)`（Android 14+ 必须显式 flag）
- `onDestroy()` 里 `unregisterReceiver(superResReceiver)`

**LabSettingsFragment 触发**：toggle 切换时，除了写 SP，还要发广播：
```kotlin
binding.swLabMpvSuperResolution.setOnClickListener {
    val on = binding.swLabMpvSuperResolution.isChecked
    spUtil.labMpvSuperResolution = on
    val action = if (on) LongVideoActivity.ACTION_SUPER_RESOLUTION_ON
                 else LongVideoActivity.ACTION_SUPER_RESOLUTION_OFF
    appContext.sendBroadcast(Intent(action))
    Toast.makeText(...).show()
}
```

**adb 调试入口**：保留这个能力（参考 spike 的 `-f 0x10` 经验）：
```bash
adb shell am broadcast -f 0x10 -a me.lingci.dy.player.SUPER_RES_ON
adb shell am broadcast -f 0x10 -a me.lingci.dy.player.SUPER_RES_OFF
```

## 边界情况

| 场景 | 行为 |
|---|---|
| 切换开关时正在播放 ExoPlayer | 广播被忽略（`as? MpvMediaPlayer` 返回 null），SP 已写入，下次切到 MPV 内核时生效 |
| 播放中切换 MPV↔Exo 内核 | 新内核实例初始化时读 SP，自动按开关状态挂 shader |
| 视频本身已是高分辨率（1080p+） | EASU 近似 passthrough，无负面影响 |
| 释放/重新创建播放器（`isReleased`） | `setSuperResolutionEnabled` 被状态守卫拦截，不崩 |
| 卸载/降级 | SP 键以默认 `false` 读出，行为=关闭，无副作用 |
| 备份/恢复 | 加进现有 lab 键列表，跟其他 `labMpv*` 一起备份 |
| HarmonyOS/Huawei 设备 | 广播需 `-f 0x10` 才能到达动态接收器（spike 已验证），UI 内部 sendBroadcast 无此问题 |
| 短视频页 + 长视频页同时活跃 | 两个 Activity 各有独立 receiver，发广播时双方都会处理（仅当前可见的 Activity 的 player 会真正执行；另一个 pause 状态的 Activity 收到也无害） |

## 测试与验证

### 手动验证（无法写自动化测试，因为是 GPU 渲染）

1. **效果对比**：
   - 关闭开关 → 播放低分辨率实拍视频 → 在细节多的帧暂停 → 截图（adb screencap）
   - 开启开关 → 同一视频同一帧（通过 `adb shell am broadcast -f 0x10 -a me.lingci.dy.player.SUPER_RES_ON`）→ 截图
   - 肉眼对比：开启后应明显更锐利、边缘更清晰
2. **持久化**：开启开关 → 杀进程 → 重启 App → 播放视频 → 确认 shader 仍生效
3. **内核切换**：播放中切到 ExoPlayer → 不崩 → 切回 MPV → shader 自动挂上
4. **关闭回归**：开启后再关闭 → 确认画面回到无 shader 状态（不残留）
5. **备份/恢复**：备份 → 卸载 → 安装 → 恢复 → 确认开关状态恢复

### 回归点

- MPV 现有播放不崩（profile=fast 与 FSR shader 不冲突，因为 mpvKt 也用 profile=fast）
- ExoPlayer 播放完全不受影响（shader 加载只在 MPV 实例）
- 短视频 / 长视频 / WebDav / 本地 / 流媒体 各跑一遍冒烟

## 影响范围（修改文件清单）

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `lib-player/player-mpv/src/main/assets/shaders/FSR.glsl` | 新增 | 从 gist 拷贝（单文件含 EASU+RCAS 两个 LUMA hook） |
| `lib-player/player-mpv/src/main/assets/shaders/spike_red_tint.glsl` | 删除 | spike 残留 |
| `lib-player/player-mpv/src/main/assets/shaders/Anime4K_*.glsl` (3 个) | 删除 | spike 残留 |
| `lib-player/player-mpv/src/main/assets/shaders/FSRCNNX_*.glsl` | 删除 | spike 残留 |
| `lib-player/player-mpv/src/main/assets/shaders/adaptive-sharpen.glsl` | 删除 | spike 残留 |
| `lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt` | 重构 | 删除 spike 函数，加产品级 `setSuperResolutionEnabled` / `applySuperResolutionOnInit` |
| `dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt` | 新增属性 | `labMpvSuperResolution` |
| `dy-player/src/main/java/me/lingci/dy/player/ui/tool/LabSettingsFragment.kt` | 新增绑定 | toggle 绑定 + 广播发送 |
| `dy-player/src/main/res/layout/fragment_lab_setting.xml` | 新增 UI 行 | 紧贴 `swLabMpvSequentialRead` 下方 |
| `dy-player/src/main/res/values/strings.xml` | 新增文案 | 标题 + 副标题 |
| `dy-player/src/main/java/me/lingci/dy/player/ui/tool/BackupSettingsFragment.kt` | 新增键 | 加进备份列表 |
| `dy-player/.../ui/long_video/LongVideoActivity.kt` | 改造 | spike 广播 → 产品级 super-res 广播 |
| `dy-player/.../ui/short_video/ShortVideoActivity.kt` | 改造 | 同上 |

## 工作量估计

约 **1.5–2 小时**：
- 0.5h：下载 FSR shader、清理 spike shader
- 0.5h：MpvMediaPlayer 改造
- 0.5h：UI + SP + 备份
- 0.5h：广播接收器改造 + 真机验证

## 后续可扩展方向（不在本次范围）

1. **效果对比的自动化**：如果未来想量化对比，可以保留 spike 里的 `screenshot-to-file scaled` + SSIM 工具链作为调试入口
2. **多档位 shader 预设**：未来可加「弱/中/强」三档（不同 shader 组合），但需要先看默认两件套的实际效果再决定是否需要
3. **ExoPlayer 端的超分**：如果未来需要，可以走 Media3 GL effect + GLSL 路线，但架构改动大，单独立项
4. **KrigBilateral 色度升采样**：未来如果觉得色度质量不够，可以加 CHROMA hook 的 Krig shader
