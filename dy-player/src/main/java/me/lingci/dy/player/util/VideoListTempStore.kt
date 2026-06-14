package me.lingci.dy.player.util

import android.content.Context
import me.lingci.dy.player.entity.VideoData
import me.lingci.lib.base.util.AppFile
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.deleteExists
import java.io.File
import java.util.UUID

/**
 * Persists large Activity launch lists through a temporary cache file.
 *
 * Android intent extras and saved-state bundles are not a safe place for large video lists. This
 * helper centralizes the cache-file handoff and deletes the file immediately after reading.
 */
object VideoListTempStore {

    /** Writes [list] into a private cache JSON file and returns the file path to pass in a Bundle. */
    fun write(context: Context, list: List<VideoData>): String {
        val file = AppFile(context).buildCache(".data/${UUID.randomUUID()}.json")
        FileOperator.writeText(file, JsonUtil.toJsonString(list))
        return file.path
    }

    /** Reads a previously written temp list and removes the cache file. */
    fun readAndDelete(path: String): List<VideoData> {
        val file = File(path)
        val json = FileOperator.readText(file)
        file.deleteExists()
        return JsonUtil.toList(json)
    }
}
