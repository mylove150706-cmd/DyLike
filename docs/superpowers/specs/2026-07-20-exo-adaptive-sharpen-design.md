# 设计文档：ExoPlayer Adaptive Sharpen 画质增强开关

**日期**：2026-07-20
**分支**：`feat/exo-super-resolution`（基于 `feat/mpv-super-resolution` 切出，复用开关 UI 但替换实现）
**状态**：✅ 设计已批准，待转实施计划
**前置文档**：
- `docs/superpowers/specs/2026-07-20-mpv-shader-super-resolution-spike.md`（MPV shader 路线失败的最终报告）
- `docs/superpowers/specs/2026-07-20-mpv-fsr-super-resolution-design.md`（已废弃的 MPV 路线设计，保留作参考）

## 背景

用户希望「开启开关后对低分辨率视频进行超分，并以增强后的画质播放」。

**两次失败的尝试**：
1. **MPV shader 路线（Anime4K）**：spike 验证 SSIM≈1.00，判定无效
2. **MPV shader 路线（FSR EASU + CAS）**：FSR 染色测试误导，一度判定"管线通了"，但最终 CAS SHARPENING=1.0 测试 SSIM=0.9989 证明 shader hook 的输出**未被最终渲染管线采用**。MPV Android 集成下 user shader 路线**根本走不通**。

**最终选定路线**：Media3 `ExoPlayer.setVideoEffects(List<Effect>)` + 自定义 `GlEffect`，让 Media3 自己管 GL context，我们只提供 shader。Media3 1.9.0 的 GlEffect pipeline 把效果直接渲染到现有 TextureRenderView/SurfaceRenderView 的 Surface 上，不需要自己写 GLSurfaceView。

## 目标

提供**单一开关**，开启后用 adaptive-sharpen 算法对 ExoPlayer 解码的每一帧做锐化。开关位置、shader 选择、范围、调试入口都已与用户确认。

## 非目标

- ❌ 不实现 1080p/2K/4K 分档（用 adaptive-sharpen 在源分辨率上做锐化，不再追求"放大到指定分辨率"）
- ❌ 不在 MPV 上做任何超分尝试（已两次证实走不通）
- ❌ 不做"按源视频分辨率智能判断"——开关一开，所有 ExoPlayer 播放的视频都过 shader
- ❌ 不做离线神经超分（Real-ESRGAN ncnn）

## 用户界面

**位置**：复用现有 `LabSettingsFragment` 的「画质增强」开关位（之前 `swLabMpvSuperResolution`），文案改为通用语义。

| 文案 | 内容 |
|---|---|
| 标题 | 画质增强（原"MPV 画质增强"） |
| 副标题 | 使用锐化算法提升视频清晰度（仅 ExoPlayer 内核）（原"使用 FSR 着色器..."） |

**切换反馈**：与之前一致，切换时写 SP + 发广播 → 当前 ExoPlayer 实例立即应用。

## 数据层（SharedPreferences）

### SP 键名（关键决策）

**保留 SP 键名 `labMpvSuperResolution`** 不变。原因：
1. 已有用户开过这个开关（虽然之前不生效），改键名会丢用户的开关状态
2. 备份/恢复文件里也有这个键，改键名需要迁移逻辑
3. SP 键名是字符串，与"MPV" 这个语义无关，只是个唯一标识

只更新 Kotlin 属性的注释，说明已迁移到 Exo：

```kotlin
/**
 * 画质增强：开启后给 ExoPlayer 内核挂 adaptive-sharpen GlEffect，
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

## Shader 实现

### Adaptive Sharpen 算法

**来源**：[igv/adaptive-sharpen](https://gist.github.com/igv/8a77e4eb8276753b54bb94c1c50c317e)，mpv 社区事实标准锐化 shader。

**原版特性**：
- 默认挂 OUTPUT hook（mpv 后处理阶段）
- ~13 taps 的边缘自适应卷积
- 主要参数 `curve_height`（1.0 默认，0.3~2.0 合理范围）

### 移植到 Media3

**关键差异**：
- mpv 用 `LUMA_texOff(vec2(x,y))` 在 YUV plane 上采样 → Media3 用 RGB `sampler2D` 在 2D 纹理上采样
- mpv 用 `HOOKED_pos` + `HOOKED_pt` 变量 → Media3 由 vertex shader 提供 `vTexCoord` + 应用层提供 `uTexelSize`
- mpv 用 `//!HOOK OUTPUT` 元数据 → Media3 没有 hook 概念，整个 fragment shader 就是一个完整 pass

