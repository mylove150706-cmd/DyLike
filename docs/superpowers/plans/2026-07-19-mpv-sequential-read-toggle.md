# MPV 顺序读优化开关 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在「实验室」设置页添加「MPV 顺序读优化」开关,开启后 MPV 在 init 时设 `demuxer-lavf-o=interleaved_read=0`,修复 badly-interleaved MP4 over HTTP 的 seek 风暴卡顿(实测 seek 次数从 3697/150s 降到 4)。

**Architecture:** 跨模块读 SharedPreferences 方案 —— `dy-player.SpUtil` 新增 `labMpvSequentialRead` 属性(写入 app 默认 SharedPreferences);`lib-player/player-mpv/MpvMediaPlayer` 在 `setInitOptions()` 里直接用 `PreferenceManager.getDefaultSharedPreferences(appContext)` 读这个 key(因为 `player-mpv` 不能依赖 `dy-player`),开启时设置 `demuxer-lavf-o=interleaved_read=0`。实验室页加一个 SwitchCompat 行镜像现有的「MPV 专用 Render」开关。

**Tech Stack:** Kotlin, Android, MPV (libplayer), ViewBinding, SharedPreferences

## Global Constraints

- 不修改 Exo 后端(只影响 MPV)。
- 不重新引入之前撤销的 cache 选项(`cache=yes` 等)。
- 不动 `MpvMediaSourceHelper.kt`(保留死代码,不扩散)。
- 不改 `MpvMediaPlayerFactory.kt`、`DyPlayerCoreRegistry.kt`、`LongVideoActivity.kt`、`ShortVideoActivity.kt`。
- 跨模块 key 约定:`SpUtil.labMpvSequentialRead` 属性名 ↔ `MpvMediaPlayer.SP_KEY_LAB_MPV_SEQUENTIAL_READ` 常量值,都是 `"labMpvSequentialRead"`;两端默认值都是 `true`。重命名需同步。
- 注释/字符串保持简体中文(遵循 AGENTS.md)。
- 不引入新依赖。
- 不修改 `targetSdk`(仍 28)。
- Gradle 用 wrapper:`./gradlew`(禁止系统 gradle)。JDK 17 + Android SDK 已在本机配置(`JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot"`、`ANDROID_HOME="/c/Android"`)。
- 仓库无测试目录,验证以编译 + 手动为主。每个代码 task 用相应模块的 `compileXxxKotlin` 验证编译。
- ⚠️ `xyz.doikki.videoplayer.util.L.e(msg: String)` 是**单参数** API,调用时用字符串模板拼接,不要传两个参数。

## File Structure

| 文件 | 改动类型 | 职责 |
|------|---------|------|
| `dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt` | 修改(+1 属性) | 持久化 `labMpvSequentialRead` 布尔值 |
| `lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt` | 修改(+1 import、+2 常量、+配置块) | MPV init 时读 SP 并设置 `interleaved_read=0` |
| `dy-player/src/main/res/values/strings.xml` | 修改(+2 string) | UI 文案 |
| `dy-player/src/main/res/layout/fragment_lab_setting.xml` | 修改(+1 行 + 改 1 行约束) | 实验室页 UI 行 |
| `dy-player/src/main/java/me/lingci/dy/player/ui/tool/LabSettingsFragment.kt` | 修改(+4 行) | SwitchCompat 与 SpUtil 双向绑定 |

无新增文件、无测试文件。

**Task 切分逻辑**:按依赖顺序,从底层到 UI。Task 1(MPV 配置核心)是最小可测单元。Task 2(SpUtil)+ Task 3(UI)让开关可被用户操作。Task 4 是端到端验证。

---

## Task 1: MPV 启用 `interleaved_read=0`

**Files:**
- Modify: `lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt`

**Interfaces:**
- Consumes: app 默认 SharedPreferences 的 `labMpvSequentialRead` key(由 Task 2 写入;本 task 先用默认值 `true` 也能工作)。
- Produces: 无新对外接口。`setInitOptions()` 在 MPV init 前设置 `demuxer-lavf-o=interleaved_read=0`。

