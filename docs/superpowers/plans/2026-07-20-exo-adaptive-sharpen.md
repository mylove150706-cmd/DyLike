# ExoPlayer Adaptive Sharpen 画质增强开关 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 ExoPlayer 加一个画质增强开关，开启后用 GLSL 锐化 shader 实时处理每一帧，提升低分辨率视频的视觉清晰度。

**Architecture:** Media3 `ExoPlayer.setVideoEffects(List<Effect>)` + 自定义 `GlEffect`（继承 `BaseGlShaderProgram`）。Media3 自己管 GL context 和渲染，把处理过的帧 blit 到现有 TextureRenderView/SurfaceRenderView 的 Surface 上，不需要自己写 GLSurfaceView。

**Tech Stack:** Kotlin + Java · Media3 1.9.0 (`media3-effect`, `BaseGlShaderProgram`, `GlProgram`) · GLSL ES 2.0 · Android BroadcastReceiver · SharedPreferences via SPManager delegate

## Global Constraints

- 不要硬编码依赖版本（用 `libs.*` catalog）
- SP 键名 `labMpvSuperResolution` **保留不变**（兼容备份文件和历史用户），只改 Kotlin 注释
- GLSL 必须 ES 2.0 兼容（Media3 1.9.0 默认走 ES2 路径）
- 中文文案，与现有 lab strings 风格一致
- 广播在 Android 14+ (TIRAMISU+) 需 `Context.RECEIVER_EXPORTED` flag
- 严格保留 MPV 路径完全不变（开关只在 ExoPlayer 实例生效）
- 不要 bump `targetSdk`（保持 28）
- 注释和用户可见字符串用简体中文
- **经验教训**（来自 MPV shader spike）：先验证管线（染色测试），再上算法（锐化）。染色变红 = pipeline 通；染色不变 = pipeline 死。

**实施分支**：`feat/exo-super-resolution`（基于 `feat/mpv-super-resolution` 切出）

---

## 实施策略：渐进式三阶段

来自 MPV spike 的教训：染色测试证明"管线通" ≠ "算法有效"。为避免重蹈覆辙，本 plan 分三阶段，每阶段都有可独立验证的产出：

| Phase | 目标 | 验证方法 |
|---|---|---|
| **Phase 1** | 证明 Media3 setVideoEffects 在 DyLike 现有 TextureView 上能跑 | 用染色 shader（红染），开关切换时画面应变红 |
| **Phase 2** | 把染色换成 Unsharp Mask（3x3 简化锐化），证明锐化效果肉眼可见 | 对比开关前后的清晰度 |
| **Phase 3** | 把 Unsharp Mask 升级为完整 adaptive-sharpen（如 Phase 2 效果不够） | 对比锐化质量，可选执行 |

Phase 1 走不通就停下（说明 Media3 在本机有兼容问题，需要切到方案 B 自己写 GLSurfaceView）。Phase 3 是 optional，根据 Phase 2 效果决定要不要做。

---

## 文件结构概览

### 新建
- `lib-player/player-exo/src/main/res/raw/fragment_shader_tint_es2.glsl` — Phase 1 染色 shader
- `lib-player/player-exo/src/main/res/raw/fragment_shader_unsharp_mask_es2.glsl` — Phase 2 锐化 shader
- `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/effect/SuperResolutionEffect.kt` — GlEffect + BaseGlShaderProgram 实现

### 修改
- `gradle/libs.versions.toml` — 加 `media3-effect` 别名
- `lib-player/player-exo/build.gradle.kts` — 加 `api(libs.media3.effect)` 依赖
- `lib-player/player-exo/src/main/java/xyz/doikki/videoplayer/exo/ExoMediaPlayer.java` — 加 `setVideoEffects` 字段 + setter + Builder 链
- `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/CustomExoMediaPlayer.kt` — 加 `setSuperResolutionEnabled` + `applySuperResolutionOnInit` + SP 常量
- `dy-player/src/main/res/values/strings.xml` — 改文案
- `dy-player/src/main/java/me/lingci/dy/player/ui/long_video/LongVideoActivity.kt` — receiver cast 从 MpvMediaPlayer 改 CustomExoMediaPlayer
- `dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt` — 同上

### 删除
- `lib-player/player-mpv/src/main/assets/shaders/FSR.glsl`
- `lib-player/player-mpv/src/main/assets/shaders/CAS.glsl`
- MpvMediaPlayer.kt 里的所有 spike super-res 方法

---

## Task 1: 准备分支 + 清理 MPV spike 残留

**Files:**
- Modify: `lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt`
- Delete: `lib-player/player-mpv/src/main/assets/shaders/FSR.glsl`, `CAS.glsl`

**Interfaces:**
- Consumes: `feat/mpv-super-resolution` 分支
- Produces: 干净的 `feat/exo-super-resolution` 分支（MPV 端无 super-res 残留）

- [ ] **Step 1: 创建实施分支**

Run:
```bash
cd /c/Users/jiangyunfei/Desktop/DyLike
git checkout feat/mpv-super-resolution
git status  # 应该干净
git checkout -b feat/exo-super-resolution
git branch --show-current
```
Expected: 输出 `feat/exo-super-resolution`

