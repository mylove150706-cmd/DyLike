package me.lingci.dy.player.ui.long_video

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.databinding.ActivityLongVideoBinding
import me.lingci.dy.player.core.DyPlayerCore
import me.lingci.dy.player.core.DyPlayerCoreRegistry
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.ExternalSubtitleLoader
import me.lingci.dy.player.util.MediaPlaybackRecorder
import me.lingci.dy.player.util.PlaybackTraceHelper
import me.lingci.dy.player.util.PlaybackLogCache
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.util.VideoListTempStore
import me.lingci.dy.player.view.LongVideoControlView
import me.lingci.lib.base.entity.TitleItem
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.ui.BaseActivity
import me.lingci.lib.base.util.AppFile
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.base.util.logD
import me.lingci.lib.base.util.safeGetParcelable
import me.lingci.lib.player.danmaku.PlayerInitializer
import me.lingci.lib.player.exo.CustomExoMediaPlayer
import me.lingci.lib.player.listener.OnFontChangeListener
import me.lingci.lib.player.listener.OnLongVideoListener
import me.lingci.lib.player.listener.OnPlayNextListener
import me.lingci.lib.player.render.SurfaceRenderViewFactory
import me.lingci.lib.player.widget.component.DanmakuControlView
import me.lingci.lib.player.widget.component.DmConfControlView
import me.lingci.lib.player.widget.component.DmListControlView
import me.lingci.lib.player.widget.component.DmSelectControlView
import me.lingci.lib.player.widget.component.DmTrackControlView
import me.lingci.lib.player.widget.component.EpSelectControlView
import me.lingci.lib.player.widget.component.MediaInfoControlView
import me.lingci.lib.player.widget.component.SpeedControlView
import me.lingci.lib.player.widget.component.SubtitleControlView
import me.lingci.lib.player.widget.component.TrackPanelControlView
import me.lingci.lib.player.widget.component.VideoScaleControlView
import me.lingci.lib.player.widget.videoview.CustomVideoView
import xyz.doikki.videocontroller.StandardVideoController
import xyz.doikki.videocontroller.component.CompleteView
import xyz.doikki.videocontroller.component.GestureView
import me.lingci.lib.player.capability.PlayerCapabilities
import me.lingci.lib.player.capability.PlayerCapabilityProvider
import me.lingci.lib.player.mediainfo.MediaInfoProviderOwner
import me.lingci.lib.player.subtitle.SubtitleCueProvider
import me.lingci.lib.player.track.ExternalTrackController
import me.lingci.lib.player.track.MediaTrackController
import me.lingci.lib.player.track.MediaTrackProvider
import me.lingci.lib.player.track.MediaTrackSnapshot
import xyz.doikki.videoplayer.player.BaseVideoView.OnStateChangeListener
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.render.TextureRenderViewFactory
import xyz.doikki.videoplayer.util.PlayerUtils
import java.io.File

/**
 * 长视频播放页
 */
class LongVideoActivity : BaseActivity(), OnLongVideoListener, OnPlayNextListener, OnFontChangeListener {

    companion object {
        private const val KEY_BUNDLE = "bundle"
        private const val KEY_MEDIA = "media"
        private const val KEY_INDEX = "index"
        private const val KEY_HISTORY = "history"
        private const val KEY_TEMP = "temp"
        private const val BACKGROUND_RECOVERY_WINDOW_MS = 5000L

        fun start(context: Context, list: ArrayList<VideoData>, index: Int, history: Boolean) {
            val bundle = bundleOf()
            bundle.putInt(KEY_INDEX, index)
            bundle.putBoolean(KEY_HISTORY, history)
            start(context, bundle, list)
        }

        fun start(
            context: Context, mediaData: MediaData?, list: ArrayList<VideoData>,
            index: Int, history: Boolean
        ) {
            val bundle = bundleOf()
            bundle.putInt(KEY_INDEX, index)
            bundle.putBoolean(KEY_HISTORY, history)
            if (mediaData != null && !history) {
                bundle.putParcelable(KEY_MEDIA, mediaData)
            }
            start(context, bundle, list)
        }

        private fun start(context: Context, bundle: Bundle, list: ArrayList<VideoData>) {
            bundle.putString(KEY_TEMP, VideoListTempStore.write(context, list))
            val intent = Intent(context, LongVideoActivity::class.java)
            intent.putExtra(KEY_BUNDLE, bundle)
            context.startActivity(intent)
        }
    }

