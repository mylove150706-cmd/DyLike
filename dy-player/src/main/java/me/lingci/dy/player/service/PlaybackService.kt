package me.lingci.dy.player.service

import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import xyz.doikki.videoplayer.player.AbstractPlayer

/**
 * 后台播放 Foreground Service。
 *
 * 职责:
 * 1. 持有 player 引用(临时,从 Activity 转入) + setVideoSurface(null) 停止视频解码
 * 2. 显示 MediaStyle 通知 + 前台保活
 * 3. MediaSession(对接锁屏/蓝牙/车机)
 * 4. 音频焦点管理(LOSS_TRANSIENT 暂停 / GAIN 恢复)
 *
 * 不负责:创建 player、加载媒体源、进度保存、列表管理。
 */
class PlaybackService : Service() {

    companion object {
        private const val TAG = "PlaybackService"
    }

    private val binder = PlaybackBinder(this)
    private var player: AbstractPlayer? = null
    private var metadata: PlaybackMetadata? = null
    private var mediaSession: MediaSessionCompat? = null
    private var notificationHelper: PlaybackNotificationHelper? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null

    val isHoldingPlayer: Boolean get() = player != null

    override fun onCreate() {
        super.onCreate()
        notificationHelper = PlaybackNotificationHelper(this)
        notificationHelper?.ensureChannel()
        setupMediaSession()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        Log.i(TAG, "PlaybackService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 立即 startForeground(空通知),避免 5 秒超时
        // 实际内容在 takePlayer 后更新
        val placeholderNotification = notificationHelper?.buildNotification(
            PlaybackMetadata(title = "正在后台播放"),
            isPlaying = false,
            sessionToken = mediaSession?.sessionToken,
            contentIntent = createContentIntent()
        )
        if (placeholderNotification != null) {
            startForeground(PlaybackNotificationHelper.NOTIFICATION_ID, placeholderNotification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Activity 把 player 交给 Service。 */
    fun takePlayer(player: AbstractPlayer, metadata: PlaybackMetadata) {
        this.player = player
        this.metadata = metadata
        // setVideoSurface(null) 已在 BaseVideoView.detachPlayerForBackground 中调用
        // 更新 MediaSession + 通知
        updateMediaSession(metadata, isPlaying = player.isPlaying)
        updateNotification()
        requestAudioFocus()
        Log.i(TAG, "took player: ${metadata.title}")
    }

    /** Activity 取回 player。 */
    fun returnPlayer(): AbstractPlayer? {
        val p = player
        player = null
        abandonAudioFocus()
        stopForegroundAndNotification()
        Log.i(TAG, "returned player")
        return p
    }

    fun updateMetadata(metadata: PlaybackMetadata) {
        this.metadata = metadata
        updateMediaSession(metadata, isPlaying = player?.isPlaying == true)
        updateNotification()
    }

    fun stopForegroundAndNotification() {
        abandonAudioFocus()
        // minSdk = 24 (N),STOP_FOREGROUND_REMOVE 自 API 24 起可用,可直接调用
        stopForeground(STOP_FOREGROUND_REMOVE)
        mediaSession?.isActive = false
    }

    override fun onDestroy() {
        super.onDestroy()
        // 如果 Service 被销毁时仍持有 player(Activity 异常销毁),释放它
        player?.release()
        player = null
        abandonAudioFocus()
        mediaSession?.release()
        mediaSession = null
        Log.i(TAG, "PlaybackService destroyed")
    }

    // === 内部方法 ===

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "DyPlayer").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { player?.start() }
                override fun onPause() { player?.pause() }
                override fun onStop() { player?.stop() }
            })
            isActive = true
        }
    }

    private fun updateMediaSession(metadata: PlaybackMetadata, isPlaying: Boolean) {
        val session = mediaSession ?: return
        // PlaybackState
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, metadata.currentPosition, 1.0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP
            )
            .build()
        session.setPlaybackState(playbackState)
        // Metadata
        val md = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, metadata.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, metadata.subtitle)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, metadata.duration)
        if (metadata.coverBitmap != null) {
            md.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, metadata.coverBitmap)
        }
        session.setMetadata(md.build())
    }

    private fun updateNotification() {
        val md = metadata ?: return
        val p = player ?: return
        val notification = notificationHelper?.buildNotification(
            md,
            isPlaying = p.isPlaying,
            sessionToken = mediaSession?.sessionToken,
            contentIntent = createContentIntent()
        ) ?: return
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(PlaybackNotificationHelper.NOTIFICATION_ID, notification)
    }

    private fun createContentIntent(): android.app.PendingIntent {
        // 点击通知打开 MainActivity(让它跳转到对应播放页)
        val intent = Intent(this, me.lingci.dy.player.ui.main.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // minSdk = 24 (M = 23),FLAG_IMMUTABLE 自 API 23 起可用,可直接使用
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        return android.app.PendingIntent.getActivity(this, 0, intent, flags)
    }

    // === AudioFocus ===

    private fun requestAudioFocus() {
        audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focus ->
            when (focus) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    player?.pause()
                    metadata?.let { updateNotification() }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    player?.setVolume(0.1f, 0.1f)
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    player?.setVolume(1.0f, 1.0f)
                    // LOSS 时不自动恢复(用户手动点播放);GAIN(从 DUCK 恢复)可以继续
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(audioFocusChangeListener!!)
                .build()
            audioManager?.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            audioFocusChangeListener?.let {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(it)
            }
        }
        audioFocusChangeListener = null
    }
}
