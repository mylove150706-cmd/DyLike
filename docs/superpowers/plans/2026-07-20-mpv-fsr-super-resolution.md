# MPV FSR 画质增强开关 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在实验室设置页加一个开关，开启后给 MPV 内核挂 FSR 着色器（LUMA hook 上的 EASU 升采样 + RCAS 锐化），实时提升低分辨率视频的视觉清晰度。

**Architecture:** 单一开关写入 SharedPreferences（`labMpvSuperResolution`），MpvMediaPlayer 在初始化时读取并决定是否加载 `FSR.glsl`。运行时切换通过 Activity 的 BroadcastReceiver 路由到当前 MPV 实例的 `setSuperResolutionEnabled`。同时把之前 spike 留下的实验代码（染色 shader、screenshot dump、调试广播）正式化或删除。

**Tech Stack:** Kotlin · mpv-android-lib 0.1.12 (`is.xyz.mpv.MPV`) · GLSL user shader (AMD FSR 1.0.2 port by agyild) · Android BroadcastReceiver · SharedPreferences via SPManager delegate

## Global Constraints

- 不要硬编码依赖版本（用 `libs.*` catalog）
- 跨模块契约：SP 键字符串 `"labMpvSuperResolution"` 在 dy-player 和 lib-player/player-mpv 两侧使用，重命名需同步
- Shader 必须挂在 **LUMA hook**（POSTKERNEL/RGB 在本 AAR 上不触发，已 spike 验证）
- 中文文案，与现有 lab strings 风格一致
- 所有广播在 Android 14+ (TIRAMISU+) 需 `Context.RECEIVER_EXPORTED` flag
- 现有 spike 代码（`applySpikeAnime4KShaders`、`spikeShaderPaths`、`spikeTakeScreenshot`、`spikeClearShaders`、`spikeReloadShaders`、`spikeForceRerender`）将在本计划中删除或重构为产品代码
- 严格保留现有的 ExoPlayer 路径完全不变（开关只在 MPV 实例生效）
- 不要 bump `targetSdk`（保持 28）
- 注释和用户可见字符串用简体中文

**实施分支**：`feat/mpv-super-resolution`（从 `spike/super-resolution-anime4k` 切出）

---

## 文件结构概览

### 新建
- `lib-player/player-mpv/src/main/assets/shaders/FSR.glsl` — 单文件 FSR shader，包含 EASU + RCAS 两个 LUMA hook

### 修改
- `lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt` — 删除 spike 函数，新增 `setSuperResolutionEnabled` / `applySuperResolutionOnInit` / `ensureShadersCopied` / `forceRerender`
- `dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt` — 新增 `labMpvSuperResolution` 属性
- `dy-player/src/main/java/me/lingci/dy/player/ui/tool/LabSettingsFragment.kt` — 新增开关绑定 + 广播发送
- `dy-player/src/main/res/layout/fragment_lab_setting.xml` — 新增 toggle UI 行（紧贴 lab_mpv_sequential_read 下方）
- `dy-player/src/main/res/values/strings.xml` — 新增标题/副标题文案
- `dy-player/src/main/java/me/lingci/dy/player/ui/tool/BackupSettingsFragment.kt` — 新增备份/恢复键
- `dy-player/src/main/java/me/lingci/dy/player/ui/long_video/LongVideoActivity.kt` — spike 广播 → 产品级 super-res 广播
- `dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt` — 同上

### 删除（spike 残留）
- `lib-player/player-mpv/src/main/assets/shaders/spike_red_tint.glsl`
- `lib-player/player-mpv/src/main/assets/shaders/Anime4K_Clamp_Highlights.glsl`
- `lib-player/player-mpv/src/main/assets/shaders/Anime4K_Restore_CNN_VL.glsl`
- `lib-player/player-mpv/src/main/assets/shaders/Anime4K_Upscale_CNN_x2_VL.glsl`
- `lib-player/player-mpv/src/main/assets/shaders/FSRCNNX_x2_8-0-4-1.glsl`
- `lib-player/player-mpv/src/main/assets/shaders/adaptive-sharpen.glsl`

---

## Task 1: 准备分支

**Files:**
- Modify: 无（git 操作）

**Interfaces:**
- Consumes: 当前 `spike/super-resolution-anime4k` 分支
- Produces: 新分支 `feat/mpv-super-resolution`

- [ ] **Step 1: 确认当前状态干净**

Run:
```bash
cd /c/Users/jiangyunfei/Desktop/DyLike
git status
git branch --show-current
git log --oneline -3
```
Expected: 当前在 `spike/super-resolution-anime4k` 分支，工作区干净。最新 commit 是 `2db70bf spec(super-res): design doc for MPV FSR toggle`。

- [ ] **Step 2: 创建实施分支**

