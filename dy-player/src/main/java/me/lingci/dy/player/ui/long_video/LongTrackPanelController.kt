package me.lingci.dy.player.ui.long_video

import android.net.Uri
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.view.LongVideoControlView
import me.lingci.lib.base.entity.TitleItem
import me.lingci.lib.base.util.Log
import me.lingci.lib.player.capability.PlayerCapabilities
import me.lingci.lib.player.widget.component.SubtitleControlView
import me.lingci.lib.player.widget.component.TrackPanelControlView
import me.lingci.lib.player.track.MediaTrack
import me.lingci.lib.player.track.MediaTrackController
import me.lingci.lib.player.track.MediaTrackKey
import me.lingci.lib.player.track.MediaTrackSnapshot
import me.lingci.lib.player.track.MediaTrackType
import java.io.File

/**
 * 长视频音轨/字幕轨面板控制器。
 *
 * Activity 仍负责从播放器查询 capability/provider；这里仅消费统一的轨道快照，负责面板展示、
 * 用户选轨、外部字幕入口以及字幕 cue 监听器的开关。
 */
class LongTrackPanelController(
    private val spUtil: SpUtil,
    private val trackPanelControlView: TrackPanelControlView,
    private val longVideoControlView: LongVideoControlView,
    private val subtitleControlView: SubtitleControlView,
    private val getCapabilities: () -> PlayerCapabilities,
    private val getMediaTrackController: () -> MediaTrackController?,
    private val hasSubtitleCueProvider: () -> Boolean,
    private val addExternalSubtitle: (uri: Uri, mimeType: String?, title: String?) -> Boolean,
    private val attachSubtitleCueListener: () -> Unit,
    private val clearSubtitleCueListener: () -> Unit
) {
    private var mediaTrackSnapshot = MediaTrackSnapshot()

    /** 绑定轨道面板的所有用户操作回调，并应用持久化字幕字体设置。 */
    fun bind() {
        trackPanelControlView.setOnChangeTrackListener { tabType, name, position ->
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
        trackPanelControlView.setOnSubtitleFileSelectedListener { filePath ->
            val mimeType = TrackPanelControlView.getSubtitleMimeType(filePath)
            if (mimeType != null) {
                Log.d(this, "subtitle file selected", filePath, mimeType)
                val file = File(filePath)
                addExternalSubtitle(Uri.fromFile(file), mimeType, file.name)
            }
        }
        trackPanelControlView.setOnAudioLanguageChangedListener {
            applyPreferredLanguages()
        }
        trackPanelControlView.setOnSubtitleLanguageChangedListener {
            applyPreferredLanguages()
        }
        trackPanelControlView.setOnSubtitleFontChangedListener { fontPath ->
            subtitleControlView.setSubtitleFont(fontPath)
        }
        trackPanelControlView.setOnSubtitleFontSizeChangedListener { size ->
            subtitleControlView.setSubtitleAbsoluteTextSize(size.toFloat())
        }

        spUtil.subtitleFont?.let { fontPath ->
            if (fontPath.isNotBlank()) {
                subtitleControlView.setSubtitleFont(fontPath)
            }
        }
        subtitleControlView.setSubtitleAbsoluteTextSize(spUtil.subtitleFontSize.toFloat())
    }

    /** 使用播放器上报的轨道快照刷新面板，并根据当前字幕选择维护 cue 渲染状态。 */
    fun updateTracks(snapshot: MediaTrackSnapshot?) {
        mediaTrackSnapshot = snapshot ?: MediaTrackSnapshot()
        // 这里只负责“轨道面板展示 + 字幕渲染器开关”，不再做默认选轨，避免和底层策略冲突。
        val audioTracks = mediaTrackSnapshot.audioTracks
            .filter { it.isSupported }
            .mapIndexed { index, track -> track.toTitleItem(index, MediaTrackType.AUDIO) }
        val subtitleTracks = mediaTrackSnapshot.subtitleTracks
            .filter { it.isSupported }
            .mapIndexed { index, track -> track.toTitleItem(index, MediaTrackType.SUBTITLE) }
        trackPanelControlView.setAudioTracks(audioTracks)
        trackPanelControlView.setSubtitleTracks(subtitleTracks)
        updateEntryByCapabilities()
        if (mediaTrackSnapshot.audioTracks.isEmpty() && mediaTrackSnapshot.subtitleTracks.isEmpty()) {
            clearSubtitleCueListener()
            subtitleControlView.clearText()
            return
        }
        val selectedSubtitle = mediaTrackSnapshot.subtitleTracks.firstOrNull { it.isSelected }
        if (selectedSubtitle != null && hasSubtitleCueProvider()) {
            // Exo provides generic cues for self-rendered subtitles. MPV normally returns null here
            // and continues rendering subtitles internally.
            attachSubtitleCueListener()
        } else {
            clearSubtitleCueListener()
            subtitleControlView.clearText()
        }
    }

    /** 根据播放器能力控制“轨道”入口显隐，避免 UI 直接判断具体播放内核。 */
    fun updateEntryByCapabilities() {
        // Entry visibility is capability-driven rather than core-name-driven so future backends can
        // expose track support without adding more Exo/MPV checks in the UI.
        if (getCapabilities().canListTracks) {
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

    /** 把用户配置的首选语言下发给当前播放器内核。 */
    fun applyPreferredLanguages() {
        val audioLanguages = spUtil.preferredAudioLanguage.toLanguageList()
        val subtitleLanguages = spUtil.preferredSubtitleLanguage.toLanguageList()
        getMediaTrackController()?.setPreferredLanguages(audioLanguages, subtitleLanguages)
    }

    private fun String?.toLanguageList(): List<String> {
        return this?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
    }
}
