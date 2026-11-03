package com.smbplayer.tv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.smbplayer.tv.databinding.ItemSectionHeaderBinding

/**
 * 分区标题（最近播放 / 收藏路径 / 服务器）。
 *
 * [visible] 为 false 时条目数为 0——ConcatAdapter 会自动跳过，
 * 用它控制整个分区（标题 + 列表）的显隐。
 */
class SectionAdapter(private val titleRes: Int) : RecyclerView.Adapter<SectionAdapter.Holder>() {

    var visible: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    override fun getItemCount(): Int = if (visible) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemSectionHeaderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) =
        holder.binding.tvSection.setText(titleRes)

    class Holder(val binding: ItemSectionHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)
}
