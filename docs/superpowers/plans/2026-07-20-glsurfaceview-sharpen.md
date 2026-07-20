# GLSurfaceView 路线 ExoPlayer 实时锐化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 ExoPlayer 加一个画质增强开关，开启后用自写的 GLSurfaceView + GLSL 锐化 shader 实时处理每一帧。

**Architecture:** 自写 `GlRenderView extends GLSurfaceView implements IRenderView`，内部持有 SurfaceTexture 给 ExoPlayer 写入；自写 `SharpenVideoRenderer implements GLSurfaceView.Renderer` 把 OES 纹理采样+锐化后画到屏幕。完全绕开 Media3 effect system。

**Tech Stack:** Kotlin · GLSurfaceView + OpenGL ES 2.0 · `samplerExternalOES` · Media3 1.9.0 ExoPlayer (`setVideoSurface(Surface(SurfaceTexture))`) · SharedPreferences via SPManager

## Global Constraints

- 不要硬编码依赖版本（用 `libs.*` catalog）
- SP 键名 `labMpvSuperResolution` **保留不变**（兼容备份文件）
- GLSL 必须 ES 2.0 + `#extension GL_OES_EGL_image_external : require`
- 中文文案，与现有 lab strings 风格一致
- 广播在 Android 14+ (TIRAMISU+) 需 `Context.RECEIVER_EXPORTED` flag
- 严格保留 MPV 路径完全不变（开关只在 ExoPlayer 实例生效）
- 不要 bump `targetSdk`（保持 28）
- 注释和用户可见字符串用简体中文
- **关键经验教训**（来自前面两次失败的 spike）：
  - Phase 1 必须先染色验证 pipeline 通不通（变红 = 通；不变 = 死）
  - `onFrameAvailable` 在 binder 线程，不能直接 `updateTexImage`，要 set AtomicBoolean + requestRender
  - `setRenderMode(RENDERMODE_WHEN_DIRTY)` 必须设
  - `updateTexImage` + `getTransformMatrix` 必须在 GL 线程的 `onDrawFrame` 里
  - `Surface(SurfaceTexture)` 给 player 必须切到主线程

**实施分支**：`feat/glsurfaceview-sharpen`（基于 `feat/exo-super-resolution` 切出）

---

## 实施策略：三阶段

来自前面两次失败的 spike 教训：染色测试证明"管线通" ≠ "算法有效"。为避免重蹈覆辙，本 plan 分三阶段：

| Phase | 目标 | 验证方法 |
|---|---|---|
| **Phase 1** | 用染色 shader 证明 GLSurfaceView pipeline 能跑通 | 开关 ON 后画面应变红 |
| **Phase 2** | 把染色换成 unsharp mask（3x3 简化锐化） | 肉眼对比清晰度 |
| **Phase 3** | 性能 + 截图功能 | 1 分钟播放观察发热 |

Phase 1 走不通就停下，跟用户讨论切到其他方案（前面已经失败过两次，第三次再失败说明这条路在 DyLike 现有架构下不可行）。

---

## 文件结构概览

### 新建
- `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/GlRenderView.kt` — 外层 GLSurfaceView 壳
- `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/GlRenderViewFactory.kt` — 工厂
- `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/SharpenVideoRenderer.kt` — GL Renderer 核心
- `lib-player/player-exo/src/main/assets/shaders/video_vertex_es2.glsl` — 顶点 shader（共用）
- `lib-player/player-exo/src/main/assets/shaders/tint_fragment_es2.glsl` — Phase 1 染色 fragment
- `lib-player/player-exo/src/main/assets/shaders/unsharp_fragment_es2.glsl` — Phase 2 锐化 fragment

### 修改
- `dy-player/src/main/java/me/lingci/dy/player/core/DyPlayerCoreRegistry.kt` — EXO 分支按开关选 GlRenderViewFactory
- `dy-player/src/main/java/me/lingci/dy/player/ui/long_video/LongVideoActivity.kt` — 接收器运行时切换
- `dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt` — 同上
- `dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt` — 更新 KDoc

### 删除（清理前面失败的 Media3 setVideoEffects 路线代码）
- `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/effect/SuperResolutionEffect.kt`
- `lib-player/player-exo/src/main/assets/shaders/fragment_shader_tint_es2.glsl`
- `lib-player/player-exo/src/main/java/xyz/doikki/videoplayer/exo/ExoMediaPlayer.java` 里的 `mVideoEffects` 字段 + setVideoEffects/getVideoEffects 方法 + initPlayer 里的 setVideoEffects 调用
- `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/CustomExoMediaPlayer.kt` 里的 `setSuperResolutionEnabled` + `applySuperResolutionOnInit` + companion SP 常量 + SuperResolutionEffect import + PreferenceManager import

---

## Task 1: 准备分支 + 清理 Media3 setVideoEffects 路线代码

**Files:**
- Modify: `lib-player/player-exo/src/main/java/xyz/doikki/videoplayer/exo/ExoMediaPlayer.java`
- Modify: `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/CustomExoMediaPlayer.kt`
- Delete: `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/effect/SuperResolutionEffect.kt`
- Delete: `lib-player/player-exo/src/main/assets/shaders/fragment_shader_tint_es2.glsl`
- Modify: `gradle/libs.versions.toml` (可选，移除 media3-effect 如果其他地方没用)

