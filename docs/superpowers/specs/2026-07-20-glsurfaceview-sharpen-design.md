# 设计文档：GLSurfaceView 路线 ExoPlayer 实时锐化

**日期**：2026-07-20
**分支**：`feat/glsurfaceview-sharpen`（从 `feat/exo-super-resolution` 切出，复用 SP 键和 UI 开关）
**状态**：✅ 设计已批准，待转实施计划

## 背景

用户希望「开启开关后对低分辨率视频进行实时锐化」。前面已经走过两条失败路线：

1. **MPV shader 路线**（Anime4K/FSR/CAS）—— 经 spike 证实 hook 输出不被最终渲染采用。详见 `docs/superpowers/specs/2026-07-20-mpv-shader-super-resolution-spike.md`
2. **Media3 setVideoEffects + GlEffect 路线** —— 1.9.0 的 effect pipeline 启动正常，但 `FinalShaderWrapper: Output surface and size not set, dropping frame`，所有帧被丢弃。Media3 effect pipeline 的 output surface 接入 API 在 1.9.0 上不完整。详见 `feat/exo-super-resolution` 分支的 spike commits

最终选定路线：**自己写 GLSurfaceView + EGL + SurfaceTexture**，参考 Google ExoPlayer demo `VideoProcessingGLSurfaceView`（release-v2 分支）。这条路完全绕开 Media3 的 effect 系统，由我们自己控制 GL pipeline。

## 目标

提供**单一开关**，开启后用 GLSL 锐化 shader 对 ExoPlayer 解码的每一帧做实时锐化。开关位置、范围、shader 选择、执行策略都已与用户确认。

## 非目标

- ❌ 不在 MPV 内核上做任何锐化尝试（已两次证实走不通）
- ❌ 不实现 1080p/2K/4K 分档（用 adaptive-sharpen 在源分辨率上做锐化）
- ❌ 不做"按源视频分辨率智能判断"——开关一开，所有 ExoPlayer 播放的视频都过 shader
- ❌ 不做离线神经超分

## 用户界面

**位置**：复用现有 `LabSettingsFragment` 的「画质增强」开关位（之前 `swLabMpvSuperResolution`，由前一分支已改为通用文案）。

| 文案 | 内容 |
|---|---|
| 标题 | 画质增强 |
| 副标题 | 使用锐化算法提升视频清晰度（仅 ExoPlayer 内核） |

**切换反馈**：切换时写 SP + 发广播 → Activity 重建 ExoPlayer + render view。用户会看到短暂中断（重 load 视频），这是必要的——切换 render view 必须重建 player。

## 数据层（SharedPreferences）

### SP 键名（沿用上一分支决策）

保留 SP 键 `labMpvSuperResolution`（历史名）不变，只更新 Kotlin 注释：

```kotlin
/**
 * 画质增强：开启后给 ExoPlayer 内核挂 GLSurfaceView 渲染管线 + 锐化 shader，
 * 实时锐化视频画面。仅 ExoPlayer 内核生效。
 *
 * 历史命名：曾经是 MPV FSR 试用键（labMpvSuperResolution），后续路线均废弃，
 * 但 SP 键名保留以兼容备份文件和已开过该开关的用户。
 *
 * ⚠️ 跨模块约定：此 key 由 dy-player/SpUtil 定义，dy-player/DyPlayerCoreRegistry
 * 在 applyCore 时读取以决定是否用 GlRenderView。重命名属性会破坏 Exo 端读取。
 */
var labMpvSuperResolution by SPManager.boolean(false)
```

## 总体架构

```
[Lab UI 开关] ──写 SP──→ [SpUtil.labMpvSuperResolution]
       │                            │
       │                            ↓ DyPlayerCoreRegistry.applyCore 读
       │              ┌─────────────────────────────────┐
       │              │  OFF: TextureRenderView (默认)    │
       │              │  ON:  GlRenderView (新)           │
       │              └─────────────────────────────────┘
       │                            │
       │                            ↓ render view 提供 Surface(SurfaceTexture) 给
       │              [ExoPlayer] ←─┘
       │                  ↓ 解码帧写入 SurfaceTexture (OES texture)
       │              [SharpenVideoRenderer.onDrawFrame]
       │                  ↓ 读 OES texture → 跑 shader → 画到屏幕
       └──发广播──→ [Activity] → (运行时切换：release → 切 render view → restart)
```