**移植要点**：
1. `get(x,y)` 宏改为采样 `uTexSampler` 的对应偏移像素：`texture2D(uTexSampler, vTexCoord + vec2(x, y) * uTexelSize).rgb`
2. RGB 空间里算 luma（用 BT.709 系数）：`luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722))`
3. 锐化结果只作用到 luma 上，再回写 RGB（保留色度，跟原 mpv LUMA hook 的效果一致）
4. `curve_height` 用 uniform `uStrength` 传，便于运行时调整

### 文件位置

| 文件 | 内容 |
|---|---|
| `lib-player/player-exo/src/main/res/raw/fragment_shader_adaptive_sharpen_es2.glsl` | Adaptive Sharpen 的 fragment shader |
| `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/effect/AdaptiveSharpenEffect.kt` | GlEffect + BaseGlShaderProgram 实现 |

Media3 自带 `R.raw.vertex_shader_transformation_es2`（vertex shader），直接复用。

## ExoMediaPlayer 接入

### `ExoMediaPlayer.java` 修改

加 effects 字段 + setter + Builder 链：

```java
// 新增字段（放在现有 protected 字段附近，约 line 50）
@Nullable
private List<Effect> mVideoEffects = null;

/** 设置视频效果（运行时可调用，会同步到 mInternalPlayer）。 */
public void setVideoEffects(@Nullable List<Effect> effects) {
    mVideoEffects = effects;
    if (mInternalPlayer != null) {
        mInternalPlayer.setVideoEffects(effects != null ? effects : Collections.emptyList());
    }
}

/** 读取已设置的视频效果（供子类在 initPlayer 时使用）。 */
@Nullable
public List<Effect> getVideoEffects() {
    return mVideoEffects;
}
```

`initPlayer()` 的 Builder 链加 `.setVideoEffects(...)`：

```java
// 原：new ExoPlayer.Builder(...).build();
// 新：
ExoPlayer.Builder builder = new ExoPlayer.Builder(...);
if (mVideoEffects != null && !mVideoEffects.isEmpty()) {
    builder.setVideoEffects(mVideoEffects);
}
mInternalPlayer = builder.build();
```

### `CustomExoMediaPlayer.kt` 修改

```kotlin
companion object {
    private const val SP_KEY_LAB_MPV_SUPER_RESOLUTION = "labMpvSuperResolution"
    private const val SP_LAB_MPV_SUPER_RESOLUTION_DEFAULT = false
}

/**
 * 画质增强开关。开启时挂 AdaptiveSharpen GlEffect，关闭时清空。
 * 可在播放中调用。
 */
fun setSuperResolutionEnabled(enabled: Boolean) {
    val effects = if (enabled) listOf(AdaptiveSharpenEffect(strength = 1.0f)) else emptyList()
    setVideoEffects(effects)
}

/**
 * 初始化时按 SP 决定要不要默认挂上。保证切到下个视频/重启 App 后开关状态持续生效。
 */
private fun applySuperResolutionOnInit() {
    val sp = PreferenceManager.getDefaultSharedPreferences(appContext)
    val enabled = sp.getBoolean(SP_KEY_LAB_MPV_SUPER_RESOLUTION, SP_LAB_MPV_SUPER_RESOLUTION_DEFAULT)
    if (enabled) setSuperResolutionEnabled(true)
}
```

在 `CustomExoMediaPlayer.initPlayer()` 的 `super.initPlayer()` 之后调用 `applySuperResolutionOnInit()`。

