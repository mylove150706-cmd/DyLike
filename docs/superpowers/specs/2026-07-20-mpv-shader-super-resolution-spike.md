# Spike 报告：MPV GLSL 着色器超分在 DyLike 的可行性

**日期**：2026-07-20（初版）/ 2026-07-19（v2 更新）/ 2026-07-20（v3 最终）
**分支**：`spike/super-resolution-anime4k` → `feat/mpv-super-resolution`
**状态**：⛔ **最终结论：不可行**（路线问题，非配置问题）
**目的**：验证用户提议的「开启开关后对低分辨率视频超分到 1080p/2K/4K」功能，是否能在当前 MPV Android 集成下用 GLSL user shader 实现。

## ⛔ 最终结论（v3）

**MPV Android 集成（abdallahmehiz/mpv-android-lib:0.1.12）下的 user shader 路线无法用于实时超分。**

这个结论经过了三轮验证：
- v1（2026-07-20 初版）：Anime4K 等 shader SSIM≈1.00 → 判定「不可行」
- v2（2026-07-19）：染色测试画面变黄 → 推翻 v1，判定「管线通了，可行」
- **v3（2026-07-20 最终）**：FSR EASU + CAS 都无效果 → 推翻 v2，**真正确认不可行**

## v3 关键证据（推翻 v2 的染色测试结论）

| Shader | Hook | 配置 | SSIM | 锐度比 | 结论 |
|---|---|---|---|---|---|
| FSR EASU+RCAS | LUMA | 默认 | 0.9956 | 1.002x | 无效 |
| FSR EASU+RCAS | LUMA | + video-zoom=1.0（2x 放大） | 0.5632（仅尺寸变） | 0.240x（仅测量副作用） | EASU 算法无效果 |
| **CAS** | LUMA | **SHARPENING=1.0（最大）** | **0.9989** | **0.984x** | **完全无效** |

所有测试都通过 `glsl-shaders` 属性确认 shader 已挂上。但画面无任何变化。

## v2 染色测试为什么误导

v2 用 `spike_red_tint.glsl`（把像素强制改成 `vec4(1,0,0,a)`）测试，画面变黄，于是判定「LUMA hook 生效」。

**这个判定错了。** 染色 shader 改的是**绝对像素值**——无论 hook 在什么分辨率执行、无论结果是否被后续管线覆盖，强制写入 `vec4(1,0,0)` 这种硬编码值总能保留下来。

而 FSR EASU / CAS 改的是**相对像素值**（基于邻域卷积的锐化/放大）。如果 hook 在源分辨率执行、然后 GPU 用 bilinear 把结果缩放到 SurfaceView 时**重新做了插值**，所有卷积效果都会被插值覆盖回原样。

也就是说：**LUMA/CHROMA hook 的代码确实被执行了，但 hook 输出没有被最终渲染管线采用**。GPU 的最后 bilinear blit 用源图像重新插值，覆盖了 hook 的所有卷积效果。染色（绝对值）能存活，锐化/放大（相对值）被覆盖。

## 技术原理分析

mpv 在 Android 上的渲染路径：

```
video frame (402x720)
   ↓
[hwdec 解码]
   ↓
[user shader hooks 在源分辨率执行] ← LUMA/CHROMA hook 在这里跑
   ↓
[hook 输出]                          ← 但这个输出没有被采用！
   ↓
[GPU bilinear blit 把源图像缩放到 SurfaceView (1256x2808)]
   ↑ 这里用的是源图像，不是 hook 输出
   ↓
[显示]
```

`context_android.c:104-122` 的 `android_reconfig()` 设置了 `vo->dwidth = surface_w`，但这个值只影响最后的 blit 目标，不影响 user shader 的执行分辨率或输出采用。

## 之前 v2 报告中关于「dwidth/dheight 误读」的判断仍然成立

v2 报告中关于 `command.c:4712 M_PROPERTY_ALIAS("dwidth", "video-out-params/dw")` 的发现是对的：客户端属性层的 dwidth/dhight 是「视频应该按多大显示」（与像素比相关），跟 VO 实际 framebuffer 尺寸无关。但这跟 shader 是否生效**没关系**——shader 是否生效取决于 hook 输出是否被采用，而本 AAR 的实现里 hook 输出被忽略了。

## 结论

**用户提议的「shader 实时超分」功能在当前 mpv Android 集成下无法实现。** 这不是配置问题，不是 shader 选择问题，是 AAR 的渲染管线设计问题——hook 输出没有被采用。

后续走 **ExoPlayer + Media3 GlEffect** 路线（自定义 OpenGL 后处理层，绕开 mpv 的 hook 机制）。