    /**
     * 当前播放位置
     */
    private var mCurPos = 0
    private val spUtil by lazy { SpUtil(this) }
    private var onHistory: Boolean = false
    private val itemViewModel: LongVideoViewModel by viewModels()
    private lateinit var binding: ActivityLongVideoBinding
    private lateinit var videoView: CustomVideoView
    private lateinit var videoController: StandardVideoController
    private lateinit var longVideoControlView: LongVideoControlView
    private lateinit var danmakuView: DanmakuControlView
    private lateinit var dmSelectView: DmSelectControlView
    private lateinit var dmConfView: DmConfControlView
    private lateinit var videoScaleControlView: VideoScaleControlView
    private lateinit var dmTrackView: DmTrackControlView
    private lateinit var dmListView: DmListControlView
    private lateinit var epSelectView: EpSelectControlView
    private lateinit var subtitleControlView: SubtitleControlView
    private lateinit var mTrackPanelControlView: TrackPanelControlView
    private lateinit var speedControlView: SpeedControlView
    private lateinit var mediaInfoControlView: MediaInfoControlView
    private val playbackLogCache = PlaybackLogCache()
    // 只负责调试态起播黑屏诊断，后台恢复策略仍留在 Activity 内统一编排。
    private val blackScreenWatchdog by lazy {
        BlackScreenWatchdog(
            context = this,
            scope = lifecycleScope,
            videoView = videoView,
            playbackLogCache = playbackLogCache,
            currentPosition = { mCurPos },
            showSubTips = { longVideoControlView.showSubTips(it) },
            logAndCache = ::logAndCache
        )
    }
    private var backgroundRecoveryJob: Job? = null
    private val mediaPlaybackRecorder by lazy { MediaPlaybackRecorder(spUtil) }
    // 轨道能力查询仍依赖当前 videoView，面板交互和字幕 cue 开关交给 controller。
    private val longTrackPanelController by lazy {
        LongTrackPanelController(
            spUtil = spUtil,
            trackPanelControlView = mTrackPanelControlView,
            longVideoControlView = longVideoControlView,
            subtitleControlView = subtitleControlView,
            getCapabilities = ::getPlayerCapabilities,
            getMediaTrackController = ::getMediaTrackController,
            hasSubtitleCueProvider = { getSubtitleCueProvider() != null },
            addExternalSubtitle = ::addExternalSubtitle,
            attachSubtitleCueListener = ::attachSubtitleCueListener,
            clearSubtitleCueListener = ::clearSubtitleCueListener
        )
    }
    // Activity 只提供当前视频和播放页回调，弹幕文件解析/合并/恢复交给 controller。
    private val longDanmakuController by lazy {
        LongDanmakuController(
            context = this,
            scope = lifecycleScope,
            spUtil = spUtil,
            danmakuView = danmakuView,
            dmSelectView = dmSelectView,
            dmConfView = dmConfView,
            dmTrackView = dmTrackView,
            dmListView = dmListView,
            longVideoControlView = longVideoControlView,
            videoView = videoView,
            currentVideoData = {
                if (itemViewModel.getItemSize() > mCurPos) itemViewModel.getItem(mCurPos) else null
            },
            currentVideoName = {
                if (itemViewModel.getItemSize() > mCurPos) itemViewModel.getItem(mCurPos).name else ""
            },
            isMerge = itemViewModel::isMerge,
            setMergeState = itemViewModel::setMergeState
        )
    }
    private var hasPausedForBackgroundRecovery = false
    private var lastKnownPlaybackPosition = 0L

    private fun getPlayerCapabilities(): PlayerCapabilities {
        return videoView.getPlayerCapability(PlayerCapabilityProvider::class.java)
            ?.getPlayerCapabilities()
            ?: PlayerCapabilities()
    }

    private fun getMediaTrackProvider(): MediaTrackProvider? {
        // Track UI consumes common snapshots so it works for Exo and MPV without private models.
        return videoView.getPlayerCapability(MediaTrackProvider::class.java)
    }

    private fun getMediaTrackController(): MediaTrackController? {
        return videoView.getPlayerCapability(MediaTrackController::class.java)
    }

    private fun getExternalTrackController(): ExternalTrackController? {
        return videoView.getPlayerCapability(ExternalTrackController::class.java)
    }

    private fun getSubtitleCueProvider(): SubtitleCueProvider? {
        return videoView.getPlayerCapability(SubtitleCueProvider::class.java)
    }

    private fun getMediaInfoProviderOwner(): MediaInfoProviderOwner? {
        return videoView.getPlayerCapability(MediaInfoProviderOwner::class.java)
    }

    private fun logAndCache(tag: String, level: String, message: String) {
        // 统一走共享 trace helper，保证页面日志与诊断缓存格式一致。
        PlaybackTraceHelper.logAndCache(playbackLogCache, tag, level, message)
    }

    private fun setupSurfaceTrace() {
        PlaybackTraceHelper.setupSurfaceTrace(spUtil.debugMode, playbackLogCache)
    }

    private fun clearSurfaceTrace() {
        PlaybackTraceHelper.clearSurfaceTrace()
    }