核心：自己写 `GlRenderView extends GLSurfaceView implements IRenderView`。它内部持有 SurfaceTexture 给 ExoPlayer 写入，GLSurfaceView 的 Renderer 把 OES 纹理采样+锐化后画到屏幕。

## GL pipeline 数据流

```
ExoPlayer 解码帧
     ↓
Surface(SurfaceTexture) ← GlRenderView 在 onSurfaceCreated 里创建
     ↓ SurfaceTexture 持有 GL_TEXTURE_EXTERNAL_OES 纹理
onFrameAvailable (binder 线程)
     ↓ set AtomicBoolean + requestRender()
onDrawFrame (GL 线程)
     ↓ updateTexImage() 把最新帧贴到 OES texture
     ↓ getTransformMatrix() 拿到本帧的变换矩阵
     ↓ 用 shader 采样 OES texture，应用 shader 算法
     ↓ glDrawArrays 画到 GLSurfaceView 默认 framebuffer
     ↓ 显示到屏幕
```

## 文件结构

### 新建

| 文件 | 责任 |
|---|---|
| `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/GlRenderView.kt` | `class GlRenderView : GLSurfaceView, IRenderView`（外层壳，参考 MpvSurfaceRenderView 结构） |
| `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/GlRenderViewFactory.kt` | `class GlRenderViewFactory : RenderViewFactory()` |
| `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/SharpenVideoRenderer.kt` | `class SharpenVideoRenderer : GLSurfaceView.Renderer`（核心 GL 逻辑） |
| `lib-player/player-exo/src/main/assets/shaders/video_vertex_es2.glsl` | 标准 vertex shader（参考 Google demo） |
| `lib-player/player-exo/src/main/assets/shaders/tint_fragment_es2.glsl` | Phase 1 染色 shader（验证 pipeline 用） |
| `lib-player/player-exo/src/main/assets/shaders/unsharp_fragment_es2.glsl` | Phase 2 锐化 shader（验证 pipeline 后启用） |

### 修改

| 文件 | 改动 |
|---|---|
| `dy-player/.../core/DyPlayerCoreRegistry.kt` | EXO 分支：开关 ON 时设 GlRenderViewFactory |
| `dy-player/.../ui/long_video/LongVideoActivity.kt` | 运行时切换（广播收到 ON/OFF 时 release + 重建 player） |
| `dy-player/.../ui/short_video/ShortVideoActivity.kt` | 同上 |
| `dy-player/.../util/SpUtil.kt` | 更新 labMpvSuperResolution KDoc（路线变更） |

### 不动

- `TextureRenderView` / `SurfaceRenderView` / `MpvSurfaceRenderView` 完全不变
- `ExoMediaPlayer.java` 完全不变（继续走 setVideoSurface(Surface)）
- MPV 路径完全不变

## GlRenderView 关键实现

基于 `MpvSurfaceRenderView`（SurfaceView 子类）+ Google `VideoProcessingGLSurfaceView`（GLSurfaceView + Renderer 分离）模式。

```kotlin
class GlRenderView(context: Context, attrs: AttributeSet? = null) :
    GLSurfaceView(context, attrs), IRenderView {

    private var mMediaPlayer: AbstractPlayer? = null
    private val mMeasureHelper = MeasureHelper()
    private var mVideoRenderer: SharpenVideoRenderer? = null
    private var mIsReleased = false

    init {
        // GLSurfaceView 配置
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)  // RGBA_8888
        // 注意：不调 setRenderMode/setRenderer，等 attachToPlayer 时再设
    }

    override fun attachToPlayer(player: AbstractPlayer) {
        mMediaPlayer = player
        mVideoRenderer = SharpenVideoRenderer(context) { surfaceTexture ->
            // SharpenVideoRenderer 在 onSurfaceCreated 创建 SurfaceTexture 后回调主线程
            // 主线程把 Surface(SurfaceTexture) 给 ExoPlayer
            post {
                if (!mIsReleased) {
                    mMediaPlayer?.setSurface(Surface(surfaceTexture))
                }
            }
        }
        setRenderer(mVideoRenderer)
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY)
    }

    override fun getView(): View = this

    override fun setVideoSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            mMeasureHelper.setVideoSize(width, height)
            mVideoRenderer?.onVideoSizeChanged(width, height)
            requestLayout()
        }
    }

    override fun setVideoRotation(degree: Int) {
        mMeasureHelper.setVideoRotation(degree)
        invalidate()
    }

    override fun setScaleType(scaleType: Int) {
        mMeasureHelper.setScreenScale(scaleType)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measured = mMeasureHelper.doMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measured[0], measured[1])
    }

    override fun doScreenShot(): Bitmap? {
        // glReadPixels 必须在 GL 线程跑
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        queueEvent {
            bitmap = mVideoRenderer?.readFramebuffer()
            latch.countDown()
        }
        latch.await(500, TimeUnit.MILLISECONDS)
        return bitmap
    }

    override fun release() {
        mIsReleased = true
        mMediaPlayer?.setSurface(null)
        mMediaPlayer = null
        // GLSurfaceView 的 EGL 由自己管，onPause 会触发释放
        onPause()  // 触发 GL context 释放
        mVideoRenderer?.release()
        mVideoRenderer = null
    }
}
```

