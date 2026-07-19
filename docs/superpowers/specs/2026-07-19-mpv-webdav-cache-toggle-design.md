# MPV 流媒体优化开关 — 设计文档

- 日期: 2026-07-19
- 状态: 已批准
- 影响模块: `dy-player`, `lib-player/player-mpv`

## 1. 背景与问题

通过 MPV 内核播放 WebDav 文件时（长、短视频都有），拖动进度条后会进入**「播放几秒→卡几秒→播放几秒→卡几秒」的循环**，无法正常观看。

### 根因

`MpvMediaPlayer.setInitOptions()` (`lib-player/player-mpv/.../MpvMediaPlayer.kt:1037-1055`) 当前**只设了 demuxer 的字节上限**（`demuxer-max-bytes` / `demuxer-max-back-bytes`），**缺失以下关键 cache 选项**：

| 缺失选项 | 作用 |
|---------|------|
| `cache=yes` | 显式启用 stream-cache |
| `cache-secs=30` | 至少缓存 30 秒数据，避免 underflow |
| `demuxer-readahead-secs=5` | demuxer 预读 5 秒数据 |
| `demuxer-seekable-cache=yes` | **关键**：让 seek 复用已下载数据，而不是丢弃重新 Range GET |
| `network-timeout=60` | 网络请求超时兜底（MPV 默认 0=无限） |

结果：MPV 在 HTTP 流上边下边播时 demuxer 队列反复耗尽 → 触发 `paused-for-cache` → 缓冲恢复后播放几秒 → 再次耗尽 → 循环。

> **注**：`MpvMediaSourceHelper.setupCache()` / `setupNetworkSettings()` 里**已经写好了**这些配置（`cache-secs=30`、`demuxer-readahead-secs=5` 等），但全仓库从未被调用 —— 属于历史遗留死代码。本次不复用它们（架构耦合不当），直接在 `setInitOptions()` 里设置。

### 已排除的非根因

- **UI 层 seek 风暴**：`ShortVideoControlView.java:149-179, 377-380` 已做「松手才 seek」，每次手势只触发 1 次 `seekTo`。
- **Auth 头丢失**：`Authorization` 通过 `http-header-fields` 正确传给 MPV（`MpvMediaSourceHelper.setHeaders` `:17-43`）。
- **302 重定向未解析**：长视频已经解析（`LongVideoActivity.kt:643` 调 `playUrl()`），仍卡。说明根因在 MPV 配置层，不在 URL 解析层。
- **代理/临时文件**：不存在该路径，URL 直接给 MPV。

## 2. 目标与非目标

### 目标

- 在「实验室」设置页添加一个 **「MPV 流媒体优化」** 开关，开启后对 MPV 播放的所有来源（WebDav / 本地 / HTTP 流）应用上述 5 个 cache 选项。
- 默认**开启**，老用户升级后自动获益。
- 修复 MPV + WebDav 拖动进度条后的「播放几秒卡几秒」循环（长、短视频都受益）。

### 非目标

- 不改 Exo 后端（只影响 MPV）。
- 不动 `MpvMediaSourceHelper` 里的死代码（保留，不扩散）。
- 不改 `ShortVideoActivity` 的 302 未解析问题（与卡顿无关，独立 issue）。
- 不改 factory / registry / activity 的签名（保持现有架构）。
- 不增加多档预设或细分开关（YAGNI，单总开关足够）。

## 3. 设计

### 3.1 用户视角

**位置**：设置 → 实验室 → 新增一行，紧邻现有的「MPV 专用 Render」。

| 字段 | 值 |
|------|-----|
| 标题 | `MPV 流媒体优化` |
| 描述 | `为远程视频（WebDav/HTTP）启用缓存优化，缓解拖动进度条卡顿` |
| 控件 | `SwitchCompat` |
| 默认值 | **开** |
| 生效时机 | 下次播放生效（与现有 `useOkhttp` / `surfaceRender` 等设置一致；不需要重启 app） |

### 3.2 关键架构约束

⚠️ **`cache=yes` / `demuxer-seekable-cache` 等是 MPV init-time-only 选项**：必须在 `mpv.init()` 之前通过 `setOptionString()` 设置（见 `MpvMediaPlayer.kt:614-641`，`setInitOptions()` 在 `mpv.init()` 之前调用）。

因此**不能**走 `setOnPlayerInitializedListener` 里调 setter 的路（那是 init 之后），也不能事后调 `MpvMediaPlayer` 的 runtime setter。配置值必须在 **`setInitOptions()` 执行时**就能拿到。

### 3.3 配置传递方案：跨模块读 SharedPreferences（采用）

`lib-player/player-mpv` 模块**不能依赖** `dy-player`（反向依赖，SpUtil 在 dy-player 里）。所以 MPV 模块无法直接 import SpUtil。

**方案**：`MpvMediaPlayer` 持有 `appContext`，直接通过 `PreferenceManager.getDefaultSharedPreferences(appContext)` 读 app 默认 SharedPreferences 的 `labMpvCache` key。

**已验证**（`lib-base/.../SpBase.kt:22`）：

```kotlin
private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
```

