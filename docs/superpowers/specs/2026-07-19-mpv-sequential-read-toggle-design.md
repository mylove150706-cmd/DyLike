# MPV interleaved_read 实验室开关 — 设计文档

- 日期: 2026-07-19
- 状态: 已批准
- 影响模块: `dy-player`, `lib-player/player-mpv`
- 关联文档:
  - `docs/superpowers/specs/2026-07-19-mpv-webdav-lag-rootcause.md`(根因分析)
  - `docs/superpowers/specs/2026-07-19-mpv-webdav-cache-toggle-design.md`(上一个被撤销的 cache 方案 — 保留作探索记录)

## 1. 背景与根因

### 现象

MPV 内核播放某些 WebDav 视频(尤其 badly-interleaved 的 MP4),拖动进度条后出现「播放几秒→卡几秒→播放几秒→卡几秒」循环。同一文件用 Exo 内核流畅。

### 根因(已通过 MPV log-file 验证)

FFmpeg 的 MOV demuxer 默认开启 `interleaved_read=1`,会在 demuxer 层主动 interleave 多个 track(读取音频→视频→音频→视频交替)。对于:

- **坏 interleaving 的 MP4**(音视频 sample 块在文件中相隔很远,本案例中相隔 150MB)
- **HTTP 流**(每次跳读 = 一次新的 Range GET 请求)

会触发 **seek 风暴**:MPV 日志实测 150 秒内出现 **3697 次 stream level seek**(平均每秒 25+ 次),每次 seek 都触发 HTTP Range 请求 → WebDav 服务器响应延迟 → demuxer 卡住 → 卡顿循环。

### 修复(已通过 MPV log-file 验证)

设 `demuxer-lavf-o=interleaved_read=0`,禁用 demuxer-level interleave,让 ffmpeg 按文件物理顺序读。

**修复后实测**:同一文件、同样 30 秒播放,seek 次数从 **3697 降到 4**(都是正常的初始 moov 读取 + 拖动 seek),无 `moov atom not found` 错误,播放正常。

### 副作用评估

- **本地文件**:几乎无影响。本地 seek 是内存/磁盘操作,即使触发额外 seek 也是纳秒级,用户感知不到。
- **好 interleaving 的 MP4 文件**(大多数正常 faststart 文件):禁用 `interleaved_read` 让 ffmpeg 按顺序读;好文件本身就是顺序交错的,所以行为基本一致。理论上可能极轻微影响初始解码预读,实测无可感知差异。
- **非 MP4 文件**(mkv/ts 等):`interleaved_read` 是 MOV demuxer 的选项,对其他 demuxer 无效,无副作用。

## 2. 目标与非目标

### 目标

- 在「实验室」设置页添加一个 **「MPV 顺序读优化」** 开关,开启后 MPV 在 init 时设 `demuxer-lavf-o=interleaved_read=0`。
- 默认**开启**,所有用户升级后自动获益。
- 修复 MPV 播放 badly-interleaved MP4 over HTTP 时的 seek 风暴卡顿。

### 非目标

- 不改 Exo 后端(只影响 MPV)。
- 不重新引入之前撤销的 cache 选项(`cache=yes` / `cache-secs` 等)—— 经实测对本 bug 无效。
- 不改 factory / registry / activity 签名。
- 不加多档预设或细分开关(YAGNI)。
- 不修改 `MpvMediaSourceHelper.kt` 里的死代码。

## 3. 设计

### 3.1 用户视角

**位置**:设置 → 实验室 → 新增一行,紧邻「MPV 专用 Render」(如果之前 cache 方案的开关没合入,就在 `lab_mpv_special_render` 之后)。

| 字段 | 值 |
|------|-----|
| 标题 | `MPV 顺序读优化` |
| 描述 | `禁用 ffmpeg 对 MP4 的 demuxer-level 交错读取,缓解结构不良的远程视频拖动卡顿` |
| 控件 | `SwitchCompat` |
| 默认值 | **开** |
| 生效时机 | 下次播放生效(init-time-only 选项,与现有 `useOkhttp` / `surfaceRender` 一致) |

### 3.2 关键架构约束

⚠️ **`demuxer-lavf-o` 是 MPV init-time-only 选项**:必须在 `mpv.init()` 之前通过 `setOptionString()` 设置。和之前的 cache 方案一样,**不能**走 `setOnPlayerInitializedListener` 里调 setter 的路。

