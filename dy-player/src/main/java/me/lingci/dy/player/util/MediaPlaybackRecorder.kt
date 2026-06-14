package me.lingci.dy.player.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.MediaData
import me.lingci.lib.base.util.logD
import xyz.doikki.videoplayer.player.VideoView

/**
 * Updates media playback metadata shared by long-video and short-video playback pages.
 *
 * The delayed last-played timestamp intentionally mirrors the old Activity behavior: once one
 * timestamp is written during this recorder lifetime, later playback changes do not write again.
 */
class MediaPlaybackRecorder(private val spUtil: SpUtil) {

    companion object {
        private const val MEDIA_LAST_PLAYED_UPDATE_DELAY_MS = 10_000L
    }

    private var lastPlayedUpdateJob: Job? = null
    private var lastPlayedUpdated = false

    /** Cancels a pending delayed last-played timestamp update without resetting written state. */
    fun cancelLastPlayedUpdate() {
        lastPlayedUpdateJob?.cancel()
        lastPlayedUpdateJob = null
    }

    /** Releases pending work owned by this recorder. */
    fun release() {
        cancelLastPlayedUpdate()
    }

    /** Stores the most recently played URL back into the matching media library entry. */
    fun saveLastPlayedUrl(mediaData: MediaData?, url: String) {
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

    /**
     * Schedules the delayed `lastPlayedAt` write and validates the page is still playing the same
     * item before touching persisted media state.
     */
    fun scheduleLastPlayedAtUpdate(
        scope: CoroutineScope,
        mediaData: MediaData?,
        position: Int,
        currentPosition: () -> Int,
        currentPlayState: () -> Int
    ) {
        if (lastPlayedUpdated) return
        val mediaId = mediaData?.id?.takeIf { it.isNotBlank() } ?: return
        cancelLastPlayedUpdate()
        lastPlayedUpdateJob = scope.launch {
            delay(MEDIA_LAST_PLAYED_UPDATE_DELAY_MS)
            if (lastPlayedUpdated || position != currentPosition()) return@launch
            if (currentPlayState() != VideoView.STATE_PLAYING) return@launch
            withContext(Dispatchers.IO) {
                updateMediaLastPlayedAt(mediaId)
            }
            lastPlayedUpdated = true
        }
    }

    private fun updateMediaLastPlayedAt(mediaId: String) {
        val list = LibraryCompat.loadMedia(spUtil)
        list.find { it.id == mediaId }?.let { media ->
            media.lastPlayedAt = System.currentTimeMillis()
            LibraryCompat.saveMedia(spUtil, list)
        }
    }
}
