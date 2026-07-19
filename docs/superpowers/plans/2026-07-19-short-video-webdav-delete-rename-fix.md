# 短视频 WebDav 删除/重命名修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复短视频页面删除/重命名 WebDav 文件失败的 bug，让点击删除真正删除 WebDav 服务器上的文件。

**Architecture:** `VideoData.videoUrl` 对 WebDav 存的是完整 URL，但 `IStorage.delete/rename` 期望相对路径。在 `ShortVideoFileActions` 内新增私有路径转换工具 `IStorage.toRelativePath(fullUrl)`，调用 `storage.delete/rename` 前把完整 URL 转回相对路径。改动隔离在单个文件，不触碰 `lib-base` 存储层、不影响本地文件路径。

**Tech Stack:** Kotlin, Android, kotlinx.coroutines, OkHttp（间接，通过 `WebDavStorage`）

## Global Constraints

- 不修改 `lib-base/.../storage/` 下任何文件（`IStorage`、`WebDavStorage`、`LocalStorage`、`SmbStorage`）。
- 不修改 `MediaDetailActivity` 或其它调用点（媒体库批量删除/重命名缺陷留待后续）。
- 修改仅限单文件：`dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoFileActions.kt`。
- 不引入新的依赖或测试框架（仓库当前无测试目录，验证以手动为主，AGENTS.md 明确要求人工验证）。
- 注释/字符串保持简体中文（遵循 AGENTS.md 编码约定）。
- 不改动 `VideoData.videoUrl` 数据模型。
- Gradle 构建用 wrapper：`./gradlew`，禁止调用系统 Gradle。

## File Structure

- **Modify**: `dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoFileActions.kt`
  - 在文件末尾（类内、`resolveStorage` 之后）新增私有扩展函数 `IStorage.toRelativePath(fullUrl: String): String`。
  - 修改 `deleteVideo()`（`:180-209`）调用点。
  - 修改 `renameVideo()`（`:132-177`）调用点。

无新增文件、无测试文件、无资源文件改动。

---

## Task 1: 新增 `toRelativePath` 路径转换工具

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoFileActions.kt`（在 `resolveStorage()` 函数后，类闭合 `}` 之前）

**Interfaces:**
- Consumes: `IStorage.fullPath(path: String): String`（来自 `lib-base/.../storage/IStorage.kt:19`），返回完整 URL 或路径。
- Produces: `private fun IStorage.toRelativePath(fullUrl: String): String` — 仅供本类内 `deleteVideo` / `renameVideo` 调用。

- [ ] **Step 1: 在 `resolveStorage()` 后插入转换函数**

打开 `dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoFileActions.kt`，找到 `resolveStorage()` 函数（约 `:211-221`）的闭合 `}`，在其**之后、类的闭合 `}` 之前**插入：

```kotlin

    /**
     * 将 videoData.videoUrl 转成 storage.delete/rename 期望的相对路径。
     *
     * 背景：WebDav 的 videoUrl 是完整 URL（如 https://dav.example.com/dav/movies/a.mp4），
     * 但 WebDavStorage.delete/rename 内部又会拼一次 rootUrl，必须先剥离掉前缀。
     * - WebDav：fullPath("") 返回 rootUrl，命中 startsWith → 剥离得到 "/movies/a.mp4"。
     * - 本地：LocalStorage.fullPath("") 返回 ""，root 为空 → 原样返回。
     *
     * 同时幂等：传入已经是相对路径的字符串也能正确处理（startsWith 不命中 → 原样返回）。
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

- [ ] **Step 2: 确认插入位置正确**

定位到文件末尾，确认结构是：

```kotlin
    /** 根据媒体库记录优先解析 storageId，兼容旧数据只按视频类型匹配存储源。 */
    private fun resolveStorage(videoData: VideoData): IStorage? {
        ...
        return source?.toStorage()
    }

    /** <新插入的 toRelativePath 文档注释> */
    private fun IStorage.toRelativePath(fullUrl: String): String {
        ...
    }
}  // <- 类的闭合
```

如果发现新函数跑到了类外面（缺缩进或位置错误），调整到类内部。

- [ ] **Step 3: 编译验证**

Run: `./gradlew :dy-player:compileBetaDebugKotlin`
Expected: BUILD SUCCESSFUL。如果失败，常见原因：函数放在了类外、`fullPath` 不可见（不会发生，`IStorage` 是 public interface）。

- [ ] **Step 4: Commit**

```bash
git add dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoFileActions.kt
git commit -m "fix(short-video): add toRelativePath helper for WebDav delete/rename"
```

---

## Task 2: 修复 `deleteVideo()` 调用点

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoFileActions.kt:190`