- [ ] **Step 1: 新增 `PreferenceManager` 导入**

打开 `lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt`。检查文件顶部 import 区是否已有 `import android.preference.PreferenceManager`。若没有,新增一行:

```kotlin
import android.preference.PreferenceManager
```

放置位置:和其他 `import android.*` 在一起,保持字母序(约在 `import android.os.Build` 附近)。

- [ ] **Step 2: 新增跨模块 key 常量**

找到 `cacheSize` 变量定义(约 `:127-128`):

```kotlin
    // 缓存大小（MB）
    private var cacheSize: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) 64 else 32
```

在其**之后**插入两个常量:

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

- [ ] **Step 3: 在 `setInitOptions()` 末尾追加配置块**

找到 `setInitOptions()` 函数末尾(约 `:1037-1055`),定位到这两行:

```kotlin
        // 缓存大小
        val bytes = cacheSize * 1024 * 1024L
        mpv.setOptionString("demuxer-max-bytes", bytes.toString())
        mpv.setOptionString("demuxer-max-back-bytes", bytes.toString())
    }
```

在 `mpv.setOptionString("demuxer-max-back-bytes", bytes.toString())` 之后、函数闭合 `}` 之前插入:

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

⚠️ 注意:`L.e(...)` 是**单参数** API,必须用字符串模板拼接成一个参数(`"msg: ${e.message}"`),不要写成 `L.e("msg", e.message)`(会编译失败)。

- [ ] **Step 4: 编译验证**

Run(注意路径是 `:lib-player:player-mpv` 模块):

```bash
./gradlew :lib-player:player-mpv:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL。如果失败:
- `PreferenceManager` unresolved → 检查 Step 1 import。
- `L.e` 报 "Too many arguments" → Step 3 的 catch 块写成了两参数,改回单参数字符串模板。
- `appContext` unresolved → 它是类成员(构造时传入),不应报错;若报错检查是否误删了别的代码。

环境变量(本机已配置,subagent 可能需要 export):

```bash
export JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot"
export ANDROID_HOME="/c/Android"
export PATH="$JAVA_HOME/bin:$PATH"
```

- [ ] **Step 5: Commit**

```bash
git add lib-player/player-mpv/src/main/java/me/lingci/lib/player/mpv/MpvMediaPlayer.kt
git commit -m "feat(mpv): set interleaved_read=0 behind labMpvSequentialRead toggle"
```

---

## Task 2: SpUtil 新增 `labMpvSequentialRead` 属性

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt`

**Interfaces:**
- Consumes: `SPManager.boolean(defaultValue)` 委托工厂(来自 `SpBase`)。
- Produces: `SpUtil.labMpvSequentialRead: Boolean`(属性名 = SP key = `"labMpvSequentialRead"`,默认 true)。供 Task 3 的 `LabSettingsFragment` 读写;被 Task 1 的 `MpvMediaPlayer` 跨模块按字符串 key 读取。

- [ ] **Step 1: 在 `labMpvSpecialRender` 后新增属性**

打开 `dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt`。找到(约 `:173`):

```kotlin
    var labMpvSpecialRender by SPManager.boolean(true)
```

在其**之后**插入:

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

- [ ] **Step 2: 编译验证**

Run:

```bash
./gradlew :dy-player:compileBetaDebugKotlin
```

Expected: BUILD SUCCESSFUL。`SPManager.boolean(true)` 是 `SpBase` 的标准用法(参见同文件 `:173` 的 `labMpvSpecialRender`),不应报错。

- [ ] **Step 3: Commit**

```bash
git add dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt
git commit -m "feat(settings): add labMpvSequentialRead preference"
```

---

## Task 3: 实验室页 UI 开关

**Files:**
- Modify: `dy-player/src/main/res/values/strings.xml`
- Modify: `dy-player/src/main/res/layout/fragment_lab_setting.xml`
- Modify: `dy-player/src/main/java/me/lingci/dy/player/ui/tool/LabSettingsFragment.kt`

