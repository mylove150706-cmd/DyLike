package me.lingci.dy.player.util

import me.lingci.lib.base.util.Log
import me.lingci.lib.player.util.SurfaceRenderTrace

/**
 * Shared playback trace bridge used by long-video and short-video pages.
 *
 * It keeps the Activity code focused on lifecycle orchestration while all trace events are still
 * written to both the in-memory playback cache and the normal app log.
 */
object PlaybackTraceHelper {

    /** Records one playback diagnostic line in the cache and logcat. */
    fun logAndCache(logCache: PlaybackLogCache, tag: String, level: String, message: String) {
        logCache.add(tag, level, message)
        Log.d(tag, message)
    }

    /** Installs the global Surface render trace sink for the current playback page. */
    fun setupSurfaceTrace(debugMode: Boolean, logCache: PlaybackLogCache) {
        SurfaceRenderTrace.enabled = debugMode
        SurfaceRenderTrace.sink = { tag, level, message ->
            logCache.add(tag, level, message)
            Log.d(tag, message)
        }
    }

    /** Clears the global trace sink so a destroyed Activity does not keep receiving render events. */
    fun clearSurfaceTrace() {
        if (SurfaceRenderTrace.sink != null) {
            SurfaceRenderTrace.sink = null
        }
        SurfaceRenderTrace.enabled = false
    }
}