**Interfaces:**
- Consumes: `feat/exo-super-resolution` 分支
- Produces: 干净的 `feat/glsurfaceview-sharpen` 分支（无 Media3 effect 代码）

- [ ] **Step 1: 创建分支**

Run:
```bash
cd /c/Users/jiangyunfei/Desktop/DyLike
git checkout feat/exo-super-resolution
git status  # 应该干净
git checkout -b feat/glsurfaceview-sharpen
git branch --show-current
```
Expected: 输出 `feat/glsurfaceview-sharpen`

- [ ] **Step 2: 删除 SuperResolutionEffect.kt（含整个 effect 目录）**

Run:
```bash
rm -rf lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/effect/
ls lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/  # 确认 effect 目录已删
```
Expected: effect 目录已不存在

- [ ] **Step 3: 删除 tint shader 文件**

Run:
```bash
rm -f lib-player/player-exo/src/main/assets/shaders/fragment_shader_tint_es2.glsl
ls lib-player/player-exo/src/main/assets/shaders/ 2>&1
```
Expected: shaders 目录可能不存在或为空

- [ ] **Step 4: 清理 ExoMediaPlayer.java 的 setVideoEffects 代码**

打开 `lib-player/player-exo/src/main/java/xyz/doikki/videoplayer/exo/ExoMediaPlayer.java`，删除以下内容：

a) **删除 import**（如果加了）：
```java
import androidx.media3.common.Effect;
import java.util.Collections;
import java.util.List;
import androidx.annotation.Nullable;
```
（注意：如果其他地方用到这些 import 不要删，按需保留）

b) **删除字段**（约在 line 50-55）：
```java
@Nullable
private List<Effect> mVideoEffects = null;
```

c) **删除 setVideoEffects/getVideoEffects 方法**（约在 line 95-120），完整删除这两个方法。