## AdaptiveSharpenEffect + Program 实现

完整模板参考 Media3 自带的 `ColorLutShaderProgram.java`。结构：

```kotlin
@UnstableApi
class AdaptiveSharpenEffect(
    private val strength: Float = 1.0f  // curve_height，0.3~2.0 合理范围
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useDebug: Boolean): GlShaderProgram {
        return AdaptiveSharpenProgram(context, strength)
    }
}

@UnstableApi
private class AdaptiveSharpenProgram(
    context: Context,
    private val strength: Float
) : BaseGlShaderProgram(
    /* useHighPrecisionColorComponents */ false,
    /* texturePoolCapacity */ 1
) {
    private val glProgram: GlProgram = GlProgram(
        context,
        /* vertexShaderResId */ androidx.media3.effect.R.raw.vertex_shader_transformation_es2,
        /* fragmentShaderResId */ R.raw.fragment_shader_adaptive_sharpen_es2
    ).apply {
        // 标准模板：normalized coord bounds + identity matrices
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
        return Size(inputWidth, inputHeight)  // 不改变尺寸，只做锐化
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        glProgram.use()
        glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex */ 0)
        glProgram.setFloatUniform("uStrength", strength)
        glProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first */ 0, /* count */ 4)
    }

    override fun release() {
        super.release()
        glProgram.delete()
    }
}
```

### Fragment Shader（移植要点）

GLSL ES 2.0 兼容（Media3 1.9.0 默认走 ES2 路径）。完整代码包含：
- `uniform sampler2D uTexSampler`（Media3 标准）
- `uniform float uStrength`（运行时控制强度）
- `uniform vec2 uTexelSize`（用 `1.0/vec2(width, height)` 初始化，构造时设置）
- standard vertex shader 输出 `varying vec2 vTexCoord`（来自 `uTransformationMatrix * aFramePosition`）
- 算法主体：igv adaptive-sharpen 的 RGB 移植版（~150 行）

## 广播接收器改造

`LongVideoActivity` 和 `ShortVideoActivity` 的 `superResolutionReceiver` 把硬 cast 从 `MpvMediaPlayer` 改为 `CustomExoMediaPlayer`：

```kotlin
private val superResolutionReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
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
    }
}
```

Action 常量名 `ACTION_SUPER_RESOLUTION_ON/OFF` 保持不变。

## MPV 路线代码清理

按"复用开关位"决策：删除 MPV 端的所有 super-resolution 实现（保留 spike 报告文档）。

从 `MpvMediaPlayer.kt` 删除：
- `setSuperResolutionEnabled(enabled)`
- `applySuperResolutionOnInit()`
- `ensureShadersCopied(names)`
- `forceRerender()`
- `dumpSuperResDiagnostics()`
- `superResolutionShaderNames` 字段
- `SP_KEY_LAB_MPV_SUPER_RESOLUTION` / `SP_LAB_MPV_SUPER_RESOLUTION_DEFAULT` 常量
- `initialize()` 里 `applySuperResolutionOnInit()` 的调用点

从 `lib-player/player-mpv/src/main/assets/shaders/` 删除整个目录：
- `FSR.glsl`
- `CAS.glsl`

## 边界情况

| 场景 | 行为 |
|---|---|
| 切换开关时在 MPV 内核 | 广播送达但 cast 失败（`as? CustomExoMediaPlayer` 返回 null），忽略；SP 已写入，切到 Exo 后生效 |
| ExoPlayer 还在 prepare 中 | Media3 `setVideoEffects` 可在 prepare 前后调用。prepare 前调用最稳，运行时切换会触发 video output 重建（可能有短暂闪烁） |
| SurfaceView 模式（用户开了 `surfaceRender`） | Media3 GlEffect 在 SurfaceView 下正常工作 |
| TextureView 模式（默认） | GlEffect 也支持。截图通过 PixelCopy |
| 视频已是高分辨率 | Adaptive Sharpen 在源分辨率上跑锐化，可能略过锐——可在 UI 加警示文字或调小默认 strength |
| ExoPlayer release / recreate | `applySuperResolutionOnInit` 在 initPlayer 后调用，自动按 SP 状态决定 |
| 短视频 + 长视频 | 两个 Activity 都有 receiver，都会调 setSuperResolutionEnabled |
| HDR 视频 | BaseGlShaderProgram 用 `useHighPrecisionColor=false`，HDR 暂不支持（与 ColorLut 一致）。若需 HDR 后续单独处理 |
| 设备不支持 GLES 2.0 | minSdk=24 已保证支持（GLES2 需要 API 16+） |

