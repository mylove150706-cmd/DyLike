# DyLike 开发者指南

本文面向需要继续开发 `DyLike` / `dy-player` 的开发者，默认以 `dy-player` 作为主要应用入口。  

## 仓库结构

```text
DyLike/
├─ dy-player/                  # 主入口应用模块
├─ lib-base/                   # 基础能力
├─ lib-player/
│  ├─ dkplayer-java/           # 播放器核心
│  ├─ player-exo/              # Exo / Media3 实现
│  ├─ player-mpv/              # MPV 实现
│  └─ player-ui/               # 播放器 UI 与控制组件，不承载具体内核实现
├─ lib-dm/
│  ├─ dm-view/                 # 弹幕显示
├─ lib-common/                 # 通用能力模块
```

## 开发入口建议

如果你是第一次接触这个仓库，建议按下面顺序阅读：  

1. `dy-player`  
2. `lib-player/player-ui`  
3. `lib-player/dkplayer-java`  
4. `lib-player/player-exo`  
5. `lib-player/player-mpv`  
6. `lib-dm/dm-view`  
7. `lib-base`  

## 关键入口

- 应用主入口：`dy-player/src/main/java/me/lingci/dy/player/ui/main/MainActivity.kt`  
- 长视频播放页：`dy-player/src/main/java/me/lingci/dy/player/ui/long_video/LongVideoActivity.kt`  
- 短视频播放页：`dy-player/src/main/java/me/lingci/dy/player/ui/short_video/ShortVideoActivity.kt`  
- 媒体库首页：`dy-player/src/main/java/me/lingci/dy/player/ui/media/MediaFragment.kt`  
- 资源库首页：`dy-player/src/main/java/me/lingci/dy/player/ui/source/SourceFragment.kt`  
- 设置首页：`dy-player/src/main/java/me/lingci/dy/player/ui/tool/SettingFragment.kt`  

## 关键能力分布

- `dy-player`：负责应用入口、页面组织、媒体库与资源库、播放页业务逻辑、设置与备份恢复  
- `lib-player/dkplayer-java`：提供播放器核心抽象层与控制逻辑基础  
- `lib-player/player-ui`：提供播放器 UI、控制组件、长短视频场景下的交互能力，通过通用 optional interfaces 消费内核能力  
- `lib-player/player-exo`：提供 Exo / Media3 播放能力实现  
- `lib-player/player-mpv`：提供 MPV 播放能力实现  
- `lib-dm/dm-view`：提供弹幕渲染、解析与相关显示能力  
- `lib-base`：提供基础 UI、工具类、文件选择、网络请求和存储封装等基础设施  

## 开发环境

- Android Studio Panda `2025.3.3` 或兼容版本  
- JDK `17`  
- Android SDK `36`  
- Kotlin `2.2.20`  
- Gradle `9.3.1`  
- Android Gradle Plugin `9.1.0`  
- `compileSdk = 36`  
- `minSdk = 24`  
- `targetSdk = 28`  

当前 `dy-player` 模块版本信息：  

- `applicationId`: `me.lingci.dy.player`  
- `versionName`: `0.2.4`  
- `versionCode`: `24`  

## 构建说明

推荐直接针对 `dy-player` 模块构建。  

当前仓库中应用 flavor 为：  

- `prod`  
- `bate`  

说明：这里保持仓库现有命名，不额外改动。  

### 构建调试包

```bash
./gradlew :dy-player:assembleBateDebug
```

### 安装调试包

```bash
./gradlew :dy-player:installBateDebug
```

### 构建正式包

```bash
./gradlew :dy-player:assembleProdRelease
```

### 运行单元测试

```bash
./gradlew :dy-player:test
```

## 运行与调试建议

- 默认从 `dy-player` 模块启动  
- 需要重点验证长视频模式和短视频模式的差异行为  
- 涉及播放器修改时，至少验证本地视频、串流链接和 WebDav 入口  
- 涉及弹幕修改时，优先验证长视频页中的加载、样式和同步逻辑  
- 涉及设置项修改时，注意 `SpUtil` / `SpBase` 持久化配置对首页、播放页和工具页的联动影响  
- 涉及媒体库相关逻辑时，注意历史记录、收藏和媒体详情页是否被同步更新  
- 涉及数据迁移时，注意旧媒体库数据可能尚未绑定具体资源源  

## 已知开发注意点

- `dy-player` 已有 Room 的实体、DAO、数据库管理代码，但媒体库、资源库、播放设置、弹幕设置和部分播放状态仍大量依赖 `SpUtil` / `SpBase` / `SharedPreferences`，Room 迁移尚未完全收口。  
- 旧媒体库数据可能存在未绑定具体资源源的兼容提示，继续改媒体库逻辑时需要保留迁移和兼容路径。  
- `lib-base/src/main/java/me/lingci/lib/base/storage/impl/SmbStorage.kt` 仍存在未实现的 TODO，SMB 存储能力不能按完整入口对外描述。  
- 部分关于页文案、协议展示、截图和新手引导仍待完善。  
- Release 构建默认聚焦 `arm64-v8a`。  
- Debug 构建额外包含 `x86_64`。  

## 适合继续扩展的方向

- 播放内核切换与稳定性优化  
- 长短视频模式的体验统一  
- WebDav 浏览与缓存能力增强  
- 弹幕下载、管理与匹配链路增强  
- 字幕体验与轨道支持增强  
- 媒体库封面、搜索、筛选和排序优化  
- 文档、截图和新手引导补齐  
- Room 数据层迁移和旧数据兼容收口  

## 给后续开发者

- 更新功能说明时，同步维护 `README.md`、`docs/user-guide.md` 和 `docs/developer-guide.md`。  
- 如果某项能力已经完成，请及时从“当前欠缺”或“已知开发注意点”中移除，避免文档误导。  
- 如果新增截图，请放入 `docs/screenshots/`，并同步更新 README 和用户指南中的截图说明。  
- 如果完成 Room 迁移，请明确说明哪些数据仍保留在 `SharedPreferences`，哪些已经进入 Room。  

## 参与开发

这个项目非常欢迎更多开发者一起参与完善。  

如果你对以下方向感兴趣，欢迎加入：  

- Android 播放器相关能力开发  
- 弹幕系统与字幕系统优化  
- 媒体库 / 资源库体验改进  
- WebDav、串流与存储接入  
- UI / UX 优化  
- 文档补充、截图整理、问题排查  
- Bug 修复与性能优化  

欢迎通过 `Issue`、`Pull Request` 或其他协作方式参与开发。    
无论你是想修一个小问题、补一段文档、完善一个页面，还是推进一项完整功能，这些贡献都很重要。  

期待广大开发者一起把 `DyLike` 打磨成一个更稳定、更好用的 Android 播放器项目。  

## 鸣谢

- [DKVideoPlayer](https://github.com/Doikki/DKVideoPlayer)  
- [DanDanPlayForAndroid](https://github.com/xyoye/DanDanPlayForAndroid)  

## 返回

- [返回 README](../README.md)  