- [ ] **Step 2: 找到 MpvMediaPlayer.kt 里所有 super-res 相关代码**

Run:
```bash
grep -n "SuperResolution\|superResolution\|Super_res\|SUPER_RES\|SP_KEY_LAB_MPV_SUPER\|SP_LAB_MPV_SUPER\|ensureShadersCopied\|forceRerender\|dumpSuperRes\|applySuperResolution" lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt
```
记录所有命中行号。

- [ ] **Step 3: 删除 SP 常量**

在 MpvMediaPlayer.kt 里找到 `SP_KEY_LAB_MPV_SUPER_RESOLUTION` 和 `SP_LAB_MPV_SUPER_RESOLUTION_DEFAULT` 常量定义（及上面的注释块），整段删除。

- [ ] **Step 4: 删除 super-res 方法**

删除以下方法（连同它们的 KDoc 注释）：
- `setSuperResolutionEnabled(enabled: Boolean)`
- `dumpSuperResDiagnostics()`
- `applySuperResolutionOnInit()`
- `ensureShadersCopied(names: List<String>): List<String>`
- `forceRerender()`
- `superResolutionShaderNames` 字段

- [ ] **Step 5: 删除 initialize() 里的调用**

找到 `applySuperResolutionOnInit()` 的调用点（约在 `initialize()` 中 `setRuntimeOptions()` 之后），整段删除。同时删除"runtime options set, ready for super-resolution check"那行 log。

- [ ] **Step 6: 删除 MPV shader assets**

Run:
```bash
rm lib-player/player-mpv/src/main/assets/shaders/FSR.glsl
rm lib-player/player-mpv/src/main/assets/shaders/CAS.glsl
rmdir lib-player/player-mpv/src/main/assets/shaders  # 如果目录空了
rmdir lib-player/player-mpv/src/main/assets  # 如果 assets 也空了
ls lib-player/player-mpv/src/main/assets/ 2>&1 || echo "assets removed"
```
Expected: 目录已被移除（或不再含 shaders 子目录）

- [ ] **Step 7: 编译验证**

Run:
```bash
./gradlew :lib-player:player-mpv:compileDebugKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL，没有 SuperResolution 相关未解析引用。

- [ ] **Step 8: 验证 spike 残留已清理**

Run:
```bash
grep -n "SuperResolution\|superResolution\|spike\|SPIKE\|FSR\|CAS" lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt
```
Expected: 无输出。

- [ ] **Step 9: 提交**

Run:
```bash
git add lib-player/player-mpv/
git commit -m "refactor(super-res): remove MPV shader spike remnants

MPV shader route is dead (see spike report
2026-07-20-mpv-shader-super-resolution-spike.md). Removing the
setSuperResolutionEnabled scaffolding, SP constants, and shader
assets (FSR.glsl, CAS.glsl) from MpvMediaPlayer.kt.

Pivoting to ExoPlayer + Media3 GlEffect route (next commits)."
```
Expected: commit 成功

---

## Task 2: 加 media3-effect 依赖

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `lib-player/player-exo/build.gradle.kts`

**Interfaces:**
- Consumes: 现有 `media3 = "1.9.0"` 版本
- Produces: `libs.media3.effect` accessor + `media3-effect` 在 player-exo 模块可用

- [ ] **Step 1: 加 media3-effect 别名到 libs.versions.toml**

找到 media3 库定义区（约 line 90-100），在 `media3-transformer` 行之后添加：

```toml
media3-effect = { module = "androidx.media3:media3-effect", version.ref = "media3" }
```

- [ ] **Step 2: 在 player-exo/build.gradle.kts 加依赖**

找到 dependencies 块（约 line 41-54），在 `api(libs.media3.inspector)` 之后添加：

```kotlin
api(libs.media3.effect)
```

- [ ] **Step 3: 验证依赖解析**

Run:
```bash
./gradlew :lib-player:player-exo:dependencies --configuration debugCompileClasspath 2>&1 | grep "media3-effect" | head -3
```
Expected: 看到 `androidx.media3:media3-effect:1.9.0` 已解析

- [ ] **Step 4: 验证编译**

Run:
```bash
./gradlew :lib-player:player-exo:assembleDebug 2>&1 | tail -3
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

Run:
```bash
git add gradle/libs.versions.toml lib-player/player-exo/build.gradle.kts
git commit -m "feat(exo-super-res): add media3-effect dependency"
```

---

## Task 3: 写 Phase 1 染色 shader + GlEffect 实现

**Phase 1 目标**：用一个最简单的"红染"shader 验证 Media3 setVideoEffects 在 DyLike 现有架构上能跑。

**Files:**
- Create: `lib-player/player-exo/src/main/res/raw/fragment_shader_tint_es2.glsl`
- Create: `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/effect/SuperResolutionEffect.kt`

**Interfaces:**
- Consumes: Media3 `BaseGlShaderProgram`、`GlProgram`、`androidx.media3.effect.R.raw.vertex_shader_transformation_es2`
- Produces:
  - `SuperResolutionEffect(strength)` GlEffect 类
  - `SuperResolutionProgram` BaseGlShaderProgram 子类（内部）

- [ ] **Step 1: 创建 raw 资源目录**