Run:
```bash
git checkout -b feat/mpv-super-resolution
```
Expected: 切到新分支 `feat/mpv-super-resolution`。

- [ ] **Step 3: 验证分支切换**

Run:
```bash
git branch --show-current
```
Expected: 输出 `feat/mpv-super-resolution`

---

## Task 2: 添加 FSR shader + 清理 spike shader 文件

**Files:**
- Create: `lib-player/player-mpv/src/main/assets/shaders/FSR.glsl`
- Delete: 6 个 spike shader 文件

**Interfaces:**
- Consumes: 无
- Produces: `assets/shaders/FSR.glsl`，被 Task 3 的 `ensureShadersCopied(listOf("FSR.glsl"))` 引用

- [ ] **Step 1: 下载 FSR.glsl 内容到本地文件**

Run（在仓库根目录）：
```bash
curl -sL "https://gist.githubusercontent.com/agyild/82219c545228d70c5604f865ce0b0ce5/raw/FSR.glsl" \
  -o lib-player/player-mpv/src/main/assets/shaders/FSR.glsl
```

验证文件已创建：
```bash
ls -la lib-player/player-mpv/src/main/assets/shaders/FSR.glsl
wc -l lib-player/player-mpv/src/main/assets/shaders/FSR.glsl
head -40 lib-player/player-mpv/src/main/assets/shaders/FSR.glsl
```
Expected: 文件存在，约 453 行，开头是 AMD MIT license 注释 + "FidelityFX FSR v1.0.2 by AMD" 行，第 34 行是 `//!HOOK LUMA`，第 363 行也是 `//!HOOK LUMA`。

- [ ] **Step 2: 验证 shader 包含两个 LUMA hook**

Run：
```bash
grep -n "HOOK LUMA\|HOOK SCALED\|HOOK RGB\|HOOK POSTKERNEL\|HOOK CHROMA" lib-player/player-mpv/src/main/assets/shaders/FSR.glsl
```
Expected: 输出仅两行，都是 `//!HOOK LUMA`（行号约 34 和 363）。不应出现 POSTKERNEL 或 RGB hook。

- [ ] **Step 3: 删除 spike shader 文件**

Run：
```bash
cd lib-player/player-mpv/src/main/assets/shaders
rm spike_red_tint.glsl
rm Anime4K_Clamp_Highlights.glsl
rm Anime4K_Restore_CNN_VL.glsl
rm Anime4K_Upscale_CNN_x2_VL.glsl
rm FSRCNNX_x2_8-0-4-1.glsl
rm adaptive-sharpen.glsl
cd -
ls lib-player/player-mpv/src/main/assets/shaders/
```
Expected: shaders 目录只剩 `FSR.glsl` 一个文件。

- [ ] **Step 4: 构建验证（确保没有别处引用被删 shader）**

Run：
```bash
./gradlew :lib-player:player-mpv:assembleDebug 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL（spike 代码虽然引用了被删的 shader 名，但当前 `applySpikeAnime4KShaders` 里 `shaderNames = emptyList()`，所以不会真的去加载，编译通过。Task 3 会清理这些代码。）

- [ ] **Step 5: 提交**

Run：
```bash
git add lib-player/player-mpv/src/main/assets/shaders/
git commit -m "feat(super-res): add FSR shader, remove spike shaders

- Add FSR.glsl (AMD FSR 1.0.2 mpv port by agyild, single file with
  two LUMA hooks: EASU upscale + RCAS sharpen)
