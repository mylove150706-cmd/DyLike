package me.lingci.dy.player.ui.source

import android.view.LayoutInflater
import android.view.ViewGroup
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.ItemSourceListBinding
import me.lingci.dy.player.entity.SourceData
import me.lingci.lib.base.ui.BaseAdapter

/**
 * 资源库条目适配器
 */
class SourceItemAdapter(
    private val dataSet: MutableList<SourceData>
) : BaseAdapter<SourceData, ItemSourceListBinding>(dataSet) {

    private var onItemLongClick: ((item: SourceData, position: Int) -> Unit)? = null

    override fun createBinding(inflater: LayoutInflater, parent: ViewGroup): ItemSourceListBinding {
        return ItemSourceListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    }

    override fun bindData(binding: ItemSourceListBinding, item: SourceData, position: Int) {
        binding.tvTitle.text = item.title
        binding.tvSubtitle.text = item.siteUrl
        when(item.storageType()) {
            StorageType.STREAM_LINK -> binding.ivIcon.setImageResource(R.drawable.ic_stream_link)
            StorageType.WEBDAV, StorageType.SMB -> binding.ivIcon.setImageResource(R.drawable.ic_cloud_storage)
            else -> binding.ivIcon.setImageResource(R.drawable.ic_mobile_storage)
        }
        binding.root.setOnClickListener {
            onItemClick?.invoke(item, position)
        }
        binding.root.setOnLongClickListener {
            onItemLongClick?.invoke(item, position)
            true
        }
    }

    fun onItemLongClick(onItemLongClick: (item: SourceData, position: Int) -> Unit) {
        this.onItemLongClick = onItemLongClick
    }

    fun getCustomData(): MutableList<SourceData> {
        return dataSet.filter { it.isCustomType() }.toMutableList()
    }

}