Run:
```bash
mkdir -p lib-player/player-exo/src/main/res/raw
ls lib-player/player-exo/src/main/res/
```
Expected: raw 目录已建

- [ ] **Step 2: 创建 Phase 1 染色 fragment shader**

创建文件 `lib-player/player-exo/src/main/res/raw/fragment_shader_tint_es2.glsl`：

```glsl
// Phase 1 验证 shader：把画面染红，证明 Media3 setVideoEffects 在 DyLike 现有
// TextureRenderView/SurfaceRenderView 上能跑通。
// 验证通过后会替换为 unsharp_mask（Phase 2）和 adaptive_sharpen（Phase 3）。
//
// uniform sampler2D uTexSampler - Media3 提供的输入纹理（已转 RGB）
// varying vec2 vTexCoord - 由 vertex_shader_transformation_es2 输出

precision mediump float;
uniform sampler2D uTexSampler;
varying vec2 vTexCoord;

void main() {
    vec4 c = texture2D(uTexSampler, vTexCoord);
    // 强制把 R 通道拉满，画面整体变红
    gl_FragColor = vec4(1.0, c.g, c.b, c.a);
}
```

- [ ] **Step 3: 创建 SuperResolutionEffect.kt**

创建文件 `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/effect/SuperResolutionEffect.kt`：

```kotlin
package me.lingci.lib.player.exo.effect

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.R
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/**
 * 画质增强 GlEffect。当前 Phase 1 加载染色 shader 验证管线。
 *
 * 后续 Phase 会替换为 unsharp_mask / adaptive_sharpen。
 *
 * 详见 spec：docs/superpowers/specs/2026-07-20-exo-adaptive-sharpen-design.md
 */
@OptIn(UnstableApi::class)
class SuperResolutionEffect(
    @Suppress("unused") private val strength: Float = 1.0f
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useDebug: Boolean): GlShaderProgram {
        return SuperResolutionProgram(context)
    }
}

/**
 * Media3 BaseGlShaderProgram 子类。模板参考 androidx.media3.effect.ColorLutShaderProgram。
 *
 * 注意构造函数签名：BaseGlShaderProgram(useHighPrecisionColorComponents, texturePoolCapacity)
 * - useHighPrecisionColorComponents: HDR 用 true，SDR 用 false
 * - texturePoolCapacity: 输出纹理池大小，单输入单输出用 1
 */
@OptIn(UnstableApi::class)
private class SuperResolutionProgram(
    context: Context
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents */ false, /* texturePoolCapacity */ 1) {

    private val glProgram: GlProgram = try {
        GlProgram(
            context,
            /* vertexShaderResId */ R.raw.vertex_shader_transformation_es2,
            /* fragmentShaderResId */ me.lingci.lib.player.exo.R.raw.fragment_shader_tint_es2
        )
    } catch (e: Exception) {
        throw VideoFrameProcessingException(e)
    }.apply {
        // 标准 Media3 模板：填满整个 NDC 空间 [-1, 1]
        setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
        val identity = GlUtil.create4x4IdentityMatrix()
        setFloatsUniform("uTransformationMatrix", identity)
        setFloatsUniform("uTexTransformationMatrix", identity)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        // 不改变尺寸
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex */ 0)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first */ 0, /* count */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            // ignore on release
        }
    }
}
```

- [ ] **Step 4: 编译验证**

Run:
```bash
./gradlew :lib-player:player-exo:compileDebugKotlin 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

如果报错"R.raw.vertex_shader_transformation_es2 找不到"，检查 `androidx.media3.effect.R` 的 import；如果报错 "fragment_shader_tint_es2 找不到"，检查 raw 资源路径。

- [ ] **Step 5: 提交**

Run:
```bash
git add lib-player/player-exo/src/main/res/raw/fragment_shader_tint_es2.glsl lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/effect/SuperResolutionEffect.kt
git commit -m "feat(exo-super-res): add SuperResolutionEffect (Phase 1 tint shader)

GlEffect + BaseGlShaderProgram implementation modeled on
ColorLutShaderProgram. Phase 1 uses a tint shader (turns frame red)
to verify Media3 setVideoEffects pipeline works on DyLike's existing
TextureRenderView. Will be replaced with unsharp_mask (Phase 2)
and adaptive_sharpen (Phase 3) once pipeline is verified."
```

---

## Task 4: ExoMediaPlayer setVideoEffects 接入

**Files:**
- Modify: `lib-player/player-exo/src/main/java/xyz/doikki/videoplayer/exo/ExoMediaPlayer.java`

**Interfaces:**
- Consumes: `androidx.media3.common.Effect`、`androidx.media3.exoplayer.ExoPlayer`
- Produces: `ExoMediaPlayer.setVideoEffects(List<Effect>)` 方法 + Builder 链使用 `mVideoEffects`

- [ ] **Step 1: 找到 ExoMediaPlayer 的 import 区和字段区**

Read 文件，找到 line 39-52（class 声明 + 字段区）。

- [ ] **Step 2: 加 import**

在 ExoMediaPlayer.java 的 import 区添加：

```java
import androidx.media3.common.Effect;
import java.util.Collections;
import java.util.List;
```

（如果已有就跳过）

- [ ] **Step 3: 加 mVideoEffects 字段**

在现有字段区（约 line 41-52 之间）添加：

```java
@Nullable
private List<Effect> mVideoEffects = null;
```

确保文件顶部已 import `androidx.annotation.Nullable`（如果没有就加）。

- [ ] **Step 4: 加 setter/getter**

在 `setDataSource` 等公开方法附近添加：

```java
/**
 * 设置视频效果列表。运行时可调用，会同步到 mInternalPlayer。
 * 传 null 或空列表表示禁用所有效果。
 */
