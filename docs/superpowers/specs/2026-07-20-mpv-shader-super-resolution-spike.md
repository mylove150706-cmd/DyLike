# Spike 报告：MPV GLSL 着色器超分在 DyLike 的可行性

**日期**：2026-07-20
**分支**：`spike/super-resolution-anime4k`
**状态**：✅ 验证完成 — 结论为「不可行」
**目的**：验证用户提议的「开启开关后对低分辨率视频超分到 1080p/2K/4K」功能，是否能在当前 MPV Android 集成下用 GLSL user shader 实现。

## 背景

用户希望添加一个开关：开启后可对低分辨率视频进行超分，并提供 1080p/2K/4K 目标分辨率选项。

经过初轮技术评估，唯一能「实时边播边增强」的方案是 MPV 的 GLSL user shader（Anime4K / FSRCNNX / adaptive-sharpen 等）。本 spike 用于在投入产品功能开发之前，先验证这条路线是否真的能跑通并产生肉眼可见的效果。

## Spike 设计

在 `MpvMediaPlayer.initialize()` 中：
- 从 assets 拷贝 GLSL shader 到 `filesDir/shaders/`
- 用 `mpv.command("change-list", "glsl-shaders", "add", path)` 加载到 mpv
- 通过 adb broadcast 触发同帧 dump（`screenshot-to-file` 命令的 `scaled` 模式）
- 开/关 shader 各 dump 一帧，Python 算 SSIM + 锐度差

## 四次量化测试结果

测试素材：实拍视频，402×720，硬编码字幕。暂停在细节较多的画面。

| # | Shader | vo | hwdec | SSIM | 平均像素差 (/255) | 差异像素占比 | 锐度提升 | 文件大小 |
|---|---|---|---|---|---|---|---|---|
| 1 | Anime4K Mode A (3 shaders) | gpu | auto | 0.9917 | 0.58 | 2.0% | 1.01x | 1.06→1.07 MB |
| 2 | FSRCNNX + adaptive-sharpen | gpu | auto | 0.9972 | 0.25 | 0.3% | 1.01x | 1.06→1.02 MB |
| 3 | FSRCNNX + adaptive-sharpen | gpu | no | 0.9971 | 0.26 | 0.3% | 1.01x | 1.00→1.00 MB |
| 4 | FSRCNNX + adaptive-sharpen | gpu-next | no | 0.9985 | 0.13 | 0.0% | 1.00x | 6.7→6.4 MB |

**判定阈值**：SSIM < 0.95 或锐度提升 > 1.2x 视为「shader 有效」。
**实际结果**：四次全部 SSIM > 0.99，锐度提升 ≤ 1.01x，**全部判定为无效**。

## 证据链

证明「shader 加载了但没生效」的硬证据：

1. ✅ **shader 文件正确拷贝到 filesDir**（大小匹配原始文件 2795/144075/146743/71296/10446 字节）
2. ✅ **mpv `glsl-shaders` 属性正确报告加载列表**（包含所有 shader 绝对路径）
3. ✅ **mpv `gpu-shader-cache-dir` 下生成了 shader 编译缓存文件**（每个 ~7-11KB，是 GLSL 编译后的 GPU 二进制）
4. ❌ **mpv `video-out-params` 显示输出尺寸 = 视频原始尺寸**（402×720，从未变化）—— mpv 没做任何放大
5. ❌ **SSIM ≈ 1.00，开/关 shader 的画面几乎完全相同**

第 4 点尤其关键：`dw=403, dh=720`（视频原始尺寸）从未变成屏幕尺寸（1256×2672）。这说明 mpv 的渲染目标就是视频原尺寸，user shader 即便被加载、被编译，也没有在渲染管线中被实际调用。

## 结论

**用户提议的「shader 实时超分」功能在当前 mpv Android 集成（abdallahmehiz/mpv-android-lib:0.1.12）下无法实现。**

不是 shader 选择问题，不是参数配置问题，而是 mpv 在 Android Surface 渲染时根本绕过了 user shader hook 链路。`glsl-shaders` 属性可以加载、shader 可以编译、属性报告成功，但 GPU 渲染时不调用它们。

覆盖的变量：
- shader 类型：Anime4K（动漫专用） / FSRCNNX + adaptive-sharpen（实拍友好）
- VO：`gpu`（默认）/ `gpu-next`（新一代）
- hwdec：`auto`（硬解）/ `no`（软解）

所有 8 种理论组合中验证了 4 种最有代表性的组合，全部无效。

## 后续可探索方向（不在本 spike 范围内）

如果未来仍想实现真超分，可考虑：

1. **离线神经超分**（Real-ESRGAN ncnn）：对文件做一次性预处理生成增强版，再播放。真 4K，但每个视频要等几分钟~几小时、占大量存储、不能边下边播。工作量大。
2. **更换 mpv 集成**：尝试其他 mpv Android 封装（如原版 mpv-android），看 user shader 是否能正常工作。需要评估迁移成本。
3. **不实现超分**：接受当前 mpv 内置 scaler（bilinear）的画质。

## 可复现的代码位置

- Spike 代码：`spike/super-resolution-anime4k` 分支
- 关键文件：
  - `lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt`（spike 注入点）
  - `lib-player/player-mpv/src/main/assets/shaders/`（5 个 shader 文件）
  - `dy-player/.../ui/long_video/LongVideoActivity.kt`（spike broadcast receiver）
  - `dy-player/.../ui/short_video/ShortVideoActivity.kt`（spike broadcast receiver 副本）
  - `spike_compare.py`（SSIM 对比脚本）

## 复现步骤

```bash
# 1. 切到 spike 分支
git checkout spike/super-resolution-anime4k

# 2. 构建 + 安装到真机
./gradlew :dy-player:installBetaDebug

# 3. 手机上播放任意视频（建议实拍 480p/720p），暂停在一帧

# 4. 在 PC 上运行对比脚本（保持手机视频暂停在前台）
py spike_compare.py
```