**Interfaces:**
- Consumes: `IStorage.toRelativePath(fullUrl)`（Task 1 新增）、`IStorage.delete(path)`（既有）。
- Produces: 无新对外接口。

- [ ] **Step 1: 修改 `deleteVideo()` 中的 storage.delete 调用**

在 `ShortVideoFileActions.kt` 中找到 `deleteVideo()` 函数（约 `:180-209`）。定位到这一行（约 `:190`）：

```kotlin
                    storage.delete(path)
```

替换为：

```kotlin
                    storage.delete(storage.toRelativePath(path))
```

其它行不动。

- [ ] **Step 2: 确认改动是唯一的**

检查 `deleteVideo()` 函数体内除了这一行，没有其它对 `path` 的直接使用（`File(path)` 那条是 `else` 分支，本地文件路径不受影响，保持不变）。

- [ ] **Step 3: 编译验证**

Run: `./gradlew :dy-player:compileBetaDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoFileActions.kt
git commit -m "fix(short-video): delete WebDav file with relative path"
```

---

## Task 3: 修复 `renameVideo()` 调用点

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoFileActions.kt:143`（storage.rename 调用）与 `:164`（成功后 newPath 计算，保持不变但需校验）

**Interfaces:**
- Consumes: `IStorage.toRelativePath(fullUrl)`（Task 1 新增）、`IStorage.rename(oldPath, newName)`（既有）。
- Produces: 无新对外接口。

- [ ] **Step 1: 修改 `renameVideo()` 中的 storage.rename 调用**

在 `ShortVideoFileActions.kt` 中找到 `renameVideo()` 函数（约 `:132-177`）。定位到这一行（约 `:143`）：

```kotlin
                    storage.rename(oldPath, newName)
```

替换为：

```kotlin
                    storage.rename(storage.toRelativePath(oldPath), newName)
```

- [ ] **Step 2: 校验 `newPath` 回写逻辑（不改代码）**

找到 `renameVideo()` 主线程分支中的这一行（约 `:164`）：

```kotlin
                    val newPath = oldPath.substring(0, oldPath.lastIndexOf("/") + 1) + newName
```

确认它**保持不变**。

**校验逻辑**：`oldPath` 是完整 URL（如 `https://dav.example.com/dav/movies/a.mp4`）。`lastIndexOf("/")` 找到 `a.mp4` 前面的 `/`，`substring(0, idx + 1)` 得到 `https://dav.example.com/dav/movies/`，再拼 `newName` 得到新完整 URL。前缀（含 `https://`）保持完整，仅末尾文件名被替换。符合预期：`videoData.videoUrl` 仍以完整 URL 格式存回，下次操作仍能正确转换。

不需要改这行。

- [ ] **Step 3: 编译验证**

Run: `./gradlew :dy-player:compileBetaDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoFileActions.kt
git commit -m "fix(short-video): rename WebDav file with relative path"
```

---

## Task 4: 构建完整 APK 并手动验证（WebDav 删除）

**Files:**
- 无文件改动。

**Interfaces:**
- 无。

- [ ] **Step 1: 构建并安装 beta debug APK**

Run: `./gradlew :dy-player:installBetaDebug`
Expected: BUILD SUCCESSFUL，APK 安装到连接的设备/模拟器（applicationId `me.lingci.dy.player.debug`）。

如果设备未连接，退而求其次用 `./gradlew :dy-player:assembleBetaDebug` 生成 APK 手动安装。

- [ ] **Step 2: 准备 WebDav 测试环境**

确保已在 app 设置中配置好可用的 WebDav 源（设置 → 存储源 / 媒体库），且该源根目录下有一个用于测试删除的短视频文件。

- [ ] **Step 3: 复现修复路径**

1. 进入 app → 媒体库 → 选择 WebDav 来源 → 进入该视频所在的文件夹。
2. 进入短视频模式播放该 WebDav 视频（长按或短视频入口，按 `ShortVideoActivity` 的进入路径）。
3. 播放中点击「更多」按钮（`ShortMoreDialog`）。
4. 点击「删除」 → 在确认框点「确定」。