SpUtil 继承 SpBase，所有 `SPManager.boolean(...)` 委托的属性都存在**默认文件**里，key 就是属性名。所以只要 `SpUtil.labMpvCache` 的属性名是 `labMpvCache`，MPV 模块就能在默认文件里按这个名字读到。

**跨模块约定**：
- key 名：`labMpvCache`（SpUtil 属性名）
- 默认值：`true`（两端必须一致）
- 文件：app 默认 SharedPreferences（不写死文件名，更稳健）

约定通过常量 + 注释文档化，避免后续重命名导致失联。

### 3.4 选项选择与替代方案对比

| 方案 | 描述 | 结论 |
|------|------|------|
| **跨模块读 SharedPreferences（C-1）** | MPV 模块直接读 app 默认 SP 的 `labMpvCache` key | ✅ 采用：零架构改动、不破坏 factory stateless |
| 改 factory 签名传配置（C-2） | `MpvMediaPlayerFactory` 加 `MpvConfig` 参数 | 否决：要改 `PlayerFactory` / `DyPlayerCoreRegistry.applyCore` / 两个 activity 的所有调用点，破坏现有设计 |
| 静态全局配置（C-3） | `object MpvConfig { var cacheEnabled = true }` | 否决：全局可变状态、进程重建丢失、测试不友好 |

## 4. 详细改动清单

### 4.1 `dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt`

在 `labMpvSpecialRender`（约 `:173`）旁边新增一行：

```kotlin
/**
 * MPV 流媒体缓存优化（缓解 WebDav 等 HTTP 流拖动进度条/播放卡顿）。
 *
 * ⚠️ 跨模块约定：此 key 由 lib-player/player-mpv/MpvMediaPlayer 在 init 时直接读取
 * （app 默认 SharedPreferences）。重命名属性会破坏 MPV 端读取。
 */
var labMpvCache by SPManager.boolean(true)
```

### 4.2 `lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt`

#### 4.2.1 新增常量（避免 magic string，文档化跨模块约定）

在文件顶部（接近其他常量定义处，约 `:83-128` 区域）新增：

```kotlin
/**
 * MPV 流媒体优化开关的 SharedPreferences key。
 *
 * ⚠️ 跨模块约定：值由 dy-player.SpUtil.labMpvCache 写入（app 默认 SharedPreferences）。
 * player-mpv 模块不能依赖 dy-player，所以按字符串 key 读取。
 * 默认 true：老用户升级后自动获益；如某设备/服务器有副作用可手动关。
 */
private const val SP_KEY_LAB_MPV_CACHE = "labMpvCache"
private const val SP_LAB_MPV_CACHE_DEFAULT = true
```

#### 4.2.2 新增导入

`MpvMediaPlayer.kt` 需要导入 `android.preference.PreferenceManager`（SpBase 用的就是它）。如果文件已 import `android.content.Context`（已有，因为构造函数用 Context）则无需重复。

#### 4.2.3 在 `setInitOptions()` 末尾追加 cache 配置

在 `MpvMediaPlayer.kt:1054`（`demuxer-max-back-bytes` 设置之后、函数闭合 `}` 之前）追加：

```kotlin
// MPV 流媒体优化：缓解 WebDav 等 HTTP 流拖动进度条/播放卡顿。
// 跨模块读 dy-player 写入的 labMpvCache（app 默认 SharedPreferences）。
val mpvCacheEnabled = try {
    PreferenceManager.getDefaultSharedPreferences(appContext)
        .getBoolean(SP_KEY_LAB_MPV_CACHE, SP_LAB_MPV_CACHE_DEFAULT)
} catch (e: Exception) {
    L.e("Failed to read mpv cache preference", e.message)
    SP_LAB_MPV_CACHE_DEFAULT
}
if (mpvCacheEnabled) {
    mpv.setOptionString("cache", "yes")
    mpv.setOptionString("cache-secs", "30")
    mpv.setOptionString("demuxer-readahead-secs", "5")
    mpv.setOptionString("demuxer-seekable-cache", "yes")
    mpv.setOptionString("network-timeout", "60")
}
```

`try/catch` 防御性处理：极少数情况下 SharedPreferences 可能不可读（如多进程、权限问题），失败时回落到默认值，不影响播放。

### 4.3 `dy-player/src/main/res/values/strings.xml`

在 `hint_lab_mpv_special_render_desc`（`:116`）之后追加：

```xml
<string name="hint_lab_mpv_cache">MPV 流媒体优化</string>
<string name="hint_lab_mpv_cache_desc">为远程视频（WebDav/HTTP）启用缓存优化，缓解拖动进度条卡顿</string>
```

### 4.4 `dy-player/src/main/res/layout/fragment_lab_setting.xml`

在 `lab_mpv_special_render` 行闭合（`:160` 的 `</androidx.constraintlayout.widget.ConstraintLayout>`）之后、`debug_mode` 行开始（`:162`）之前，插入新行（镜像 `lab_mpv_special_render` 的结构 `:119-160`）：

