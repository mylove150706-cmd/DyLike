# 短视频 WebDav 删除/重命名修复 — 设计文档

- 日期: 2026-07-19
- 状态: 已批准（方案 A）
- 影响模块: `dy-player`

## 1. 背景与问题

短视频页面播放 WebDav 来源的文件时，点击「删除」按钮会提示「删除失败」，文件并未从 WebDav 服务器删除。同样的逻辑缺陷也影响「重命名」操作（虽然未在用户报告中明确指出，但属于同一类 bug，本次一并修复）。

### 根因

`VideoData.videoUrl` 对 WebDav 来源存的是**完整 URL**（由 `WebDavStorage.fullPath()` 生成，例如 `https://dav.example.com/dav/movies/file.mp4`）。

但 `IStorage.delete(path)` / `rename(oldPath, newName)` 的契约是**相对路径**：`WebDavStorage` 内部通过 `getWebDavUrl(path)` 又拼一次 `rootUrl`（`"$rootUrl${path.trimStart('/')}"`）。

`ShortVideoFileActions.deleteVideo()` (`ShortVideoFileActions.kt:190`) 和 `renameVideo()` (`ShortVideoFileActions.kt:143`) 直接把 `videoData.videoUrl`（完整 URL）当相对路径传入，导致最终请求 URL 变成：

```
https://dav.example.com/dav/https://dav.example.com/dav/movies/file.mp4
```

该 URL 404，`response.isSuccessful == false`，`delete()` 返回 `false`，主线程弹出「删除失败」。重命名走 `WebDavStorage.rename()` 同样的 `getWebDavUrl()` 逻辑，存在相同问题。

## 2. 目标与非目标

### 目标

- 修复短视频页面删除 WebDav 文件失败的问题，点击删除后能真正删除 WebDav 服务器上的文件。
- 同时修复短视频页面重命名 WebDav 文件（同根因）。

### 非目标（本次明确不做）

- 不修改 `WebDavStorage` / `IStorage` / `SmbStorage` 等 `lib-base` 存储层代码。
- 不修改 `MediaDetailActivity` 等其它调用点（媒体库批量删除/重命名仍有同类缺陷，留待后续）。
- 不修改 `VideoData.videoUrl` 的数据模型（仍保持存完整 URL）。

## 3. 方案对比（最终采用 A）

| 方案 | 改动范围 | 风险 | 结论 |
|------|---------|------|------|
| **A. 调用点转换回相对路径** | 仅 `ShortVideoFileActions.kt` | 低，隔离在单文件 | ✅ 采用 |
| B. `WebDavStorage` 防御性归一化 | `lib-base` 存储层 | 中，破坏接口契约、累积隐式逻辑；与 AGENTS.md 提到的 storage 层不稳定方向相悖 | 否决 |
| C. 改数据模型，`videoUrl` 统一存相对路径 | 播放/分享/历史/缓存等多处 | 高，远超 bug 修复范围，违反 YAGNI | 否决 |

## 4. 详细设计

### 4.1 核心改动：新增路径转换工具

在 `ShortVideoFileActions.kt` 中新增私有函数，把 `videoData.videoUrl`（可能是完整 URL 或本地路径）转成 `IStorage.delete/rename` 期望的相对路径：

```kotlin
/**
 * 将 videoData.videoUrl（WebDav 存的是完整 URL，本地/SMB 存的是路径）
 * 转成 storage.delete/rename 期望的相对路径。
 *
 * 对 WebDav：剥离 fullPath() 拼上的 rootUrl，得到原始相对路径。
 * 对本地/SMB：fullPath() 不改写路径，原样返回。
 */
private fun IStorage.toRelativePath(fullUrl: String): String {
    val root = fullPath("")
    return if (root.isNotEmpty() && fullUrl.startsWith(root)) {
        fullUrl.removePrefix(root)
    } else {
        fullUrl
    }
}
```

#### 为什么用 `fullPath("")` 而不是直接判断 `http://`

