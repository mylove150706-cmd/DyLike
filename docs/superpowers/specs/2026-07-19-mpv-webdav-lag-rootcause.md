# WebDav MPV 播放卡顿根因分析

- 日期: 2026-07-19
- 状态: 调研完成 — 问题在文件本身,不在 app
- 关联: docs/superpowers/specs/2026-07-19-mpv-webdav-cache-toggle-design.md（该方案验证失败,代码已撤销）

## 1. 现象

通过 MPV 内核播放特定 WebDav 视频,拖动进度条后出现「播放几秒→卡几秒→播放几秒→卡几秒」循环,无法正常观看。同一文件用 Exo 内核播放流畅。**仅一个 WebDav 视频有此现象,其他 WebDav 视频正常。**

## 2. 调研过程

### 已排除的非根因

- ❌ **seekbar UI 风暴**：每次手势只触发 1 次 seek。
- ❌ **Auth 头丢失**：Authorization 正确通过 `http-header-fields` 传给 MPV。
- ❌ **302 重定向**：长视频已解析仍卡（短视频才有这个问题）。
- ❌ **网络/服务器问题**：同一个 WebDav 服务器,Exo 流畅 → 网络层 OK。
- ❌ **MPV cache 配置不足**：尝试了 `cache=yes`、`cache-secs=30`、`demuxer-readahead-secs=5`、`demuxer-seekable-cache=yes`、`network-timeout=60`,**完全无效**。

### 关键证据：MPV debug log

通过临时启用 MPV 的 `log-file` 选项（绕过荣耀 HKS 日志加密）抓到详细日志：

```
[   3.528][v][ffmpeg] stream level seek from 8568 to 415183456      ← 启动跳到末尾读 moov
[   6.346][v][demux] Detected file format: mov,mp4,m4a,3gp,3g2,mj2  ← MP4 文件
[   7.133][v][lavf] cached range 0: -0.023220 <-> 14.966667         ← cache 只填到 15 秒
[   7.294][v][ffmpeg] stream level seek from 81441151 to 232675668  ← 开始 seek 风暴
[   7.781][v][ffmpeg] stream level seek from 81430150 to 232744609
[   7.800][v][ffmpeg] stream level seek from 232752885 to 81430143
...
```

**统计**：150 秒内出现 **3697 次 stream level seek**（平均每秒 25+ 次）,seek 位置在两个相隔 ~150MB 的字节地址之间反复跳动（81MB ↔ 232MB）。

## 3. 真正根因

**这个 MP4 文件结构有两个问题**：

### 3.1 moov atom 在文件末尾

MP4 的 `moov` atom 包含元数据（编解码信息、采样表、索引等）。多数编码器默认把 moov 写在文件末尾,这导致播放器必须先 **HTTP Range 请求到文件末尾读 moov,再回到开头读数据**。

### 3.2 音视频数据 interleaving 极差

MP4 文件中,音频 sample 和视频 sample 本应交错存放（每段视频紧跟对应的音频）。但这个文件的音频块集中在 ~81MB 处,视频块集中在 ~232MB 处,相隔 150MB。

**结果**：MPV/ffmpeg 必须在 81MB 和 232MB 两个位置之间反复跳读 → 每个 seek 在 HTTP 流上都是一次新的 Range GET → WebDav 服务器响应延迟 → demuxer 卡住 → 「播几秒卡几秒」循环。

### 3.3 为什么 Exo 不卡

Exo 用自己的 MP4 extractor + 大缓冲 + 智能预读,本质上是把文件按顺序读进内存,seek 只发生在内存里。MPV/ffmpeg 是按需 seek,每次 seek 都触发网络请求。

## 4. 为什么 MPV cache 配置无效

我们尝试的 5 个 cache 选项（`cache` / `cache-secs` / `demuxer-readahead-secs` / `demuxer-seekable-cache` / `network-timeout`）针对的是「数据被消耗空后重新下载」的场景。

但本 bug 的问题是 **demuxer 主动 seek 到新位置读 interleaved 数据** —— 即使把整文件下完缓存住,MPV 还是要在两个 150MB 间距的块之间 seek（只是变成内存 seek）。而且根据 [mpv #6726](https://github.com/mpv-player/mpv/issues/6726),`demuxer-readahead-secs` 依赖容器有规范的 timestamp 和 interleaving,坏 interleaving 时反而**加剧** seek。

更广泛的方案调研（`seekable=0`、`analyzeduration`、`probesize` 等）也都不能解决「文件结构本身差」这个根本问题。

## 5. 修复方案

**根本修复在文件层,不在 app 层**。用 ffmpeg 重新封装文件：

```bash
ffmpeg -i input.mp4 -c copy -movflags +faststart output.mp4
```

参数说明：
- `-c copy`：不重新编码（秒级完成,无质量损失,只重排容器结构）
- `-movflags +faststart`：把 moov atom 移到文件开头 + 重排音视频 interleaving

修复后所有播放器（MPV / Exo / VLC / 桌面 mpv 等）都会流畅播放这个文件。

> ⚠️ `-movflags +faststart` 需要足够的临时磁盘空间（会复制整个文件）。

## 6. 对未来类似问题的策略

- **不要在 app 里加 fallback-Exo 逻辑** 来规避坏文件 —— 牺牲未来 MPV 路线（如 Anime4K 超分 shader）。
- **不要加 WebDav 预下载机制** —— 为单个坏文件加一整套下载+缓存管理是过度设计。
- **遇到 MPV 播放特定文件卡顿** 时,先怀疑文件本身结构,用 `ffmpeg -v trace -i file.mp4 2>&1 | grep -e type:\'mdat\' -e type:\'moov\'` 看 moov 位置,或抓 MPV log-file 看 seek 频率。
- **如果将来发现普遍问题**（很多用户都遇到）,再考虑 app 层方案。

## 7. 已撤销的工作

- `20201fb` feat(mpv): enable streaming cache options — **已撤销**
- `f6002d2` feat(settings): add labMpvCache preference — **已撤销**
- `b042991` feat(lab): add MPV streaming cache toggle UI — **已撤销**

保留的：
- `430a49b` docs: spec for MPV streaming cache lab toggle — 作为方案探索记录保留
- `0225150` docs: implementation plan for MPV streaming cache lab toggle — 同上

## 8. 参考资料

- [mpv #6726 — demuxer-readahead-secs 与 interleaving 的关系](https://github.com/mpv-player/mpv/issues/6726)
- [mpv #6802 — 远程 mount 上 demuxer 缓存读取慢](https://github.com/mpv-player/mpv/issues/6802)
- [Stack Overflow — How to fix "moov atom not found"](https://stackoverflow.com/questions/55896329/how-to-fix-moov-atom-not-found-error-in-ffmpeg)
- [Super User — ffmpeg moov atom not found](https://superuser.com/questions/1712123/ffmpeg-moov-atom-not-found-on-a-seemingly-valid-file)
- [mpv manual](https://mpv.io/manual/stable/)
