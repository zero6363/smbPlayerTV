package com.smbplayer.tv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.smbplayer.tv.databinding.ItemActionBinding

/**
 * 主页底部的操作条目（添加服务器 / 设置）。
 *
 * TV 上工具栏按钮会和列表条目组成焦点环（上下键在按钮与条目间乱跳），
 * 所以把操作都放进列表里，焦点只在这一个列表内循环。
 */
class ActionAdapter(
    private val actions: List<Action>,
) : RecyclerView.Adapter<ActionAdapter.Holder>() {

    data class Action(
        val iconRes: Int,
        val titleRes: Int,
        val onClick: () -> Unit,
    )

    override fun getItemCount(): Int = actions.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemActionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val action = actions[position]
        with(holder.binding) {
            icon.setImageResource(action.iconRes)
            tvTitle.setText(action.titleRes)
            root.setOnClickListener { action.onClick() }
        }
    }

    class Holder(val binding: ItemActionBinding) :
        RecyclerView.ViewHolder(binding.root)
}
