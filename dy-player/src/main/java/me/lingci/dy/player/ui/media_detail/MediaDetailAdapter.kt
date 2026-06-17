package me.lingci.dy.player.ui.media_detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.ui.BaseAdapter
import me.lingci.dy.player.databinding.ItemMediaDetailListBinding
import me.lingci.dy.player.entity.VideoData
import java.io.File

class MediaDetailAdapter(
    dataSet: MutableList<VideoData>
) : BaseAdapter<VideoData, ItemMediaDetailListBinding>(dataSet) {

    private var onLongItemClick: ((v: View, item:VideoData, position: Int) -> Unit)? = null
    private var showDanmaku: Boolean = false
    private val dmTrackCountMap = mutableMapOf<String, Int>()

    fun onLongItemClick(onLongItemClick: (v: View, item:VideoData, position: Int) -> Unit) {
        this.onLongItemClick = onLongItemClick
    }

    override fun createBinding(
        inflater: LayoutInflater,
        parent: ViewGroup
    ): ItemMediaDetailListBinding {
        return ItemMediaDetailListBinding.inflate(inflater, parent, false)
    }

    override fun bindData(binding: ItemMediaDetailListBinding, item: VideoData, position: Int) {
        binding.tvTitle.text = item.name
        val dmCount = dmTrackCountMap[item.videoUrl] ?: 0
        binding.tvType.text = if (dmCount > 0) "已绑定 $dmCount 个弹幕" else "未绑定弹幕"
        binding.tvType.visibility = if (showDanmaku) View.VISIBLE else View.GONE
        binding.ivPlaying.visibility = if (item.lastPlay) View.VISIBLE else View.GONE
        binding.root.setOnClickListener {
            onItemClick?.invoke(item, position)
        }
        binding.root.setOnLongClickListener {
            onLongItemClick?.invoke(it, item, position)
            return@setOnLongClickListener true
        }
    }

    fun sorted() {
        dataList.reverse()
        notifyAllChanged()
    }

    fun sortByName(ascending: Boolean) {
        dataList.sortBy { it.name.lowercase() }
        if (!ascending) dataList.reverse()
        notifyAllChanged()
    }

    fun sortByModifiedTime(ascending: Boolean) {
        dataList.sortBy { videoData ->
            if (videoData.type == StorageType.LOCAL_STORAGE) {
                File(videoData.videoUrl).lastModified()
            } else {
                0L
            }
        }
        if (!ascending) dataList.reverse()
        notifyAllChanged()
    }

    fun playIndex(): Int {
        val index = dataList.indexOfFirst { it.lastPlay }
        return if (index > -1) {
            index
        } else {
            0
        }
    }

    fun updateLastPlay(playLast: String) {
        dataList.forEach { it.lastPlay = it.videoUrl == playLast }
        notifyAllChanged()
    }

    /**
     * 精确更新 lastPlay 标记，只刷新变化的 item
     * @return Pair(旧lastPlay位置, 新lastPlay位置)，-1 表示无
     */
    fun updateLastPlayPrecise(playLast: String): Pair<Int, Int> {
        var oldPosition = -1
        var newPosition = -1
        dataList.forEachIndexed { index, videoData ->
            val wasLastPlay = videoData.lastPlay
            videoData.lastPlay = videoData.videoUrl == playLast
            if (wasLastPlay && !videoData.lastPlay) oldPosition = index
            if (videoData.lastPlay) newPosition = index
        }
        return oldPosition to newPosition
    }

    fun showDanmaku(show: Boolean = false) {
        this.showDanmaku = show
    }

    fun updateDanmakuCount(map: Map<String, Int>) {
        // 找出弹幕数量变化的 item，精确刷新，避免全量 notifyDataSetChanged
        val changedPositions = mutableListOf<Int>()
        map.forEach { (url, count) ->
            if (dmTrackCountMap[url] != count) {
                val index = dataList.indexOfFirst { it.videoUrl == url }
                if (index >= 0) changedPositions.add(index)
            }
        }
        dmTrackCountMap.clear()
        dmTrackCountMap.putAll(map)
        if (changedPositions.isEmpty()) {
            // 无变化时（如首次设置），全量刷新一次以保证显示
            notifyAllChanged()
        } else {
            changedPositions.forEach { notifyItemChanged(it) }
        }
    }

}