public void setVideoEffects(@Nullable List<Effect> effects) {
    mVideoEffects = effects;
    if (mInternalPlayer != null) {
        mInternalPlayer.setVideoEffects(
            effects != null ? effects : Collections.<Effect>emptyList()
        );
    }
}

@Nullable
public List<Effect> getVideoEffects() {
    return mVideoEffects;
}
```

- [ ] **Step 5: 修改 initPlayer() 里的 Builder 链**

找到 `initPlayer()` 里 Builder 链（约 line 76-86）。把：

```java
mInternalPlayer = new ExoPlayer.Builder(
        mAppContext,
        mRenderersFactory,
        new DefaultMediaSourceFactory(mAppContext),
        mTrackSelector,
        mLoadControl,
        bandwidthMeter,
        new DefaultAnalyticsCollector(Clock.DEFAULT))
        .build();
```

改为：

```java
ExoPlayer.Builder builder = new ExoPlayer.Builder(
        mAppContext,
        mRenderersFactory,
        new DefaultMediaSourceFactory(mAppContext),
        mTrackSelector,
        mLoadControl,
        bandwidthMeter,
        new DefaultAnalyticsCollector(Clock.DEFAULT));
if (mVideoEffects != null && !mVideoEffects.isEmpty()) {
    builder.setVideoEffects(mVideoEffects);
}
mInternalPlayer = builder.build();
```

- [ ] **Step 6: 编译验证**

Run:
```bash
./gradlew :lib-player:player-exo:compileDebugJavaWithJavac 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 提交**

Run:
```bash
git add lib-player/player-exo/src/main/java/xyz/doikki/videoplayer/exo/ExoMediaPlayer.java
git commit -m "feat(exo-super-res): wire setVideoEffects into ExoMediaPlayer.Builder

Adds mVideoEffects field + setter/getter. When non-empty,
ExoPlayer.Builder is chained with .setVideoEffects(effects) so
effects are active from playback start."
```

---

## Task 5: CustomExoMediaPlayer 加 setSuperResolutionEnabled

**Files:**
- Modify: `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/CustomExoMediaPlayer.kt`

**Interfaces:**
- Consumes: `ExoMediaPlayer.setVideoEffects`（Task 4）、`SuperResolutionEffect`（Task 3）、SP key `labMpvSuperResolution`
- Produces: `CustomExoMediaPlayer.setSuperResolutionEnabled(enabled)` 方法 + `applySuperResolutionOnInit()` 方法

- [ ] **Step 1: 加 import + companion object 常量**

在 CustomExoMediaPlayer.kt 顶部 import 区添加（如果还没有）：

```kotlin
import android.preference.PreferenceManager
import androidx.media3.common.Effect
import me.lingci.lib.player.exo.effect.SuperResolutionEffect
```

找到 companion object（如果没有就在 class 体最末尾加），添加：

```kotlin
companion object {
    // 跨模块契约：SP 键名跟 dy-player/SpUtil 保持一致。
    // 保留 labMpvSuperResolution 这个历史名（兼容备份文件和已开过该开关的用户），
    // 但语义上现在是 ExoPlayer 的画质增强。
    private const val SP_KEY_LAB_MPV_SUPER_RESOLUTION = "labMpvSuperResolution"
    private const val SP_LAB_MPV_SUPER_RESOLUTION_DEFAULT = false
}
```

如果 companion object 已存在，把这两个 const 加到现有 companion 里。

- [ ] **Step 2: 加 setSuperResolutionEnabled + applySuperResolutionOnInit**

在 CustomExoMediaPlayer 类里（推荐放在已有的 initPlayer() override 附近）添加：

```kotlin
/**
 * 画质增强开关。开启时挂 SuperResolutionEffect，关闭时清空 effects。
 * 可在播放中调用，Media3 会自动重建 video output。
 *
 * ⚠️ 跨模块契约：开关 SP 键为 `labMpvSuperResolution`（历史名），
 * 由 dy-player/SpUtil 定义，这里通过字符串字面量读取。
 */
fun setSuperResolutionEnabled(enabled: Boolean) {
    val effects: List<Effect> = if (enabled) {
        listOf(SuperResolutionEffect(strength = 1.0f))
    } else {
        emptyList()
    }
    setVideoEffects(effects)
}

/**
 * 初始化时按 SP 决定要不要默认挂上。
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
```

注意：检查 `appContext` 是否是 CustomExoMediaPlayer 已有的字段（从 ExoMediaPlayer 继承）。如果实际名字不同（如 `mAppContext` 或 `context`），用对的那个。

- [ ] **Step 3: 在 initPlayer() 调用 applySuperResolutionOnInit**