- Remove 6 spike shader files no longer needed"
```
Expected: commit 成功。

---

## Task 3: MpvMediaPlayer 改造（核心逻辑）

**Files:**
- Modify: `lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt`

**Interfaces:**
- Consumes: `assets/shaders/FSR.glsl`（Task 2 已添加）
- Produces:
  - 公共方法 `fun setSuperResolutionEnabled(enabled: Boolean)` — 运行时开关
  - 私有方法 `applySuperResolutionOnInit()` — 初始化时按 SP 决定
  - 私有方法 `ensureShadersCopied(names: List<String>): List<String>` — shader 拷贝辅助
  - 私有方法 `forceRerender()` — 强制重渲染
  - companion 常量 `SP_KEY_LAB_MPV_SUPER_RESOLUTION`、`SP_LAB_MPV_SUPER_RESOLUTION_DEFAULT`

- [ ] **Step 1: 找到现有 spike 代码段**

在 `MpvMediaPlayer.kt` 中定位以下元素（用 Read/grep 确认行号，行号可能随前几次编辑变化）：
- `companion object` 块（含 `SP_KEY_LAB_MPV_SEQUENTIAL_READ` 等常量）
- `applySpikeAnime4KShaders()` 方法
- `spikeShaderPaths: List<String>` 字段
- `spikeTakeScreenshot(outPath: String): Boolean`
- `spikeClearShaders()`
- `spikeReloadShaders()`
- `spikeForceRerender()`
- `initialize()` 中的 `if (!isMpvInitialized) { applySpikeAnime4KShaders() }` 调用（约 line 657-659）

Read these to know exact current state:
```bash
grep -n "applySpikeAnime4KShaders\|spikeShaderPaths\|spikeTakeScreenshot\|spikeClearShaders\|spikeReloadShaders\|spikeForceRerender\|SP_KEY_LAB_MPV_SEQUENTIAL_READ\|companion object" lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt
```

- [ ] **Step 2: 在 companion object 加新 SP 常量**

找到 `companion object` 块（含 `SP_KEY_LAB_MPV_SEQUENTIAL_READ` 等），添加新常量。结构（保持现有风格）：

```kotlin
private val SP_KEY_LAB_MPV_SUPER_RESOLUTION = "labMpvSuperResolution"
private val SP_LAB_MPV_SUPER_RESOLUTION_DEFAULT = false
```

注意：放在 `SP_KEY_LAB_MPV_SEQUENTIAL_READ` 同一区块下方。如果现有 `SP_KEY_LAB_MPV_SEQUENTIAL_READ` 是 `private val`，新键也用 `private val`，保持一致。

- [ ] **Step 3: 用产品函数替换整个 `applySpikeAnime4KShaders` 及相关 spike 函数**

删除这些（包括它们之间的字段、注释）：
- `applySpikeAnime4KShaders()`
- `spikeShaderPaths: List<String>` 字段声明
- `spikeTakeScreenshot(outPath: String): Boolean` 方法
- `spikeClearShaders()`
- `spikeReloadShaders()`
- `spikeForceRerender()`

替换为以下完整代码块（放在原 `applySpikeAnime4KShaders` 的位置）：

```kotlin
/**
 * FSR 画质增强：开启时挂 AMD FSR shader 到 LUMA hook（EASU 升采样 + RCAS 锐化），
 * 关闭时清空。可在播放中调用，pause 状态下会强制 seek 重渲染当前帧。
 *
 * 详见 spec：docs/superpowers/specs/2026-07-20-mpv-fsr-super-resolution-design.md
 *
 * ⚠️ 跨模块契约：开关 SP 键为 `labMpvSuperResolution`，由 dy-player/SpUtil 定义。
 * 这里通过字符串字面量读取。改名需同步两边。
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
 * 初始化时按 SP 决定要不要默认挂上 FSR。
 * 保证切到下个视频/重启 App 后开关状态持续生效。
 */
private fun applySuperResolutionOnInit() {
    val sp = PreferenceManager.getDefaultSharedPreferences(appContext)
    val enabled = sp.getBoolean(
        SP_KEY_LAB_MPV_SUPER_RESOLUTION,
        SP_LAB_MPV_SUPER_RESOLUTION_DEFAULT
    )
    if (enabled) setSuperResolutionEnabled(true)
}

/**
 * 从 assets 拷贝 shader 文件到 filesDir/shaders/（若已存在且非空则跳过）。
 * 返回拷贝后的绝对路径列表。
 */
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

/**
 * 在 pause 状态下强制 mpv 重渲染当前帧（seek 到当前位置）。
 * 改完 shader 后 mpv 不会自动重绘 paused frame，需要主动触发。
 */
private fun forceRerender() {
    try {
        val pos = mpv.getPropertyDouble("time-pos") ?: return
        mpv.command("seek", pos.toString(), "absolute", "exact")
    } catch (_: Exception) {}
}

private val superResolutionShaderNames = listOf("FSR.glsl")
```

- [ ] **Step 4: 修改 `initialize()` 中的 spike 调用**

找到 `initialize()` 里这段（约 line 657-659）：
```kotlin
if (!isMpvInitialized) {
    applySpikeAnime4KShaders()
}
```

替换为：
```kotlin
if (!isMpvInitialized) {
    applySuperResolutionOnInit()
}
```

同时删除 `L.d("[SPIKE] runtime options set, ready for shader injection")` 这行（在它上方），改为：
```kotlin
L.d("runtime options set, ready for super-resolution check")
```

- [ ] **Step 5: 编译验证**

Run：
```bash
./gradlew :lib-player:player-mpv:compileDebugKotlin 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL，没有 `applySpikeAnime4KShaders` / `spike*` 未解析引用的错误。

- [ ] **Step 6: 验证 spike 残留代码已清理**

Run：
```bash
grep -n "spike\|SPIKE\|applySpikeAnime4KShaders" lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt
```
Expected: 无输出（所有 spike 标识符已清理）。

- [ ] **Step 7: 提交**