```xml
<androidx.constraintlayout.widget.ConstraintLayout
    android:id="@+id/lab_mpv_cache"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:paddingStart="@dimen/dp32"
    android:paddingTop="@dimen/dp8"
    android:paddingEnd="@dimen/dp16"
    android:paddingBottom="@dimen/dp8"
    app:layout_constraintEnd_toEndOf="parent"
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintTop_toBottomOf="@+id/lab_mpv_special_render">

    <TextView
        android:id="@+id/tv_lab_mpv_cache"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/hint_lab_mpv_cache"
        android:textColor="?android:attr/textColorPrimary"
        android:textSize="18sp"
        app:layout_constraintBottom_toTopOf="@id/tv_lab_mpv_cache_desc"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />

    <TextView
        android:id="@+id/tv_lab_mpv_cache_desc"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="12sp"
        android:text="@string/hint_lab_mpv_cache_desc"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/tv_lab_mpv_cache" />

    <androidx.appcompat.widget.SwitchCompat
        android:id="@+id/sw_lab_mpv_cache"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        tools:ignore="MissingConstraints" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

同时把 `debug_mode` 行的 `app:layout_constraintTop_toBottomOf="@+id/lab_mpv_special_render"`（约 `:172`）改为 `app:layout_constraintTop_toBottomOf="@+id/lab_mpv_cache"`。

### 4.5 `dy-player/src/main/java/me/lingci/dy/player/ui/tool/LabSettingsFragment.kt`

在 `init()` 的 `swLabMpvSpecialRender` 块（`:49-52`）之后、`swDebugMode` 块（`:53-56`）之前插入：

```kotlin
binding.swLabMpvCache.isChecked = spUtil.labMpvCache
binding.swLabMpvCache.setOnClickListener {
    spUtil.labMpvCache = binding.swLabMpvCache.isChecked
}
```

ViewBinding 会自动生成 `binding.swLabMpvCache`（基于 XML 的 `sw_lab_mpv_cache` id）。

## 5. 参数选择说明

| 选项 | 值 | 理由 |
|------|-----|------|
| `cache` | `yes` | 显式启用 stream-cache（避免依赖 MPV 默认行为） |
| `cache-secs` | `30` | 复用 `MpvMediaSourceHelper.setupNetworkSettings()` 里已经写好的「正常模式」值，社区 MPV 流媒体推荐配置 |
| `demuxer-readahead-secs` | `5` | 同上，复用既有值 |
| `demuxer-seekable-cache` | `yes` | **关键**：让 seek 命中已下载数据，避免丢弃重新 Range GET |
| `network-timeout` | `60` | MPV 默认 0（无限），加兜底防止极端情况无限卡死 |

`demuxer-max-bytes`（64MiB）保持不变 —— 已是合理值；`cache-secs=30` 只是时间维度目标，在 64MiB 上限内会被自动截断，内存占用基本不变。

## 6. 风险与回归验证

### 风险

- **内存**：`demuxer-max-bytes` 不变（64MiB），`cache-secs=30` 在该上限内会被截断，内存占用基本不变。
- **本地文件**：本地读取速度远超 5 秒 readahead，cache 配置不会引入负作用（最多多读一点，不影响体验）。
- **Exo 后端**：完全不受影响（只改 MPV 模块）。
- **老用户升级**：默认开 → 自动获益；如某设备/服务器出现副作用（内存压力、特定 WebDav 实现不兼容 Range/cache），可手动关闭。
- **跨模块 key 失联**：通过常量 `SP_KEY_LAB_MPV_CACHE` + 注释文档化，重命名需同步两端。

### 验证清单（手动，遵循 AGENTS.md）

1. **MPV + WebDav 长视频**（核心修复目标）：拖动进度条后不再出现「播放几秒卡几秒」循环。
2. **MPV + WebDav 短视频**（用户原始报告）：同上。
3. **MPV + 本地文件**（回归）：播放 / seek 行为正常，无负作用。
4. **Exo + WebDav**（回归）：行为不变，确认没破坏 Exo 路径。
5. **关闭开关**：在实验室关闭「MPV 流媒体优化」→ 重新开始播放 → MPV + WebDav 恢复原有卡顿行为（确认开关真的生效、可关）。
6. **开关切换时机**：开关切换后需要重新开始播放才生效（与现有 `useOkhttp` 等一致），不会影响当前正在播放的视频。

## 7. 影响面

- 修改文件（共 5 个）：
  - `dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt`（+1 属性 + 注释）
  - `lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt`（+2 常量、+1 import、+cache 块）
  - `dy-player/src/main/res/values/strings.xml`（+2 string）
  - `dy-player/src/main/res/layout/fragment_lab_setting.xml`（+1 行 + 改 1 行约束）
  - `dy-player/src/main/java/me/lingci/dy/player/ui/tool/LabSettingsFragment.kt`（+4 行）
- 不修改：`MpvMediaSourceHelper.kt`（死代码保留）、`MpvMediaPlayerFactory.kt`、`DyPlayerCoreRegistry.kt`、`LongVideoActivity.kt`、`ShortVideoActivity.kt`、Exo 后端。
- 不涉及持久化数据迁移（新 key，默认值 true）。
- 不涉及 ProGuard 规则变更。
- 不修改 `targetSdk`（仍 28）。