**Interfaces:**
- Consumes: `SpUtil.labMpvSequentialRead`(Task 2 新增)。
- Produces: 用户可操作的 SwitchCompat,写入 `SpUtil.labMpvSequentialRead`,由 `MpvMediaPlayer`(Task 1)下次播放时读取生效。

- [ ] **Step 1: 新增字符串资源**

打开 `dy-player/src/main/res/values/strings.xml`。找到(约 `:115-116`):

```xml
    <string name="hint_lab_mpv_special_render">MPV 专用 Render</string>
    <string name="hint_lab_mpv_special_render_desc">专用Render无闪屏，关闭后同步支持Texture</string>
```

在其**之后**插入:

```xml
    <string name="hint_lab_mpv_sequential_read">MPV 顺序读优化</string>
    <string name="hint_lab_mpv_sequential_read_desc">禁用 ffmpeg 对 MP4 的 demuxer-level 交错读取,缓解结构不良的远程视频拖动卡顿</string>
```

- [ ] **Step 2: 在布局中新增 lab_mpv_sequential_read 行**

打开 `dy-player/src/main/res/layout/fragment_lab_setting.xml`。找到 `lab_mpv_special_render` 行的闭合(约 `:160`)和 `debug_mode` 行的开始(约 `:162`):

```xml
            </androidx.constraintlayout.widget.ConstraintLayout>

            <androidx.constraintlayout.widget.ConstraintLayout
                android:id="@+id/debug_mode"
```

在 `lab_mpv_special_render` 的 `</androidx.constraintlayout.widget.ConstraintLayout>` 之后、`<androidx.constraintlayout.widget.ConstraintLayout android:id="@+id/debug_mode"` 之前,插入新行:

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

- [ ] **Step 3: 修改 debug_mode 行的约束指向新行**

在同一个 `fragment_lab_setting.xml` 文件里,找到 `debug_mode` 行的约束(约 `:172`):

```xml
                app:layout_constraintTop_toBottomOf="@+id/lab_mpv_special_render">
```

改为:

```xml
                app:layout_constraintTop_toBottomOf="@+id/lab_mpv_sequential_read">
```

这是唯一一处需要改的约束 —— `debug_mode` 之前挂在 `lab_mpv_special_render` 下面,现在挂到新插入的 `lab_mpv_sequential_read` 下面。其余行(`lab_long_video_portrait` 等)的约束链不受影响。

- [ ] **Step 4: 在 `LabSettingsFragment.init()` 中绑定开关**

打开 `dy-player/src/main/java/me/lingci/dy/player/ui/tool/LabSettingsFragment.kt`。找到(约 `:49-52`):

```kotlin
        binding.swLabMpvSpecialRender.isChecked = spUtil.labMpvSpecialRender
        binding.swLabMpvSpecialRender.setOnClickListener {
            spUtil.labMpvSpecialRender = binding.swLabMpvSpecialRender.isChecked
        }
```

在其**之后**、`binding.swDebugMode` 块(约 `:53`)之前插入:

```kotlin
        binding.swLabMpvSequentialRead.isChecked = spUtil.labMpvSequentialRead
        binding.swLabMpvSequentialRead.setOnClickListener {
            spUtil.labMpvSequentialRead = binding.swLabMpvSequentialRead.isChecked
        }
```

ViewBinding 会根据 XML 的 `sw_lab_mpv_sequential_read` id 自动生成 `binding.swLabMpvSequentialRead`(编译时检查)。

- [ ] **Step 5: 编译验证**

Run:

```bash
./gradlew :dy-player:assembleBetaDebug
```

Expected: BUILD SUCCESSFUL。完整 assemble 而非仅 compile,是为了让 ViewBinding 和资源处理都跑一遍,确保 XML id ↔ binding 名称匹配、字符串资源无冲突。

如果失败:
- `binding.swLabMpvSequentialRead` unresolved → ViewBinding 没重新生成,先 `./gradlew clean` 再 assemble;或检查 XML 里 id 是否拼成 `sw_lab_mpv_sequential_read`。
- 资源引用错误 → 检查 `@string/hint_lab_mpv_sequential_read` 是否在 strings.xml 里。