- `IStorage.fullPath("")` 是各 storage 实现自身定义的「完整 URL 前缀」，是契约层概念，不依赖具体协议字符串。
- 各实现的行为：
  - `LocalStorage.fullPath("")` 返回 `""`（`LocalStorage.kt:32-34`，直接 `return path`）→ `root.isNotEmpty()` 为 false → 转换函数原样返回 `fullUrl`，行为不变。
  - `WebDavStorage.fullPath("")` 返回 `rootUrl`（如 `https://dav.example.com/dav/`，`WebDavStorage.kt:135-137`），刚好是需要剥离的前缀 → `startsWith(root)` 命中 → 剥离得到相对路径。
  - `SmbStorage` 本次不在范围，`fullPath` 行为未深究，但有 `root.isNotEmpty()` 守卫：即便返回空字符串也会原样返回 `fullUrl`，不会误伤。

#### 幂等性

转换函数同时支持两种输入：
- 完整 URL（`videoData.videoUrl` 当前实际形态）→ 剥离得到相对路径。
- 已经是相对路径 → `startsWith(root)` 不成立 → 原样返回，仍可被 `WebDavStorage.getWebDavUrl()` 正确处理。

这意味着即使未来 `videoData.videoUrl` 的来源发生细微变化（例如某些场景下传了相对路径），函数也不会破坏数据。

### 4.2 `deleteVideo()` 改动

`ShortVideoFileActions.kt:180-209`，把第 190 行：

```kotlin
storage.delete(path)
```

改为：

```kotlin
storage.delete(storage.toRelativePath(path))
```

其余逻辑（成功移除列表项、toast 文案）不变。

### 4.3 `renameVideo()` 改动

`ShortVideoFileActions.kt:132-177`：

1. 第 143 行的 `storage.rename(oldPath, newName)` 改为：

   ```kotlin
   storage.rename(storage.toRelativePath(oldPath), newName)
   ```

2. 第 164 行的 `newPath` 计算（成功后回写 `videoData.videoUrl`）需要保持「完整 URL」格式，因为 `videoData.videoUrl` 原本是完整 URL：

   现状（路径最后一段替换为新文件名）：

   ```kotlin
   val newPath = oldPath.substring(0, oldPath.lastIndexOf("/") + 1) + newName
   ```

   这行对完整 URL 仍然成立 —— `lastIndexOf("/")` 找到的是文件名前的 `/`，前缀（含 `https://...`）保持不变，仅替换末尾文件名。**无需改动**，但需在代码注释里说明 `oldPath` 是完整 URL 时的行为，避免后续误解。

### 4.4 边界与正确性论证

- **本地文件**：`resolveStorage(videoData)` 返回 `null`（本地无 SourceData），走 `File(path).delete()` 分支，不受影响。
- **SMB**：AGENTS.md 标注 `SmbStorage` 仍有未实现的 TODO，本次不动它；如果 `SmbStorage.delete` 未实现，行为与现状一致（仍可能失败，但不是本次修复目标）。
- **WebDav**：转换后传给 `delete()` 的是 `/movies/file.mp4` 形式的相对路径，`getWebDavUrl()` 正确拼出完整 URL，HTTP DELETE 命中正确资源。
- **重命名后**：`videoData.videoUrl` 被回写为新完整 URL（仅文件名段变化），下次操作仍能正确转换。

## 5. 测试与验证

按 `docs/developer-guide.md` 和 AGENTS.md 的检查清单：

1. **短视频 + WebDav 文件**：
   - 播放一个 WebDav 视频 → 点删除 → 确认 → 应弹出「删除成功」，当前页被移除。
   - 回到 WebDav 浏览页确认服务器上文件已消失。
   - 重做一次重命名：播放 → 点重命名 → 输入新名 → 确认 → 应「重命名成功」，列表标题更新，回到 WebDav 浏览页确认服务器上旧文件消失、新文件出现。
2. **短视频 + 本地文件**（回归）：删除、重命名行为应与修复前完全一致。
3. **短视频 + 分享**（回归）：`shareVideo()` 不在本次改动范围，但应保持可用（它走的是 `storage.download()`，本次未触碰）。

由于本仓库无针对该路径的自动化测试，验证以手动为主。

## 6. 影响面

- 修改文件：`dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoFileActions.kt`（仅 1 个文件）。
- 不修改 `lib-base`，不修改 `MediaDetailActivity`，不修改资源/字符串。
- 不涉及持久化数据迁移。
- 不涉及 ProGuard 规则变更。

## 7. 风险

- 主要风险已通过方案对比规避：不引入跨层改动，不破坏现有契约。
- 转换函数 `toRelativePath` 仅在 `ShortVideoFileActions` 内私有使用，不扩散到其它调用点。