找到 CustomExoMediaPlayer 的 `initPlayer()` override（约 line 155-204），在 `super.initPlayer()` 调用之后、其他逻辑之前，加：

```kotlin
applySuperResolutionOnInit()
```

- [ ] **Step 4: 编译验证**

Run:
```bash
./gradlew :lib-player:player-exo:compileDebugKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

Run:
```bash
git add lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/CustomExoMediaPlayer.kt
git commit -m "feat(exo-super-res): wire setSuperResolutionEnabled into CustomExoMediaPlayer

Adds setSuperResolutionEnabled(enabled) for runtime toggle and
applySuperResolutionOnInit() for SP-driven init. Reads SP key
'labMpvSuperResolution' (kept for backup compat)."
```

---

## Task 6: 改造广播接收器（Long + Short VideoActivity）

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/ui/long_video/LongVideoActivity.kt`
- Modify: `dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt`

**Interfaces:**
- Consumes: `CustomExoMediaPlayer.setSuperResolutionEnabled`（Task 5）
- Produces: 接收 SUPER_RES_ON/OFF 时调 ExoPlayer 的 setSuperResolutionEnabled

- [ ] **Step 1: 修改 LongVideoActivity 的 import**

把 `MpvMediaPlayer` 的 import（如果有）替换为：

```kotlin
import me.lingci.lib.player.exo.CustomExoMediaPlayer
```

如果两个 import 都需要保留（比如别处也用了 MpvMediaPlayer），就只追加 CustomExoMediaPlayer import。

- [ ] **Step 2: 修改 LongVideoActivity 的 superResolutionReceiver**

找到 `superResolutionReceiver` 字段（约 line 216-240）。把：

```kotlin
val mpv = videoView.getCurrentPlayer() as? MpvMediaPlayer ?: return
when (intent.action) {
    ACTION_SUPER_RESOLUTION_ON -> {
        mpv.setSuperResolutionEnabled(true)
        Toast.makeText(this@LongVideoActivity, "MPV 画质增强：已开启", Toast.LENGTH_SHORT).show()
    }
    ACTION_SUPER_RESOLUTION_OFF -> {
        mpv.setSuperResolutionEnabled(false)
        Toast.makeText(this@LongVideoActivity, "MPV 画质增强：已关闭", Toast.LENGTH_SHORT).show()
    }
}
```

替换为：

```kotlin
val player = videoView.getCurrentPlayer() as? CustomExoMediaPlayer ?: return
when (intent.action) {
    ACTION_SUPER_RESOLUTION_ON -> {
        player.setSuperResolutionEnabled(true)
        Toast.makeText(this@LongVideoActivity, "画质增强：已开启", Toast.LENGTH_SHORT).show()
    }
    ACTION_SUPER_RESOLUTION_OFF -> {
        player.setSuperResolutionEnabled(false)
        Toast.makeText(this@LongVideoActivity, "画质增强：已关闭", Toast.LENGTH_SHORT).show()
    }
}
```

- [ ] **Step 3: ShortVideoActivity 同样改造**

在 ShortVideoActivity.kt：
- import `me.lingci.lib.player.exo.CustomExoMediaPlayer`
- 把 `superResolutionReceiver` 里 `mVideoView.getCurrentPlayer() as? MpvMediaPlayer ?: return` 改为 `mVideoView.getCurrentPlayer() as? CustomExoMediaPlayer ?: return`
- 把 `mpv.setSuperResolutionEnabled(true/false)` 改为 `player.setSuperResolutionEnabled(true/false)`（注意把局部变量名 mpv 改 player）

- [ ] **Step 4: 编译验证**

Run:
```bash
./gradlew :dy-player:compileBetaDebugKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

Run:
```bash
git add dy-player/src/main/java/me/lingci/dy/player/ui/long_video/LongVideoActivity.kt dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt
git commit -m "feat(exo-super-res): receiver cast MpvMediaPlayer -> CustomExoMediaPlayer

Both LongVideoActivity and ShortVideoActivity now dispatch the
SUPER_RES_ON/OFF broadcast to CustomExoMediaPlayer.setSuperResolutionEnabled.
MPV kernel is ignored (returns null from cast)."
```

---

## Task 7: 改 UI 文案

**Files:**
- Modify: `dy-player/src/main/res/values/strings.xml`
- Modify: `dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt`

**Interfaces:**
- Consumes: 无
- Produces: 用户可见文案改为通用语义（去掉"MPV"）

- [ ] **Step 1: 改 strings.xml**

找到（约 line 119-120）：

```xml
<string name="hint_lab_mpv_super_resolution">MPV 画质增强</string>
<string name="hint_lab_mpv_super_resolution_desc">使用 FSR 着色器提升低分辨率视频清晰度（仅 MPV 内核）</string>
```

改为：

```xml
<string name="hint_lab_mpv_super_resolution">画质增强</string>
<string name="hint_lab_mpv_super_resolution_desc">使用锐化算法提升视频清晰度（仅 ExoPlayer 内核）</string>
```

注意：保留 string name `hint_lab_mpv_super_resolution`（避免 layout 改动）。

- [ ] **Step 2: 更新 SpUtil.kt 的注释**

找到 `var labMpvSuperResolution by SPManager.boolean(false)`（约 line 184-192）。把 KDoc 整段改为：

```kotlin
/**
 * 画质增强：开启后给 ExoPlayer 内核挂 AdaptiveSharpen GlEffect，
 * 实时锐化视频画面。仅 ExoPlayer 内核生效。
 *
 * 历史命名：曾经是 MPV FSR 试用键（labMpvSuperResolution），MPV 路线已废弃，
 * 但 SP 键名保留以兼容备份文件和已开过该开关的用户。
 *
 * ⚠️ 跨模块约定：此 key 由 lib-player/player-exo/CustomExoMediaPlayer 在 init 时
 * 直接读取（app 默认 SharedPreferences）。重命名属性会破坏 Exo 端读取。
 */