d) **修改 initPlayer() 的 Builder 链**回到原始版本：
```java
// 原：ExoPlayer.Builder builder = new ExoPlayer.Builder(...);
//         if (mVideoEffects != null && !mVideoEffects.isEmpty()) { ... }
//         mInternalPlayer = builder.build();
// 改回：
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

- [ ] **Step 5: 清理 CustomExoMediaPlayer.kt**

打开 `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/CustomExoMediaPlayer.kt`，删除：

a) **删除 import**：
```kotlin
import androidx.media3.common.Effect
import android.preference.PreferenceManager
import me.lingci.lib.player.exo.effect.SuperResolutionEffect
```

b) **删除 companion object 里的 SP 常量**：
```kotlin
private const val SP_KEY_LAB_MPV_SUPER_RESOLUTION = "labMpvSuperResolution"
private const val SP_LAB_MPV_SUPER_RESOLUTION_DEFAULT = false
```

c) **删除 setSuperResolutionEnabled + applySuperResolutionOnInit 方法**

d) **修改 initPlayer() 删除 applySuperResolutionOnInit() 调用**，回到原始：
```kotlin
override fun initPlayer() {
    super.initPlayer()
    mInternalPlayer.addListener(  // 直接接 listener，不再调 applySuperResolutionOnInit
```

- [ ] **Step 6: 编译验证**

Run:
```bash
./gradlew :lib-player:player-exo:compileDebugKotlin :lib-player:player-exo:compileDebugJavaWithJavac 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL，没有 setVideoEffects/SuperResolution 相关未解析引用

- [ ] **Step 7: 验证残留清理**

Run:
```bash
grep -rn "SuperResolution\|setVideoEffects\|mVideoEffects\|videoeffects" lib-player/player-exo/src/main/
```
Expected: 无输出

- [ ] **Step 8: 提交**

Run:
```bash
git add -A
git commit -m "refactor(glsurfaceview-sharpen): remove failed Media3 setVideoEffects route

Previous route (Media3 setVideoEffects + GlEffect) failed due to
FinalShaderWrapper output surface missing in Media3 1.9.0. Removing:
  - SuperResolutionEffect.kt + effect/ package
  - fragment_shader_tint_es2.glsl
  - ExoMediaPlayer mVideoEffects field + setVideoEffects/getVideoEffects
  - CustomExoMediaPlayer setSuperResolutionEnabled + applySuperResolutionOnInit
  - SP constants in companion object

Pivoting to custom GLSurfaceView (next commits)."
```

---

## Task 2: 写 Phase 1 shaders

**Files:**
- Create: `lib-player/player-exo/src/main/assets/shaders/video_vertex_es2.glsl`
- Create: `lib-player/player-exo/src/main/assets/shaders/tint_fragment_es2.glsl`

**Interfaces:**
- Consumes: 无
- Produces: 被 SharpenVideoRenderer 在 Task 4 加载

- [ ] **Step 1: 确认 assets/shaders 目录存在**

Run:
```bash
mkdir -p lib-player/player-exo/src/main/assets/shaders
ls lib-player/player-exo/src/main/assets/shaders/ 2>&1
```
Expected: 目录存在（可能为空）

- [ ] **Step 2: 写 video_vertex_es2.glsl**

创建 `lib-player/player-exo/src/main/assets/shaders/video_vertex_es2.glsl`，内容（直接复制 Google demo 的 vertex shader）：

```glsl
attribute vec4 aFramePosition;
attribute vec4 aTexCoords;
uniform mat4 uTexTransform;
varying vec2 vTexCoords;
void main() {
 gl_Position = aFramePosition;
 vTexCoords = (uTexTransform * aTexCoords).xy;
}
```

- [ ] **Step 3: 写 tint_fragment_es2.glsl（Phase 1 染色）**

创建 `lib-player/player-exo/src/main/assets/shaders/tint_fragment_es2.glsl`：

```glsl
// Phase 1 验证 shader：把画面染红，证明 GLSurfaceView pipeline 能跑通。
// 验证通过后会被 unsharp_fragment_es2.glsl 替换。
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uVideoTex;
varying vec2 vTexCoords;
void main() {
    vec4 c = texture2D(uVideoTex, vTexCoords);
    // R 通道拉满，画面整体变红
    gl_FragColor = vec4(1.0, c.g, c.b, c.a);
}
```

- [ ] **Step 4: 提交**

Run:
```bash
git add lib-player/player-exo/src/main/assets/shaders/
git commit -m "feat(glsurfaceview-sharpen): add Phase 1 shaders (vertex + tint fragment)"
```

---

## Task 3: 写 SharpenVideoRenderer（核心 GL 逻辑）

**Files:**
- Create: `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/SharpenVideoRenderer.kt`

**Interfaces:**
- Consumes: shaders from Task 2、`androidx.media3.common.util.GlProgram`、`GlUtil`
- Produces: `SharpenVideoRenderer` 类（构造参数 `context` + `onSurfaceTextureReady` 回调），公开方法 `onVideoSizeChanged(w,h)`、`readFramebuffer()`、`release()`，实现 `GLSurfaceView.Renderer`

- [ ] **Step 1: 创建 render 包**

Run:
```bash
mkdir -p lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render
ls lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render
```

- [ ] **Step 2: 写 SharpenVideoRenderer.kt**

创建 `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/SharpenVideoRenderer.kt`：

```kotlin
package me.lingci.lib.player.exo.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import me.lingci.lib.base.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 锐化 GL Renderer。移植自 Google ExoPlayer demo VideoProcessingGLSurfaceView.VideoRenderer +
 * BitmapOverlayVideoProcessor。把 BitmapOverlay 部分换成我们的 tint/sharpen shader。
 *
 * 核心流程：
 * 1. onSurfaceCreated (GL 线程): 创建 OES external texture + SurfaceTexture，
 *    通过回调通知外层把 Surface(SurfaceTexture) 给 ExoPlayer
 * 2. ExoPlayer 解码后写入 SurfaceTexture，onFrameAvailable 在 binder 线程触发，
 *    set AtomicBoolean + requestRender
 * 3. onDrawFrame (GL 线程): updateTexImage + getTransformMatrix，跑 shader，画到屏幕
 *
 * @param onSurfaceTextureReady 在 onSurfaceCreated 完成后调，参数是就绪的 SurfaceTexture。
 *   回调内部负责切回主线程调用 player.setVideoSurface(Surface(st))。
 */
class SharpenVideoRenderer(
    private val context: Context,
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer {

    private val frameAvailable = AtomicBoolean(false)
    private val transformMatrix = FloatArray(16)
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var program: GlProgram? = null
    private var glSurfaceViewRef: WeakReference<GLSurfaceView>? = null

    /** 绑定外层 GLSurfaceView，用于在 onFrameAvailable 触发 requestRender。 */
    fun bindGlSurfaceView(view: GLSurfaceView) {
        glSurfaceViewRef = WeakReference(view)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            // 1. 创建 OES external texture
            textureId = GlUtil.createExternalTexture()
            // 2. 创建 SurfaceTexture
            surfaceTexture = SurfaceTexture(textureId).apply {
                setOnFrameAvailableListener { _ ->
                    // binder 线程：只设 flag + requestRender，不能 updateTexImage
                    frameAvailable.set(true)
                    glSurfaceViewRef?.get()?.requestRender()
                }
            }
            // 3. 通知外层（外层负责切回主线程把 Surface 给 ExoPlayer）
            onSurfaceTextureReady(surfaceTexture!!)
            // 4. 编译 shader（Phase 1 用 tint）
            program = GlProgram(
                context,
                /* vertexShaderFilePath */ "shaders/video_vertex_es2.glsl",
                /* fragmentShaderFilePath */ "shaders/tint_fragment_es2.glsl"
            ).apply {
                setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
                )
                setBufferAttribute(
                    "aTexCoords",
                    GlUtil.getTextureCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
                )
            }
            Log.d("SharpenVideoRenderer", "onSurfaceCreated OK, textureId=$textureId")
        } catch (e: Exception) {
            Log.e("SharpenVideoRenderer", "onSurfaceCreated failed: ${e.message}", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // 必须先 updateTexImage 才能采样到最新帧
        if (frameAvailable.compareAndSet(true, false)) {
            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(transformMatrix)
            } catch (e: Exception) {
                Log.e("SharpenVideoRenderer", "updateTexImage failed: ${e.message}")
            }
        }
        program?.let { p ->
            try {
                p.use()
                p.setSamplerTexIdUniform("uVideoTex", textureId, /* texUnitIndex */ 0)
                p.setFloatsUniform("uTexTransform", transformMatrix)
                p.bindAttributesAndUniforms()
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first */ 0, /* count */ 4)
            } catch (e: GlUtil.GlException) {
                Log.e("SharpenVideoRenderer", "draw failed: ${e.message}")
            }
        }
    }

    /** 视频尺寸变化时调用（用于将来算 uTexelSize）。 */
    fun onVideoSizeChanged(width: Int, height: Int) {
        // Phase 1 tint 不用，留接口给 Phase 2
    }

    /** 截图：glReadPixels 读 framebuffer（在 GL 线程调用）。 */
    fun readFramebuffer(): android.graphics.Bitmap? {
        // Phase 1 不实现，Phase 3 加
        return null
    }

    fun release() {
        try {
            surfaceTexture?.release()
            program?.delete()
        } catch (_: Exception) {}
    }
}
```

- [ ] **Step 3: 编译验证**

Run:
```bash
./gradlew :lib-player:player-exo:compileDebugKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

如果报错 `GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE` 找不到，可能需要查 Media3 1.9.0 的常量名（可能是 `GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE` 或 `4`）。

- [ ] **Step 4: 提交**

Run:
```bash
git add lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/SharpenVideoRenderer.kt
git commit -m "feat(glsurfaceview-sharpen): add SharpenVideoRenderer (GLSurfaceView.Renderer)

Ports Google ExoPlayer demo VideoProcessingGLSurfaceView.VideoRenderer
to Kotlin. Holds SurfaceTexture that ExoPlayer writes to, samples the
OES external texture via tint shader, draws to GLSurfaceView default
framebuffer. Phase 1 uses tint shader; Phase 2 will swap to unsharp."
```

---

## Task 4: 写 GlRenderView + Factory

**Files:**
- Create: `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/GlRenderView.kt`
- Create: `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/GlRenderViewFactory.kt`

**Interfaces:**
- Consumes: `SharpenVideoRenderer` (Task 3)、`IRenderView` (dkplayer-java)、`MeasureHelper`、`AbstractPlayer.setSurface`
- Produces: `GlRenderView : GLSurfaceView, IRenderView` + `GlRenderViewFactory : RenderViewFactory`

- [ ] **Step 1: 写 GlRenderView.kt**

创建 `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/GlRenderView.kt`：

```kotlin
package me.lingci.lib.player.exo.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Surface
import android.view.View
import android.opengl.GLSurfaceView
import androidx.annotation.Keep
import xyz.doikki.videoplayer.player.AbstractPlayer
import xyz.doikki.videoplayer.render.IRenderView
import xyz.doikki.videoplayer.render.MeasureHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * GLSurfaceView 渲染视图，支持自定义 GLSL shader 处理 ExoPlayer 解码的每一帧。
 *
 * 设计参考：
 * - `MpvSurfaceRenderView`：IRenderView 的 SurfaceView 子类模板
 * - Google ExoPlayer demo `VideoProcessingGLSurfaceView`：GLSurfaceView + Renderer 分离模式
 *
 * 工作流：
 * 1. attachToPlayer: 创建 SharpenVideoRenderer，setRenderer/setRenderMode
 * 2. SharpenVideoRenderer.onSurfaceCreated (GL 线程): 创建 SurfaceTexture
 * 3. 通过 onSurfaceTextureReady 回调（主线程）: player.setVideoSurface(Surface(st))
 * 4. ExoPlayer 解码帧写入 SurfaceTexture → onFrameAvailable → requestRender
 * 5. SharpenVideoRenderer.onDrawFrame (GL 线程): 跑 shader，画到屏幕
 *
 * 截图通过 queueEvent + glReadPixels 实现（Phase 3）。
 */
class GlRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), IRenderView {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val measureHelper = MeasureHelper()

    private var mediaPlayer: AbstractPlayer? = null
    private var videoRenderer: SharpenVideoRenderer? = null
    private var isReleased = false

    init {
        // GLSurfaceView EGL 配置（参考 Google demo）
        setEGLContextClientVersion(2)
        setEGLConfigChooser(
            /* redSize */ 8,
            /* greenSize */ 8,
            /* blueSize */ 8,
            /* alphaSize */ 8,
            /* depthSize */ 0,
            /* stencilSize */ 0
        )
        // 注意：不在这里 setRenderer，等 attachToPlayer 才设
    }

    override fun attachToPlayer(player: AbstractPlayer) {
        mediaPlayer = player
        videoRenderer = SharpenVideoRenderer(context) { surfaceTexture ->
            // 此回调在 GL 线程触发，切回主线程调 player.setVideoSurface
            mainHandler.post {
                if (!isReleased) {
                    try {
                        mediaPlayer?.setSurface(Surface(surfaceTexture))
                        Log.d("GlRenderView", "setSurface OK")
                    } catch (e: Exception) {
                        Log.e("GlRenderView", "setSurface failed: ${e.message}")
                    }
                }
            }
        }.also {
            it.bindGlSurfaceView(this)
        }
        setRenderer(videoRenderer)
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY)
    }

    override fun getView(): View = this

    override fun setVideoSize(videoWidth: Int, videoHeight: Int) {
        if (videoWidth > 0 && videoHeight > 0) {
            measureHelper.setVideoSize(videoWidth, videoHeight)
            videoRenderer?.onVideoSizeChanged(videoWidth, videoHeight)
            requestLayout()
        }
    }

    override fun setVideoRotation(degree: Int) {
        measureHelper.setVideoRotation(degree)
        invalidate()
    }

    override fun setScaleType(scaleType: Int) {
        measureHelper.setScreenScale(scaleType)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredSize = measureHelper.doMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredSize[0], measuredSize[1])
    }

    override fun doScreenShot(): Bitmap? {
        // glReadPixels 必须在 GL 线程跑
        val renderer = videoRenderer ?: return null
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        queueEvent {
            bitmap = renderer.readFramebuffer()
            latch.countDown()
        }
        try {
            latch.await(500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {}
        return bitmap
    }

    override fun release() {
        isReleased = true
        mainHandler.post {
            try {
                mediaPlayer?.setSurface(null)
            } catch (_: Exception) {}
        }
        mediaPlayer = null
        // GLSurfaceView 自己管 EGL 释放
        try {
            onPause()
        } catch (_: Exception) {}
        videoRenderer?.release()
        videoRenderer = null
    }

    // 兼容 Log（复用 lib-base 的 Log）
    private object Log {
        fun d(tag: String, msg: String) = me.lingci.lib.base.util.Log.d(tag, msg)
        fun e(tag: String, msg: String, t: Throwable? = null) = me.lingci.lib.base.util.Log.e(tag, msg, t)
    }
}
```

注意：上面的 `private object Log` 是简化版，实际可能用现成的 `me.lingci.lib.base.util.Log`。如果可以直接 import，删掉这个 object。

- [ ] **Step 2: 写 GlRenderViewFactory.kt**

创建 `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/GlRenderViewFactory.kt`：

```kotlin
package me.lingci.lib.player.exo.render

import android.content.Context
import xyz.doikki.videoplayer.render.IRenderView
import xyz.doikki.videoplayer.render.RenderViewFactory

/**
 * GlRenderView 的工厂。参考 TextureRenderViewFactory / MpvSurfaceRenderViewFactory 模式。
 */
class GlRenderViewFactory : RenderViewFactory() {

    override fun createRenderView(context: Context): IRenderView {
        return GlRenderView(context)
    }

    companion object {
        @JvmStatic
        fun create(): GlRenderViewFactory = GlRenderViewFactory()
    }
}
```

- [ ] **Step 3: 编译验证**

Run:
```bash
./gradlew :lib-player:player-exo:compileDebugKotlin 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

如果报错 import 路径或方法找不到，检查 dkplayer-java 的实际包名（`xyz.doikki.videoplayer.render.*`）。

- [ ] **Step 4: 提交**

Run:
```bash
git add lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/
git commit -m "feat(glsurfaceview-sharpen): add GlRenderView + Factory (IRenderView impl)

GlRenderView extends GLSurfaceView implements IRenderView. Mirrors
MpvSurfaceRenderView structure but holds its own SurfaceTexture for
ExoPlayer to write into. GlRenderViewFactory follows the standard
RenderViewFactory pattern."
```

---

## Task 5: DyPlayerCoreRegistry 接入 GlRenderViewFactory

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/core/DyPlayerCoreRegistry.kt`

**Interfaces:**
- Consumes: `GlRenderViewFactory` (Task 4)、`spUtil.labMpvSuperResolution`
- Produces: 开关 ON + EXO 内核 → 用 GlRenderViewFactory

- [ ] **Step 1: 读 DyPlayerCoreRegistry 当前代码**

Read `dy-player/src/main/java/me/lingci/dy/player/core/DyPlayerCoreRegistry.kt` 确认当前 applyCore 实现。

- [ ] **Step 2: 修改 EXO 分支**

在 EXO 分支里加 GlRenderView 选择逻辑：

```kotlin
DyPlayerCore.EXO -> {
    videoView.setPlayerFactory(CustomExoMediaPlayerFactory.create())
    // 开启画质增强时用 GlRenderView + 锐化 shader
    if (spUtil.labMpvSuperResolution) {
        videoView.setRenderViewFactory(GlRenderViewFactory.create())
    }
    // 关闭时不覆盖，沿用 applyConfiguredRenderFactory 设的默认 TextureView/SurfaceView
}
```

确保顶部 import 了 `me.lingci.lib.player.exo.render.GlRenderViewFactory` 和 `spUtil` 能访问（构造参数或 companion）。

- [ ] **Step 3: 编译验证**

Run:
```bash
./gradlew :dy-player:compileBetaDebugKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

Run:
```bash
git add dy-player/src/main/java/me/lingci/dy/player/core/DyPlayerCoreRegistry.kt
git commit -m "feat(glsurfaceview-sharpen): use GlRenderViewFactory when toggle ON in EXO core"
```

---

## Task 6: 改造广播接收器（运行时切换）

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/ui/long_video/LongVideoActivity.kt`
- Modify: `dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt`

**Interfaces:**
- Consumes: `videoView.release()` + `videoView.startPlay(...)` 现有的重播逻辑（如果不存在需要找替代）
- Produces: 收到 SUPER_RES_ON/OFF 时写 SP + 重播视频

- [ ] **Step 1: 看 LongVideoActivity 现有的"重播"逻辑**

Run:
```bash
grep -n "fun.*[Rr]eplay\|fun.*[Rr]etry\|fun startPlay\|videoView.startPlay\|videoView.release" dy-player/src/main/java/me/lingci/dy/player/ui/long_video/LongVideoActivity.kt | head -10
```

记录可用的"重新播放当前视频"方法名。

- [ ] **Step 2: 修改 LongVideoActivity 的 superResolutionReceiver**

找到 `superResolutionReceiver`（约 line 216-240），改成运行时切换：

```kotlin
private val superResolutionReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 现在切换 render view 必须重建 player，不能热切换
        val on = intent.action == ACTION_SUPER_RESOLUTION_ON
        if (spUtil.labMpvSuperResolution == on) return  // 状态没变
        spUtil.labMpvSuperResolution = on
        android.widget.Toast.makeText(
            this@LongVideoActivity,
            if (on) "画质增强：已开启（重播以生效）" else "画质增强：已关闭（重播以生效）",
            android.widget.Toast.LENGTH_SHORT
        ).show()
        // 重新播放当前视频以应用新 render view
        replayCurrentVideo()
    }
}
```

注意：`replayCurrentVideo()` 需要从 LongVideoActivity 已有的方法里选；如果没有，需要新增：

```kotlin
private fun replayCurrentVideo() {
    // 释放当前播放器（含 render view），然后用相同 URL 重新 startPlay
    val pos = videoView.currentPosition
    videoView.release()
    // 调用已有的 startPlay 路径（具体方法名按 Step 1 结果填）
    // 例：startPlay(currentVideoData, currentPosition = pos)
}
```

- [ ] **Step 3: ShortVideoActivity 同样改造**

同样修改 ShortVideoActivity 的 superResolutionReceiver，调用对应的重播方法。

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
git commit -m "feat(glsurfaceview-sharpen): receiver triggers player rebuild on toggle

GLSurfaceView can't hot-swap; toggling writes SP then replays the
current video to rebuild player with new render view factory."
```