    // ===== 后台恢复重试相关状态 ===== // track-id: bg-retry-20260421
    /**
     * 标记当前是否处于后台返回后的短暂恢复窗口
     * 仅在 onResume 中设为 true，恢复成功、自动重试或窗口结束后设为 false
     */
    private var isReturningFromBackground = false

    /**
     * 标记是否已经执行过后台恢复自动重试
     * 防止无限重试循环，每次进入后台只重试一次
     */
    private var hasAutoRetriedOnResume = false

    /**
     * 标记是否正在执行自动重试后的暂停
     * 用于在 STATE_PREPARED 时判断是否需要暂停
     */
    private var shouldPauseAfterAutoRetry = false
    // ================================

    private fun clearBackgroundRecoveryState() {
        isReturningFromBackground = false
        backgroundRecoveryJob?.cancel()
        backgroundRecoveryJob = null
    }

    private fun rememberPlaybackPosition(position: Long) {
        if (position > 0) {
            lastKnownPlaybackPosition = position
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLongVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        logD(intent.type, intent.action, intent.scheme, intent.data)
        init(savedInstanceState?: intent.getBundleExtra(KEY_BUNDLE))
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        fromOpen()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!isChangingConfigurations) {
            Log.d(this, "isFinishing", isFinishing)
            outState.putInt(KEY_INDEX, mCurPos)
            outState.putBoolean(KEY_HISTORY, onHistory)
            outState.putParcelable(KEY_MEDIA, itemViewModel.getMediaData())
            outState.putString(KEY_TEMP, VideoListTempStore.write(this, itemViewModel.getData()))
        }
    }

    private fun fromOpen() {
        val filepath = intent?.let { AppUtil.parsePathFromIntent(it) }
        if (filepath == null) {
            return
        }
        logD("new file", filepath)
        val list = mutableListOf<VideoData>()
        mCurPos = 0
        if (filepath.startsWith("/")) {
            FileOperator.getSortedFiles(File(filepath).parentFile!!, FileOperator.VIDEO_EXTENSIONS).let {
                it.forEach { file ->
                    if (file.path == filepath) {
                        mCurPos = list.size
                    }
                    list.add(VideoData(file))
                }
            }
        } else {
            list.add(VideoData(File(filepath)))
        }
        onHistory = true
        itemViewModel.addAll(list)
    }