var labMpvSuperResolution by SPManager.boolean(false)
```

- [ ] **Step 3: 提交**

Run:
```bash
git add dy-player/src/main/res/values/strings.xml dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt
git commit -m "feat(exo-super-res): update UI copy from MPV/FSR to ExoPlayer/sharpen"
```

---

## Task 8: Phase 1 真机验证（关键决策点）

**Files:** 无（运行时验证）

**Phase 1 验证目标**：染色 shader 应让画面变红。这是判断 Media3 setVideoEffects pipeline 在 DyLike 现有 TextureRenderView 上能跑的关键测试。

**如果 Phase 1 失败**：停下，跟用户讨论切到方案 B（自己写 GLSurfaceView）。

- [ ] **Step 1: 构建 + 安装**

Run:
```bash
./gradlew :dy-player:assembleBetaDebug 2>&1 | tail -3
adb install -r dy-player/build/outputs/apk/beta/debug/dy-player-beta-universal-debug.apk
```
Expected: BUILD SUCCESSFUL + Success

- [ ] **Step 2: 准备测试环境**

请用户配合：
1. 杀掉 DyLike 进程
2. 打开 App，**切换到 ExoPlayer 内核**（设置 → 播放器 → 长视频内核 → ExoPlayer）
3. 播放任意视频（之前的 WebDav 测试素材就行）
4. 暂停在一帧
5. 等"暂停了"信号

- [ ] **Step 3: adb 触发染色开关**

Run:
```bash
# 基准（应该是正常颜色）
adb exec-out screencap -p > /tmp/baseline.png

# 发 ON
adb shell am broadcast -f 0x10 -a me.lingci.dy.player.SUPER_RES_ON
sleep 3

