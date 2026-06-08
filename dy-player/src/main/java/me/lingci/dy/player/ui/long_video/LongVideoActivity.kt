package me.lingci.dy.player.ui.long_video

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
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
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.PlaybackLogCache
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.view.LongVideoControlView
import me.lingci.lib.base.entity.TitleItem
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.ui.BaseActivity
import me.lingci.lib.base.util.AppFile
import me.lingci.lib.base.util.DensityCalculator
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.base.util.deleteExists
import me.lingci.lib.base.util.logD
import me.lingci.lib.base.util.safeGetParcelable
import me.lingci.lib.dm.view.common.DmInitializer
import me.lingci.lib.dm.view.entity.DmTrack
import me.lingci.lib.dm.view.entity.DmTrackMode
import me.lingci.lib.dm.view.entity.xml.DmItem
import me.lingci.lib.dm.view.util.XmlConverter
import me.lingci.lib.dm.view.util.XmlMerger
import me.lingci.lib.dm.view.util.ZipXmlLoader
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
import xyz.doikki.videoplayer.player.BaseVideoView.OnStateChangeListener
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.render.TextureRenderViewFactory
import me.lingci.lib.player.util.SurfaceRenderTrace
import me.lingci.lib.player.subtitle.SubtitleCueProvider
import me.lingci.lib.player.track.ExternalTrackController
import me.lingci.lib.player.track.ExternalTrackRequest
import me.lingci.lib.player.track.MediaTrack
import me.lingci.lib.player.track.MediaTrackController
import me.lingci.lib.player.track.MediaTrackKey
import me.lingci.lib.player.track.MediaTrackProvider
import me.lingci.lib.player.track.MediaTrackSnapshot
import me.lingci.lib.player.track.MediaTrackType
import xyz.doikki.videoplayer.util.PlayerUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import androidx.core.graphics.createBitmap

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
        private const val BLACK_SCREEN_CHECK_DELAY_MS = 5000L
        private const val BACKGROUND_RECOVERY_WINDOW_MS = 5000L
        private const val MEDIA_LAST_PLAYED_UPDATE_DELAY_MS = 10_000L

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
            val toJson = JsonUtil.toJsonString(list)
            AppFile(context).buildCache(".data/${UUID.randomUUID()}.json").let { file ->
                FileOperator.writeText(file, toJson)
                bundle.putString(KEY_TEMP, file.path)
                val intent = Intent(context, LongVideoActivity::class.java)
                intent.putExtra(KEY_BUNDLE, bundle)
                context.startActivity(intent)
            }
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
    private var mediaTrackSnapshot = MediaTrackSnapshot()
    private val playbackLogCache = PlaybackLogCache()
    private var blackScreenWatchJob: Job? = null
    private var blackScreenWatchToken = 0L
    private var backgroundRecoveryJob: Job? = null
    private var mediaLastPlayedUpdateJob: Job? = null
    private var mediaLastPlayedUpdated = false
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
        playbackLogCache.add(tag, level, message)
        Log.d(tag, message)
    }

    private fun setupSurfaceTrace() {
        SurfaceRenderTrace.enabled = spUtil.debugMode
        SurfaceRenderTrace.sink = { tag, level, message ->
            playbackLogCache.add(tag, level, message)
            Log.d(tag, message)
        }
    }

    private fun clearSurfaceTrace() {
        if (SurfaceRenderTrace.sink != null) {
            SurfaceRenderTrace.sink = null
        }
        SurfaceRenderTrace.enabled = false
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
            AppFile(this).buildCache(".data/${UUID.randomUUID()}.json").let { file ->
                FileOperator.writeText(file, JsonUtil.toJsonString(itemViewModel.getData()))
                outState.putString(KEY_TEMP, file.path)
            }
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
                val file = File(path)
                FileOperator.readText(file).let { json ->
                    file.deleteExists()
                    val list = JsonUtil.toList<VideoData>(json)
                    withContext(Dispatchers.Main) {
                        itemViewModel.addAll(list)
                    }
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
        setDmFont()
        danmakuView.toggleVis(spUtil.showDm)
        danmakuView.showFps(spUtil.showDmFps)
        danmakuView.setStrokeMultiple(spUtil.dmStrokeMultiple)
        danmakuView.updateTextBold(spUtil.dmBold)
        danmakuView.setDmGradient(spUtil.dmGradientMode, spUtil.dmGradientRatio)
        danmakuView.setGradientWithTextColor(spUtil.dmGradientWithTextColor)
        danmakuView.setMergeOption(
            spUtil.dmShowTime,
            spUtil.dmMergeMode,
            spUtil.dmMergeShow,
            spUtil.dmMergeTop,
            spUtil.debugMode
        )
        if (spUtil.dmFilterMode) {
            spUtil.dmFilter?.let { filterStr ->
                if (filterStr.isNotBlank()) {
                    val keywords = filterStr.split(DmInitializer.FILTER_SEPARATOR).filter { it.isNotBlank() }
                    if (keywords.isNotEmpty()) {
                        danmakuView.addBlackList(false, *keywords.toTypedArray())
                    }
                }
            }
        }
        videoController.addControlComponent(danmakuView)
        videoController.setTimeSyncListener { onTimeSync() }
        // 长视频控制器
        longVideoControlView = LongVideoControlView(this)
        longVideoControlView.setTitle("本地播放")
        //longVideoControlView.setFull(true)
        longVideoControlView.setOnLongVideoListener(this)
        longVideoControlView.setOnPlayNextListener(this)
        videoController.addControlComponent(longVideoControlView)
        videoView.setVideoController(videoController)
        videoView.startFullScreen()
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
        dmConfView.setOnValueChangeListener { type, value->
            when (type) {
                DmConfControlView.TYPE_SIZE -> danmakuView.updateDanmuSize()
                DmConfControlView.TYPE_LINE,
                DmConfControlView.TYPE_TOP_LINE,
                DmConfControlView.TYPE_BOTTOM_LINE -> danmakuView.updateMaxLine()

                DmConfControlView.TYPE_OPACITY -> danmakuView.updateDanmuAlpha()
                DmConfControlView.TYPE_STROKE -> danmakuView.updateDanmuStroke()
                DmConfControlView.TYPE_OFFSET -> danmakuView.updateOffsetTime()
                DmConfControlView.TYPE_ROLL -> danmakuView.updateMobileDanmuState()
                DmConfControlView.TYPE_TOP -> danmakuView.updateTopDanmuState()
                DmConfControlView.TYPE_BOTTOM -> danmakuView.updateBottomDanmuState()
                DmConfControlView.TYPE_STYLE -> danmakuView.updateTextBold(spUtil.dmBold)
                DmConfControlView.TYPE_MARGIN -> danmakuView.updateDanmuMargin()
                DmConfControlView.TYPE_VIEW_MARGIN -> danmakuView.updateDanMuViewMargin()
                DmConfControlView.TYPE_SCROLL_SPEED -> danmakuView.updateScrollSpeed()
                DmConfControlView.TYPE_FPS -> danmakuView.showFps(value as Boolean)
                else -> {}
            }
        }
        danmakuView.updateScrollSpeed()
        // 弹幕选择
        dmSelectView.setOnDmSelectListener { dmTrackConf ->
            dmTrackView.setConf(dmTrackConf)
            when (dmTrackConf.trackMode) {
                // 多轨合并
                DmTrackMode.MULTI_MERGE -> {
                    dmTrackConf.dmTrack.let { track ->
                        track.checked = true
                        dmTrackView.addDmTrack(track)
                        if (dmTrackView.dataSize() > 1) {
                            mergeDm(false)
                        } else {
                            danmakuView.release()
                            loadDmTrack(track)
                        }
                    }
                }
                // 单轨切换
                else -> {
                    dmTrackConf.dmTrack.let { selectedTrack ->
                        dmTrackView.addDmTrack(selectedTrack)
                        val currentTrack = dmTrackView.selectTrack(selectedTrack) ?: selectedTrack.apply {
                            selected = true
                        }
                        loadDmTrack(currentTrack)
                    }
                }
            }
            saveCacheInfo(videoView.currentPosition, false, dmTrackConf.dmTrack)
        }
        // 弹幕轨道调整
        dmTrackView.setOnChangeTrackListener { track, _ ->
            danmakuView.release()
            loadDmTrack(track)
            saveCacheInfo(videoView.currentPosition, false, track)
        }
        dmTrackView.setOnMergeTrackListener { onSave ->
            mergeDm(onSave)
        }
        dmTrackView.setOnDmOffsetListener { _ ->
            danmakuView.syncTime()
        }
        dmTrackView.setOnRemoveTrackListener {
            saveCacheInfo(videoView.currentPosition, false)
        }
        dmListView.setOnChangeTimeListener { time, position ->

        }
        mTrackPanelControlView.setOnChangeTrackListener { tabType, name, position ->
            // TrackPanelControlView still exposes the legacy name/position callback. In the
            // modularized path, name carries MediaTrackKey.id and blank name means disable track.
            when (tabType) {
                TrackPanelControlView.TAB_AUDIO -> {
                    if (name.isBlank()) {
                        getMediaTrackController()?.disableTrack(MediaTrackType.AUDIO)
                    } else {
                        findTrackKey(MediaTrackType.AUDIO, name)?.let { key ->
                            Log.d(this, "audio track", name, position)
                            getMediaTrackController()?.selectTrack(key)
                        }
                    }
                }
                TrackPanelControlView.TAB_SUBTITLE -> {
                    if (name.isBlank()) {
                        getMediaTrackController()?.disableTrack(MediaTrackType.SUBTITLE)
                        clearSubtitleCueListener()
                        subtitleControlView.clearText()
                    } else {
                        findTrackKey(MediaTrackType.SUBTITLE, name)?.let { key ->
                            Log.d(this, "subtitle track", name, position)
                            if (getMediaTrackController()?.selectTrack(key) == true) {
                                attachSubtitleCueListener()
                            }
                        }
                    }
                }
            }
        }
        mTrackPanelControlView.setOnSubtitleFileSelectedListener { filePath ->
            val mimeType = TrackPanelControlView.getSubtitleMimeType(filePath)
            if (mimeType != null) {
                Log.d(this, "subtitle file selected", filePath, mimeType)
                addExternalSubtitle(File(filePath).toUri(), mimeType, File(filePath).name)
            }
        }
        mTrackPanelControlView.setOnAudioLanguageChangedListener { language ->
            applyPreferredLanguages()
        }
        mTrackPanelControlView.setOnSubtitleLanguageChangedListener { language ->
            applyPreferredLanguages()
        }
        mTrackPanelControlView.setOnSubtitleFontChangedListener { fontPath ->
            subtitleControlView.setSubtitleFont(fontPath)
        }
        mTrackPanelControlView.setOnSubtitleFontSizeChangedListener { size ->
            subtitleControlView.setSubtitleAbsoluteTextSize(size.toFloat())
        }
        // 主动应用持久化的字幕设置
        spUtil.subtitleFont?.let { fontPath ->
            if (fontPath.isNotBlank()) {
                subtitleControlView.setSubtitleFont(fontPath)
            }
        }
        subtitleControlView.setSubtitleAbsoluteTextSize(spUtil.subtitleFontSize.toFloat())
        speedControlView.setOnChangeSpeedListener { speed, position ->
            Log.d(this@LongVideoActivity, "onSpeedChange", PlayerInitializer.Player.videoSpeed)
            videoView.speed = PlayerInitializer.Player.videoSpeed
            danmakuView.updateDanmuSpeed()
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
                    saveCacheInfo(binding.videoView.currentPosition, true)
                    if (spUtil.autoNext) {
                        longVideoControlView.showTips("播放错误，自动播放下一集")
                        onNextPlay()
                    } else {
                        longVideoControlView.showTips("播放错误")
                    }
                }
                if (playState == VideoView.STATE_PLAYBACK_COMPLETED) {
                    cancelBlackScreenWatchdog()
                    saveCacheInfo(0, true)
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

    private fun loadDmTrack(track: DmTrack) {
        danmakuView.release()
        if (track.mineType == ZipXmlLoader.ZIP) {
            loadZipDmTrack(track)
        } else {
            danmakuView.loadDanMu(track.path)
            setDmList(track.path)
        }
    }

    private fun loadZipDmTrack(track: DmTrack) {
        lifecycleScope.launch(Dispatchers.IO) {
            val fileEntity = FileEntity(
                name = track.title,
                title = track.title,
                path = track.path,
                mimeType = track.mineType
            )
            val result = ZipXmlLoader.loadXmlFromZipResult(fileEntity, track.password)
            when (result.state) {
                ZipXmlLoader.OpenResultState.SUCCESS -> {
                    val byteArray = result.stream?.use { it.readBytes() }
                    val dmList = byteArray?.let {
                        XmlConverter.fromXmlInput(ByteArrayInputStream(it)).list
                    }.orEmpty()
                    withContext(Dispatchers.Main) {
                        if (byteArray == null) {
                            clearDmPanels()
                            longVideoControlView.showTips("弹幕读取失败")
                            return@withContext
                        }
                        danmakuView.loadDanMu(ByteArrayInputStream(byteArray))
                        applyDmList(dmList)
                    }
                }

                ZipXmlLoader.OpenResultState.WRONG_PASSWORD -> {
                    withContext(Dispatchers.Main) {
                        clearDmPanels()
                        longVideoControlView.showTips("弹幕密码错误，请重新选择")
                    }
                }

                ZipXmlLoader.OpenResultState.UNSUPPORTED_METHOD -> {
                    withContext(Dispatchers.Main) {
                        clearDmPanels()
                        longVideoControlView.showTips("压缩包方法不支持")
                    }
                }

                ZipXmlLoader.OpenResultState.ENTRY_NOT_FOUND -> {
                    withContext(Dispatchers.Main) {
                        clearDmPanels()
                        longVideoControlView.showTips("压缩包条目不存在")
                    }
                }

                ZipXmlLoader.OpenResultState.ERROR -> {
                    withContext(Dispatchers.Main) {
                        clearDmPanels()
                        longVideoControlView.showTips("弹幕读取失败")
                    }
                }
            }
        }
    }

    private fun setDmList(path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dmList = XmlConverter.fromXmlFile(path).list
            withContext(Dispatchers.Main) {
                applyDmList(dmList)
            }
        }
    }

    private fun clearDmPanels() {
        dmListView.cleanDmList()
        longVideoControlView.updateCurveData(floatArrayOf())
    }

    private fun applyDmList(items: List<DmItem>) {
        val list = items.sortedBy { it.time }
        if (list.isEmpty()) {
            clearDmPanels()
            return
        }
        dmListView.setDmList(list)
        val countList = list.map { item -> (item.time * 1000).toLong() }
        val curveData = if (countList.isEmpty()) {
            floatArrayOf()
        } else {
            DensityCalculator.calculateDensity(countList, videoView.duration)
        }
        longVideoControlView.updateCurveData(curveData)
    }

    private fun setDmFont() {
        if (spUtil.dmFontMode) {
            spUtil.dmCurrentFont?.let {
                if (it.isNotBlank()) {
                    val file = File(it)
                    if (file.exists()) {
                        danmakuView.setTypeface(Typeface.createFromFile(file))
                        dmConfView.setCustomFont(it)
                    }
                }
            }
        }
    }

    private fun mergeDm(onSave: Boolean) {
        if (itemViewModel.isMerge()) {
            longVideoControlView.showTips("正在合并，清稍等")
            return
        }
        val dmTrackList = dmTrackView.listSelectedTrack()
        if (dmTrackList.isNotEmpty()) {
            itemViewModel.setMergeState(true)
            lifecycleScope.launch(Dispatchers.IO) {
                if (onSave) {
                    val file = AppFile(this@LongVideoActivity).buildCustom(
                        "xml",
                        "${itemViewModel.getItem(mCurPos).name}.xml"
                    )
                    val success = AtomicInteger(dmTrackList.size)
                    XmlMerger.mergeXmlParts(dmTrackList, file) { name, message ->
                        if (message.isNotBlank()) {
                            success.getAndDecrement()
                            runOnUiThread {
                                longVideoControlView.showSubTips("$name $message")
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        longVideoControlView.showTips("${success.get()}个文件合并成功")
                    }
                } else {
                    ByteArrayOutputStream().use { outputStream ->
                        XmlMerger.mergeXmlParts(dmTrackList, outputStream) { name, message ->
                            if (message.isNotBlank()) {
                                runOnUiThread {
                                    longVideoControlView.showSubTips("$name $message")
                                }
                            }
                        }
                        val byteArray = outputStream.toByteArray()
                        ByteArrayInputStream(byteArray).use { inputStream ->
                            withContext(Dispatchers.Main) {
                                try {
                                    danmakuView.release()
                                    danmakuView.loadDanMu(inputStream)
                                    longVideoControlView.showTips("合并装载成功")
                                } catch (e: Exception) {
                                    FileOperator.writeText(
                                        lifecycleScope,
                                        AppFile(this@LongVideoActivity).buildCustom(
                                            "logs",
                                            "dm_track_merge_${System.currentTimeMillis()}.log"
                                        ),
                                        e.stackTraceToString()
                                    )
                                }
                            }
                        }
                        ByteArrayInputStream(byteArray).use { inputStream ->
                            val dmList = XmlConverter.fromXmlInput(inputStream).list
                            withContext(Dispatchers.Main) {
                                applyDmList(dmList)
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    itemViewModel.setMergeState(false)
                }
            }
        }

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
        danmakuView.syncTime()
    }

    private fun startPlay(position: Int) {
        mediaLastPlayedUpdateJob?.cancel()
        clearDmPanels()
        val videoBean = itemViewModel.getItem(position)
        Log.d(this, "startPlay", position, videoBean.name)
        logAndCache("LongVideoActivity", "D", "startPlay position=$position name=${videoBean.name} url=${videoBean.videoUrl}")
        lifecycleScope.launch(Dispatchers.IO) {
            videoBean.playUrl().let { playUrl ->
                lifecycleScope.launch(Dispatchers.Main) {
                    danmakuView.release()
                    dmTrackView.cleanData()
                    dmSelectView.resetView()
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
        cancelBlackScreenWatchdog()
        if (!spUtil.debugMode) {
            return
        }
        val token = System.currentTimeMillis()
        blackScreenWatchToken = token
        logAndCache("BlackScreenWatchdog", "D", "start token=$token position=$position title=$title")
        blackScreenWatchJob = lifecycleScope.launch {
            delay(BLACK_SCREEN_CHECK_DELAY_MS)
            if (blackScreenWatchToken != token || position != mCurPos) {
                return@launch
            }
            if (videoView.currentPlayState == VideoView.STATE_ERROR ||
                videoView.currentPlayState == VideoView.STATE_PLAYBACK_COMPLETED
            ) {
                return@launch
            }
            checkSurfaceFrameAndSaveIfBlack(token, position, title, playUrl)
        }
    }

    private fun cancelBlackScreenWatchdog() {
        blackScreenWatchJob?.cancel()
        blackScreenWatchJob = null
    }

    private fun checkSurfaceFrameAndSaveIfBlack(token: Long, position: Int, title: String, playUrl: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            longVideoControlView.showSubTips("画面检测失败")
            saveBlackScreenLog(token, position, title, playUrl, "SurfaceView startup diagnostic: 画面检测失败, PixelCopy requires API 26")
            return
        }
        val renderView = videoView.getRenderTransformView()
        if (renderView !is SurfaceView) {
            logAndCache("BlackScreenWatchdog", "D", "skip non-SurfaceView render=${renderView?.javaClass?.name}")
            longVideoControlView.showSubTips("非SurfaceView")
            return
        }
        val width = renderView.width
        val height = renderView.height
        if (width <= 0 || height <= 0) {
            longVideoControlView.showSubTips("画面检测失败")
            saveBlackScreenLog(token, position, title, playUrl, "SurfaceView startup diagnostic: 画面检测失败, invalid size ${width}x$height")
            return
        }
        val bitmap = createBitmap(width, height)
        try {
            PixelCopy.request(renderView, bitmap, { result ->
                if (blackScreenWatchToken != token || position != mCurPos) {
                    return@request
                }
                if (result != PixelCopy.SUCCESS) {
                    longVideoControlView.showSubTips("画面检测失败")
                    saveBlackScreenLog(token, position, title, playUrl, "SurfaceView startup diagnostic: 画面检测失败, PixelCopy result=$result size=${width}x$height")
                    return@request
                }
                val isMostlyBlack = isMostlyBlack(bitmap)
                if (isMostlyBlack) {
                    longVideoControlView.showSubTips("无画面")
                }
                logAndCache("BlackScreenWatchdog", "D", "surface frame diagnostic token=$token result=$isMostlyBlack size=${width}x$height")
                saveBlackScreenLog(token, position, title, playUrl, "SurfaceView startup diagnostic: $isMostlyBlack, PixelCopy success size=${width}x$height")
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            longVideoControlView.showSubTips("画面检测失败")
            saveBlackScreenLog(token, position, title, playUrl, "SurfaceView startup diagnostic: 画面检测失败, PixelCopy exception: ${e.message}")
        }
    }

    private fun isMostlyBlack(bitmap: Bitmap): Boolean {
        val stepX = maxOf(1, bitmap.width / 32)
        val stepY = maxOf(1, bitmap.height / 32)
        var total = 0
        var black = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                if (luminance <= 16) {
                    black++
                }
                total++
                x += stepX
            }
            y += stepY
        }
        return total > 0 && black * 100 / total >= 95
    }

    private fun saveBlackScreenLog(token: Long, position: Int, title: String, playUrl: String, reason: String) {
        logAndCache("BlackScreenWatchdog", "E", "save black screen log token=$token reason=$reason")
        val entries = playbackLogCache.getEntries()
        val playPosition = videoView.currentPosition
        val playState = videoView.currentPlayState
        val playerState = videoView.currentPlayerState
        val hasPlayer = videoView.hasPlayer()
        val renderView = videoView.getRenderTransformView()
        val hasRenderView = renderView != null
        val renderViewType = renderView?.javaClass?.name
        lifecycleScope.launch(Dispatchers.IO) {
            val content = buildString {
                appendLine("=== 长视频 SurfaceView 起播诊断日志 ===")
                appendLine("时间: ${System.currentTimeMillis()}")
                appendLine("原因: $reason")
                appendLine("视频位置: $position")
                appendLine("当前视频位置: $mCurPos")
                appendLine("视频名称: $title")
                appendLine("播放位置: $playPosition")
                appendLine("播放状态: $playState")
                appendLine("播放器状态: $playerState")
                appendLine("是否有MediaPlayer: $hasPlayer")
                appendLine("是否有RenderView: $hasRenderView")
                appendLine("RenderView类型: $renderViewType")
                appendLine("URL: $playUrl")
                appendLine()
                appendLine("--- 开播后最近 ${entries.size} 条日志 ---")
                entries.forEach { entry ->
                    appendLine("[${entry.timestamp}] [${entry.level}] [${entry.tag}] ${entry.message}")
                }
                appendLine("======================")
            }
            FileOperator.writeText(
                lifecycleScope,
                AppFile(this@LongVideoActivity).buildCustom(
                    "logs",
                    "surface_startup_diag_${System.currentTimeMillis()}.log"
                ),
                content
            )
        }
    }

    private fun saveMediaInfo(url: String) {
        itemViewModel.getMediaData()?.let { media ->
            media.playLast = url
            val sourceList = LibraryCompat.loadSources(spUtil)
            LibraryCompat.loadMedia(spUtil).let { dataSet ->
                val index = dataSet.indexOfFirst { LibraryCompat.sameMedia(it, media, sourceList) }
                if (index > -1) {
                    dataSet[index] = media
                    logD("lastPlay", media)
                    LibraryCompat.saveMedia(spUtil, dataSet)
                }
            }
        }
    }

    private fun scheduleMediaLastPlayedUpdate(position: Int) {
        if (mediaLastPlayedUpdated) return
        val mediaId = itemViewModel.getMediaData()?.id?.takeIf { it.isNotBlank() } ?: return
        mediaLastPlayedUpdateJob?.cancel()
        mediaLastPlayedUpdateJob = lifecycleScope.launch {
            delay(MEDIA_LAST_PLAYED_UPDATE_DELAY_MS)
            if (mediaLastPlayedUpdated || position != mCurPos) return@launch
            if (videoView.currentPlayState != VideoView.STATE_PLAYING) return@launch
            withContext(Dispatchers.IO) {
                updateMediaLastPlayedAt(mediaId)
            }
            mediaLastPlayedUpdated = true
        }
    }

    private fun updateMediaLastPlayedAt(mediaId: String) {
        val list = LibraryCompat.loadMedia(spUtil)
        list.find { it.id == mediaId }?.let { media ->
            media.lastPlayedAt = System.currentTimeMillis()
            LibraryCompat.saveMedia(spUtil, list)
        }
    }

    private fun addDmFile(file: File) {
        if (file.exists()) {
            dmTrackView.addDmTrack(
                DmTrack(
                    title = file.name,
                    path = file.path
                )
            )
            danmakuView.release()
            danmakuView.loadDanMu(file.path)
            setDmList(file.path)
        }
    }

    private fun restoreSavedDmTrack(info: PlayInfo): Boolean {
        info.lastDmTrack?.let { lastTrack ->
            dmTrackView.findTrack(lastTrack)?.let { track ->
                dmTrackView.selectTrack(track)
                loadDmTrack(track)
                return true
            }
        }
        dmTrackView.listSelectedTrack().let { selectedTracks ->
            if (selectedTracks.size > 1) {
                itemViewModel.setMergeState(false)
                mergeDm(false)
                return true
            }
            if (selectedTracks.size == 1) {
                loadDmTrack(selectedTracks.first())
                return true
            }
        }
        dmTrackView.allTrack().firstOrNull()?.let { track ->
            loadDmTrack(track)
            return true
        }
        return false
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
                    for (track in list) {
                        //track.selected = true
                        //track.checked = true
                        if (File(track.path).exists()) {
                            dmTrackView.addDmTrack(track)
                        }
                    }
                    if (dmTrackView.allTrack().isNotEmpty()) {
                        unLoadDm = false
                        restoreSavedDmTrack(info)
                    }
                }
            }
            if (unLoadDm) {
                if (videoData.type == StorageType.LOCAL_STORAGE) {
                    addDmFile(File(videoData.dmLink))
                }
                if (videoData.type == StorageType.WEBDAV) {
                    PlayHelper.loadDavDm(
                        this@LongVideoActivity,
                        lifecycleScope,
                        videoData
                    ) { file ->
                        addDmFile(file)
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
        mediaTrackSnapshot = snapshot
        // 这里只负责“轨道面板展示 + 字幕渲染器开关”，不再做默认选轨，避免和底层策略冲突。
        val audioTracks = snapshot.audioTracks
            .filter { it.isSupported }
            .mapIndexed { index, track -> track.toTitleItem(index, MediaTrackType.AUDIO) }
        val subtitleTracks = snapshot.subtitleTracks
            .filter { it.isSupported }
            .mapIndexed { index, track -> track.toTitleItem(index, MediaTrackType.SUBTITLE) }
        mTrackPanelControlView.setAudioTracks(audioTracks)
        mTrackPanelControlView.setSubtitleTracks(subtitleTracks)
        updateTrackEntryByCapabilities()
        if (snapshot.audioTracks.isEmpty() && snapshot.subtitleTracks.isEmpty()) {
            clearSubtitleCueListener()
            subtitleControlView.clearText()
            return
        }
        val selectedSubtitle = snapshot.subtitleTracks.firstOrNull { it.isSelected }
        if (selectedSubtitle != null && getSubtitleCueProvider() != null) {
            // Exo provides generic cues for self-rendered subtitles. MPV normally returns null here
            // and continues rendering subtitles internally.
            attachSubtitleCueListener()
        } else {
            clearSubtitleCueListener()
            subtitleControlView.clearText()
        }
    }

    private fun updateTrackEntryByCapabilities() {
        if (!::longVideoControlView.isInitialized) {
            return
        }
        // Entry visibility is capability-driven rather than core-name-driven so future backends can
        // expose track support without adding more Exo/MPV checks in the UI.
        if (getPlayerCapabilities().canListTracks) {
            longVideoControlView.useTrack()
        } else {
            longVideoControlView.unTrack()
        }
    }

    private fun findTrackKey(type: MediaTrackType, id: String): MediaTrackKey? {
        // TrackPanelControlView still uses TitleItem.name as a UI id, so map it back to the common key.
        val tracks = when (type) {
            MediaTrackType.AUDIO -> mediaTrackSnapshot.audioTracks
            MediaTrackType.SUBTITLE -> mediaTrackSnapshot.subtitleTracks
            MediaTrackType.VIDEO -> mediaTrackSnapshot.videoTracks
            MediaTrackType.UNKNOWN -> emptyList()
        }
        return tracks.firstOrNull { it.key.id == id }?.key
    }

    private fun MediaTrack.toTitleItem(index: Int, type: MediaTrackType): TitleItem {
        return TitleItem(
            title = buildTrackTitle(this, index, type),
            name = key.id,
            selected = isSelected
        )
    }

    private fun buildTrackTitle(track: MediaTrack, index: Int, type: MediaTrackType): String {
        val fallback = when (type) {
            MediaTrackType.AUDIO -> "Audio Track ${index + 1}"
            MediaTrackType.SUBTITLE -> "Subtitle Track ${index + 1}"
            MediaTrackType.VIDEO -> "Video Track ${index + 1}"
            MediaTrackType.UNKNOWN -> "Track ${index + 1}"
        }
        val baseTitle = track.title?.takeIf { it.isNotBlank() } ?: fallback
        val suffix = listOfNotNull(
            track.language?.takeIf { it.isNotBlank() },
            track.codec?.takeIf { it.isNotBlank() }
        ).joinToString(" ")
        return if (suffix.isBlank()) baseTitle else "$baseTitle $suffix"
    }

    private fun applyPreferredLanguages() {
        val audioLanguages = spUtil.preferredAudioLanguage.toLanguageList()
        val subtitleLanguages = spUtil.preferredSubtitleLanguage.toLanguageList()
        getMediaTrackController()?.setPreferredLanguages(audioLanguages, subtitleLanguages)
    }

    private fun String?.toLanguageList(): List<String> {
        return this?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
    }

    private fun addExternalSubtitle(uri: Uri, mimeType: String?, title: String?): Boolean {
        // The backend decides whether this becomes an Exo merged source or an MPV sub-add command.
        val success = getExternalTrackController()?.addExternalTrack(
            ExternalTrackRequest(
                type = MediaTrackType.SUBTITLE,
                uri = uri,
                mimeType = mimeType,
                title = title,
                selectAfterAdd = true
            )
        ) == true
        if (success) {
            attachSubtitleCueListener()
        }
        return success
    }

    private fun attachSubtitleCueListener() {
        getSubtitleCueProvider()?.setSubtitleCueListener(subtitleControlView::onSubtitleCues)
    }

    private fun clearSubtitleCueListener() {
        getSubtitleCueProvider()?.setSubtitleCueListener(null)
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
        mediaLastPlayedUpdateJob?.cancel()
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
        dmTrackView.switchVib()
    }

    override fun onShowDmList() {
        videoController.hide()
        dmListView.switchVib()
        dmListView.scrollToPosition(videoView.currentPosition)
    }

    override fun onShowTrackPanel() {
        videoController.hide()
        mTrackPanelControlView.switchVib()
    }

    override fun onVideoProgress(duration: Int, position: Int) {
        rememberPlaybackPosition(position.toLong())
        if (position > 3000) {
            saveCacheInfo(position.toLong())
        }
        if (itemViewModel.hasMediaData()) {
            itemViewModel.getMediaData()?.let {
                // 跳过片尾
                if (it.edOffset > 0 && (duration - position) / 1000 == it.edOffset) {
                    logD("duration", duration, "position", position, "ed", it.edOffset)
                    saveCacheInfo(0)
                    longVideoControlView.showTips("跳过片尾")
                    onNextPlay()
                }
            }
        }
    }

    private fun loadDmFile(fileName: String) {
        val file = File(fileName)
        if (file.exists()) {
            danmakuView.release()
            lifecycleScope.launch(Dispatchers.IO) {
                danmakuView.loadDanMu(fileName)
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
        if (fontPath.isNotBlank()) {
            lifecycleScope.launch(Dispatchers.IO) {
                danmakuView.setTypeface(Typeface.createFromFile(fontPath))
            }
        } else {
            danmakuView.clearTypeface()
        }
    }

    private fun saveCacheInfo(process: Long, update: Boolean = true, track: DmTrack? = null) {
        if (itemViewModel.getItemSize() <= 0) {
            return
        }
        if (update) {
            PlayHelper.updateInfo(
                this,
                lifecycleScope,
                itemViewModel.getItem(mCurPos),
                process,
                dmTrackView.allTrack(),
                track ?: dmTrackView.allTrack().lastOrNull()
            )
        } else {
            PlayHelper.saveInfo(
                this,
                lifecycleScope,
                itemViewModel.getItem(mCurPos),
                process,
                dmTrackView.allTrack(),
                track ?: dmTrackView.allTrack().lastOrNull()
            )
        }
    }

}
