package me.lingci.dy.player.ui.short_video

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.ItemShortVideoBinding
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.ShortTitleFormatter
import me.lingci.dy.player.view.ShortVideoControlView
import me.lingci.lib.base.util.md5
import java.io.File

/**
 * 短视频item
 */
class ShortVideoAdapter(
    private val mVideoData: List<VideoData>
) : RecyclerView.Adapter<ShortVideoAdapter.ViewHolder>() {

    private lateinit var binding: ItemShortVideoBinding
    private var mOnShortVideoListener: ShortVideoControlView.OnShortVideoListener? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        binding = ItemShortVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding).apply {
            itemView.tag = this
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bindItem(mVideoData[position])
        holder.setOnLikeClickListener(mOnShortVideoListener)
        holder.binding.shortVideoView.setOnLikeClickListener(mOnShortVideoListener)
    }

    override fun getItemCount(): Int {
        return mVideoData.size
    }

    fun setOnShortVideoListener(listener: ShortVideoControlView.OnShortVideoListener) {
        mOnShortVideoListener = listener
    }

    fun changeVisibility() {
        binding.shortVideoView.changeVisibility()
    }

    class ViewHolder(
        val binding: ItemShortVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        lateinit var mVideoData: VideoData
        private var onLongVideoListener: ShortVideoControlView.OnShortVideoListener? = null

        init {
            itemView.tag = this
        }

        internal fun setOnLikeClickListener(listener: ShortVideoControlView.OnShortVideoListener?) {
            onLongVideoListener = listener
        }

        fun bindItem(item: VideoData) {
            mVideoData = item
            binding.shortVideoView.changeVisibility()
            binding.shortVideoView.setTitle(ShortTitleFormatter.format(item.name))
            binding.shortVideoView.setLike(item.like)
        }

        private fun loadThumb(item: VideoData) {
            File(binding.root.context.externalCacheDir, ".thumb/${item.videoUrl.md5()}.${AppUtil.THUMB_TYPE}").let {
                if (it.exists()) {
                    Glide.with(itemView.context)
                        .load(it)
                        .into(binding.shortVideoView.findViewById<View>(R.id.iv_thumb) as ImageView)
                } else {
                    Glide.with(itemView.context)
                        .load(item.preview)
                        .into(binding.shortVideoView.findViewById<View>(R.id.iv_thumb) as ImageView)
                }
            }
        }

        val shortVideoControlView: ShortVideoControlView
            get() = binding.shortVideoView
        val playerContainer: FrameLayout
            get() = binding.container
    }

}
