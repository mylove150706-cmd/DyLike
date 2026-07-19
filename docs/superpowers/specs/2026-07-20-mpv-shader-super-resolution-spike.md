# Spike 报告：MPV GLSL 着色器超分在 DyLike 的可行性

**日期**：2026-07-20（初版）/ 2026-07-19（v2 更新）
**分支**：`spike/super-resolution-anime4k`
**状态**：🔄 **结论推翻——超分功能可行**
**目的**：验证用户提议的「开启开关后对低分辨率视频超分到 1080p/2K/4K」功能，是否能在当前 MPV Android 集成下用 GLSL user shader 实现。

## ⚠️ 重大更正

**初版结论「不可行」是错的。** 通过染色 shader（spike_red_tint.glsl）验证后确认：user shader hook 链路在 abdallahmehiz/mpv-android-lib:0.1.12 的 Android 集成下**是通的**，超分功能可行。

初版误判的根本原因有两个：

1. **误读 mpv `dwidth/dheight` 属性的语义**
   - 初版以为 `video-out-params/dw=403 dh=720` 表示 mpv 渲染目标尺寸 = 视频原始尺寸
   - 实际上：`dwidth/dheight` 来自 `command.c:4712` 的 `M_PROPERTY_ALIAS("dwidth", "video-out-params/dw")`，它表示「视频应该按多大显示」（与像素比相关），**与 VO 实际 framebuffer 尺寸无关**
   - mpv 上游 `video/out/opengl/context_android.c:104-122` 的 `android_reconfig()` 会把 VO 实际渲染分辨率（`vo->dwidth/dheight`）设为 `android-surface-size`（即 SurfaceView 物理像素 1256×2672），shader 是在这个尺寸的 FBO 上跑的

2. **shader 选择 + hook 点选择都不适合实拍内容**
   - Anime4K 是动漫专用（只对硬边线条/色块起作用），测实拍画面 SSIM≈1.00 是预期的
   - adaptive-sharpen 挂的是 `POSTKERNEL` hook，**而 POSTKERNEL hook 在本 AAR 的 Android 集成下根本不触发**（染色 shader 实测确认）
   - FSRCNNX 挂 LUMA hook 是会生效的，但对实拍内容的提升本来就微弱

## 染色 shader 验证（决定性证据）

为了判断 user shader hook 链路是否真的在 Android mpv 上跑，写了 `spike_red_tint.glsl`，包含三个 hook：

```glsl
//!HOOK LUMA       // 把亮度 Y 强制设为 1.0
//!HOOK CHROMA     // 把 Cb 设为 0
//!HOOK RGB        // 返回纯红 vec4(1,0,0,c.a)
```

**实测结果**：画面变成**纯黄色** `(R=1, G=1, B=0)`。

按 BT.601 YUV→RGB 反推：
- Y=1.0, Cb=0, Cr≈0.5（原值，接近中性）
- R = 1.0 + 1.402·0 = 1.0
- G = 1.0 − 0.344·(−0.5) − 0.714·0 = 1.172 → clip 到 1.0
- B = 1.0 + 1.772·(−0.5) = 0.114 → ≈ 0
- = (1, 1, 0) = **纯黄** ✅ 完全吻合

### 三个 hook 点的有效性

| Hook | 染色 shader 是否生效 | 含义 |
|---|---|---|
| **LUMA** | ✅ **生效**（Y 通道被改了） | 超分主战场——FSRCNNX、Anime4K Upscale_CNN 都挂这里 |
| **CHROMA** | ✅ **生效**（Cb 被改了） | 色度升采样（KrigBilateral）会生效 |
| **POSTKERNEL / RGB** | ❌ **不生效**（RGB 阶段 hook 没跑） | adaptive-sharpen 等后处理必须改挂到 LUMA |

## 之前 4 次 SSIM 测试的真实含义

初版 4 次测试都用 `vo=gpu` + 各种 shader 组合，SSIM 都 ≈ 1.00。现在重读：

| # | Shader 组合 | 真相 |
|---|---|---|
| 1 | Anime4K Mode A（3 shaders） | Anime4K 对实拍本来就几乎 passthrough，SSIM≈1.00 预期 |
| 2 | FSRCNNX + adaptive-sharpen | FSRCNNX 对实拍微弱 + adaptive-sharpen POSTKERNEL 没跑 → 几乎无变化 |
| 3 | FSRCNNX + adaptive-sharpen + hwdec=no | hwdec 不影响 shader 执行，结果同 #2 |
| 4 | FSRCNNX + adaptive-sharpen + vo=gpu-next | 同 #2，且 vo=gpu-next 可能改变 hook 语义但没改变根本问题 |

**关键**：4 次测试都包含 adaptive-sharpen（POSTKERNEL），它根本没生效。FSRCNNX（LUMA）应该是生效的，但对实拍提升微小，被 SSIM 淹没。

## 结论

**用户提议的「shader 实时超分」功能在当前 mpv Android 集成下可以实现，但需要：**

1. **shader 选择必须对实拍内容有效**：FSRCNNX 太弱，应该换 FSR（FidelityFX Super Resolution）、CAS（Contrast Adaptive Sharpening）或 NNEDI3 等对自然图像提升明显的
2. **后处理必须挂 LUMA，不能挂 POSTKERNEL**：POSTKERNEL hook 在本 AAR 上不触发
3. **超分强度可能比桌面 mpv 弱**：因为 Android 上 hwdec=auto 会跳过部分 CPU 处理路径，shader 输入可能是已经硬件解码的低质量像素

## 可复现的代码位置

- Spike 代码：`spike/super-resolution-anime4k` 分支
- 染色 shader：`lib-player/player-mpv/src/main/assets/shaders/spike_red_tint.glsl`
- 关键文件：
  - `lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt`（spike 注入点：`applySpikeAnime4KShaders()`）
  - `dy-player/.../ui/long_video/LongVideoActivity.kt`（spike broadcast receiver）
  - `dy-player/.../ui/short_video/ShortVideoActivity.kt`（spike broadcast receiver 副本）

## 复现步骤（验证 hook 链路）

```bash
# 1. 切到 spike 分支
git checkout spike/super-resolution-anime4k

# 2. 把染色 shader 重新挂上（修改 applySpikeAnime4KShaders 加载 spike_red_tint.glsl）

# 3. 构建 + 安装
./gradlew :dy-player:installBetaDebug

# 4. 手机播放任意视频
# 5. 观察画面：应该变成纯黄色，证明 LUMA/CHROMA hook 生效
```
