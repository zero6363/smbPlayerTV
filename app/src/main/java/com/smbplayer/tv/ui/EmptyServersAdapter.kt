package com.smbplayer.tv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.smbplayer.tv.databinding.ItemEmptyServersBinding

/**
 * 服务器为空时的提示条目（不可聚焦）。
 * 添加服务器的可聚焦入口在列表末尾的 [ActionAdapter] 里。
 */
class EmptyServersAdapter : RecyclerView.Adapter<EmptyServersAdapter.Holder>() {

    var visible: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    override fun getItemCount(): Int = if (visible) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemEmptyServersBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = Unit

    class Holder(val binding: ItemEmptyServersBinding) :
        RecyclerView.ViewHolder(binding.root)
}