Run：
```bash
git add lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt
git commit -m "feat(super-res): replace spike code with product FSR toggle API

MpvMediaPlayer now exposes:
  - setSuperResolutionEnabled(enabled): runtime toggle for FSR shaders
  - applySuperResolutionOnInit(): reads SP on init, applies if enabled
  - ensureShadersCopied(): asset copy helper (formerly spike code)
  - forceRerender(): seeks current frame to trigger repaint

Removes all spike scaffolding (applySpikeAnime4KShaders,
spikeShaderPaths, spikeTakeScreenshot, spikeClearShaders,
spikeReloadShaders, spikeForceRerender) from this file."
```
Expected: commit 成功。

---

## Task 4: 添加 SharedPreferences 键

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt:182` 附近

**Interfaces:**
- Consumes: SPManager delegate pattern（现有）
- Produces: `SpUtil.labMpvSuperResolution` 属性 + 持久化键字符串 `"labMpvSuperResolution"`

- [ ] **Step 1: 在 SpUtil.kt 找到 `labMpvSequentialRead` 定义**

Read：
```bash
grep -nB1 -A1 "labMpvSequentialRead" dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt
```
Expected: 找到 line 175-182 的定义（带跨模块契约注释）。

- [ ] **Step 2: 紧贴 `labMpvSequentialRead` 下方插入新属性**

在 `var labMpvSequentialRead by SPManager.boolean(true)` 这一行之后插入：

```kotlin

    /**
     * MPV FSR 画质增强：开启后给 MPV 内核挂 AMD FSR shader（LUMA hook 上的
     * EASU 升采样 + RCAS 锐化），实时提升低分辨率视频清晰度。仅 MPV 内核生效。
     *
     * ⚠️ 跨模块约定：此 key 由 lib-player/player-mpv/MpvMediaPlayer 在 init 时
     * 直接读取（app 默认 SharedPreferences），运行时也通过广播触发 MpvMediaPlayer
     * 的 setSuperResolutionEnabled。重命名属性会破坏 MPV 端读取。
     */
    var labMpvSuperResolution by SPManager.boolean(false)
```

注意空行格式与周围一致。

- [ ] **Step 3: 编译验证**

Run：
```bash
./gradlew :dy-player:compileBetaDebugKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

Run：
```bash
git add dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt
git commit -m "feat(super-res): add labMpvSuperResolution preference key

Boolean toggle, default false. Follows the same cross-module contract
as labMpvSequentialRead: read by lib-player/player-mpv via string literal."
```

---

## Task 5: 添加 UI 文案字符串

**Files:**
- Modify: `dy-player/src/main/res/values/strings.xml`（在 `hint_lab_mpv_sequential_read_desc` 后插入）

**Interfaces:**
- Consumes: 无
- Produces: 字符串 `hint_lab_mpv_super_resolution` 和 `hint_lab_mpv_super_resolution_desc`

- [ ] **Step 1: 定位插入点**

Read：
```bash
grep -nA1 "hint_lab_mpv_sequential_read_desc" dy-player/src/main/res/values/strings.xml
```
Expected: 找到 line 118 附近。

- [ ] **Step 2: 在 `hint_lab_mpv_sequential_read_desc` 行之后插入新字符串**

在 `<string name="hint_lab_mpv_sequential_read_desc">...</string>` 那一行之后插入：

```xml
    <string name="hint_lab_mpv_super_resolution">MPV 画质增强</string>
    <string name="hint_lab_mpv_super_resolution_desc">使用 FSR 着色器提升低分辨率视频清晰度（仅 MPV 内核）</string>
```

- [ ] **Step 3: 提交**

Run：
```bash
git add dy-player/src/main/res/values/strings.xml
git commit -m "feat(super-res): add lab strings for FSR toggle"
```

---

## Task 6: 添加 UI toggle 行（layout XML）

**Files:**
- Modify: `dy-player/src/main/res/layout/fragment_lab_setting.xml`

**Interfaces:**
- Consumes: 字符串 `hint_lab_mpv_super_resolution` / `hint_lab_mpv_super_resolution_desc`（Task 5）
- Produces: View IDs `lab_mpv_super_resolution`（容器）、`sw_lab_mpv_super_resolution`（开关）— 被 Task 7 引用

- [ ] **Step 1: 定位插入点**

Read `fragment_lab_setting.xml` 行 162-203（lab_mpv_sequential_read ConstraintLayout 块的完整范围）。

- [ ] **Step 2: 修改 layout_constraintTop_toBottomOf 引用链**

新 toggle 要插入在 `lab_mpv_sequential_read` 和 `debug_mode` 之间。需要做两件事：

**A. 找到 `debug_mode` 容器**，把它的 `app:layout_constraintTop_toBottomOf="@+id/lab_mpv_sequential_read"` 改为 `app:layout_constraintTop_toBottomOf="@+id/lab_mpv_super_resolution"`。