## SharpenVideoRenderer 关键实现

移植 Google demo `VideoProcessingGLSurfaceView.VideoProcessor`，把 BitmapOverlay 换成锐化 shader。

```kotlin
class SharpenVideoRenderer(
    private val context: Context,
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer {

    private val mFrameAvailable = AtomicBoolean(false)
    private var mTextureId = 0
    private var mSurfaceTexture: SurfaceTexture? = null
    private var mTransformMatrix = FloatArray(16)
    private var mProgram: GlProgram? = null
    private var mVideoWidth = 0
    private var mVideoHeight = 0
    private var mGlRenderViewRef: WeakReference<GLSurfaceView>? = null  // for requestRender

    fun bindGlSurfaceView(view: GLSurfaceView) {
        mGlRenderViewRef = WeakReference(view)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            // 1. 创建 OES external texture
            mTextureId = GlUtil.createExternalTexture()
            // 2. 创建 SurfaceTexture
            mSurfaceTexture = SurfaceTexture(mTextureId).apply {
                setOnFrameAvailableListener { _ ->
                    mFrameAvailable.set(true)
                    // 在 binder 线程触发 GL 重绘
                    mGlRenderViewRef?.get()?.requestRender()
                }
            }
            // 3. 通知主线程：SurfaceTexture 已就绪
            onSurfaceTextureReady(this)
            // 4. 编译 shader（Phase 1 用 tint）
            mProgram = GlProgram(
                context,
                "shaders/video_vertex_es2.glsl",
                "shaders/tint_fragment_es2.glsl"
            ).apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            }
        } catch (e: Exception) {
            L.e("SharpenVideoRenderer onSurfaceCreated failed: ${e.message}")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (mFrameAvailable.compareAndSet(true, false)) {
            try {
                mSurfaceTexture?.updateTexImage()
                mSurfaceTexture?.getTransformMatrix(mTransformMatrix)
            } catch (_: Exception) {}
        }
        mProgram?.let { p ->
            try {
                p.use()
                p.setSamplerTexIdUniform("uVideoTex", mTextureId, 0)
                p.setFloatsUniform("uTexTransform", mTransformMatrix)
                p.bindAttributesAndUniforms()
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: Exception) {
                L.e("SharpenVideoRenderer onDrawFrame failed: ${e.message}")
            }
        }
    }

    fun onVideoSizeChanged(width: Int, height: Int) {
        mVideoWidth = width
        mVideoHeight = height
    }

    /** glReadPixels 读 framebuffer 截图（在 GL 线程调用） */
    fun readFramebuffer(): Bitmap? {
        // 实现略，参考 Media3 GlUtil.readBufferToBitmap
        return null  // Phase 1 实现
    }

    fun release() {
        try {
            mSurfaceTexture?.release()
            mProgram?.delete()
        } catch (_: Exception) {}
    }
}
```

## Shaders

### Vertex shader（所有 Phase 共用，标准模板）

```glsl
// shaders/video_vertex_es2.glsl
attribute vec4 aFramePosition;
attribute vec4 aTexCoords;
uniform mat4 uTexTransform;  // = SurfaceTexture.getTransformMatrix()
varying vec2 vTexCoords;
void main() {
    gl_Position = aFramePosition;
    vTexCoords = (uTexTransform * aTexCoords).xy;
}
```

### Phase 1 tint fragment（验证 pipeline）

```glsl
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uVideoTex;
varying vec2 vTexCoords;
void main() {
    vec4 c = texture2D(uVideoTex, vTexCoords);
    gl_FragColor = vec4(1.0, c.g, c.b, c.a);  // R 拉满，画面变红
}
```

### Phase 2 unsharp mask fragment（锐化）