# 开启后（应该是红色）
adb exec-out screencap -p > /tmp/tinted.png
```

- [ ] **Step 4: 判断结果**

Run:
```bash
py -3 - <<'EOF'
import numpy as np
from PIL import Image
base = np.asarray(Image.open(r'C:\Users\jiangyunfei\AppData\Local\Temp\baseline.png').convert('RGB'), dtype=np.float32)
tint = np.asarray(Image.open(r'C:\Users\jiangyunfei\AppData\Local\Temp\tinted.png').convert('RGB'), dtype=np.float32)
# 计算视频区域的平均颜色（取截图中心 1/3 区域）
h, w = base.shape[:2]
center = lambda img: img[h//3:2*h//3, w//3:2*w//3].mean(axis=(0,1))
print(f"baseline center RGB: {center(base)}")
print(f"tinted   center RGB: {center(tint)}")
print(f"R channel diff: {tint[..., 0].mean() - base[..., 0].mean():.1f}")
EOF
```

判断标准：
- **R 通道显著升高（>30）+ G/B 下降** → ✅ Phase 1 通过，进 Task 9
- **R 通道无变化（<5）** → ❌ Phase 1 失败，Media3 setVideoEffects 在 DyLike 上不工作。停下来跟用户讨论是否切到方案 B。

- [ ] **Step 5: 询问用户肉眼确认**

让用户确认：开启瞬间画面**是否变红**？

用户回答：
- **变红了** → 进 Task 9（Phase 2：换锐化 shader）
- **没变化** → 停下，记录失败现象，跟用户讨论下一步

- [ ] **Step 6: 提交 Phase 1 验证日志**

无论成败，把结果写进 spec 文档的"实施记录"小节：

```bash
# 在 docs/superpowers/specs/2026-07-20-exo-adaptive-sharpen-design.md 末尾追加：
# ## 实施记录
# ### Phase 1 染色测试
# - 时间：YYYY-MM-DD
# - 设备：xxx
# - 结果：[✅ 通过 / ❌ 失败]
# - 现象：xxx
```

---

## Task 9: 写 Phase 2 Unsharp Mask shader（Phase 1 通过后才做）

**前提**：Task 8 Step 5 用户确认画面变红。

**Files:**
- Create: `lib-player/player-exo/src/main/res/raw/fragment_shader_unsharp_mask_es2.glsl`
- Modify: `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/effect/SuperResolutionEffect.kt`

**Phase 2 目标**：把染色 shader 换成 unsharp mask（3x3 高斯模糊 + 减法），让画面真正变锐利。

- [ ] **Step 1: 写 unsharp mask shader**

创建文件 `lib-player/player-exo/src/main/res/raw/fragment_shader_unsharp_mask_es2.glsl`：

```glsl
// Phase 2 锐化 shader：Unsharp Mask（3x3 高斯 + 减法）
// sharp = original + amount * (original - blur)
//
// uniforms:
//   uTexSampler - Media3 输入纹理（已转 RGB）
//   uTexelSize  - 单像素 = 1/vec2(width, height)
//   uAmount     - 锐化强度（0.0~2.0，建议 0.5~1.5）

precision mediump float;
uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform float uAmount;
varying vec2 vTexCoord;

void main() {
    vec3 c = texture2D(uTexSampler, vTexCoord).rgb;

    // 3x3 高斯模糊（标准化权重 1/4/6/4/1）
    vec3 blur = vec3(0.0);
    // 4-邻接权重 1
    blur += texture2D(uTexSampler, vTexCoord + vec2(-1.0, -1.0) * uTexelSize).rgb * 1.0;
    blur += texture2D(uTexSampler, vTexCoord + vec2( 1.0, -1.0) * uTexelSize).rgb * 1.0;
    blur += texture2D(uTexSampler, vTexCoord + vec2(-1.0,  1.0) * uTexelSize).rgb * 1.0;
    blur += texture2D(uTexSampler, vTexCoord + vec2( 1.0,  1.0) * uTexelSize).rgb * 1.0;
    // 4-邻接权重 4
    blur += texture2D(uTexSampler, vTexCoord + vec2( 0.0, -1.0) * uTexelSize).rgb * 4.0;
    blur += texture2D(uTexSampler, vTexCoord + vec2(-1.0,  0.0) * uTexelSize).rgb * 4.0;
    blur += texture2D(uTexSampler, vTexCoord + vec2( 1.0,  0.0) * uTexelSize).rgb * 4.0;
    blur += texture2D(uTexSampler, vTexCoord + vec2( 0.0,  1.0) * uTexelSize).rgb * 4.0;
    // 中心权重 6
    blur += c * 6.0;
    blur /= 24.0;  // 总权重 = 1+1+1+1 + 4+4+4+4 + 6 = 24

    // Unsharp mask
    vec3 sharp = c + uAmount * (c - blur);

    gl_FragColor = vec4(clamp(sharp, 0.0, 1.0), 1.0);
}
```

- [ ] **Step 2: 修改 SuperResolutionProgram 加载新 shader**

在 `SuperResolutionEffect.kt` 里，把 `SuperResolutionProgram` 构造函数中 `fragmentShaderResId` 从：

```kotlin
R.raw.fragment_shader_tint_es2
```

改为：

```kotlin
me.lingci.lib.player.exo.R.raw.fragment_shader_unsharp_mask_es2
```

- [ ] **Step 3: 在 SuperResolutionProgram.drawFrame 设置 uTexelSize + uAmount**

修改 `drawFrame`：

```kotlin
override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
    try {
        glProgram.use()
        glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex */ 0)
        // uTexelSize 用 configure 时的 inputWidth/inputHeight 算
        // 注意：configure 在 drawFrame 之前调用，可缓存到字段
        glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1f / cachedInputWidth, 1f / cachedInputHeight))
        glProgram.setFloatUniform("uAmount", 1.0f)  // 默认强度，Phase 3 改为 uStrength
        glProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first */ 0, /* count */ 4)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
}
```

需要在类里加字段缓存 inputWidth/inputHeight：

```kotlin
private var cachedInputWidth = 1
private var cachedInputHeight = 1

override fun configure(inputWidth: Int, inputHeight: Int): Size {
    cachedInputWidth = if (inputWidth > 0) inputWidth else 1
    cachedInputHeight = if (inputHeight > 0) inputHeight else 1
    return Size(inputWidth, inputHeight)
}
```

- [ ] **Step 4: 删除 Phase 1 的染色 shader 文件（可选）**

Run:
```bash
# 保留 fragment_shader_tint_es2.glsl 作为 fallback 调试用，或删掉
rm lib-player/player-exo/src/main/res/raw/fragment_shader_tint_es2.glsl
```

- [ ] **Step 5: 编译验证**

Run:
```bash
./gradlew :lib-player:player-exo:compileDebugKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 真机验证**

构建安装，请用户对比开关前后的画面清晰度：

```bash
./gradlew :dy-player:assembleBetaDebug 2>&1 | tail -3
adb install -r dy-player/build/outputs/apk/beta/debug/dy-player-beta-universal-debug.apk
```

请用户：
1. 杀进程重开
2. ExoPlayer 内核播放视频
3. 暂停 → 等信号
4. adb 发 SUPER_RES_ON → 肉眼判断画面是否变锐利

用户回答：
- **明显变锐** → ✅ Phase 2 通过，进 Task 11（合并）或 Task 10（Phase 3）
- **没变化/变模糊** → 检查 shader 编译是否成功、`uTexelSize` 是否正确、`uAmount` 是否合理；调整后重试

- [ ] **Step 7: 提交**

Run:
```bash
git add lib-player/player-exo/src/main/res/raw/fragment_shader_unsharp_mask_es2.glsl lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/effect/SuperResolutionEffect.kt
git commit -m "feat(exo-super-res): Phase 2 - swap tint for unsharp mask shader

3x3 Gaussian blur + sharpen subtraction. Tunable via uAmount
(currently hardcoded 1.0). Phase 1 tint shader removed (or kept
as debug fallback)."
```