---

## Task 7: 更新 SpUtil.kt 的 KDoc

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt`

- [ ] **Step 1: 找到 labMpvSuperResolution 的 KDoc**

Run:
```bash
grep -nB 8 "var labMpvSuperResolution" dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt
```

- [ ] **Step 2: 更新 KDoc**

把 KDoc 改为反映 GLSurfaceView 路线：

```kotlin
/**
 * 画质增强：开启后给 ExoPlayer 内核挂 GLSurfaceView 渲染管线 + 锐化 shader，
 * 实时锐化视频画面。仅 ExoPlayer 内核生效。
 *
 * 历史命名：曾经是 MPV FSR 试用键（labMpvSuperResolution），后续路线均废弃
 * （MPV shader hook 输出不生效；Media3 setVideoEffects 1.9.0 有 output surface bug），
 * 但 SP 键名保留以兼容备份文件和已开过该开关的用户。
 *
 * ⚠️ 跨模块约定：此 key 由 dy-player/SpUtil 定义，dy-player/DyPlayerCoreRegistry
 * 在 applyCore 时读取以决定是否用 GlRenderView。重命名属性会破坏 Exo 端读取。
 */
var labMpvSuperResolution by SPManager.boolean(false)
```

- [ ] **Step 3: 提交**

Run:
```bash
git add dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt
git commit -m "docs(glsurfaceview-sharpen): update labMpvSuperResolution KDoc to reflect GL route"
```

---

## Task 8: Phase 1 真机/模拟器验证（关键决策点）

**Phase 1 验证目标**：染色 shader 应让画面变红。这是 GLSurfaceView 路线成败的关键测试。

**如果 Phase 1 失败**：停下，跟用户讨论是否还有其他方案。前面已经失败过两次（MPV shader + Media3 setVideoEffects），第三次再失败说明 DyLike 现有架构下实时超分不可行。

- [ ] **Step 1: 完整构建 + 安装**

Run:
```bash
./gradlew :dy-player:assembleBetaDebug 2>&1 | tail -3
# 装到模拟器（如果模拟器还开着）
adb devices  # 确认 emulator-5554 在
adb -s emulator-5554 install -r dy-player/build/outputs/apk/beta/debug/dy-player-beta-universal-debug.apk
```

- [ ] **Step 2: 准备测试环境**

启动 App + 切换到 ExoPlayer 内核（默认就是 EXO）+ 播放测试视频。

模拟器命令：
```bash
adb -s emulator-5554 shell "am force-stop me.lingci.dy.player.debug"
# 确保 SP=false（默认）开始
adb -s emulator-5554 shell "am start -a android.intent.action.VIEW -d 'file:///sdcard/Movies/long_test.mp4' -t video/mp4 -n me.lingci.dy.player.debug/me.lingci.dy.player.ui.long_video.LongVideoActivity"
sleep 5
```

- [ ] **Step 3: 抓 baseline 截图**

Run:
```bash
adb -s emulator-5554 exec-out screencap -p > /tmp/baseline.png
```

- [ ] **Step 4: 发广播 ON + 等重播**

Run:
```bash
adb -s emulator-5554 shell "am broadcast -f 0x10 -a me.lingci.dy.player.SUPER_RES_ON"
sleep 5  # 等 replayCurrentVideo 完成 + 第一帧渲染
```

- [ ] **Step 5: 抓染色后截图**

Run:
```bash
adb -s emulator-5554 exec-out screencap -p > /tmp/tinted.png
```

- [ ] **Step 6: 分析 R 通道变化**

Run:
```bash
py -3 - <<'EOF'
import numpy as np
from PIL import Image
base = np.asarray(Image.open(r'C:\Users\jiangyunfei\AppData\Local\Temp\baseline.png').convert('RGB'), dtype=np.float32)
tint = np.asarray(Image.open(r'C:\Users\jiangyunfei\AppData\Local\Temp\tinted.png').convert('RGB'), dtype=np.float32)
print(f"baseline: R={base[...,0].mean():.1f} G={base[...,1].mean():.1f} B={base[...,2].mean():.1f}")
print(f"tinted:   R={tint[...,0].mean():.1f} G={tint[...,1].mean():.1f} B={tint[...,2].mean():.1f}")
dR = tint[...,0].mean() - base[...,0].mean()
dG = tint[...,1].mean() - base[...,1].mean()
print(f"diff: R={dR:+.1f}  G={dG:+.1f}")
if dR > 30 and dR - dG > 20:
    print("✅ Phase 1 PASSED: 画面变红了！GLSurfaceView pipeline 通！")