配置值必须在 `MpvMediaPlayer.setInitOptions()` 执行时就能拿到。

### 3.3 配置传递方案:跨模块读 SharedPreferences(沿用上次方案)

`lib-player/player-mpv` 模块不能依赖 `dy-player`(反向依赖)。但 `MpvMediaPlayer` 持有 `appContext`,可以直接通过 `PreferenceManager.getDefaultSharedPreferences(appContext)` 读 app 默认 SharedPreferences 的 key。

**已验证**(`lib-base/.../SpBase.kt:22`):

```kotlin
private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
```

SpUtil 继承 SpBase,所有 `SPManager.boolean(...)` 委托的属性都存在**默认文件**里,key 就是属性名。

**跨模块约定**:
- key 名:`labMpvSequentialRead`(SpUtil 属性名,与 MPV 端常量值一致)
- 默认值:`true`(两端必须一致)
- 文件:app 默认 SharedPreferences(不写死文件名)

约定通过常量 + 注释文档化,避免后续重命名导致失联。

## 4. 详细改动清单

### 4.1 `dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt`

在 `labMpvSpecialRender`(约 `:173`)之后新增:

```kotlin
/**
 * MPV 顺序读优化（禁用 ffmpeg mov demuxer 的 interleaved_read，避免 badly-interleaved
 * MP4 over HTTP 触发 seek 风暴，缓解拖动进度条卡顿）。
 *
 * ⚠️ 跨模块约定：此 key 由 lib-player/player-mpv/MpvMediaPlayer 在 init 时直接读取
 * （app 默认 SharedPreferences）。重命名属性会破坏 MPV 端读取。
 */
var labMpvSequentialRead by SPManager.boolean(true)
```

### 4.2 `lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt`

#### 4.2.1 新增导入

```kotlin
import android.preference.PreferenceManager
```

#### 4.2.2 新增常量(放在 `cacheSize` 之后,约 `:129`)

```kotlin
/**
 * MPV 顺序读优化开关的 SharedPreferences key。
 *
 * ⚠️ 跨模块约定：值由 dy-player.SpUtil.labMpvSequentialRead 写入（app 默认 SharedPreferences）。
 * player-mpv 模块不能依赖 dy-player，所以按字符串 key 读取。
 * 默认 true：老用户升级后自动获益；如某文件出现副作用可手动关。
 */
private val SP_KEY_LAB_MPV_SEQUENTIAL_READ = "labMpvSequentialRead"
private val SP_LAB_MPV_SEQUENTIAL_READ_DEFAULT = true
```

#### 4.2.3 在 `setInitOptions()` 末尾追加配置块

在 `demuxer-max-back-bytes` 之后、函数闭合 `}` 之前(约 `:1054` 后)追加:

```kotlin
// MPV 顺序读优化：禁用 ffmpeg mov demuxer 的 interleaved_read，避免 badly-interleaved
// MP4 over HTTP 触发 seek 风暴。跨模块读 dy-player 写入的 labMpvSequentialRead。
val mpvSequentialRead = try {
    PreferenceManager.getDefaultSharedPreferences(appContext)
        .getBoolean(SP_KEY_LAB_MPV_SEQUENTIAL_READ, SP_LAB_MPV_SEQUENTIAL_READ_DEFAULT)
} catch (e: Exception) {
    L.e("Failed to read mpv sequential read preference: ${e.message}")
    SP_LAB_MPV_SEQUENTIAL_READ_DEFAULT
}
if (mpvSequentialRead) {
    mpv.setOptionString("demuxer-lavf-o", "interleaved_read=0")
}
```

⚠️ 注意 `L.e(...)` 是单参数 API(`xyz.doikki.videoplayer.util.L`),用字符串模板拼接,不能传两个参数。

### 4.3 `dy-player/src/main/res/values/strings.xml`

在 `hint_lab_mpv_special_render_desc`(`:116`)之后新增:

```xml
<string name="hint_lab_mpv_sequential_read">MPV 顺序读优化</string>
<string name="hint_lab_mpv_sequential_read_desc">禁用 ffmpeg 对 MP4 的 demuxer-level 交错读取,缓解结构不良的远程视频拖动卡顿</string>
```

