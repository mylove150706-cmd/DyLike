package me.lingci.dy.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle

/**
 * 后台播放通知构建器(MediaStyle + 封面大视图)。
 *
 * 通知布局:
 * - ContentView(收起): MediaStyle + 标题/副标题 + 3 键(上/暂停/下)
 * - BigContentView(展开): 封面大图 + 标题/副标题 + 3 键 + 关闭/打开
 */
class PlaybackNotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "dy_player_playback"
        const val CHANNEL_NAME = "后台播放"
        const val NOTIFICATION_ID = 1001
    }

    /** 创建通知渠道(Android 8+ 必需)。幂等。 */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "视频后台播放控制"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager?.createNotificationChannel(channel)
            }
        }
    }

    /**
     * 构建后台播放通知。
     *
     * @param metadata 视频元数据(标题/封面/时长)
     * @param isPlaying 当前是否在播放(决定暂停/播放按钮图标)
     * @param sessionToken MediaSession token(用于 MediaStyle 对接系统)
     * @param contentIntent 点击通知主体打开 Activity 的 PendingIntent
     * @return 构建好的 Notification
     */
    fun buildNotification(
        metadata: PlaybackMetadata,
        isPlaying: Boolean,
        sessionToken: android.support.v4.media.session.MediaSessionCompat.Token?,
        contentIntent: PendingIntent
    ): Notification {
        ensureChannel()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(metadata.title)
            .setContentText(metadata.subtitle)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)

        // 封面
        if (metadata.coverBitmap != null) {
            builder.setLargeIcon(metadata.coverBitmap)
        }

        // 动作按钮(顺序:上一个、暂停/播放、下一个)
        builder.addAction(
            buildAction(PlaybackAction.ACTION_PREV, android.R.drawable.ic_media_previous, "上一个")
        )
        builder.addAction(
            buildAction(
                if (isPlaying) PlaybackAction.ACTION_PAUSE else PlaybackAction.ACTION_PLAY,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "暂停" else "播放"
            )
        )
        builder.addAction(
            buildAction(PlaybackAction.ACTION_NEXT, android.R.drawable.ic_media_next, "下一个")
        )
        builder.addAction(
            buildAction(PlaybackAction.ACTION_CLOSE, android.R.drawable.ic_menu_close_clear_cancel, "关闭")
        )

        // MediaStyle:对接系统媒体控制(锁屏/蓝牙/车机)
        val mediaStyle = MediaStyle()
        if (sessionToken != null) {
            mediaStyle.setMediaSession(sessionToken)
        }
        mediaStyle.setShowActionsInCompactView(0, 1, 2)  // 收起时显示前 3 个按钮
        builder.setStyle(mediaStyle)

        return builder.build()
    }

    /** 构建广播 PendingIntent(通知按钮点击 → 发广播 → Activity/Service 接收)。 */
    private fun buildAction(action: String, icon: Int, label: String): NotificationCompat.Action {
        val intent = Intent(action).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getBroadcast(context, action.hashCode(), intent, flags)
        return NotificationCompat.Action.Builder(icon, label, pendingIntent).build()
    }
}