**Expected**:
- 显示 Toast「删除成功」（不是「删除失败」）。
- 当前短视频页被移除（`removeItem(position)` 触发）。

- [ ] **Step 4: 在 WebDav 服务器侧确认文件已删**

回到媒体库浏览页刷新该 WebDav 目录，或用浏览器/文件管理器直接访问 WebDav 服务器，确认测试文件已不存在。

如果文件仍在 → 修复失败，检查 `toRelativePath` 是否正确剥离了 `rootUrl`（可在 logcat 抓 `deleteVideo failed` / `delete failed` 日志核对）。

- [ ] **Step 5: 回归验证（本地文件，不受改动影响但需确认未破坏）**

1. 进入 app → 媒体库 → 本地存储 → 选择一个本地短视频。
2. 进入短视频模式播放。
3. 点击「更多」→「删除」→ 确认。

**Expected**: 显示「删除成功」，文件从本地真正删除，短视频页移除。行为应与修复前完全一致。

如果本地删除失败 → `toRelativePath` 误伤了本地路径分支，检查 `LocalStorage.fullPath("")` 是否确实返回空字符串。

---

## Task 5: 手动验证（WebDav 重命名 + 分享回归）

**Files:**
- 无文件改动。

**Interfaces:**
- 无。

- [ ] **Step 1: 构建并安装（若 Task 4 之后有改动）**

如果 Task 4 之后没有任何代码改动，可跳过此步使用 Task 4 的 APK。否则：

Run: `./gradlew :dy-player:installBetaDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 2: 验证 WebDav 重命名**

1. 进入 app → 媒体库 → WebDav 来源 → 进入视频所在文件夹。
2. 进入短视频模式播放另一个测试视频（不是 Task 4 删除的那个）。
3. 点击「更多」→「重命名」→ 输入新文件名 → 确认。

**Expected**:
- 显示 Toast「重命名成功」。
- 短视频列表标题更新为新名字。
- 控制器标题更新为新名字（`ShortTitleFormatter.format(newName)`）。

- [ ] **Step 3: WebDav 服务器侧确认重命名**

回到媒体库浏览页刷新目录：
- 旧文件名应消失。
- 新文件名应出现，文件可正常播放。

- [ ] **Step 4: 回归验证分享（未改但相邻路径）**

`shareVideo()` 用的是 `storage.download()`，本次未修改，但与删除/重命名属同一文件，需确认未破坏：

1. 在短视频模式播放一个 WebDav 视频。
2. 点击「更多」→「分享」。

**Expected**: 文件被下载到缓存目录后弹出系统分享选择器。如果此前能用、现在不能用 → 检查 Task 1 是否误改了 `shareVideo()`（不应该改）。

- [ ] **Step 5: 最终提交（如 Task 4/5 中发现了需要修复的问题）**

如果验证中发现 bug 并修复了，按修复点单独 commit：

```bash
git add <files>
git commit -m "fix(short-video): <具体修复>"
```

如果一切顺利、无需额外改动，则本步无操作 —— 前面 Task 1-3 已 commit 完毕。

---

## Self-Review（plan 作者自查记录）

**1. Spec coverage**（对照 spec 各章节）：
- spec §4.1（`toRelativePath` 工具）→ Task 1 ✅
- spec §4.2（deleteVideo 改动）→ Task 2 ✅
- spec §4.3（renameVideo 改动 + newPath 校验）→ Task 3 ✅
- spec §5（测试与验证：WebDav 删除、本地回归、分享回归）→ Task 4 + Task 5 ✅
- spec §6（影响面：单文件）→ Global Constraints 已约束 ✅
- spec §3 方案 A 已在 Global Constraints 中固化为「不修改 lib-base、不修改 MediaDetailActivity」✅

**2. Placeholder scan**：无 TBD/TODO/「类似 Task N」/省略代码块。所有改动都有完整 before/after 代码。

**3. Type consistency**：
- `IStorage.toRelativePath(fullUrl: String): String` —— Task 1 定义、Task 2/3 调用，签名一致。
- 调用形式 `storage.toRelativePath(path)` / `storage.toRelativePath(oldPath)` —— 与定义一致（receiver 是 `IStorage`，实参 `String`）。
- Task 2 改动 `:190`、Task 3 改动 `:143`、Task 3 校验 `:164` —— 行号引用与 spec 一致。
