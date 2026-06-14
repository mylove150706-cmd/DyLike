package me.lingci.dy.player.ui.long_video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.lingci.dy.player.util.PlaybackLogCache
import me.lingci.lib.base.util.AppFile
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.player.widget.videoview.CustomVideoView
import xyz.doikki.videoplayer.player.VideoView

/**
 * 长视频起播黑屏诊断器。
 *
 * 仅在调试模式由 Activity 启动，延迟采样当前 SurfaceView 首帧；若检测到黑屏或采样失败，
 * 会保存包含播放状态和最近渲染日志的诊断文件，便于定位 Surface 起播问题。
 */
class BlackScreenWatchdog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val videoView: CustomVideoView,
    private val playbackLogCache: PlaybackLogCache,
    private val currentPosition: () -> Int,
    private val showSubTips: (String) -> Unit,
    private val logAndCache: (tag: String, level: String, message: String) -> Unit
) {
    private var watchJob: Job? = null
    private var watchToken = 0L

    /** 开始一次带 token 的延迟检测，播放项变化或检测被取消时自动丢弃旧任务。 */
    fun start(position: Int, title: String, playUrl: String) {
        cancel()
        val token = System.currentTimeMillis()
        watchToken = token
        logAndCache("BlackScreenWatchdog", "D", "start token=$token position=$position title=$title")
        watchJob = scope.launch {
            delay(BLACK_SCREEN_CHECK_DELAY_MS)
            if (watchToken != token || position != currentPosition()) {
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

    /** 取消当前检测任务；不会清理已写出的诊断日志。 */
    fun cancel() {
        watchJob?.cancel()
        watchJob = null
    }

    /** 从 SurfaceView 复制当前帧并按结果决定是否写诊断日志。 */
    private fun checkSurfaceFrameAndSaveIfBlack(token: Long, position: Int, title: String, playUrl: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showSubTips("画面检测失败")
            saveBlackScreenLog(token, position, title, playUrl, "SurfaceView startup diagnostic: 画面检测失败, PixelCopy requires API 26")
            return
        }
        val renderView = videoView.getRenderTransformView()
        if (renderView !is SurfaceView) {
            logAndCache("BlackScreenWatchdog", "D", "skip non-SurfaceView render=${renderView?.javaClass?.name}")
            showSubTips("非SurfaceView")
            return
        }
        val width = renderView.width
        val height = renderView.height
        if (width <= 0 || height <= 0) {
            showSubTips("画面检测失败")
            saveBlackScreenLog(token, position, title, playUrl, "SurfaceView startup diagnostic: 画面检测失败, invalid size ${width}x$height")
            return
        }
        val bitmap = createBitmap(width, height)
        try {
            PixelCopy.request(renderView, bitmap, { result ->
                if (watchToken != token || position != currentPosition()) {
                    return@request
                }
                if (result != PixelCopy.SUCCESS) {
                    showSubTips("画面检测失败")
                    saveBlackScreenLog(token, position, title, playUrl, "SurfaceView startup diagnostic: 画面检测失败, PixelCopy result=$result size=${width}x$height")
                    return@request
                }
                val isMostlyBlack = isMostlyBlack(bitmap)
                if (isMostlyBlack) {
                    showSubTips("无画面")
                }
                logAndCache("BlackScreenWatchdog", "D", "surface frame diagnostic token=$token result=$isMostlyBlack size=${width}x$height")
                saveBlackScreenLog(token, position, title, playUrl, "SurfaceView startup diagnostic: $isMostlyBlack, PixelCopy success size=${width}x$height")
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            showSubTips("画面检测失败")
            saveBlackScreenLog(token, position, title, playUrl, "SurfaceView startup diagnostic: 画面检测失败, PixelCopy exception: ${e.message}")
        }
    }

    /** 低成本抽样判断画面是否几乎全黑，避免全量遍历大尺寸帧。 */
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

    /** 保存一次黑屏诊断快照，包含当前播放状态和起播后的渲染日志缓存。 */
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
        val currentPosition = currentPosition()
        scope.launch(Dispatchers.IO) {
            val content = buildString {
                appendLine("=== 长视频 SurfaceView 起播诊断日志 ===")
                appendLine("时间: ${System.currentTimeMillis()}")
                appendLine("原因: $reason")
                appendLine("视频位置: $position")
                appendLine("当前视频位置: $currentPosition")
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
                scope,
                AppFile(context).buildCustom(
                    "logs",
                    "surface_startup_diag_${System.currentTimeMillis()}.log"
                ),
                content
            )
        }
    }

    private companion object {
        const val BLACK_SCREEN_CHECK_DELAY_MS = 5000L
    }
}
