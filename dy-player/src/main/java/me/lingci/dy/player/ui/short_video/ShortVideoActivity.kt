package me.lingci.dy.player.ui.short_video

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.RectF
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.ActivityShortVideoBinding
import me.lingci.dy.player.core.DyPlayerCore
import me.lingci.dy.player.core.DyPlayerCoreRegistry
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.ui.long_video.PlayHelper
import me.lingci.dy.player.ui.long_video.PlayInfo
import me.lingci.dy.player.ui.media_detail.RenameDialog
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.AppUtil.removeViewFormParent
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.MediaManger
import me.lingci.dy.player.util.PlaybackErrorLogger
import me.lingci.dy.player.util.PlaybackLogCache
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.view.ShortVideoControlView
import me.lingci.dy.player.view.ShortVideoController
import me.lingci.lib.base.storage.IStorage
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.ui.BaseActivity
import me.lingci.lib.base.util.AppFile
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.HttpUtil
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.createNew
import me.lingci.lib.base.util.deleteExists
import me.lingci.lib.base.util.dp
import me.lingci.lib.base.util.isLocal
import me.lingci.lib.base.util.logD
import me.lingci.lib.base.util.safeGetParcelable
import me.lingci.lib.base.util.safeGetParcelableArrayList
import me.lingci.lib.player.danmaku.PlayerInitializer
import me.lingci.lib.player.view.SubtitleView
import me.lingci.lib.player.widget.component.SubtitleControlView
import me.lingci.lib.player.widget.render.ShortVideoRenderViewFactory
import me.lingci.lib.player.widget.videoview.CustomVideoView
import xyz.doikki.videoplayer.player.BaseVideoView.OnStateChangeListener
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.render.TextureRenderViewFactory
import me.lingci.lib.player.util.SurfaceRenderTrace
import me.lingci.lib.player.subtitle.SubtitleCueProvider
import me.lingci.lib.player.track.ExternalTrackController
import me.lingci.lib.player.track.ExternalTrackRequest
import me.lingci.lib.player.track.MediaTrackType
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 短视频，使用ViewPager2实现
 * Created by Doikki on 2019/12/04.
 */
class ShortVideoActivity : BaseActivity() {

    private val subtitleScaleNormal = 1.0f
    private val subtitleScaleFullScreen = 1.2f