**B. 在 `lab_mpv_sequential_read` 容器结束（`</androidx.constraintlayout.widget.ConstraintLayout>` 行 203）和 `debug_mode` 容器开始之间插入新容器：**

```xml
            <androidx.constraintlayout.widget.ConstraintLayout
                android:id="@+id/lab_mpv_super_resolution"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:paddingStart="@dimen/dp32"
                android:paddingTop="@dimen/dp8"
                android:paddingEnd="@dimen/dp16"
                android:paddingBottom="@dimen/dp8"
                app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toBottomOf="@+id/lab_mpv_sequential_read">

                <TextView
                    android:id="@+id/tv_lab_mpv_super_resolution"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/hint_lab_mpv_super_resolution"
                    android:textColor="?android:attr/textColorPrimary"
                    android:textSize="18sp"
                    app:layout_constraintBottom_toTopOf="@id/tv_lab_mpv_super_resolution_desc"
                    app:layout_constraintStart_toStartOf="parent"
                    app:layout_constraintTop_toTopOf="parent" />

                <TextView
                    android:id="@+id/tv_lab_mpv_super_resolution_desc"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textSize="12sp"
                    android:text="@string/hint_lab_mpv_super_resolution_desc"
                    app:layout_constraintBottom_toBottomOf="parent"
                    app:layout_constraintStart_toStartOf="parent"
                    app:layout_constraintTop_toBottomOf="@id/tv_lab_mpv_super_resolution" />

                <androidx.appcompat.widget.SwitchCompat
                    android:id="@+id/sw_lab_mpv_super_resolution"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    app:layout_constraintBottom_toBottomOf="parent"
                    app:layout_constraintEnd_toEndOf="parent"
                    app:layout_constraintTop_toTopOf="parent"
                    tools:ignore="MissingConstraints" />
            </androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 3: 构建验证**

Run：
```bash
./gradlew :dy-player:processBetaDebugResources 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL，没有 layout 警告或资源未解析错误。

- [ ] **Step 4: 提交**

Run：
```bash
git add dy-player/src/main/res/layout/fragment_lab_setting.xml
git commit -m "feat(super-res): add lab UI row for FSR toggle"
```

---

## Task 7: LabSettingsFragment 绑定开关 + 发广播

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/ui/tool/LabSettingsFragment.kt`

**Interfaces:**
- Consumes: View IDs（Task 6）、`spUtil.labMpvSuperResolution`（Task 4）、`LongVideoActivity.ACTION_SUPER_RESOLUTION_ON/OFF`（Task 8）
- Produces: 切换开关时写 SP + 发广播触发当前 MPV 实例

**注意**：Task 8 还没创建 ACTION 常量，但本 Task 引用它们——按顺序执行时 Task 8 会补充。如果按 Task 编号顺序执行，Task 7 编译会失败，要 Task 8 完成后一起编译。

- [ ] **Step 1: 添加必要的 import**

在文件顶部 import 区添加：
```kotlin
import android.content.Intent
import android.widget.Toast
import me.lingci.dy.player.ui.long_video.LongVideoActivity
```

- [ ] **Step 2: 在 `init()` 中绑定新开关**

紧贴 `binding.swLabMpvSequentialRead.setOnClickListener {...}` 块（line 53-56）之后，插入：

```kotlin
        binding.swLabMpvSuperResolution.isChecked = spUtil.labMpvSuperResolution
        binding.swLabMpvSuperResolution.setOnClickListener {
            val on = binding.swLabMpvSuperResolution.isChecked
            spUtil.labMpvSuperResolution = on
            // 立即对当前 MPV 实例生效（若不在 MPV 内核则忽略，下次切到 MPV 时由 init 应用）
            val action = if (on) LongVideoActivity.ACTION_SUPER_RESOLUTION_ON
                         else LongVideoActivity.ACTION_SUPER_RESOLUTION_OFF
            requireContext().sendBroadcast(Intent(action))
            Toast.makeText(
                requireContext(),
                if (on) "已开启 MPV 画质增强" else "已关闭 MPV 画质增强",
                Toast.LENGTH_SHORT
            ).show()
        }
```

- [ ] **Step 3: 暂不编译验证（要等 Task 8 加常量）**

跳到 Task 8，结束后一起跑编译。

---

## Task 8: LongVideoActivity 改造（spike 广播 → 产品级 super-res 广播）

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/ui/long_video/LongVideoActivity.kt`

**Interfaces:**
- Consumes: `MpvMediaPlayer.setSuperResolutionEnabled`（Task 3）
- Produces: companion 常量 `ACTION_SUPER_RESOLUTION_ON` / `ACTION_SUPER_RESOLUTION_OFF`