---

## Task 10 (Optional): Phase 3 Adaptive Sharpen 完整移植

**前提**：Task 9 Phase 2 锐化效果可见，但用户希望更高质量锐化（adaptive）。

**Files:**
- Create: `lib-player/player-exo/src/main/res/raw/fragment_shader_adaptive_sharpen_es2.glsl`
- Modify: `SuperResolutionEffect.kt`

**Phase 3 目标**：把 igv adaptive-sharpen 完整移植到 Media3。

**注意**：这个 Task 的复杂度最高，建议在前两个 Phase 都稳定后再做。如果 Phase 2 效果已经满意，可以跳过。

- [ ] **Step 1: 评估 Phase 2 效果是否够用**

跟用户讨论：unsharp mask 的效果是否足够好？
- 是 → 跳过 Phase 3，直接 Task 11 合并
- 否 → 继续做 Phase 3

- [ ] **Step 2: 获取 igv adaptive-sharpen 完整源码**

Run:
```bash
curl -sL "https://gist.githubusercontent.com/igv/8a77e4eb8276753b54bb94c1c50c317e/raw/adaptive-sharpen.glsl" > /tmp/adaptive_sharpen_ref.glsl
wc -l /tmp/adaptive_sharpen_ref.glsl
```

- [ ] **Step 3: 手工移植为 Media3 fragment shader**

需要做的改动：
- 把 `HOOKED_texOff(vec2(x,y))` 宏改为 `texture2D(uTexSampler, vTexCoord + vec2(x,y) * uTexelSize).rgb`
- 把 `fwidth()` 用 `OES_standard_derivatives` 扩展包装（或简化为常量）
- `curve_height` 用 uniform `uStrength` 传
- 数组初始化（`vec3 c[25] = vec3[](...)`）改为逐元素赋值（GLES 2.0 兼容）
- 整个算法作为 `void main()` 的内容

由于这部分代码量大（230 行）且有 GLES 兼容性挑战，建议在 plan 执行时**单独做 brainstorming** 决定如何简化。

- [ ] **Step 4~N: 写 shader、修改 SuperResolutionProgram、验证、调参**

参考 Task 9 的步骤模板。

- [ ] **Step N: 提交**

```bash
git commit -m "feat(exo-super-res): Phase 3 - replace unsharp mask with adaptive sharpen"
```

---

## Task 11: 完整集成验证 + 合并

**前提**：Task 9（或 Task 10）的锐化效果可见，用户接受。

**Files:** 无（运行时验证）

- [ ] **Step 1: 完整冒烟测试**

请用户配合验证：
1. **持久化**：开关开→杀进程→重开→播放→确认效果还在
2. **内核切换**：Exo→MPV（开关在 MPV 上无效，但不崩）→切回 Exo→效果恢复
3. **短视频**：切到短视频播放→发广播→确认 receiver 工作
4. **TextureView + SurfaceView**：分别在两种 render view 下测一遍
5. **备份/恢复**：备份→卸载→重装→恢复→确认开关状态恢复
6. **性能**：播放 1 分钟，观察发热和帧率（drop frame 检查）

- [ ] **Step 2: 跟用户讨论合并策略**

询问用户：
- 直接 merge 到 main？
- 创建 PR？
- 先在分支上测试一段时间？

- [ ] **Step 3: 按用户选择执行**

如选 merge：
```bash
git checkout main
git merge --no-ff feat/exo-super-resolution -m "Merge branch 'feat/exo-super-resolution'"
```

如选 PR：用 `gh pr create`。

- [ ] **Step 4: 更新 spec 文档状态**

在 `docs/superpowers/specs/2026-07-20-exo-adaptive-sharpen-design.md` 顶部改：
```
**状态**：✅ 已合并到 main（YYYY-MM-DD）
```

---

## Self-Review Checklist

实施完成后，对照 spec 检查：

- [ ] **UI**：Lab 设置页有「画质增强」开关，副标题提到 ExoPlayer 内核
- [ ] **SP 键**：`labMpvSuperResolution` 在 SpUtil 定义，键名不变（兼容备份）
- [ ] **跨模块契约**：CustomExoMediaPlayer 用字符串 `"labMpvSuperResolution"` 读取
- [ ] **Media3 依赖**：`media3-effect` 已声明，编译通过
- [ ] **AdaptiveSharpenEffect**：实现 GlEffect 接口，构造 BaseGlShaderProgram 子类
- [ ] **ExoMediaPlayer**：Builder 链接 `.setVideoEffects(mVideoEffects)`
- [ ] **CustomExoMediaPlayer**：`setSuperResolutionEnabled` + `applySuperResolutionOnInit`
- [ ] **广播**：Long+Short VideoActivity 接收 ON/OFF 时调 CustomExoMediaPlayer
- [ ] **MPV 清理**：MpvMediaPlayer.kt 无 spike 残留，assets/shaders 已删
- [ ] **MPV 路径不受影响**：Exo↔MPV 切换不崩
- [ ] **Phase 1 验证**：染色测试通过（画面变红）
- [ ] **Phase 2 验证**：锐化效果肉眼可见
- [ ] **不 bump targetSdk**：保持 28