### 4.4 `dy-player/src/main/res/layout/fragment_lab_setting.xml`

在 `lab_mpv_special_render` 行闭合(`:160`)之后、`debug_mode` 行开始(`:162`)之前,插入新行(镜像 `lab_mpv_special_render` 结构 `:119-160`):

```xml
<androidx.constraintlayout.widget.ConstraintLayout
    android:id="@+id/lab_mpv_sequential_read"
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
        android:id="@+id/tv_lab_mpv_sequential_read"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/hint_lab_mpv_sequential_read"
        android:textColor="?android:attr/textColorPrimary"
        android:textSize="18sp"
        app:layout_constraintBottom_toTopOf="@id/tv_lab_mpv_sequential_read_desc"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />

    <TextView
        android:id="@+id/tv_lab_mpv_sequential_read_desc"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="12sp"
        android:text="@string/hint_lab_mpv_sequential_read_desc"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/tv_lab_mpv_sequential_read" />

    <androidx.appcompat.widget.SwitchCompat
        android:id="@+id/sw_lab_mpv_sequential_read"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        tools:ignore="MissingConstraints" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

同时把 `debug_mode` 行的 `app:layout_constraintTop_toBottomOf="@+id/lab_mpv_special_render"`(约 `:172`)改为 `app:layout_constraintTop_toBottomOf="@+id/lab_mpv_sequential_read"`。

### 4.5 `dy-player/src/main/java/me/lingci/dy/player/ui/tool/LabSettingsFragment.kt`

在 `init()` 的 `swLabMpvSpecialRender` 块(`:49-52`)之后、`swDebugMode` 块(`:53-56`)之前插入:

```kotlin
binding.swLabMpvSequentialRead.isChecked = spUtil.labMpvSequentialRead
binding.swLabMpvSequentialRead.setOnClickListener {
    spUtil.labMpvSequentialRead = binding.swLabMpvSequentialRead.isChecked
}
```

## 5. 风险与回归验证

### 风险

- **本地文件**:几乎无影响(本地 seek 快)。
- **好 interleaving MP4 文件**:理论上可能极轻微影响初始解码预读,实测无可感知差异。
- **非 MP4 文件**:`interleaved_read` 是 MOV demuxer 选项,对 mkv/ts 等无效,无副作用。
- **Exo 后端**:完全不受影响。
- **老用户**:默认开 → 自动获益;如某文件出现副作用,可手动关闭。
- **跨模块 key 失联**:通过常量 + 注释文档化,重命名需同步两端。

### 验证清单(手动)

1. **核心修复**:MPV + 卡顿过的 WebDav 视频拖动进度条,不再卡顿(已实测确认)。
2. **MPV + 本地 MP4**:播放/seek 正常,无副作用(回归)。
3. **MPV + 好 interleaving 的 WebDav MP4**:正常播放,无副作用(回归)。
4. **Exo + WebDav**:行为不变(回归,确认没破坏 Exo 路径)。
5. **关闭开关**:在实验室关闭「MPV 顺序读优化」→ 重新开始播放 → 卡顿 WebDav 视频恢复卡顿(确认开关生效、可关)。
6. **开关切换时机**:切换开关后需重新开始播放才生效(与现有 `useOkhttp` 一致)。

## 6. 影响面

修改 5 个文件(同上次方案):

- `dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt`(+1 属性)
- `lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt`(+1 import、+2 常量、+配置块)
- `dy-player/src/main/res/values/strings.xml`(+2 string)
- `dy-player/src/main/res/layout/fragment_lab_setting.xml`(+1 行、改 1 行约束)
- `dy-player/src/main/java/me/lingci/dy/player/ui/tool/LabSettingsFragment.kt`(+4 行)

不修改 Exo 后端、`MpvMediaSourceHelper.kt`、`MpvMediaPlayerFactory.kt`、`DyPlayerCoreRegistry.kt`、`LongVideoActivity.kt`、`ShortVideoActivity.kt`。
不涉及持久化数据迁移。
不修改 `targetSdk`(仍 28)。

## 7. 实现已完成的部分

修复方案已经在调研阶段通过最小化实验验证过(`interleaved_read=0` 硬编码 + MPV log-file 抓取),实测有效。本次 spec 是把验证过的修复**包成 lab 开关**,加入用户可操作的 UI。