```glsl
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uVideoTex;
uniform vec2 uTexelSize;
uniform float uSharpenAmount;
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

## DyPlayerCoreRegistry 接入

修改 `applyCore`：

```kotlin
DyPlayerCore.EXO -> {
    videoView.setPlayerFactory(CustomExoMediaPlayerFactory.create())
    // 开启画质增强时用 GlRenderView，否则保留 applyConfiguredRenderFactory 设的默认
    if (spUtil.labMpvSuperResolution) {
        videoView.setRenderViewFactory(GlRenderViewFactory.create())
    }
}
```

## 运行时切换（广播）

收到 ON/OFF 广播时，因为切换 render view 必须重建 player，不能像 MPV 那样热切换：

```kotlin
// LongVideoActivity.superResolutionReceiver 收到 ON/OFF 时
ACTION_SUPER_RESOLUTION_ON, ACTION_SUPER_RESOLUTION_OFF -> {
    val player = videoView.getCurrentPlayer() as? CustomExoMediaPlayer ?: return
    val on = intent.action == ACTION_SUPER_RESOLUTION_ON
    if (spUtil.labMpvSuperResolution == on) return  // 状态没变，跳过
    spUtil.labMpvSuperResolution = on
    Toast.makeText(...).show()
    // 重建 player（BaseVideoView.setRenderViewFactory 后会自动用新 factory）
    // 调用现有的内核切换/replay 逻辑
    replayCurrentVideo()
}
```

注意：`replayCurrentVideo()` 需要从 LongVideoActivity 已有的"重播"逻辑里调用（比如 `startPlay` 或 `retry`）。如果不存在，需要新增。

## 边界情况

| 场景 | 行为 |
|---|---|
| 开关切换时 ExoPlayer 在播放 | release + 重建（短暂闪屏 + 重 load） |
| ExoPlayer ↔ MPV 切换 | render view 也跟着换（DyPlayerCoreRegistry 已处理） |
| Activity 进入后台 onPause | GLSurfaceView 自动销毁 EGL；回来时 onSurfaceCreated 重新跑，SurfaceTexture 重建，重新挂给 player |
| 用户在 MPV 内核时开开关 | SP 已写但 GlRenderView 只在 EXO 路径用，等切到 EXO 生效 |
| 视频本身是高分辨率 | Phase 2 unsharp mask 在源分辨率上跑，无负面影响；过锐时调 uSharpenAmount |
| GLSurfaceView 没创建就 release | isReleased 守卫拦截，安全 |
| ExoPlayer release 时 GlRenderView 还活着 | GlRenderView.release() 自己管 EGL 释放，互不影响 |

## 测试与验证

### Phase 1 染色验证（必做）

adb 触发 SUPER_RES_ON → replayCurrentVideo → 等画面重新播放 → 肉眼判断：
- 画面**变红** = ✅ pipeline 通，进 Phase 2
- 画面**没变** = ❌ pipeline 不通，停下排查
- App 崩溃 = shader 编译错误，看 stack trace

### Phase 2 锐化验证（Phase 1 通过后）

换 unsharp mask shader，肉眼判断：
- 明显更锐利 = ✅
- 没变化 = shader 没真生效，排查
- 反而模糊 = uSharpenAmount 符号反了

### Phase 3 性能验证（可选）

播放 1 分钟观察：
- 发热（30+°C）
- 帧率（应保持 30/60 fps）
- drop frame 数

### 持久化测试

1. 开启开关 → 杀进程 → 重开 → 确认仍用 GlRenderView
2. 内核切换 → 不崩
3. 备份/恢复 → SP 状态保持

## 工作量估计

约 **1-1.5 天**：
- 0.5d：GlRenderView + SharpenVideoRenderer + shaders + Factory
- 0.25d：DyPlayerCoreRegistry 接入 + Activity 运行时切换（replayCurrentVideo）
- 0.25d：真机/模拟器验证 + 调 shader
- 0.25d：截图功能 + 边界情况处理

## 后续可扩展（不在本次范围）

1. **多 shader 预设**：未来加 dropdown 选 unsharp / adaptive-sharpen / CAS 等
2. **强度可调**：UI 加滑块控制 uSharpenAmount
3. **HDR 支持**：现在用 RGBA_8888（8bit），HDR 视频会被降级；未来可加 RGBA_F16 配置
4. **性能优化**：3x3 → separable 2-pass，减少 GPU 带宽
5. **MPV 路线**：如果未来 MPV 改用 GLSurfaceView 后端，可以复用 SharpenVideoRenderer