    companion object {
        private const val SUBTITLE_DOCK_TRACE_TAG = "SubtitleDockTrace"
        private const val KEY_LIST = "list"
        private const val KEY_INDEX = "index"
        private const val KEY_HISTORY = "history"
        private const val KEY_RANDOM = "random"
        private const val KEY_TEMP = "temp"
        private const val KEY_MEDIA = "media"
        private const val CONTROL_ALPHA_FULL = 1.0f
        private const val CONTROL_ALPHA_DIMMED = 0.3f
        private const val CONTROL_ALPHA_DIM_PROGRESS = 0.4f
        private const val CONTROL_ALPHA_RESTORE_VISIBLE_PROGRESS = 0.5f
        private const val CONTROL_ALPHA_RESTORE_DURATION = 160L
        private const val MEDIA_LAST_PLAYED_UPDATE_DELAY_MS = 10_000L

        fun start(context: Context, list: ArrayList<VideoData>, index: Int, history: Boolean) {
            val i = Intent(context, ShortVideoActivity::class.java)
            i.putExtra(KEY_INDEX, index)
            i.putExtra(KEY_HISTORY, history)
            start(context, i, list)
        }

        fun start(
            context: Context,
            mediaData: MediaData?,
            list: ArrayList<VideoData>,
            index: Int,
            history: Boolean
        ) {
            val i = Intent(context, ShortVideoActivity::class.java)
            i.putExtra(KEY_INDEX, index)
            i.putExtra(KEY_HISTORY, history)
            if (mediaData != null && !history) {
                i.putExtra(KEY_MEDIA, mediaData)
            }
            start(context, i, list)
        }

        fun start(
            context: Context,
            list: ArrayList<VideoData>,
            index: Int,
            history: Boolean,
            random: Boolean
        ) {
            val i = Intent(context, ShortVideoActivity::class.java)
            i.putExtra(KEY_INDEX, index)
            i.putExtra(KEY_HISTORY, history)
            i.putExtra(KEY_RANDOM, random)
            start(context, i, list)
        }

        private fun start(context: Context, intent: Intent, list: ArrayList<VideoData>) {
            val toJson = JsonUtil.toJsonString(list)
            AppFile(context).buildCache(".data/${UUID.randomUUID()}.json").let { file ->
                FileOperator.writeText(file, toJson)
                intent.putExtra(KEY_TEMP, file.path)
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
    private var mediaData: MediaData? = null
    private val mVideoList: MutableList<VideoData> = mutableListOf()
    private lateinit var mBinding: ActivityShortVideoBinding
    private lateinit var mVideoView: CustomVideoView
    private lateinit var mShortVideoAdapter: ShortVideoAdapter
    private lateinit var mController: ShortVideoController
    private lateinit var subtitleControlView: SubtitleControlView
    private lateinit var mViewPagerImpl: RecyclerView
    private lateinit var shortSettingsDialog: ShortSettingsDialog
    private lateinit var shortCommentDialog: ShortCommentDialog
    private lateinit var shortMoreDialog: ShortMoreDialog
    private var activeShortVideoControlView: ShortVideoControlView? = null
    private val isUserScroll = AtomicBoolean(false)
    private val playbackLogCache = PlaybackLogCache()
    private var mediaLastPlayedUpdateJob: Job? = null
    private var mediaLastPlayedUpdated = false

    private fun getExternalTrackController(): ExternalTrackController? {
        return mVideoView.getPlayerCapability(ExternalTrackController::class.java)
    }

    private fun getSubtitleCueProvider(): SubtitleCueProvider? {
        return mVideoView.getPlayerCapability(SubtitleCueProvider::class.java)
    }

    private var countDownTimer: CountDownTimer? = null
    private var timerCloseRemainingMillis: Long = 0L
    private var timerCloseMinutes: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUtil.setStatusBarTransparent(this)
        mBinding = ActivityShortVideoBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
        init()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
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

    private fun fromOpen() {
        val filepath = intent?.let { AppUtil.parsePathFromIntent(it) }
        if (filepath == null) {
            return
        }
        logD("new file", filepath)
        val list = mutableListOf<VideoData>()
        mCurPos = 0
        if (filepath.startsWith("/")) {
            FileOperator.getSortedFiles(File(filepath).parentFile!!, FileOperator.VIDEO_EXTENSIONS)
                .let {
                    it.forEach { file ->
                        if (file.path == filepath) {
                            list.add(0, VideoData(file))
                        } else {
                            list.add(VideoData(file))
                        }
                    }
                }
        } else {
            list.add(VideoData(File(filepath)))
        }
        onHistory = true
        mVideoList.clear()
        initData(0, false, false, list)
    }

    private fun init() {
        setupSurfaceTrace()
        PlayerInitializer.initShort(
            spUtil.shortLifeSpeed,
            spUtil.shortRightSpeed,
            spUtil.showShortLike,
            spUtil.showShortComment,
            spUtil.showShortTitle,
            spUtil.showShortPager,
            spUtil.showShortSeekbar,
            spUtil.showSysBar,
            spUtil.showShortMore
        )
        val customRandom = intent.hasExtra(KEY_RANDOM)
        val random = intent.getBooleanExtra(KEY_RANDOM, false)
        val index = intent.getIntExtra(KEY_INDEX, 0)
        onHistory = intent.getBooleanExtra(KEY_HISTORY, false)
        mediaData = intent.safeGetParcelable(KEY_MEDIA)
        intent.safeGetParcelableArrayList<VideoData>(KEY_LIST)?.let {
            initData(index, customRandom, random, it)
        }
        intent.getStringExtra(KEY_TEMP)?.let { path ->
            lifecycleScope.launch(Dispatchers.IO) {
                val file = File(path)
                FileOperator.readText(file).let { json ->
                    file.deleteExists()
                    val list = JsonUtil.toList<VideoData>(json).toMutableList()
                    withContext(Dispatchers.Main) {
                        initData(index, customRandom, random, list)
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!::mVideoView.isInitialized || !mVideoView.onBackPressed()) {
                    finish()
                }
            }
        })
        fromOpen()
    }

    private fun initData(
        index: Int,
        customRandom: Boolean,
        random: Boolean,
        list: MutableList<VideoData>
    ) {
        //val map = JsonUtil.toList<VideoData>(spUtil.likeJson!!).associateBy { it.id }
        val ids = JsonUtil.toList<VideoData>(spUtil.likeJson!!).map { it.md5() }
        logD("likes ${ids.joinToString(",")}")
        list.forEach {
            it.id = it.md5()
            it.like = ids.contains(it.id)
        }
        var currentIndex = index
        if ((customRandom && random) || (!customRandom && spUtil.shortRandom)) {
            val data = list.removeAt(index)
            mVideoList.addAll(list.shuffled())
            mVideoList.add(0, data)
            currentIndex = 0
        } else {
            mVideoList.addAll(list)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            MediaManger.scanVideoThumb(baseContext, mVideoList.toList())
        }

        shortSettingsDialog = ShortSettingsDialog()
        shortCommentDialog = ShortCommentDialog()
        shortMoreDialog = ShortMoreDialog()

        initViewPager()
        initVideoView()

        mBinding.viewpage2.post {
            logD("currentIndex", currentIndex)
            if (currentIndex != 0) {
                isUserScroll.set(false)
                mBinding.viewpage2.setCurrentItem(currentIndex, false)
                mBinding.viewpage2.postDelayed({ startPlay(currentIndex) }, 30)
            } else {
                startPlay(currentIndex)
            }
        }
        shortSettingsDialog.onChange {
            mVideoView.setLooping(PlayerInitializer.Player.shortAutoNext.not())
            mVideoView.changeSysBar(PlayerInitializer.Player.shortShowSysBar)
            mVideoView.start()
        }
        shortSettingsDialog.onTimerClose {
            showTimerCloseDialog()
        }
        shortCommentDialog.onComment { videoId, item, playInfo ->
            if (videoId.isBlank()) {
                return@onComment null
            }
            item.style = "${mVideoView.currentPosition}"
            var info = playInfo?.copy(playSeek = mVideoView.currentPosition)
            if (info == null) {
                info = PlayInfo(playSeek = mVideoView.currentPosition)
            }
            info.comments.add(item)
            lifecycleScope.launch(Dispatchers.IO) {
                AppFile(this@ShortVideoActivity).buildCustom("info", "${videoId}.json").writeText(
                    JsonUtil.toJsonString(info)
                )
            }
            updateCommentCount(info.comments.size)
            info
        }
        shortCommentDialog.onDeleteComment { videoId, position, playInfo ->
            if (videoId.isBlank() || playInfo == null) {
                return@onDeleteComment null
            }
            val info = playInfo.copy(playSeek = mVideoView.currentPosition)
            if (position in 0 until info.comments.size) {
                info.comments.removeAt(position)
            }
            lifecycleScope.launch(Dispatchers.IO) {
                AppFile(this@ShortVideoActivity).buildCustom("info", "${videoId}.json").writeText(
                    JsonUtil.toJsonString(info)
                )
            }
            updateCommentCount(info.comments.size)
            info
        }
    }

    private fun loadCommentCount(videoId: String): Int {
        return try {
            val file = AppFile(this@ShortVideoActivity).buildCustom("info", "${videoId}.json")
            if (file.exists() && file.canRead()) {
                val info = JsonUtil.toEntity<PlayInfo>(file.readText())
                info.comments.size
            } else {
                0
            }
        } catch (e: Exception) {
            logD("loadCommentCount failed", e.message)
            0
        }
    }

    private fun updateCommentCount(count: Int) {
        val countTmp = mViewPagerImpl.childCount
        for (i in 0 until countTmp) {
            val itemView = mViewPagerImpl.getChildAt(i)
            val viewHolder = itemView.tag as ShortVideoAdapter.ViewHolder
            if (viewHolder.bindingAdapterPosition == mCurPos) {
                viewHolder.shortVideoControlView.setCommentCount(count)
                break
            }
        }
    }

    private fun initVideoView() {
        mVideoView = CustomVideoView(this)
        applyConfiguredPlaybackCore()
        mVideoView.setLooping(PlayerInitializer.Player.shortAutoNext.not())
        mController = ShortVideoController(this)
        subtitleControlView = SubtitleControlView(this)
        //subtitleControlView.setSubtitleAbsoluteTextSize(32f)
        subtitleControlView.setSubtitleScale(subtitleScaleNormal)
        mController.addControlComponent(subtitleControlView)
        mVideoView.setVideoController(mController)
        // 音频渐入渐出配置
        mVideoView.setEnableAudioFade(spUtil.audioFadeEnabled)
        mVideoView.setAudioFadeInDuration(spUtil.audioFadeInDuration)
        mVideoView.setAudioFadeOutDuration(spUtil.audioFadeOutDuration)
        mVideoView.addOnStateChangeListener(object : OnStateChangeListener {

            private var isLandscape = false

            override fun onPlayerStateChanged(playerState: Int) {
                when (playerState) {
                    VideoView.PLAYER_NORMAL -> {
                        applySubtitleTextSizeForPlayerState(VideoView.PLAYER_NORMAL)
                        mVideoView.changeSysBar(PlayerInitializer.Player.shortShowSysBar)
                        isUserScroll.set(false)
                        updateSubtitleDocking()
                        if (isLandscape) {
                            isLandscape = false
                            mViewPagerImpl.scrollToPosition(mCurPos)
                        }
                    }

                    VideoView.PLAYER_FULL_SCREEN -> {
                        applySubtitleTextSizeForPlayerState(VideoView.PLAYER_FULL_SCREEN)
                        isUserScroll.set(false)
                        isLandscape = true
                        updateSubtitleDocking()
                        mViewPagerImpl.scrollToPosition(mCurPos)
                    }
                }
            }

            override fun onPlayStateChanged(playState: Int) {
                if (playState == VideoView.STATE_PREPARED) {
                    logAndCache(TAG, "D", "STATE_PREPARED: pos=$mCurPos")
                    updateSubtitleDocking()
                    attachSubtitle()
                }
                if (playState == VideoView.STATE_PLAYING) {
                    logAndCache(TAG, "D", "STATE_PLAYING: pos=$mCurPos")
                    updateSubtitleDocking()
                    onPlayStart()
                    scheduleMediaLastPlayedUpdate(mCurPos)
                }
                if (playState == VideoView.STATE_ERROR) {
                    logAndCache(TAG, "E", "STATE_ERROR: pos=$mCurPos")
                    subtitleControlView.clearSubtitleLayoutBounds()

                    val videoData = if (mCurPos in mVideoList.indices) mVideoList[mCurPos] else null
                    if (spUtil.debugMode) {
                        PlaybackErrorLogger.saveErrorLog(
                            lifecycleScope,
                            this@ShortVideoActivity,
                            playbackLogCache,
                            PlaybackErrorLogger.ErrorInfo(
                                videoPosition = mCurPos,
                                videoName = videoData?.name ?: "N/A",
                                videoUrl = videoData?.videoUrl ?: "N/A",
                                playPosition = mVideoView.currentPosition,
                                playState = mVideoView.currentPlayState,
                                playerState = mVideoView.currentPlayerState,
                                hasMediaPlayer = mVideoView.hasPlayer(),
                                hasRenderView = mVideoView.getRenderTransformView() != null,
                                storageType = videoData?.type?.name ?: "N/A"
                            )
                        )
                    }

                    removeItem(mCurPos)
                }
            }
        })
    }

    private fun initViewPager() {
        mBinding.viewpage2.offscreenPageLimit = 2
        mShortVideoAdapter = ShortVideoAdapter(mVideoList)
        mBinding.viewpage2.adapter = mShortVideoAdapter
        mBinding.viewpage2.overScrollMode = View.OVER_SCROLL_NEVER
        mBinding.viewpage2.registerOnPageChangeCallback(object : OnPageChangeCallback() {

            private var mCurItem = 0
            private var dragStartItem = 0

            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
                super.onPageScrolled(position, positionOffset, positionOffsetPixels)
                updateControlAlphaForPageScroll(position, positionOffset, dragStartItem)
            }

            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (position == mCurItem) return
                if (!isUserScroll.get()) return
                mBinding.viewpage2.post { startPlay(position) }
            }

            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    mCurItem = mBinding.viewpage2.currentItem
                    dragStartItem = mCurItem
                }
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    isUserScroll.set(false)
                    resetAllControlViewAlpha(animated = true)
                } else {
                    isUserScroll.set(true)
                }
            }
        })
        mShortVideoAdapter.setOnShortVideoListener(object : ShortVideoControlView.OnShortVideoListener {

            override fun onLike(like: Boolean) {
                val videoData = mVideoList[mCurPos]
                videoData.id = videoData.md5()
                val list = JsonUtil.toList<VideoData>(spUtil.likeJson!!).toMutableList()
                val index = list.indexOfFirst { it.id == videoData.id }
                if (index > -1) {
                    list.removeAt(index)
                }
                if (like) {
                    list.add(videoData)
                }
                spUtil.likeJson = JsonUtil.toJsonString(list)
                logD(list)
            }

            override fun onComment() {
                if (!shortCommentDialog.isAdded) {
                    shortCommentDialog.arguments = Bundle().apply {
                        putString(ShortCommentDialog.KEY_VIDEO_ID, mVideoList[mCurPos].id)
                    }
                    shortCommentDialog.show(supportFragmentManager, shortCommentDialog.tag)
                }
            }

            override fun onMore() {
                shortSettingsDialog.show(supportFragmentManager, shortSettingsDialog.tag)
            }

            override fun onMoreAction() {
                if (!shortMoreDialog.isAdded) {
                    shortMoreDialog.setMoreActionListener(object : ShortMoreDialog.OnMoreActionListener {
                        override fun onRename() {
                            this@ShortVideoActivity.showRenameDialog()
                        }

                        override fun onDelete() {
                            this@ShortVideoActivity.showDeleteConfirmDialog()
                        }

                        override fun onShare() {
                            this@ShortVideoActivity.shareVideo()
                        }
                    })
                    shortMoreDialog.show(supportFragmentManager, shortMoreDialog.tag)
                }
            }

            override fun playNext() {
                if (mCurPos < mShortVideoAdapter.itemCount) {
                    isUserScroll.set(false)
                    val currentIndex = mCurPos + 1
                    mBinding.viewpage2.setCurrentItem(currentIndex, false)
                    mBinding.viewpage2.postDelayed({ startPlay(currentIndex) }, 30)
                }
            }

        })

        // ViewPage2内部是通过RecyclerView去实现的，它位于ViewPager2的第0个位置
        mViewPagerImpl = mBinding.viewpage2.getChildAt(0) as RecyclerView
        //mViewPagerImpl.setScrollingTouchSlop(RecyclerView.TOUCH_SLOP_PAGING)
    }

    private fun applyPlaybackCoreFor(videoBean: VideoData) {
        if (isSmbVideo(videoBean)) {
            applyShortVideoRenderFactory()
            DyPlayerCoreRegistry.applyCore(mVideoView, DyPlayerCore.EXO, spUtil.labMpvSpecialRender)
            if (!spUtil.sortRender) {
                mVideoView.setScreenScaleType(VideoView.SCREEN_SCALE_DEFAULT)
            }
            return
        }
        applyConfiguredPlaybackCore()
    }

    private fun applyConfiguredPlaybackCore() {
        val currentCore = DyPlayerCoreRegistry.resolveCore(spUtil.shortDyPlayerCore)
        if (currentCore == DyPlayerCore.MPV) {
            if (!spUtil.labMpvSpecialRender) {
                mVideoView.setRenderViewFactory(TextureRenderViewFactory.create())
            }
            DyPlayerCoreRegistry.applyCore(mVideoView, spUtil.shortDyPlayerCore, spUtil.labMpvSpecialRender)
            // Do not replace MPV's required Surface renderer with the short-video renderer. MPV keeps
            // default scaling here until fast-swipe Surface reuse is validated across devices.
            //mVideoView.setScreenScaleType(VideoView.SCREEN_SCALE_CENTER_CROP);
            mVideoView.setScreenScaleType(VideoView.SCREEN_SCALE_DEFAULT)
            return
        }
        if (spUtil.sortRender) {
            applyShortVideoRenderFactory()
        }
        DyPlayerCoreRegistry.applyCore(mVideoView, spUtil.shortDyPlayerCore, spUtil.labMpvSpecialRender)
        if (!spUtil.sortRender) {
            mVideoView.setRenderViewFactory(TextureRenderViewFactory.create())
            mVideoView.setScreenScaleType(VideoView.SCREEN_SCALE_DEFAULT)
        }
    }

    private fun applyShortVideoRenderFactory() {
        if (spUtil.sortRender) {
            mVideoView.setRenderViewFactory(ShortVideoRenderViewFactory.create())
        } else {
            mVideoView.setRenderViewFactory(TextureRenderViewFactory.create())
        }
    }

    private fun isSmbVideo(videoBean: VideoData): Boolean {
        return videoBean.type == StorageType.SMB || videoBean.videoUrl.startsWith("smb://", ignoreCase = true)
    }

    private fun startPlay(position: Int) {
        mediaLastPlayedUpdateJob?.cancel()
        val count = mViewPagerImpl.childCount
        for (i in 0 until count) {
            val itemView = mViewPagerImpl.getChildAt(i)
            val viewHolder = itemView.tag as ShortVideoAdapter.ViewHolder
            if (viewHolder.bindingAdapterPosition == position) {
                activeShortVideoControlView?.setOnVideoTransformChangedListener(null)
                clearSubtitleCueListener()
                mVideoView.release()
                removeViewFormParent(mVideoView)
                subtitleControlView.clearText()
                subtitleControlView.clearSubtitleLayoutBounds()
                // mVideoList[position]
                val videoBean = viewHolder.mVideoData
                applyPlaybackCoreFor(videoBean)
                logAndCache(TAG, "D", "开始播放: pos=$position, url=${videoBean.videoUrl}, type=${videoBean.type}")
                if (videoBean.type == StorageType.WEBDAV) {
                    logAndCache(TAG, "D", "URL类型: webdav, url=${videoBean.videoUrl}")
                    mVideoView.setUrl(videoBean.videoUrl, videoBean.headers)
                    mVideoView.start()
                } else {
                    if (videoBean.videoUrl.startsWith("http")) {
                        logAndCache(TAG, "D", "URL类型: http(302重定向), url=${videoBean.videoUrl}")
                        lifecycleScope.launch(Dispatchers.IO) {
                            videoBean.videoUrl = HttpUtil.getRedirectUrl(videoBean.videoUrl)
                            withContext(Dispatchers.Main) {
                                mVideoView.setUrl(videoBean.videoUrl)
                                mVideoView.start()
                            }
                        }
                    } else if (videoBean.videoUrl.startsWith("smb")) {
                        logAndCache(TAG, "D", "URL类型: smb, url=${videoBean.videoUrl}")
                        mVideoView.setUrl(videoBean.videoUrl, videoBean.headers)
                        mVideoView.start()
                    } else {
                        logAndCache(TAG, "D", "URL类型: local, url=${videoBean.videoUrl}")
                        mVideoView.setUrl(videoBean.videoUrl)
                        mVideoView.start()
                    }
                }
                mController.setTitle(videoBean.name)
                viewHolder.shortVideoControlView.setTitle(videoBean.name)
                viewHolder.shortVideoControlView.setPage(position, mVideoList.size)
                mController.addShortVideoControlView(viewHolder.shortVideoControlView)
                viewHolder.shortVideoControlView.setOnVideoTransformChangedListener {
                    updateSubtitleDocking()
                }
                activeShortVideoControlView = viewHolder.shortVideoControlView
                viewHolder.playerContainer.addView(mVideoView, 0)
                viewHolder.shortVideoControlView.resetScale(1.0f)
                mCurPos = position

                lifecycleScope.launch(Dispatchers.IO) {
                    val commentCount = loadCommentCount(videoBean.id)
                    withContext(Dispatchers.Main) {
                        viewHolder.shortVideoControlView.setCommentCount(commentCount)
                    }
                }

                saveMediaInfo(videoBean.videoUrl)
                break
            }
        }
    }

    private fun attachSubtitle() {
        if (!::subtitleControlView.isInitialized || mCurPos !in mVideoList.indices) {
            return
        }
        clearSubtitleCueListener()
        subtitleControlView.clearText()
        val position = mCurPos
        val videoData = mVideoList[position]
        lifecycleScope.launch(Dispatchers.IO) {
            val subtitleFile = PlayHelper.resolveSubtitleFile(this@ShortVideoActivity, videoData)
            withContext(Dispatchers.Main) {
                if (position != mCurPos || position !in mVideoList.indices) {
                    return@withContext
                }
                if (mVideoList[position].videoUrl != videoData.videoUrl) {
                    return@withContext
                }
                if (subtitleFile != null && subtitleFile.exists() && subtitleFile.isFile) {
                    logAndCache(TAG, "D", "字幕加载: ${subtitleFile.path}")
                    addExternalSubtitle(subtitleFile)
                    updateSubtitleDocking()
                }
            }
        }
    }

    private fun addExternalSubtitle(file: File): Boolean {
        // Exo implements this as an external subtitle source; MPV implements it as native sub-add.
        val success = getExternalTrackController()?.addExternalTrack(
            ExternalTrackRequest(
                type = MediaTrackType.SUBTITLE,
                uri = file.toUri(),
                title = file.name,
                selectAfterAdd = true
            )
        ) == true
        if (success) {
            // No-op for MPV because it does not expose SubtitleCueProvider.
            getSubtitleCueProvider()?.setSubtitleCueListener(subtitleControlView::onSubtitleCues)
        }
        return success
    }

    private fun clearSubtitleCueListener() {
        getSubtitleCueProvider()?.setSubtitleCueListener(null)
    }

    private fun updateSubtitleDocking() {
        if (!::subtitleControlView.isInitialized) {
            return
        }
        mVideoView.post {
            val subtitleWidth = subtitleControlView.width
            val subtitleHeight = subtitleControlView.height
            if (subtitleWidth <= 0 || subtitleHeight <= 0) {
                traceSubtitleDockDecision(
                    reason = "host-not-ready",
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight
                )
                resetSubtitleDocking()
                return@post
            }
            if (mVideoView.isFullScreen || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                traceSubtitleDockDecision(
                    reason = if (mVideoView.isFullScreen) "fullscreen" else "landscape-ui",
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight
                )
                resetSubtitleDocking()
                return@post
            }
            val videoSize = mVideoView.videoSize
            val videoWidth = videoSize.getOrNull(0) ?: 0
            val videoHeight = videoSize.getOrNull(1) ?: 0
            if (videoWidth <= 0 || videoHeight <= 0 || videoWidth < videoHeight) {
                traceSubtitleDockDecision(
                    reason = if (videoWidth <= 0 || videoHeight <= 0) "video-size-invalid" else "portrait-video",
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight
                )
                resetSubtitleDocking()
                return@post
            }
            val playerContainer = mVideoView.getChildAt(0) as? ViewGroup ?: run {
                traceSubtitleDockDecision(
                    reason = "player-container-missing",
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight
                )
                resetSubtitleDocking()
                return@post
            }
            val renderView = mVideoView.getRenderTransformView() ?: playerContainer.getChildAt(0) ?: run {
                traceSubtitleDockDecision(
                    reason = "render-view-missing",
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight,
                    containerWidth = playerContainer.width,
                    containerHeight = playerContainer.height
                )
                resetSubtitleDocking()
                return@post
            }
            if (renderView.width <= 0 || renderView.height <= 0) {
                traceSubtitleDockDecision(
                    reason = "render-not-ready",
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight,
                    containerWidth = playerContainer.width,
                    containerHeight = playerContainer.height,
                    renderWidth = renderView.width,
                    renderHeight = renderView.height
                )
                resetSubtitleDocking()
                return@post
            }

            val containerWidth = playerContainer.width
            val containerHeight = playerContainer.height
            if (containerHeight <= 0) {
                traceSubtitleDockDecision(
                    reason = "container-not-ready",
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight,
                    containerWidth = containerWidth,
                    containerHeight = containerHeight,
                    renderWidth = renderView.width,
                    renderHeight = renderView.height
                )
                resetSubtitleDocking()
                return@post
            }

            // 字幕停靠依赖当前 renderView 的真实几何边界，这样缩放和平移后仍然贴着画面底边。
            val renderBounds = calculateRenderBoundsInParent(renderView, playerContainer)
            if (renderBounds.isEmpty) {
                traceSubtitleDockDecision(
                    reason = "render-bounds-empty",
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight,
                    containerWidth = containerWidth,
                    containerHeight = containerHeight,
                    renderWidth = renderView.width,
                    renderHeight = renderView.height,
                    renderBounds = renderBounds
                )
                resetSubtitleDocking()
                return@post
            }
            val dockingTop = renderBounds.bottom.coerceIn(0f, containerHeight.toFloat()).toInt()
            val bottomBarHeight = (containerHeight - dockingTop).coerceAtLeast(0)
            val minDockHeight = if (videoWidth == videoHeight) 28f.dp.toInt() else 56f.dp.toInt()
            if (bottomBarHeight < minDockHeight) {
                traceSubtitleDockDecision(
                    reason = "bottom-bar-too-small",
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight,
                    containerWidth = containerWidth,
                    containerHeight = containerHeight,
                    renderWidth = renderView.width,
                    renderHeight = renderView.height,
                    renderBounds = renderBounds,
                    dockingTop = dockingTop,
                    bottomBarHeight = bottomBarHeight,
                    minDockHeight = minDockHeight
                )
                resetSubtitleDocking()
                return@post
            }

            val boundsTop = dockingTop.coerceIn(0, subtitleHeight)
            val boundsBottom = subtitleHeight
            if (boundsBottom <= boundsTop) {
                traceSubtitleDockDecision(
                    reason = "layout-bounds-invalid",
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight,
                    containerWidth = containerWidth,
                    containerHeight = containerHeight,
                    renderWidth = renderView.width,
                    renderHeight = renderView.height,
                    renderBounds = renderBounds,
                    dockingTop = dockingTop,
                    bottomBarHeight = bottomBarHeight,
                    minDockHeight = minDockHeight,
                    layoutBounds = intArrayOf(0, boundsTop, subtitleWidth, boundsBottom)
                )
                resetSubtitleDocking()
                return@post
            }
            subtitleControlView.setSubtitleDockMode(SubtitleView.DOCK_MODE_BOTTOM_BAR)
            subtitleControlView.setSubtitleLayoutBounds(0, boundsTop, subtitleWidth, boundsBottom)
            traceSubtitleDockDecision(
                reason = "apply-bottom-bar",
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                subtitleWidth = subtitleWidth,
                subtitleHeight = subtitleHeight,
                containerWidth = containerWidth,
                containerHeight = containerHeight,
                renderWidth = renderView.width,
                renderHeight = renderView.height,
                renderBounds = renderBounds,
                dockingTop = dockingTop,
                bottomBarHeight = bottomBarHeight,
                minDockHeight = minDockHeight,
                layoutBounds = intArrayOf(0, boundsTop, subtitleWidth, boundsBottom),
                dockMode = SubtitleView.DOCK_MODE_BOTTOM_BAR
            )
        }
    }

    private fun applySubtitleTextSizeForPlayerState(playerState: Int) {
        if (!::subtitleControlView.isInitialized) {
            return
        }
        val scale = if (playerState == VideoView.PLAYER_FULL_SCREEN) {
            subtitleScaleFullScreen
        } else {
            subtitleScaleNormal
        }
        subtitleControlView.setSubtitleScale(scale)
    }

    private fun resetSubtitleDocking() {
        subtitleControlView.setSubtitleDockMode(SubtitleView.DOCK_MODE_NORMAL)
        subtitleControlView.clearSubtitleLayoutBounds()
    }

    private fun calculateRenderBoundsInParent(renderView: View, parent: View): RectF {
        // renderView 是 playerContainer 的直接子 View，使用父容器局部坐标可避免把位移重复算两次。
        val rect = RectF(0f, 0f, renderView.width.toFloat(), renderView.height.toFloat())
        val matrix = android.graphics.Matrix(renderView.matrix)
        matrix.mapRect(rect)
        rect.offset(renderView.left.toFloat(), renderView.top.toFloat())
        rect.intersect(0f, 0f, parent.width.toFloat(), parent.height.toFloat())
        return rect
    }

    private fun findCurrentShortVideoControlView(): ShortVideoControlView? {
        return findShortVideoControlViewByPosition(mCurPos)
    }

    private fun findShortVideoControlViewByPosition(position: Int): ShortVideoControlView? {
        val count = mViewPagerImpl.childCount
        for (i in 0 until count) {
            val itemView = mViewPagerImpl.getChildAt(i)
            val viewHolder = itemView.tag as? ShortVideoAdapter.ViewHolder ?: continue
            if (viewHolder.bindingAdapterPosition == position) {
                return viewHolder.shortVideoControlView
            }
        }
        return null
    }

    private fun updateControlAlphaForPageScroll(position: Int, positionOffset: Float, dragStartItem: Int) {
        if (positionOffset <= 0f) {
            findShortVideoControlViewByPosition(dragStartItem)?.alpha = CONTROL_ALPHA_FULL
            return
        }

        val alpha = when {
            position == dragStartItem -> {
                val ratio = (positionOffset / CONTROL_ALPHA_DIM_PROGRESS).coerceIn(0f, 1f)
                CONTROL_ALPHA_FULL - ratio * (CONTROL_ALPHA_FULL - CONTROL_ALPHA_DIMMED)
            }

            position < dragStartItem -> {
                val visibleProgress = 1f - positionOffset
                val ratio = (visibleProgress / CONTROL_ALPHA_RESTORE_VISIBLE_PROGRESS).coerceIn(0f, 1f)
                CONTROL_ALPHA_DIMMED + ratio * (CONTROL_ALPHA_FULL - CONTROL_ALPHA_DIMMED)
            }

            else -> CONTROL_ALPHA_FULL
        }

        findShortVideoControlViewByPosition(position)?.let { view ->
            view.animate().cancel()
            view.alpha = alpha
        }
    }

    private fun resetAllControlViewAlpha(animated: Boolean) {
        val count = mViewPagerImpl.childCount
        for (i in 0 until count) {
            val itemView = mViewPagerImpl.getChildAt(i)
            val viewHolder = itemView.tag as? ShortVideoAdapter.ViewHolder ?: continue
            val view = viewHolder.shortVideoControlView
            view.animate().cancel()
            if (animated) {
                view.animate()
                    .alpha(CONTROL_ALPHA_FULL)
                    .setDuration(CONTROL_ALPHA_RESTORE_DURATION)
                    .start()
            } else {
                view.alpha = CONTROL_ALPHA_FULL
            }
        }
    }

    private fun traceSubtitleDockDecision(
        reason: String,
        videoWidth: Int = 0,
        videoHeight: Int = 0,
        subtitleWidth: Int = 0,
        subtitleHeight: Int = 0,
        containerWidth: Int = 0,
        containerHeight: Int = 0,
        renderWidth: Int = 0,
        renderHeight: Int = 0,
        renderBounds: RectF? = null,
        dockingTop: Int = -1,
        bottomBarHeight: Int = -1,
        minDockHeight: Int = -1,
        layoutBounds: IntArray? = null,
        dockMode: Int = subtitleControlView.getDockMode()
    ) {
        me.lingci.lib.base.util.Log.d(
            SUBTITLE_DOCK_TRACE_TAG,
            "event=subtitle_dock_update",
            "reason=$reason",
            "curPos=$mCurPos",
            "playerState=${if (mVideoView.isFullScreen) "fullscreen" else "normal"}",
            "orientation=${if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"}",
            "video=${videoWidth}x${videoHeight}",
            "subtitleHost=${subtitleWidth}x${subtitleHeight}",
            "container=${containerWidth}x${containerHeight}",
            "render=${renderWidth}x${renderHeight}",
            "renderBounds=${formatRectF(renderBounds)}",
            "dockTop=$dockingTop",
            "bottomBar=$bottomBarHeight",
            "minDock=$minDockHeight",
            "layout=${formatBounds(layoutBounds)}",
            "dockMode=${formatDockMode(dockMode)}"
        )
    }

    private fun formatRectF(rect: RectF?): String {
        if (rect == null) {
            return "null"
        }
        return "[${rect.left.toInt()},${rect.top.toInt()},${rect.right.toInt()},${rect.bottom.toInt()}]"
    }

    private fun formatBounds(bounds: IntArray?): String {
        if (bounds == null || bounds.size < 4) {
            return "null"
        }
        return "[${bounds[0]},${bounds[1]},${bounds[2]},${bounds[3]}]"
    }

    private fun formatDockMode(mode: Int): String {
        return if (mode == SubtitleView.DOCK_MODE_BOTTOM_BAR) "bottom_bar" else "normal"
    }

    fun addData(list: List<VideoData>) {
        val size = mVideoList.size
        mVideoList.addAll(list)
        // 使用此方法添加数据，使用notifyDataSetChanged会导致正在播放的视频中断
        mShortVideoAdapter.notifyItemRangeChanged(size, mVideoList.size)
    }

    fun removeItem(position: Int) {
        if (position < 0 || position >= mVideoList.size) return
        // 先释放当前视频并从容器移除
        mVideoView.release()
        AppUtil.removeViewFormParent(mVideoView)
        activeShortVideoControlView?.setOnVideoTransformChangedListener(null)
        activeShortVideoControlView = null
        mVideoList.removeAt(position)
        mShortVideoAdapter.notifyItemRemoved(position)
        mShortVideoAdapter.notifyItemRangeChanged(position, mVideoList.size)
        if (position == mCurPos) {
            if (mVideoList.isNotEmpty()) {
                val newPos = position.coerceAtMost(mVideoList.size - 1)
                mCurPos = newPos
                // 延迟执行，等待ViewPager2完成布局刷新后再播放
                mBinding.viewpage2.post { startPlay(newPos) }
            } else {
                finish()
            }
        } else if (position < mCurPos) {
            mCurPos--
        }
    }

    private fun onPlayStart() {
        val position = mCurPos
        mBinding.root.post {
            if (onHistory) {
                return@post
            }
            val videoBean = mVideoList[position]
            PlayHelper.saveHistory(spUtil, videoBean)
        }
        doScreenShot(position)
    }

    fun doScreenShot(position: Int) {
        mBinding.root.postDelayed({
            if (position != mCurPos) {
                return@postDelayed
            }
            if (mVideoList[position].videoUrl.isLocal()) {
                return@postDelayed
            }
            runOnUiThread {
                val file = File(
                    externalCacheDir,
                    ".thumb/${mVideoList[mCurPos].md5()}.${AppUtil.THUMB_TYPE}"
                )
                if (file.exists() && file.length() < 20480) {
                    file.delete()
                }
                if (!file.exists()) {
                    try {
                        file.createNew()
                        FileOutputStream(file).use { fos ->
                            mVideoView.doScreenShot().compress(AppUtil.COMPRESS_FORMAT, 70, fos)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "doScreenShot failed: ${e.message}")
                    }
                }
            }
        }, 1000)
    }

    private fun saveMediaInfo(url: String) {
        mediaData?.let { media ->
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
        val mediaId = mediaData?.id?.takeIf { it.isNotBlank() } ?: return
        mediaLastPlayedUpdateJob?.cancel()
        mediaLastPlayedUpdateJob = lifecycleScope.launch {
            delay(MEDIA_LAST_PLAYED_UPDATE_DELAY_MS)
            if (mediaLastPlayedUpdated || position != mCurPos) return@launch
            if (mVideoView.currentPlayState != VideoView.STATE_PLAYING) return@launch
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

    override fun onResume() {
        super.onResume()
        if (::mVideoView.isInitialized) {
            mVideoView.resume()
        }
        if (timerCloseRemainingMillis > 0) {
            val remainingMinutes = (timerCloseRemainingMillis / 60_000L).toInt().coerceAtLeast(1)
            startTimerClose(remainingMinutes)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::mVideoView.isInitialized) {
            return
        }
        val playerState = if (mVideoView.isFullScreen || newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            VideoView.PLAYER_FULL_SCREEN
        } else {
            VideoView.PLAYER_NORMAL
        }
        applySubtitleTextSizeForPlayerState(playerState)
        updateSubtitleDocking()
    }

    override fun onPause() {
        super.onPause()
        if (::mVideoView.isInitialized) {
            mVideoView.pause()
        }
        countDownTimer?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaLastPlayedUpdateJob?.cancel()
        cancelTimerClose()
        activeShortVideoControlView?.setOnVideoTransformChangedListener(null)
        if (::mVideoView.isInitialized) {
            mVideoView.release()
        }
        clearSurfaceTrace()
        playbackLogCache.clear()
    }

    private fun showTimerCloseDialog() {
        val timerDialog = TimerCloseDialog.newInstance(timerCloseMinutes)
        timerDialog.onTimerSelected { minutes ->
            if (minutes > 0) {
                timerCloseMinutes = minutes
                startTimerClose(minutes)
            } else {
                timerCloseMinutes = 0
                cancelTimerClose()
            }
        }
        if (!timerDialog.isAdded) {
            timerDialog.show(supportFragmentManager, timerDialog.tag)
        }
    }

    private fun startTimerClose(minutes: Int) {
        cancelTimerClose()
        timerCloseRemainingMillis = minutes * 60_000L
        countDownTimer = object : CountDownTimer(timerCloseRemainingMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timerCloseRemainingMillis = millisUntilFinished
                updateTimerCloseDisplay(millisUntilFinished)
            }

            override fun onFinish() {
                timerCloseRemainingMillis = 0
                timerCloseMinutes = 0
                updateTimerCloseDisplay(0)
                closeApp()
            }
        }.start()
        updateTimerCloseDisplay(timerCloseRemainingMillis)
    }

    private fun cancelTimerClose() {
        countDownTimer?.cancel()
        countDownTimer = null
        timerCloseRemainingMillis = 0
        timerCloseMinutes = 0
        updateTimerCloseDisplay(0)
    }

    private fun updateTimerCloseDisplay(millisUntilFinished: Long) {
        activeShortVideoControlView?.updateTimerCloseDisplay(millisUntilFinished)
        val statusText = if (millisUntilFinished > 0) {
            val min = millisUntilFinished / 60_000
            val sec = (millisUntilFinished / 1000) % 60
            String.format("%d:%02d", min, sec)
        } else {
            getString(R.string.timer_off)
        }
        shortSettingsDialog.updateTimerCloseStatus(statusText)
    }

    private fun closeApp() {
        mVideoView.release()
        finishAffinity()
    }

    private fun showRenameDialog() {
        if (mCurPos !in mVideoList.indices) return
        val videoData = mVideoList[mCurPos]
        val extension = videoData.videoUrl.substringAfterLast(".", "")
        val renameDialog = RenameDialog()
        renameDialog.arguments = RenameDialog.buildData(videoData.name)
        renameDialog.onRenameListener { newName ->
            renameVideo(mCurPos, "${newName}.${extension}")
        }
        renameDialog.show(supportFragmentManager, renameDialog.tag)
    }

    private fun renameVideo(position: Int, newName: String) {
        if (position !in mVideoList.indices) return
        val videoData = mVideoList[position]
        val oldPath = videoData.videoUrl

        lifecycleScope.launch(Dispatchers.IO) {
            val success = try {
                val storage = resolveStorage(videoData)
                if (storage != null) {
                    storage.rename(oldPath, newName)
                } else {
                    val oldFile = File(oldPath)
                    if (oldFile.exists()) {
                        val parent = oldFile.parentFile ?: return@launch
                        oldFile.renameTo(File(parent, newName))
                    } else {
                        false
                    }
                }
            } catch (e: Exception) {
                logD("renameVideo failed", e.message)
                false
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    val dotIndex = oldPath.lastIndexOf(".")
                    val newPath = if (dotIndex > 0) {
                        oldPath.substring(0, oldPath.lastIndexOf("/") + 1) + newName
                    } else {
                        oldPath.substring(0, oldPath.lastIndexOf("/") + 1) + newName
                    }
                    videoData.name = newName
                    videoData.videoUrl = newPath
                    mShortVideoAdapter.notifyItemChanged(position)
                    findCurrentShortVideoControlView()?.setTitle(newName)
                    mController.setTitle(newName)
                    Toast.makeText(this@ShortVideoActivity, getString(R.string.action_rename) + "成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@ShortVideoActivity, getString(R.string.action_rename) + "失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showDeleteConfirmDialog() {
        if (mCurPos !in mVideoList.indices) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.hint_delete_video)
            .setMessage(R.string.hint_delete_video_desc)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                deleteVideo(mCurPos)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteVideo(position: Int) {
        if (position !in mVideoList.indices) return
        val videoData = mVideoList[position]
        val path = videoData.videoUrl

        lifecycleScope.launch(Dispatchers.IO) {
            val success = try {
                val storage = resolveStorage(videoData)
                if (storage != null) {
                    storage.delete(path)
                } else {
                    val file = File(path)
                    if (file.exists()) file.delete() else true
                }
            } catch (e: Exception) {
                logD("deleteVideo failed", e.message)
                false
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    removeItem(position)
                    Toast.makeText(this@ShortVideoActivity, getString(R.string.action_delete) + "成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@ShortVideoActivity, getString(R.string.action_delete) + "失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareVideo() {
        if (mCurPos !in mVideoList.indices) return
        val videoData = mVideoList[mCurPos]

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = if (videoData.videoUrl.startsWith("/") || videoData.videoUrl.startsWith("file://")) {
                    File(videoData.videoUrl)
                } else {
                    val cacheFile = File(externalCacheDir, "share/${videoData.name}")
                    if (!cacheFile.exists()) {
                        cacheFile.parentFile?.mkdirs()
                        val storage = resolveStorage(videoData)
                        if (storage != null) {
                            storage.download(videoData.videoUrl, cacheFile.absolutePath)
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@ShortVideoActivity, "无法分享远程文件", Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }
                    }
                    cacheFile
                }

                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ShortVideoActivity, "文件不存在", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@ShortVideoActivity,
                    "${packageName}.provider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "video/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) {
                    startActivity(Intent.createChooser(intent, getString(R.string.hint_share_video)))
                }
            } catch (e: Exception) {
                logD("shareVideo failed", e.message)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ShortVideoActivity, getString(R.string.action_share) + "失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun resolveStorage(videoData: VideoData): IStorage? {
        val sourceList = LibraryCompat.loadSources(spUtil)
        val storageId = mediaData?.let { LibraryCompat.effectiveStorageId(it, sourceList) }
        val source = if (storageId != null) {
            sourceList.find { it.id == storageId }
        } else {
            sourceList.find { it.type == videoData.type }
        }
        return source?.toStorage()
    }

}