    private fun init(bundle: Bundle?) {
        mCurPos = bundle?.getInt(KEY_INDEX, 0)?: 0
        itemViewModel.initData.observe(this) {
            if (it) {
                itemViewModel.getData().let { data ->
                    data.map { item -> TitleItem(title = item.name) }
                        .let { list -> epSelectView.setData(list) }
                }
                if (itemViewModel.getItemSize() > 0) {
                    startPlay(mCurPos)
                }
            }
        }
        onHistory = bundle?.getBoolean(KEY_HISTORY, false)?: false
        bundle?.getString(KEY_TEMP)?.let { path ->
            lifecycleScope.launch(Dispatchers.IO) {
                val list = VideoListTempStore.readAndDelete(path)
                withContext(Dispatchers.Main) {
                    itemViewModel.addAll(list)
                }
            }
        }
        bundle?.safeGetParcelable<MediaData>(KEY_MEDIA)?.let {
            itemViewModel.setMediaData(it)
        }
        initVideoView()
        setupSurfaceTrace()
        initVideoListener()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
                /*if (!videoView.onBackPressed()) {
                    finish()
                }*/
            }
        })
        fromOpen()
    }

    private fun initVideoView() {
        videoView = binding.videoView
        // Apply the generic render preference first. MPV is allowed to override it below because
        // MPV playback requires its own Surface-backed render view.
        applyConfiguredRenderFactory()
        DyPlayerCoreRegistry.applyCore(videoView, spUtil.dyPlayerCore, spUtil.labMpvSpecialRender)
        videoView.setOnPlayerInitializedListener { player ->
            // BaseVideoView may recreate the backend; reattach both Exo-only settings and common
            // capability listeners for every new concrete player instance.
            (player as? CustomExoMediaPlayer)?.let { exoPlayer ->
                exoPlayer.useOkhttp(spUtil.useOkhttp)
                exoPlayer.setDebugMode(spUtil.debugMode)
            }
            (player as? MediaTrackProvider)?.setOnMediaTracksChangedListener { snapshot ->
                Log.d(this, "updateTrackPanel", snapshot.audioTracks.size, snapshot.subtitleTracks.size)
                updateTrackPanel(snapshot)
            }
            updateTrackEntryByCapabilities()
            applyPreferredLanguages()
            setupMediaInfoProvider()
        }
        // 以下只能二选一，看你的需求
        videoView.setScreenScaleType(VideoView.SCREEN_SCALE_DEFAULT)
        // 音频渐入渐出配置
        videoView.setEnableAudioFade(spUtil.audioFadeEnabled)
        videoView.setAudioFadeInDuration(spUtil.audioFadeInDuration)
        videoView.setAudioFadeOutDuration(spUtil.audioFadeOutDuration)
        videoController = StandardVideoController(this)
        // 滑动控制视图
        videoController.addControlComponent(GestureView(this))
        // 竖屏手势
        videoController.setEnableInNormal(false)
        // 自动完成播放界面
        videoController.addControlComponent(CompleteView(this))
        // 剧集选择
        epSelectView = EpSelectControlView(this)
        epSelectView.setOnEpSelectListener { _, position ->
            dmTrackView.cleanData()
            startPlay(position)
        }
        videoController.addControlComponent(epSelectView)
        // 弹幕选择
        dmSelectView = DmSelectControlView(this)
        videoController.addControlComponent(dmSelectView)
        // 弹幕样式
        dmConfView = DmConfControlView(this)
        videoController.addControlComponent(dmConfView)
        // 视频样式
        videoScaleControlView = VideoScaleControlView(this)
        videoScaleControlView.setOnVideoScaleListener(videoView)
        videoScaleControlView.applyCache()
        videoController.addControlComponent(videoScaleControlView)
        // 弹幕轨道
        dmTrackView = DmTrackControlView(this)
        videoController.addControlComponent(dmTrackView)
        // 弹幕列表
        dmListView = DmListControlView(this)
        videoController.addControlComponent(dmListView)
        // 音频/字幕轨道
        mTrackPanelControlView = TrackPanelControlView(this)
        videoController.addControlComponent(mTrackPanelControlView)
        // 倍数
        speedControlView = SpeedControlView(this)
        videoController.addControlComponent(speedControlView)
        // 媒体信息
        mediaInfoControlView = MediaInfoControlView(this)
        videoController.addControlComponent(mediaInfoControlView)
        // 字幕
        subtitleControlView = SubtitleControlView(this)
        //subtitleControlView.setSubtitleAbsoluteTextSize(32f)
        videoController.addControlComponent(subtitleControlView)
        // 弹幕
        danmakuView = DanmakuControlView(this)
        videoController.addControlComponent(danmakuView)
        videoController.setTimeSyncListener { onTimeSync() }
        // 长视频控制器
        longVideoControlView = LongVideoControlView(this)
        longVideoControlView.setTitle("本地播放")
        //longVideoControlView.setFull(true)
        longVideoControlView.setOnLongVideoListener(this)
        longVideoControlView.setOnPlayNextListener(this)
        videoController.addControlComponent(longVideoControlView)
        // 弹幕默认配置集中在 controller，等长视频控制器就绪后再初始化，避免 lazy 依赖提前访问未初始化字段。
        longDanmakuController.applyInitialSettings()
        videoView.setVideoController(videoController)
        videoView.startFullScreen()
        // 轨道面板回调只绑一次，后续轨道数据刷新由 updateTrackPanel 驱动。
        longTrackPanelController.bind()
        updateTrackEntryByCapabilities()
    }

    private fun applyPlaybackCoreFor(videoBean: VideoData, playUrl: String) {
        applyConfiguredRenderFactory()
        val core = if (isSmbVideo(videoBean, playUrl)) DyPlayerCore.EXO else spUtil.dyPlayerCore
        DyPlayerCoreRegistry.applyCore(videoView, core, spUtil.labMpvSpecialRender)
    }

    private fun applyConfiguredRenderFactory() {
        if (spUtil.surfaceRender) {
            videoView.setRenderViewFactory(SurfaceRenderViewFactory.create())
        } else {
            videoView.setRenderViewFactory(TextureRenderViewFactory.create())
        }
    }

    private fun isSmbVideo(videoBean: VideoData, playUrl: String): Boolean {
        return videoBean.type == StorageType.SMB || playUrl.startsWith("smb://", ignoreCase = true)
    }

    private fun initVideoListener() {
        // 弹幕设置
        dmConfView.setOnFontChangeListener(this)
        // 弹幕相关 UI 回调已下沉到 controller，Activity 只保留播放状态编排。
        longDanmakuController.bind()
        speedControlView.setOnChangeSpeedListener { speed, position ->
            Log.d(this@LongVideoActivity, "onSpeedChange", PlayerInitializer.Player.videoSpeed)
            videoView.speed = PlayerInitializer.Player.videoSpeed
            longDanmakuController.updateSpeed()
        }
        // 视频播放状态监听
        videoView.addOnStateChangeListener(object : OnStateChangeListener {

            private var isLandscape = false

            override fun onPlayerStateChanged(playerState: Int) {
                when (playerState) {
                    VideoView.PLAYER_NORMAL -> {
                        if (isLandscape) {
                            isLandscape = false
                        }
                    }

                    VideoView.PLAYER_FULL_SCREEN -> {
                        isLandscape = true
                    }
                }
            }

            override fun onPlayStateChanged(playState: Int) {
                if (playState == VideoView.STATE_PREPARED) {
                    logAndCache("LongVideoActivity", "D", "STATE_PREPARED pos=$mCurPos")
                    // ===== 自动重试后维持暂停态 ===== // track-id: bg-retry-20260421
                    if (shouldPauseAfterAutoRetry) {
                        shouldPauseAfterAutoRetry = false
                        videoView.post {
                            videoView.pause()
                            Log.d(this@LongVideoActivity, "Auto retry completed, paused to maintain non-auto-play policy")
                        }
                    }
                    // ======================================
                    onPlayStart()
                }
                if (playState == VideoView.STATE_ERROR) {
                    logAndCache("LongVideoActivity", "E", "STATE_ERROR pos=$mCurPos")
                    cancelBlackScreenWatchdog()
                    lifecycleScope.launch(Dispatchers.IO) {
                        val errorLog = buildString {
                            appendLine("=== 播放错误埋点日志 ===")
                            appendLine("时间: ${System.currentTimeMillis()}")
                            appendLine("视频位置: $mCurPos")
                            appendLine("视频名称: ${if (itemViewModel.getItemSize() > 0) itemViewModel.getItem(mCurPos).name else "N/A"}")
                            appendLine("播放位置: ${videoView.currentPosition}")
                            appendLine("播放状态: ${videoView.currentPlayState}")
                            appendLine("播放器状态: ${videoView.currentPlayerState}")
                            appendLine("是否有MediaPlayer: ${videoView.hasPlayer()}")
                            appendLine("是否有RenderView: ${videoView.getRenderTransformView() != null}")
                            appendLine("是否从后台恢复: $isReturningFromBackground")
                            appendLine("是否已自动重试: $hasAutoRetriedOnResume")
                            appendLine("URL: ${if (itemViewModel.getItemSize() > 0) itemViewModel.getItem(mCurPos).videoUrl else "N/A"}")
                            appendLine("======================")
                        }
                        Log.d(this@LongVideoActivity, "STATE_ERROR triggered", errorLog)
                        FileOperator.writeText(
                            lifecycleScope,
                            AppFile(this@LongVideoActivity).buildCustom(
                                "logs",
                                "play_error_${System.currentTimeMillis()}.log"
                            ),
                            errorLog
                        )
                    }

                    // ===== 后台恢复错误自动重试 ===== // track-id: bg-retry-20260421
                    if (isReturningFromBackground && !hasAutoRetriedOnResume) {
                        hasAutoRetriedOnResume = true
                        isReturningFromBackground = false
                        shouldPauseAfterAutoRetry = true
                        Log.d(this, "Auto retry from background error, position=$mCurPos")
                        longVideoControlView.showTips("播放恢复中...")

                        // 投递到下一轮主线程循环，避免与当前错误回调里的状态切换冲突 // track-id: bg-retry-20260421
                        videoView.post {
                            retryCurrentVideoAfterBackgroundError()
                        }

                        // 后台恢复错误场景下，不立即重置进度，等重试结果确定后再处理 // track-id: bg-retry-20260421
                        return
                    }
                    // ======================================

                    // 非后台恢复错误，或已重试过一次，执行正常错误处理
                    longDanmakuController.saveCacheInfo(binding.videoView.currentPosition, true)
                    if (spUtil.autoNext) {
                        longVideoControlView.showTips("播放错误，自动播放下一集")
                        onNextPlay()
                    } else {
                        longVideoControlView.showTips("播放错误")
                    }
                }
                if (playState == VideoView.STATE_PLAYBACK_COMPLETED) {
                    cancelBlackScreenWatchdog()
                    longDanmakuController.saveCacheInfo(0, true)
                    if (spUtil.autoNext) {
                        onNextPlay()
                    }
                }
                if (playState == VideoView.STATE_PLAYING) {
                    clearBackgroundRecoveryState()
                    scheduleMediaLastPlayedUpdate(mCurPos)
                    // playUrl() 可能执行同步网络请求，需在IO线程调用
                    val position = mCurPos
                    val name = itemViewModel.getItem(position).name
                    lifecycleScope.launch(Dispatchers.IO) {
                        val playUrl = itemViewModel.getItem(position).playUrl()
                        lifecycleScope.launch(Dispatchers.Main) {
                            if (position == mCurPos) {
                                startBlackScreenWatchdog(position, name, playUrl)
                            }
                        }
                    }
                }
            }
        })
    }

    override fun onPreviousPlay() {
        if (mCurPos - 1 >= 0) {
            startPlay(mCurPos - 1)
        } else if (spUtil.loopList && itemViewModel.getItemSize() > 1) {
            startPlay(itemViewModel.getItemSize() - 1)
            longVideoControlView.showTips("循环到最后一集")
        } else {
            danmakuView.release()
            longVideoControlView.showTips("没有更多")
        }
    }

    override fun onNextPlay() {
        if (mCurPos + 1 < itemViewModel.getItemSize()) {
            startPlay(mCurPos + 1)
        } else if (spUtil.loopList && itemViewModel.getItemSize() > 1) {
            startPlay(0)
            longVideoControlView.showTips("循环到第一集")
        } else {
            danmakuView.release()
            longVideoControlView.showTips("没有更多")
        }
    }

    override fun onTimeSync() {
        longDanmakuController.syncTime()
    }

    private fun startPlay(position: Int) {
        mediaPlaybackRecorder.cancelLastPlayedUpdate()
        // 切换播放项前先清掉上一集弹幕列表/曲线，避免旧数据短暂残留。
        longDanmakuController.clearPanels()
        val videoBean = itemViewModel.getItem(position)
        Log.d(this, "startPlay", position, videoBean.name)
        logAndCache("LongVideoActivity", "D", "startPlay position=$position name=${videoBean.name} url=${videoBean.videoUrl}")
        lifecycleScope.launch(Dispatchers.IO) {
            videoBean.playUrl().let { playUrl ->
                lifecycleScope.launch(Dispatchers.Main) {
                    // 新视频起播前统一重置 controller 持有的弹幕 UI 状态。
                    longDanmakuController.resetForNewPlayback()
                    subtitleControlView.clearText()
                    videoScaleControlView.resetValue()
                    PlayerInitializer.resetSpeed()
                    videoView.release()
                    applyPlaybackCoreFor(videoBean, playUrl)
                    //mController.setTitle(videoBean.name)
                    longVideoControlView.setTitle(videoBean.name)
                    videoView.setUrl(
                        playUrl,
                        if (videoBean.is302(playUrl).not()) videoBean.headers else mutableMapOf()
                    )
                    videoView.start()
                    videoView.post { videoScaleControlView.applyCache() }
                    mCurPos = position
                    epSelectView.selected(position)
                    saveMediaInfo(videoBean.videoUrl)
                }
            }
        }
    }

    private fun retryCurrentVideoAfterBackgroundError() {
        if (itemViewModel.getItemSize() <= mCurPos) {
            return
        }
        val position = mCurPos
        val retryPosition = lastKnownPlaybackPosition
        val videoBean = itemViewModel.getItem(position)
        lifecycleScope.launch(Dispatchers.IO) {
            val playUrl = videoBean.playUrl()
            withContext(Dispatchers.Main) {
                if (position != mCurPos || itemViewModel.getItemSize() <= position) {
                    return@withContext
                }
                videoView.release()
                applyPlaybackCoreFor(videoBean, playUrl)
                videoView.setUrl(
                    playUrl,
                    if (videoBean.is302(playUrl).not()) videoBean.headers else mutableMapOf()
                )
                if (retryPosition > 0) {
                    videoView.skipPositionWhenPlay(retryPosition.toInt())
                }
                videoView.start()
                videoView.post { videoScaleControlView.applyCache() }
            }
        }
    }

    private fun startBlackScreenWatchdog(position: Int, title: String, playUrl: String) {
        if (!spUtil.debugMode) {
            return
        }
        // 诊断逻辑已抽出；Activity 只控制是否在调试模式启动检测。
        blackScreenWatchdog.start(position, title, playUrl)
    }

    private fun cancelBlackScreenWatchdog() {
        blackScreenWatchdog.cancel()
    }

    private fun saveMediaInfo(url: String) {
        mediaPlaybackRecorder.saveLastPlayedUrl(itemViewModel.getMediaData(), url)
    }

    private fun scheduleMediaLastPlayedUpdate(position: Int) {
        mediaPlaybackRecorder.scheduleLastPlayedAtUpdate(
            scope = lifecycleScope,
            mediaData = itemViewModel.getMediaData(),
            position = position,
            currentPosition = { mCurPos },
            currentPlayState = { videoView.currentPlayState }
        )
    }

    private fun onPlayStart() {
        val position = mCurPos
        /*videoView.setOnTimedTextListener(null)
        videoView.trackInfo.let {
            if (it.second.isNotEmpty()) {
                videoView.showSubTitle(1)
                videoView.setOnTimedTextListener { _, text ->
                    text?.let {
                        Log.d(this@LongVideoActivity, "timedText", text)
                    }
                }
            }
        }*/
        if (itemViewModel.getItemSize() <= 0) {
            return
        }
        val videoData = itemViewModel.getItem(position)
        attachSubtitle(videoData, position)
        PlayHelper.loadInfo(this@LongVideoActivity, lifecycleScope, videoData) { info ->
            var unLoadDm = true
            if (info != null) {
                // 超过片长9/10，重播
                if (info.playSeek > 0L){
                    videoView.seekTo(info.playSeek)
                    longVideoControlView.showTips("跳转上传播放的位置 ${PlayerUtils.stringForTime(info.playSeek.toInt())}")
                }
                info.dmTrack.let { list ->
                    // 弹幕轨道恢复细节由 controller 处理，Activity 只决定何时触发恢复。
                    longDanmakuController.addTracks(list)
                    if (longDanmakuController.hasTracks()) {
                        unLoadDm = false
                        longDanmakuController.restoreSavedTrack(info)
                    }
                }
            }
            if (unLoadDm) {
                // 没有历史轨道时，按当前存储类型兜底装载默认弹幕文件。
                if (videoData.type == StorageType.LOCAL_STORAGE) {
                    longDanmakuController.addDmFile(File(videoData.dmLink))
                }
                if (videoData.type == StorageType.WEBDAV) {
                    PlayHelper.loadDavDm(
                        this@LongVideoActivity,
                        lifecycleScope,
                        videoData
                    ) { file ->
                        longDanmakuController.addDmFile(file)
                    }
                }
            }
        }
        if (itemViewModel.hasMediaData()) {
            itemViewModel.getMediaData()?.let {
                // 跳过片头
                Log.d(this@LongVideoActivity, "op", it.opOffset)
                if (it.opOffset > 0 && videoView.currentPosition < it.opOffset * 1000) {
                    videoView.seekTo(it.opOffset * 1000L)
                    longVideoControlView.showTips("跳过片头")
                }
            }
        }
        updateTrackPanel(getMediaTrackProvider()?.getMediaTracks() ?: MediaTrackSnapshot())
        setupMediaInfoProvider()
        binding.root.post {
            if (onHistory) {
                return@post
            }
            // 保存历史
            PlayHelper.saveHistory(spUtil, videoData)
        }
        binding.root.postDelayed({
            if (position != mCurPos) {
                return@postDelayed
            }
            // 保存预览图
            PlayHelper.saveThumb(this, lifecycleScope, videoView, videoData)
        }, 1000)
    }

    private fun attachSubtitle(videoData: VideoData, position: Int) {
        clearSubtitleCueListener()
        subtitleControlView.clearText()
        lifecycleScope.launch(Dispatchers.IO) {
            val subtitleFile = PlayHelper.resolveSubtitleFile(this@LongVideoActivity, videoData)
            withContext(Dispatchers.Main) {
                if (position != mCurPos || itemViewModel.getItemSize() <= position) {
                    return@withContext
                }
                if (itemViewModel.getItem(position).videoUrl != videoData.videoUrl) {
                    return@withContext
                }
                if (subtitleFile != null && subtitleFile.exists() && subtitleFile.isFile) {
                    addExternalSubtitle(subtitleFile.toUri(), null, subtitleFile.name)
                }
            }
        }
    }

    private fun updateTrackPanel(snapshot: MediaTrackSnapshot) {
        if (!::mTrackPanelControlView.isInitialized) {
            return
        }
        // 轨道列表映射和字幕 cue 开关由 controller 统一维护。
        longTrackPanelController.updateTracks(snapshot)
    }

    private fun updateTrackEntryByCapabilities() {
        if (!::longVideoControlView.isInitialized) {
            return
        }
        longTrackPanelController.updateEntryByCapabilities()
    }

    private fun applyPreferredLanguages() {
        if (!::mTrackPanelControlView.isInitialized) {
            return
        }
        longTrackPanelController.applyPreferredLanguages()
    }

    private fun addExternalSubtitle(uri: Uri, mimeType: String?, title: String?): Boolean {
        return ExternalSubtitleLoader.addSubtitle(
            externalTrackController = getExternalTrackController(),
            subtitleCueProvider = getSubtitleCueProvider(),
            subtitleControlView = subtitleControlView,
            uri = uri,
            mimeType = mimeType,
            title = title
        )
    }

    private fun attachSubtitleCueListener() {
        // 具体 cue 绑定逻辑抽到共享 helper，避免长短视频页各自维护一套。
        ExternalSubtitleLoader.attachCueListener(getSubtitleCueProvider(), subtitleControlView)
    }

    private fun clearSubtitleCueListener() {
        ExternalSubtitleLoader.clearCueListener(getSubtitleCueProvider())
    }

    override fun onResume() {
        super.onResume()
        // 标记后台返回后的短暂恢复窗口，仅在该窗口内允许 STATE_ERROR 自动重试 // track-id: bg-retry-20260421
        clearBackgroundRecoveryState()
        if (hasPausedForBackgroundRecovery) {
            hasPausedForBackgroundRecovery = false
            isReturningFromBackground = true
            backgroundRecoveryJob = lifecycleScope.launch {
                delay(BACKGROUND_RECOVERY_WINDOW_MS)
                clearBackgroundRecoveryState()
            }
        }
        // 移除可能导致 STATE_ERROR 的同步指令。使用 post 确保在 Surface 准备就绪后的下一个主线程循环执行。
        // 这样可以安全地触发一次 seek 来恢复画面，而不会触发自动播放。
        videoView.post {
            if (videoView.currentPlayState == VideoView.STATE_PAUSED && itemViewModel.getItemSize() > 0) {
                val currentPos = videoView.currentPosition
                if (currentPos > 0) {
                    videoView.seekTo(currentPos)
                    Log.d(this, "onResume - safe frame restoration via seekTo", currentPos)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        cancelBlackScreenWatchdog()
        rememberPlaybackPosition(videoView.currentPosition)
        danmakuView.pause()
        videoView.pause()
        clearBackgroundRecoveryState()
        hasPausedForBackgroundRecovery = true
        // 重置重试标记，下次从后台回来允许重试一次 // track-id: bg-retry-20260421
        hasAutoRetriedOnResume = false
        // 重置自动重试后暂停标记 // track-id: bg-retry-20260421
        shouldPauseAfterAutoRetry = false
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelBlackScreenWatchdog()
        mediaPlaybackRecorder.release()
        clearBackgroundRecoveryState()
        clearSurfaceTrace()
        PlayerInitializer.resetSpeed()
        videoView.release()
        danmakuView.release()
    }

    override fun onDmShow(isShow: Boolean) {
        danmakuView.toggleVis(isShow)
    }

    override fun onSelectDm() {
        videoController.hide()
        dmSelectView.switchVib()
    }

    override fun onConfDm() {
        videoController.hide()
        dmConfView.switchVib()
    }

    override fun onConfVideo() {
        videoController.hide()
        videoScaleControlView.switchVib()
    }

    override fun onScreenshot() {
        PlayHelper.doScreenShot(
            lifecycleScope, videoView,
            itemViewModel.getItem(mCurPos),
            videoView.currentPosition
        ) { save ->
            lifecycleScope.launch(Dispatchers.Main) {
                longVideoControlView.showTips(if (save) "截图成功" else "截图失败")
            }
        }
    }

    override fun onShowEpisodeSelect() {
        videoController.hide()
        epSelectView.switchVib()
    }

    override fun onShowDmTrack() {
        videoController.hide()
        longDanmakuController.switchTrackPanel()
    }

    override fun onShowDmList() {
        videoController.hide()
        longDanmakuController.switchListPanel(videoView.currentPosition)
    }

    override fun onShowTrackPanel() {
        videoController.hide()
        mTrackPanelControlView.switchVib()
    }

    override fun onVideoProgress(duration: Int, position: Int) {
        rememberPlaybackPosition(position.toLong())
        if (position > 3000) {
            longDanmakuController.saveCacheInfo(position.toLong())
        }
        if (itemViewModel.hasMediaData()) {
            itemViewModel.getMediaData()?.let {
                // 跳过片尾
                if (it.edOffset > 0 && (duration - position) / 1000 == it.edOffset) {
                    logD("duration", duration, "position", position, "ed", it.edOffset)
                    longDanmakuController.saveCacheInfo(0)
                    longVideoControlView.showTips("跳过片尾")
                    onNextPlay()
                }
            }
        }
    }

    override fun onSpeedChange() {
        videoController.hide()
        speedControlView.switchVib()
    }

    override fun onShowMediaInfo() {
        videoController.hide()
        mediaInfoControlView.switchVib()
    }

    private fun setupMediaInfoProvider() {
        val owner = getMediaInfoProviderOwner()
        val capabilities = getPlayerCapabilities()
        // MediaInfoControlView stays backend-agnostic; unsupported cores simply clear the provider.
        mediaInfoControlView.setMediaInfoProvider(
            if (capabilities.canProvideMediaInfo) owner?.createMediaInfoProvider() else null
        )
        mediaInfoControlView.setProviderName(
            if (capabilities.canProvideMediaInfo) owner?.getMediaInfoProviderName().orEmpty() else ""
        )
        val videoData = if (itemViewModel.getItemSize() > mCurPos) itemViewModel.getItem(mCurPos) else null
        if (videoData != null) {
            val vd = videoData
            val position = mCurPos
            // playUrl() 可能执行同步网络请求，需在IO线程调用
            lifecycleScope.launch(Dispatchers.IO) {
                val fullPath = vd.playUrl()
                lifecycleScope.launch(Dispatchers.Main) {
                    if (position == mCurPos) {
                        val fileEntity = me.lingci.lib.base.storage.entity.FileEntity(
                            name = vd.name.ifBlank { vd.videoUrl.substringAfterLast("/") },
                            path = vd.videoUrl,
                            fullPath = fullPath,
                            type = vd.type,
                            size = 0L
                        )
                        mediaInfoControlView.setFileEntity(fileEntity, vd.type)
                    }
                }
            }
        } else {
            mediaInfoControlView.setFileEntity(null, null)
        }
    }

    override fun onFontChange(fontPath: String) {
        longDanmakuController.onFontChange(fontPath)
    }

}