elif abs(dR) < 5:
    print("❌ Phase 1 FAILED: 画面没变红。pipeline 不通")
else:
    print(f"⚠️ 不确定（dR={dR:+.1f}），可能两帧画面内容差异大")
EOF
```

- [ ] **Step 7: 看 logcat 确认 pipeline 启动**

Run:
```bash
adb -s emulator-5554 logcat -d 2>&1 | grep -iE "SharpenVideoRenderer|GlRenderView|GlException|link shader|onSurfaceCreated" | tail -10
```

期望看到：
- `SharpenVideoRenderer: onSurfaceCreated OK, textureId=X`
- 没有 GlException 或 link shader 失败

- [ ] **Step 8: 用户肉眼确认**

让用户确认：开关打开后画面是否变红？

- **变红** → ✅ Phase 1 通过，进 Task 9（Phase 2 锐化）
- **不变** → 停下，看 logcat 找原因（可能 EGL 配置错、shader 编译失败、SurfaceTexture 没绑上）
- **崩溃** → 看崩溃栈

- [ ] **Step 9: 提交 Phase 1 结果**

无论成败，更新 spec 文档的"实施记录"小节，记录 Phase 1 结果。

```bash
# 在 docs/superpowers/specs/2026-07-20-glsurfaceview-sharpen-design.md 末尾追加：
# ## 实施记录
# ### Phase 1 染色测试
# - 时间：YYYY-MM-DD
# - 设备：emulator-5554 / 真机
# - 结果：[✅ 通过 / ❌ 失败]
# - 现象：xxx
git commit -am "spike(glsurfaceview-sharpen): Phase 1 tint verification result"
```

---

## Task 9: Phase 2 Unsharp Mask 锐化 shader

**前提**：Task 8 Step 8 用户确认画面变红。

**Files:**
- Create: `lib-player/player-exo/src/main/assets/shaders/unsharp_fragment_es2.glsl`
- Modify: `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/SharpenVideoRenderer.kt`

- [ ] **Step 1: 写 unsharp_fragment_es2.glsl**

创建 `lib-player/player-exo/src/main/assets/shaders/unsharp_fragment_es2.glsl`：

```glsl
// Phase 2 锐化 shader：Unsharp Mask（3x3 高斯模糊 + 减法）
// sharp = original + amount * (original - blur)
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uVideoTex;
uniform vec2 uTexelSize;       // vec2(1.0/videoWidth, 1.0/videoHeight)
uniform float uSharpenAmount;  // 0.0 off; 0.5-1.5 typical
varying vec2 vTexCoords;
void main() {
    vec2 tc = vTexCoords;
    vec3 c  = texture2D(uVideoTex, tc).rgb;
    vec3 l  = texture2D(uVideoTex, tc + vec2(-uTexelSize.x, 0.0)).rgb;
    vec3 r  = texture2D(uVideoTex, tc + vec2( uTexelSize.x, 0.0)).rgb;
    vec3 u  = texture2D(uVideoTex, tc + vec2(0.0, -uTexelSize.y)).rgb;
    vec3 d  = texture2D(uVideoTex, tc + vec2(0.0,  uTexelSize.y)).rgb;
    vec3 ul = texture2D(uVideoTex, tc + vec2(-uTexelSize.x, -uTexelSize.y)).rgb;
    vec3 ur = texture2D(uVideoTex, tc + vec2( uTexelSize.x, -uTexelSize.y)).rgb;
    vec3 dl = texture2D(uVideoTex, tc + vec2(-uTexelSize.x,  uTexelSize.y)).rgb;
    vec3 dr = texture2D(uVideoTex, tc + vec2( uTexelSize.x,  uTexelSize.y)).rgb;
    vec3 blurred = (4.0*c + 2.0*(l+r+u+d) + (ul+ur+dl+dr)) / 16.0;
    vec3 sharpened = c + uSharpenAmount * (c - blurred);
    gl_FragColor = vec4(clamp(sharpened, 0.0, 1.0), 1.0);
}
```

- [ ] **Step 2: SharpenVideoRenderer 切换 shader + 实现 onVideoSizeChanged**

修改 `SharpenVideoRenderer.kt`：

a) **改 shader 路径**（在 onSurfaceCreated 里）：
```kotlin
// 原：program = GlProgram(context, "...vertex...", "shaders/tint_fragment_es2.glsl")
// 改：
program = GlProgram(
    context,
    /* vertexShaderFilePath */ "shaders/video_vertex_es2.glsl",
    /* fragmentShaderFilePath */ "shaders/unsharp_fragment_es2.glsl"
).apply { ... }
```

b) **加 videoWidth/videoHeight 字段**：
```kotlin
private var videoWidth = 0
private var videoHeight = 0
```

c) **实现 onVideoSizeChanged**：
```kotlin
fun onVideoSizeChanged(width: Int, height: Int) {
    if (width > 0 && height > 0) {
        videoWidth = width
        videoHeight = height
    }
}
```

d) **在 onDrawFrame 设置 uTexelSize + uSharpenAmount**：
```kotlin
// 在 p.use() 之后，bindAttributesAndUniforms 之前
if (videoWidth > 0 && videoHeight > 0) {
    p.setFloatsUniform("uTexelSize", floatArrayOf(1f / videoWidth, 1f / videoHeight))
}
p.setFloatUniform("uSharpenAmount", 1.0f)  // 默认强度，可后续加 SP 调节
```

- [ ] **Step 3: 编译 + 真机/模拟器验证**

构建 + 安装 + 触发 ON + 肉眼判断画面是否变锐利（不再变红）。

- [ ] **Step 4: 提交**

```bash
git add lib-player/player-exo/src/main/assets/shaders/unsharp_fragment_es2.glsl lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/SharpenVideoRenderer.kt
git commit -m "feat(glsurfaceview-sharpen): Phase 2 - swap tint for unsharp mask sharpening"
```

---

## Task 10 (Optional): Phase 3 截图 + 性能

**前提**：Phase 2 锐化效果可见。

- [ ] **Step 1: 实现 readFramebuffer**

修改 SharpenVideoRenderer.readFramebuffer，用 GLES20.glReadPixels 读当前 framebuffer：

```kotlin
fun readFramebuffer(): Bitmap? {
    try {
        val w = width  // GLSurfaceView 宽度
        val h = height
        val buf = java.nio.IntBuffer.allocate(w * h)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        // GL 坐标系 Y 翻转
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(buf.array(), 0, w, 0, 0, w, h)
        // 翻转 Y
        val matrix = android.graphics.Matrix().apply { postScale(1f, -1f, w/2f, h/2f) }
        return Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true)
    } catch (e: Exception) {
        Log.e("SharpenVideoRenderer", "readFramebuffer failed: ${e.message}")
        return null
    }
}
```

- [ ] **Step 2: 性能观察**

播放 1 分钟观察：
- 发热
- 帧率
- drop frame

- [ ] **Step 3: 提交**

```bash
git commit -am "feat(glsurfaceview-sharpen): Phase 3 - screenshot via glReadPixels"
```

---

## Task 11: 完整验证 + 合并

**前提**：Phase 2 锐化效果可见。

- [ ] **Step 1: 冒烟测试**

请用户配合验证：
1. 持久化：开关开→杀进程→重开→播放→确认仍用 GlRenderView
2. 内核切换：Exo↔MPV 不崩
3. 短视频 + 长视频都测
4. 备份/恢复

- [ ] **Step 2: 跟用户讨论合并策略**

询问用户：
- 直接 merge 到 main？
- 创建 PR？
- 先在分支上测试？

- [ ] **Step 3: 按用户选择执行**

如选 merge：
```bash
git checkout main
git merge --no-ff feat/glsurfaceview-sharpen -m "Merge branch 'feat/glsurfaceview-sharpen'"
```

---

## Self-Review Checklist

实施完成后检查：

- [ ] **UI**：Lab 设置页有「画质增强」开关，副标题为 ExoPlayer 语义
- [ ] **SP 键**：`labMpvSuperResolution` 键名不变，KDoc 反映 GLSurfaceView 路线
- [ ] **GlRenderView**：`extends GLSurfaceView implements IRenderView`，正确实现 7 个方法
- [ ] **SharpenVideoRenderer**：实现 `GLSurfaceView.Renderer`，正确处理 SurfaceTexture 生命周期
- [ ] **Shader**：ES 2.0 + `GL_OES_EGL_image_external` 扩展，vertex/fragment 文件在 assets/shaders/
- [ ] **DyPlayerCoreRegistry**：EXO + 开关 ON → GlRenderViewFactory
- [ ] **广播**：ON/OFF → 写 SP + replayCurrentVideo
- [ ] **Media3 setVideoEffects 残留**：grep 无 SuperResolution/setVideoEffects/mVideoEffects
- [ ] **MPV 路径不受影响**：Exo↔MPV 切换不崩
- [ ] **Phase 1 验证**：染色测试通过（画面变红）
- [ ] **Phase 2 验证**：锐化效果肉眼可见
- [ ] **不 bump targetSdk**：保持 28