- [ ] **Step 1: 替换 companion object 中的 spike ACTION 常量**

找到 line 97-99：
```kotlin
const val ACTION_SPIKE_DUMP = "me.lingci.dy.player.spike.DUMP"
const val ACTION_SPIKE_SHADER_OFF = "me.lingci.dy.player.spike.SHADER_OFF"
const val ACTION_SPIKE_SHADER_ON = "me.lingci.dy.player.spike.SHADER_ON"
```

替换为：
```kotlin
const val ACTION_SUPER_RESOLUTION_ON = "me.lingci.dy.player.SUPER_RES_ON"
const val ACTION_SUPER_RESOLUTION_OFF = "me.lingci.dy.player.SUPER_RES_OFF"
```

- [ ] **Step 2: 替换整个 spikeShaderReceiver 定义**

找到 `private val spikeShaderReceiver = object : android.content.BroadcastReceiver() { ... }`（约 line 219-252），整个块替换为：

```kotlin
    private val superResolutionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val mpv = videoView.getCurrentPlayer() as? MpvMediaPlayer ?: return
            when (intent.action) {
                ACTION_SUPER_RESOLUTION_ON -> {
                    mpv.setSuperResolutionEnabled(true)
                    android.widget.Toast.makeText(
                        this@LongVideoActivity,
                        "MPV 画质增强：已开启",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                ACTION_SUPER_RESOLUTION_OFF -> {
                    mpv.setSuperResolutionEnabled(false)
                    android.widget.Toast.makeText(
                        this@LongVideoActivity,
                        "MPV 画质增强：已关闭",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    private var isSuperResReceiverRegistered = false
```

- [ ] **Step 3: 找到所有 spike 引用并替换为产品级**

Run：
```bash
grep -n "spikeShaderReceiver\|registerSpikeShaderReceiver\|unregisterSpikeShaderReceiver\|isSpikeReceiverRegistered\|ACTION_SPIKE" dy-player/src/main/java/me/lingci/dy/player/ui/long_video/LongVideoActivity.kt
```

预期会有 4-5 处引用，分别替换：
- `registerSpikeShaderReceiver()` → `registerSuperResolutionReceiver()`
- `unregisterSpikeShaderReceiver()` → `unregisterSuperResolutionReceiver()`
- 调用现场保持（在 `onResume()` 和 `onDestroy()` 中）

- [ ] **Step 4: 替换 register/unregister 方法定义**

找到 `private fun registerSpikeShaderReceiver() { ... }`（约 line 1352-1365）和 `private fun unregisterSpikeShaderReceiver() { ... }`（约 line 1367-1372）。

整体替换为：

```kotlin
    // 注册超分开关广播接收器（exported，让 adb 和 LabSettingsFragment 都能发）。
    private fun registerSuperResolutionReceiver() {
        if (isSuperResReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_SUPER_RESOLUTION_ON)
            addAction(ACTION_SUPER_RESOLUTION_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(superResolutionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(superResolutionReceiver, filter)
        }
        isSuperResReceiverRegistered = true
    }

    private fun unregisterSuperResolutionReceiver() {
        if (isSuperResReceiverRegistered) {
            unregisterReceiver(superResolutionReceiver)
            isSuperResReceiverRegistered = false
        }
    }
```

- [ ] **Step 5: 更新 onResume/onDestroy 中的调用**

`onResume()` 中把 `registerSpikeShaderReceiver()` 改为 `registerSuperResolutionReceiver()`。

`onDestroy()` 中把 `unregisterSpikeShaderReceiver()` 改为 `unregisterSuperResolutionReceiver()`。

- [ ] **Step 6: 编译验证（含 Task 7）**

Run：
```bash
./gradlew :dy-player:compileBetaDebugKotlin 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL。Task 7 的引用现在可以解析了。

- [ ] **Step 7: 验证 spike 残留已清理**

Run：
```bash
grep -n "spike\|SPIKE\|Spike" dy-player/src/main/java/me/lingci/dy/player/ui/long_video/LongVideoActivity.kt
```
Expected: 无输出。

- [ ] **Step 8: 提交 Task 7 + Task 8 一起**

Run：
```bash
git add dy-player/src/main/java/me/lingci/dy/player/ui/tool/LabSettingsFragment.kt dy-player/src/main/java/me/lingci/dy/player/ui/long_video/LongVideoActivity.kt
git commit -m "feat(super-res): wire lab toggle to broadcast + LongVideoActivity receiver

- LabSettingsFragment: writes SP and broadcasts ACTION_SUPER_RESOLUTION_ON/OFF
- LongVideoActivity: replaces spike receiver with superResolutionReceiver
  that calls MpvMediaPlayer.setSuperResolutionEnabled

