package me.lingci.dy.player.service

import android.graphics.Bitmap

/**
 * 后台播放的元数据,用于通知栏显示 + MediaSession。
 */
data class PlaybackMetadata(
    val title: String,           // 视频标题
    val subtitle: String = "",   // 副标题(如来源、第 N 个)
    val coverBitmap: Bitmap? = null,  // 封面(可为 null)
    val duration: Long = 0,      // 总时长(ms)
    val currentPosition: Long = 0  // 当前进度(ms)
)

/**
 * 通知栏 / Service → Activity 的广播 action 常量。
 * ACTION_PLAY_PAUSE 不走广播(Service 直接控制 player)。
 */
object PlaybackAction {
    /** 通知栏点了"上一个" */
    const val ACTION_PREV = "me.lingci.dy.player.playback.PREV"
    /** 通知栏点了"下一个" */
    const val ACTION_NEXT = "me.lingci.dy.player.playback.NEXT"
    /** 通知栏点了"关闭" */
    const val ACTION_CLOSE = "me.lingci.dy.player.playback.CLOSE"
}
