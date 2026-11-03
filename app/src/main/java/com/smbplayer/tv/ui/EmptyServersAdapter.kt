package com.smbplayer.tv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.smbplayer.tv.databinding.ItemEmptyServersBinding

/**
 * 服务器为空时的提示条目：提示文案 + 可聚焦的「添加服务器」按钮，
 * 遥控器在空状态下也能直接添加。
 */
class EmptyServersAdapter(
    private val onAdd: () -> Unit,
) : RecyclerView.Adapter<EmptyServersAdapter.Holder>() {

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

    override fun onBindViewHolder(holder: Holder, position: Int) = with(holder.binding) {
        btnAddServer.setOnClickListener { onAdd() }
    }

    class Holder(val binding: ItemEmptyServersBinding) :
        RecyclerView.ViewHolder(binding.root)
}
