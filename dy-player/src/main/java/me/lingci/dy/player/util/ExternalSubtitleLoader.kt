package me.lingci.dy.player.util

import android.net.Uri
import me.lingci.lib.player.subtitle.SubtitleCueProvider
import me.lingci.lib.player.track.ExternalTrackController
import me.lingci.lib.player.track.ExternalTrackRequest
import me.lingci.lib.player.track.MediaTrackType
import me.lingci.lib.player.widget.component.SubtitleControlView

/**
 * Common external subtitle entry point for both playback pages.
 *
 * Activities still own capability lookup because that depends on their current video view, while
 * this helper keeps the add-track request and cue-listener wiring identical across pages.
 */
object ExternalSubtitleLoader {

    /** Adds and selects a subtitle track, then connects cue rendering when the backend supports it. */
    fun addSubtitle(
        externalTrackController: ExternalTrackController?,
        subtitleCueProvider: SubtitleCueProvider?,
        subtitleControlView: SubtitleControlView,
        uri: Uri,
        mimeType: String? = null,
        title: String? = null
    ): Boolean {
        val success = externalTrackController?.addExternalTrack(
            ExternalTrackRequest(
                type = MediaTrackType.SUBTITLE,
                uri = uri,
                mimeType = mimeType,
                title = title,
                selectAfterAdd = true
            )
        ) == true
        if (success) {
            attachCueListener(subtitleCueProvider, subtitleControlView)
        }
        return success
    }

    /** Routes decoded subtitle cues into the shared subtitle overlay. */
    fun attachCueListener(
        subtitleCueProvider: SubtitleCueProvider?,
        subtitleControlView: SubtitleControlView
    ) {
        subtitleCueProvider?.setSubtitleCueListener(subtitleControlView::onSubtitleCues)
    }

    /** Detaches cue delivery from the current backend when subtitle selection is cleared. */
    fun clearCueListener(subtitleCueProvider: SubtitleCueProvider?) {
        subtitleCueProvider?.setSubtitleCueListener(null)
    }
}
