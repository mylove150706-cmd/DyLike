package me.lingci.lib.player.danmaku

import android.graphics.Color

/**
 * Created by xyoye on 2020/10/29.
 */
object PlayerInitializer {

    var isPrintLog: Boolean = true
    var isOrientationEnabled = true
    var isEnableAudioFocus = true
    var isLooping = false
    //var playerType = PlayerType.TYPE_VLC_PLAYER
    //var surfaceType = SurfaceType.VIEW_TEXTURE
    //var screenScale = VideoScreenScale.SCREEN_SCALE_DEFAULT
    var selectSourceDirectory: String? = null

    object Player {
        var isMediaCodeCEnabled = false
        var isMediaCodeCH265Enabled = false
        var isOpenSLESEnabled = false
        //var pixelFormat = PixelFormat.PIXEL_AUTO
        //var vlcPixelFormat = VLCPixelFormat.PIXEL_RGB_32
        //var vlcHWDecode = VLCHWDecode.HW_ACCELERATION_AUTO
        var maxPlaybackSpeed: Float = 5f
        var maxLongPressSpeed: Float = 5f
        var videoSpeed: Float = 1f
        var pressVideoSpeed: Float = 3f
        var isAutoPlayNext = true

        var shortLeftSpeed = 0.5f
        var shortRightSpeed  = 2f
        var shortShowLike = true
        var shortShowComment = true
        var shortShowTitle = true
        var shortShowSeekbar = true
        var shortShowPager = true
        var shortAutoNext = false
        var shortShowSysBar = true
        var shortShowMore = true

        // 短视频标题策略运行时副本（从 dy-player 的 SpUtil 同步，用基础类型避免跨模块枚举依赖）。
        // shortTitleStrategy 存 ShortTitleStrategy.value（0=RAW 1=SPLIT_ALL 2=FIRST_LINE 3=REGEX_FIRST），
        // 由 dy-player 侧自行 fromValue() 还原。
        var shortTitleStrategy: Int = 0
        var shortTitleDelimiter: String = "-"
        var shortTitleRegex: String = ""
        var shortTitleMaxLines: Int = 0
    }

    object Danmu {
        private const val DEFAULT_POSITION = 0L
        private const val DEFAULT_SIZE = 40
        private const val DEFAULT_ALPHA = 75
        private const val DEFAULT_STOKE = 25
        const val DEFAULT_SPEED = 35
        private const val DEFAULT_MOBILE_ENABLE = true
        private const val DEFAULT_TOP_ENABLE = true
        private const val DEFAULT_BOTTOM_ENABLE = true
        // -1 TODO 显示行
        private const val DEFAULT_MAX_LINE = 5
        private const val DEFAULT_MAX_NUM = 0
        private const val DEFAULT_MARGIN = 0
        private const val DEFAULT_CURVER = 5000L

        var offsetPosition = DEFAULT_POSITION
        var size = DEFAULT_SIZE
        var alpha = DEFAULT_ALPHA
        var stoke = DEFAULT_STOKE
        var speed = DEFAULT_SPEED
        var mobileDanmu = DEFAULT_MOBILE_ENABLE
        var topDanmu = DEFAULT_TOP_ENABLE
        var bottomDanmu = DEFAULT_BOTTOM_ENABLE
        var maxLine = DEFAULT_MAX_LINE
        var maxNum = DEFAULT_MAX_NUM
        var cloudBlock = false
        var updateInChoreographer = true
        var maxTopLine = DEFAULT_MAX_LINE
        var maxBottomLine = DEFAULT_MAX_LINE
        var margin = DEFAULT_MARGIN
        var viewTopMargin = DEFAULT_MARGIN
        var viewBottomMargin = DEFAULT_MARGIN
        var curveTime = DEFAULT_CURVER
    }

    object Subtitle {
        var offsetPosition = 0L

        var textSize = 20
        var strokeWidth = 5
        var textColor = Color.WHITE
        var strokeColor = Color.BLACK
        var typefacePath = ""
    }

    fun changeSpeed(speed: Float) {
        Player.videoSpeed = speed.coerceAtMost(Player.maxPlaybackSpeed)
        //Danmu.speed = (Player.videoSpeed * Danmu.DEFAULT_SPEED).toInt()
    }

    fun resetSpeed() {
        Player.videoSpeed = 1f
        //Danmu.speed = Danmu.DEFAULT_SPEED
    }

    fun changeLongSpeed(speed: Float) {
        Player.pressVideoSpeed = speed.coerceAtMost(Player.maxLongPressSpeed)
    }

    fun initShort(
        left: Float,
        right: Float,
        showLike: Boolean,
        showComment: Boolean,
        showTitle: Boolean,
        showPager: Boolean,
        showSeekbar: Boolean,
        showSysBar: Boolean,
        showMore: Boolean
    ) {
        Player.shortLeftSpeed = left
        Player.shortRightSpeed = right
        Player.shortShowLike = showLike
        Player.shortShowComment = showComment
        Player.shortShowTitle = showTitle
        Player.shortShowPager = showPager
        Player.shortShowSeekbar = showSeekbar
        Player.shortShowSysBar = showSysBar
        Player.shortShowMore = showMore
    }

}