- [ ] **Step 6: Commit**

```bash
git add dy-player/src/main/res/values/strings.xml dy-player/src/main/res/layout/fragment_lab_setting.xml dy-player/src/main/java/me/lingci/dy/player/ui/tool/LabSettingsFragment.kt
git commit -m "feat(lab): add MPV sequential read toggle UI"
```

---

## Task 4: 端到端手动验证

**Files:**
- 无文件改动。

**Interfaces:**
- 无。

- [ ] **Step 1: 构建并安装 beta debug APK**

Run:

```bash
./gradlew :dy-player:installBetaDebug
```

Expected: BUILD SUCCESSFUL,APK 安装到连接的设备(applicationId `me.lingci.dy.player.debug`)。

如果未连接设备,退而求其次 `./gradlew :dy-player:assembleBetaDebug` 生成 APK 手动安装。APK 路径:`dy-player/build/outputs/apk/beta/debug/dy-player-beta-universal-debug.apk` 或 `DyLike-arm64-v8a-v0.2.4-d-debug.apk`。

- [ ] **Step 2: 验证开关 UI 存在且默认开启**

1. 打开 app → 设置 → 实验室。
2. 找到「MPV 顺序读优化」开关(应在「MPV 专用 Render」和「Debug 模式」之间)。
3. 确认开关默认是**开启**状态。
4. 确认标题是「MPV 顺序读优化」,描述是「禁用 ffmpeg 对 MP4 的 demuxer-level 交错读取,缓解结构不良的远程视频拖动卡顿」。

**Expected**:开关存在、位置正确、默认开。

- [ ] **Step 3: 核心修复验证 —— MPV + 卡顿过的 WebDav 视频**

前置:确保播放器内核设置为 MPV(设置 → 播放器 → 短视频切换播放器内核 = MPV)。

1. 媒体库 → 选择 WebDav 来源 → 进入之前卡顿过的视频文件夹。
2. 短视频模式播放该视频。
3. 等正常播几秒。
4. 拖动进度条到中段。
5. 松手后观察。

**Expected**:视频在新位置继续播放,**不再出现「播放几秒→卡几秒→播放几秒→卡几秒」的循环**。

(本步骤在调研阶段已经实测验证过,seek 次数从 3697/150s 降到 4。)

- [ ] **Step 4: 回归 —— MPV + 本地 MP4**

1. 媒体库 → 本地存储 → MPV 模式播放一个本地 MP4。
2. 拖动进度条。

**Expected**:播放/seek 行为正常,无副作用。

- [ ] **Step 5: 回归 —— MPV + 好 interleaving 的 WebDav MP4**

1. 媒体库 → WebDav 来源 → 播放另一个(之前不卡的)WebDav MP4 视频。
2. 拖动进度条。

**Expected**:行为与修复前一致,无副作用。

- [ ] **Step 6: 回归 —— Exo + WebDav**

前置:切换播放器内核为 Exo。

1. 媒体库 → WebDav 来源 → 播放卡顿过的 WebDav 视频。
2. 拖动进度条。

**Expected**:行为不变(本次改动完全不碰 Exo 路径)。

- [ ] **Step 7: 开关切换验证 —— 关闭后恢复原行为**

1. 设置 → 实验室 → 关闭「MPV 顺序读优化」。
2. **重新开始**播放卡顿过的 WebDav 视频(关掉当前播放再重新进,init-time-only 选项必须新一次播放才生效)。
3. MPV + WebDav 拖动进度条。

**Expected**:恢复原有「播放几秒卡几秒」的循环卡顿行为(确认开关真的生效、可关)。

- [ ] **Step 8: 开关切换时机验证**

1. 在 MPV + WebDav 播放过程中,去设置切换「MPV 顺序读优化」开关(任意方向)。
2. 不重新开始播放,直接观察当前视频。

**Expected**:当前视频行为**不变**(init-time-only 选项,需下次播放生效)。与现有 `useOkhttp` 等设置一致。

- [ ] **Step 9: 重新开启验证**