Broadcasts are RECEIVER_EXPORTED for adb debugging, e.g.:
  adb shell am broadcast -f 0x10 -a me.lingci.dy.player.SUPER_RES_ON"
```

---

## Task 9: ShortVideoActivity 改造（spike 广播 → 产品级 super-res 广播）

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt`

**Interfaces:**
- Consumes: `LongVideoActivity.ACTION_SUPER_RESOLUTION_ON/OFF`（Task 8）
- Produces: 短视频页的 super-res 广播接收器

- [ ] **Step 1: 找到现有 spike receiver**

Run：
```bash
grep -n "spikeShaderReceiver\|registerSpikeShaderReceiver\|unregisterSpikeShaderReceiver\|ACTION_SPIKE\|isSpikeReceiverRegistered" dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt
```

- [ ] **Step 2: 替换 spikeShaderReceiver 整块**

参考 Task 8 Step 2 的代码块，但字段名加 `superResolutionReceiver` / `isSuperResReceiverRegistered`，action 引用为 `LongVideoActivity.ACTION_SUPER_RESOLUTION_ON/OFF`，Toast 文本可省略「[SPIKE]」前缀（保持简洁，跟 Task 8 一致）。`videoView` 可能叫 `mVideoView`（按 ShortVideoActivity 现有命名保持一致）：

```kotlin
    private val superResolutionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val mpv = mVideoView.getCurrentPlayer() as? MpvMediaPlayer ?: return
            when (intent.action) {
                LongVideoActivity.ACTION_SUPER_RESOLUTION_ON ->
                    mpv.setSuperResolutionEnabled(true)
                LongVideoActivity.ACTION_SUPER_RESOLUTION_OFF ->
                    mpv.setSuperResolutionEnabled(false)
            }
        }
    }
    private var isSuperResReceiverRegistered = false
```

（先 grep 确认是 `mVideoView` 还是 `videoView`，用对的那个。）

- [ ] **Step 3: 替换 register/unregister 方法**

参考 Task 8 Step 4 的实现，方法名改为 `registerSuperResolutionReceiver` / `unregisterSuperResolutionReceiver`，filter 改为：
```kotlin
addAction(LongVideoActivity.ACTION_SUPER_RESOLUTION_ON)
addAction(LongVideoActivity.ACTION_SUPER_RESOLUTION_OFF)
```

- [ ] **Step 4: 更新 onResume/onDestroy 调用点**

把所有 `registerSpikeShaderReceiver()` → `registerSuperResolutionReceiver()`，`unregisterSpikeShaderReceiver()` → `unregisterSuperResolutionReceiver()`。

- [ ] **Step 5: 编译验证**

Run：
```bash
./gradlew :dy-player:compileBetaDebugKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 验证 spike 残留已清理**

Run：
```bash
grep -n "spike\|SPIKE\|Spike" dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt
```
Expected: 无输出。

- [ ] **Step 7: 提交**

Run：
```bash
git add dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt
git commit -m "feat(super-res): replace spike receiver in ShortVideoActivity

Mirrors LongVideoActivity's superResolutionReceiver; handles the same
broadcast actions so toggling from settings works on short-video page too."
```

---

## Task 10: BackupSettingsFragment 加新键

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/ui/tool/BackupSettingsFragment.kt`

**Interfaces:**
- Consumes: `spUtil.labMpvSuperResolution`（Task 4）
- Produces: 备份/恢复路径包含新键

- [ ] **Step 1: 在备份块加 putValue**

找到 line 167：
```kotlin
data.putValue("labMpvSpecialRender", spUtil.labMpvSpecialRender)
```

紧接其后插入：
```kotlin
        data.putValue("labMpvSuperResolution", spUtil.labMpvSuperResolution)
```

- [ ] **Step 2: 在恢复块加 hasKey/getBoolean**

找到 line 280-282：
```kotlin
if (data.hasKey("labMpvSpecialRender")) {
    spUtil.labMpvSpecialRender = data.getBoolean("labMpvSpecialRender")
}
```

紧接其后插入：
```kotlin
                if (data.hasKey("labMpvSuperResolution")) {
                    spUtil.labMpvSuperResolution = data.getBoolean("labMpvSuperResolution")
                }
```

（注意恢复块的缩进层级，跟周围 `if (data.hasKey(...))` 保持一致。）

- [ ] **Step 3: 编译验证**

Run：
```bash
./gradlew :dy-player:compileBetaDebugKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

Run：
```bash
git add dy-player/src/main/java/me/lingci/dy/player/ui/tool/BackupSettingsFragment.kt
git commit -m "feat(super-res): include labMpvSuperResolution in backup/restore"
```

---

## Task 11: 完整构建 + 安装验证

**Files:**
- 无（运行时验证）

**Interfaces:**
- Consumes: 所有前置 Task 的产物

- [ ] **Step 1: 完整构建 APK**

Run：
```bash
./gradlew :dy-player:assembleBetaDebug 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL。

