package me.lingci.dy.player.ui.short_video

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.lingci.dy.player.ui.long_video.PlayInfo
import me.lingci.lib.base.util.AppFile
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.logD

/**
 * 短视频评论持久化控制器。
 *
 * Activity 只负责展示入口和刷新当前页计数；评论增删、播放位置绑定和 `info` 目录下的 json
 * 写入集中在这里，避免短视频页继续承载文件格式细节。
 */
class ShortCommentController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val currentPlayPosition: () -> Long,
    private val updateCommentCount: (Int) -> Unit
) {

    /** 绑定评论弹窗回调，并把新增/删除后的结果写回对应视频的播放信息文件。 */
    fun bind(dialog: ShortCommentDialog) {
        dialog.onComment { videoId, item, playInfo ->
            if (videoId.isBlank()) {
                return@onComment null
            }
            val playPosition = currentPlayPosition()
            item.style = "$playPosition"
            val info = playInfo?.copy(playSeek = playPosition) ?: PlayInfo(playSeek = playPosition)
            info.comments.add(item)
            save(videoId, info)
            updateCommentCount(info.comments.size)
            info
        }
        dialog.onDeleteComment { videoId, position, playInfo ->
            if (videoId.isBlank() || playInfo == null) {
                return@onDeleteComment null
            }
            val info = playInfo.copy(playSeek = currentPlayPosition())
            if (position in 0 until info.comments.size) {
                info.comments.removeAt(position)
            }
            save(videoId, info)
            updateCommentCount(info.comments.size)
            info
        }
    }

    /** 读取指定视频已保存的评论数量，用于 ViewPager 页面首次绑定时展示角标。 */
    fun loadCommentCount(videoId: String): Int {
        return try {
            val file = AppFile(context).buildCustom("info", "${videoId}.json")
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

    /** 异步保存评论信息，避免评论弹窗回调阻塞主线程。 */
    private fun save(videoId: String, info: PlayInfo) {
        scope.launch(Dispatchers.IO) {
            AppFile(context).buildCustom("info", "${videoId}.json").writeText(
                JsonUtil.toJsonString(info)
            )
        }
    }
}