1. 设置 → 实验室 → 重新打开「MPV 顺序读优化」。
2. 重新开始播放 WebDav 视频。
3. 拖动进度条。

**Expected**:流畅,不再循环卡顿(确认开↔关↔开三态都正确)。

- [ ] **Step 10: 最终提交(如验证中发现需修复的问题)**

如果 Task 4 Step 1-9 中发现 bug 并修复了,按修复点单独 commit:

```bash
git add <files>
git commit -m "fix(mpv-sequential-read): <具体修复>"
```

如果一切顺利、无需额外改动,则本步无操作 —— 前面 Task 1-3 已 commit 完毕。

---

## Self-Review(plan 作者自查记录)

**1. Spec coverage**(对照 spec 各章节):
- spec §4.1(SpUtil 属性)→ Task 2 ✅
- spec §4.2(MpvMediaPlayer:import + 常量 + 配置块)→ Task 1 Step 1/2/3 ✅
- spec §4.3(strings.xml)→ Task 3 Step 1 ✅
- spec §4.4(fragment_lab_setting.xml 新行 + 改 debug_mode 约束)→ Task 3 Step 2/3 ✅
- spec §4.5(LabSettingsFragment 绑定)→ Task 3 Step 4 ✅
- spec §5(验证清单:MPV+卡顿 WebDav、MPV+本地、MPV+好 interleaving、Exo+WebDav、关闭开关、切换时机)→ Task 4 Step 3-9 全覆盖 ✅
- spec §6(影响面:5 个文件)→ File Structure 表与之一致 ✅
- spec §3.1(默认开、实验室页、单总开关、MPV 专用 Render 后)→ 全部体现在 Task 3 ✅

**2. Placeholder scan**:无 TBD/TODO/「类似 Task N」/省略代码块。所有改动都有完整 before/after 代码或完整新增代码。Task 1 Step 3 还**特别警告**了 `L.e` 单参数陷阱(上次踩过坑)。

**3. Type consistency**:
- 跨模块 key 约定 `"labMpvSequentialRead"`:Task 1 Step 2(`SP_KEY_LAB_MPV_SEQUENTIAL_READ = "labMpvSequentialRead"`)↔ Task 2(`var labMpvSequentialRead`,属性名自动成为 SP key)↔ Global Constraints(明确记录约定)✅
- 默认值 `true`:Task 1 Step 2(`SP_LAB_MPV_SEQUENTIAL_READ_DEFAULT = true`)↔ Task 2(`SPManager.boolean(true)`)↔ Global Constraints ✅
- ViewBinding id:Task 3 Step 2 XML(`sw_lab_mpv_sequential_read`)↔ Task 3 Step 4 Kotlin(`binding.swLabMpvSequentialRead`)—— 驼峰转换正确 ✅
- 字符串资源:Task 3 Step 1(`hint_lab_mpv_sequential_read` / `hint_lab_mpv_sequential_read_desc`)↔ Task 3 Step 2 XML 引用(`@string/hint_lab_mpv_sequential_read` 等)✅
- 布局 id:Task 3 Step 2(`lab_mpv_sequential_read`)↔ Task 3 Step 3(`debug_mode` 约束改指向 `@+id/lab_mpv_sequential_read`)✅
- MPV 选项值:Task 1 Step 3(`demuxer-lavf-o=interleaved_read=0`)↔ spec §4.2.3 一致 ✅

**4. 编译验证覆盖**:Task 1 用 `:lib-player:player-mpv:compileDebugKotlin`(单独模块);Task 2 用 `:dy-player:compileBetaDebugKotlin`;Task 3 用 `:dy-player:assembleBetaDebug`(完整 assemble 确保 ViewBinding + 资源都跑过)。三个不同命令匹配各自模块和验证需求 ✅

**5. 与上次(被撤销方案)的差异**:仅 Task 1 Step 3 的具体 MPV 选项不同(从 5 个 cache 选项 → 1 个 `interleaved_read=0`),Task 2/3/4 完全相同的模式(key/label/描述/路径都对换)。结构验证过,稳定 ✅