## 测试与验证

### 手动验证

| 验证项 | 方法 |
|---|---|
| 视觉效果 | adb 触发 ON/OFF，**肉眼对比**（不再用 SSIM——染色教训：肉眼更可靠） |
| 持久化 | 开启→杀进程→重开→播放→确认 effects 仍应用 |
| 内核切换 | Exo ↔ MPV 切换不崩 |
| 短视频 + 长视频 | 都测一遍 |
| 性能 | 播放 1 分钟，观察发热、帧率（drop frame） |
| 备份/恢复 | 备份→卸载→安装→恢复→开关状态恢复 |

### 关键观察点

- 切换是否引起画面闪烁？（Media3 文档说运行时切换可能触发 video output 重建）
- TextureView 截图是否还工作？（PixelCopy 路径）
- 是否有 drop frame / 卡顿？（CPU/GPU 负担）

## 影响范围（修改文件清单）

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `gradle/libs.versions.toml` | 新增 | `media3-effect` 别名 |
| `lib-player/player-exo/build.gradle.kts` | 新增依赖 | `api(libs.media3.effect)` |
| `lib-player/player-exo/src/main/res/raw/fragment_shader_adaptive_sharpen_es2.glsl` | 新建 | Adaptive Sharpen fragment shader |
| `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/effect/AdaptiveSharpenEffect.kt` | 新建 | GlEffect + BaseGlShaderProgram 实现 |
| `lib-player/player-exo/src/main/java/xyz/doikki/videoplayer/exo/ExoMediaPlayer.java` | 修改 | 加 `setVideoEffects` 字段 + setter + Builder 链 |
| `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/CustomExoMediaPlayer.kt` | 修改 | 加 `setSuperResolutionEnabled` + `applySuperResolutionOnInit` + init 调用 |
| `lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt` | 删除 spike 残留 | 删 super-resolution 方法 + SP 常量 + assets/shaders 目录 |
| `lib-player/player-mpv/src/main/assets/shaders/` | 删除 | 整个目录（FSR.glsl + CAS.glsl） |
| `dy-player/src/main/res/values/strings.xml` | 修改文案 | "MPV 画质增强" → "画质增强"，副标题改 Exo |
| `dy-player/src/main/java/me/lingci/dy/player/ui/long_video/LongVideoActivity.kt` | 修改 receiver | cast 从 MpvMediaPlayer 改 CustomExoMediaPlayer |
| `dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt` | 同上 | 同上 |

## 工作量估计

约 **5-7 小时**：
- 0.5h：依赖 + 资源文件
- 1.5h：Fragment shader 移植（最大不确定性）
- 1h：AdaptiveSharpenEffect + Program 类
- 0.5h：ExoMediaPlayer + CustomExoMediaPlayer 接入
- 0.5h：广播接收器改造 + MPV 清理
- 0.5h：UI 文案
- 1-2h：真机验证 + shader 参数调优

## 后续可扩展（不在本次范围）

1. **强度可调**：未来加 SP 键 `labSuperResolutionStrength`，UI 加滑块
2. **HDR 支持**：未来把 `useHighPrecisionColor` 改为根据视频 HDR 标志动态决定
3. **多种 shader 可选**：未来加 dropdown 让用户选 adaptive-sharpen / unsharp-mask / CAS 等
4. **Media3 setVideoEffects 已知问题规避**：如果运行时切换太频繁触发 stall（androidx/media#2893），可以改为延迟生效（下次播放才应用）