- [ ] **Step 2: 安装到真机**

Run：
```bash
adb install -r dy-player/build/outputs/apk/beta/debug/dy-player-beta-universal-debug.apk
```
Expected: `Success`

- [ ] **Step 3: 验证 UI 显示新开关**

在手机上：
1. 打开 App → 进入「设置」→「实验室」
2. 确认在「MPV 顺序读优化」下方有「MPV 画质增强」开关
3. 副标题文字："使用 FSR 着色器提升低分辨率视频清晰度（仅 MPV 内核）"
4. 默认状态：关

- [ ] **Step 4: 验证 adb 运行时切换生效**

手机播放一个低分辨率（≤720p）实拍视频，用 MPV 内核，暂停在细节多的帧。然后：

```bash
# 关闭状态截图（基准）
adb shell screencap -p /sdcard/baseline.png
adb pull /sdcard/baseline.png /tmp/baseline.png

# 开启 FSR
adb shell am broadcast -f 0x10 -a me.lingci.dy.player.SUPER_RES_ON

# 等几秒重渲染
sleep 3

# 开启状态截图
adb shell screencap -p /sdcard/fsr_on.png
adb pull /sdcard/fsr_on.png /tmp/fsr_on.png

# 关闭（恢复）
adb shell am broadcast -f 0x10 -a me.lingci.dy.player.SUPER_RES_OFF
```

Expected: 画面应明显更锐利（用图片查看器对比 /tmp/baseline.png 和 /tmp/fsr_on.png）。

- [ ] **Step 5: 验证 UI 切换也生效**

手机上：
1. 在「实验室」打开「MPV 画质增强」开关
2. 看到 Toast「已开启 MPV 画质增强」
3. 画面立即变锐利（pause 状态下因 forceRerender 也会重绘）
4. 关闭开关 → Toast「已关闭 MPV 画质增强」→ 画面回到原始状态

- [ ] **Step 6: 验证持久化**

1. 开启开关 → 杀进程（或重启 App）
2. 重新播放视频 → 画面应仍是 FSR 增强状态（不需要再切开关）

- [ ] **Step 7: 验证内核切换**

1. 播放中切到 ExoPlayer 内核 → 不崩，shader 不影响 Exo
2. 切回 MPV 内核 → shader 自动挂上（applySuperResolutionOnInit）

- [ ] **Step 8: 提交实施日志**

如果一切正常，无需额外提交。如果发现 bug，按修复各自提交。

---

## Task 12: 合并到主分支（可选，需用户决定）

**Files:**
- 无（git 操作）

- [ ] **Step 1: 确认所有改动**

Run：
```bash
git log --oneline feat/mpv-super-resolution ^main
```
Expected: 看到 Task 1-10 的所有 commit。

- [ ] **Step 2: 跟用户确认合并策略**

询问用户：
- 直接 merge 到 main？
- 创建 PR？
- 还是先继续在分支上测试一段时间？

- [ ] **Step 3: 按用户决定执行**

如选 merge：
```bash
git checkout main
git merge --no-ff feat/mpv-super-resolution -m "Merge branch 'feat/mpv-super-resolution'"
```

如选 PR：用 `gh pr create` 创建。

---

## Self-Review Checklist

实施完成后，对照 spec 检查：

- [ ] **UI**：Lab 设置页有「MPV 画质增强」开关，副标题正确
- [ ] **SP 键**：`labMpvSuperResolution` 在 SpUtil 定义，默认 false
- [ ] **跨模块契约**：MpvMediaPlayer 用字符串 `"labMpvSuperResolution"` 读取，注释明确「改名需同步」
- [ ] **Shader**：`FSR.glsl` 在 assets/shaders/，只含 LUMA hook
- [ ] **加载**：`setSuperResolutionEnabled(true)` 时挂上 shader，false 时清空
- [ ] **持久化**：开启后重启 App 仍生效（applySuperResolutionOnInit）
- [ ] **运行时切换**：通过广播触发，立即生效（forceRerender）
- [ ] **adb 调试**：`adb shell am broadcast -f 0x10 -a me.lingci.dy.player.SUPER_RES_ON/OFF` 可用
- [ ] **ExoPlayer 不受影响**：切换 Exo 内核时广播被忽略（as? MpvMediaPlayer 返回 null）
- [ ] **备份/恢复**：新键在 BackupSettingsFragment 已加
- [ ] **Spike 残留清理**：grep `spike\|SPIKE` 在所有改动文件中无输出
- [ ] **短视频页**：ShortVideoActivity 也接收同样广播
- [ ] **不 bump targetSdk**：保持 28
