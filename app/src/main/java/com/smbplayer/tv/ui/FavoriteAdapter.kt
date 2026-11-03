package com.smbplayer.tv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.smbplayer.tv.R
import com.smbplayer.tv.data.FavoritePath
import com.smbplayer.tv.databinding.ItemFavoriteBinding

class FavoriteAdapter(
    private val onClick: (FavoritePath) -> Unit,
    private val onLongClick: (FavoritePath) -> Unit,
    private val onRemove: (FavoritePath) -> Unit,
) : RecyclerView.Adapter<FavoriteAdapter.Holder>() {

    private val items = mutableListOf<FavoritePath>()

    fun submit(list: List<FavoritePath>) {
        // TV：数据没变就不重建列表，避免返回主页时焦点被重置
        if (items == list) return
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemFavoriteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemFavoriteBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FavoritePath) = with(binding) {
            tvLabel.text = item.label
            tvPath.text = buildString {
                append(item.serverName)
                append(" · ")
                append(item.shareName)
                if (item.path.isNotEmpty()) append(" / ").append(item.path)
            }
            root.setOnClickListener { onClick(item) }
            root.setOnLongClickListener {
                onLongClick(item)
                true
            }
            btnRemove.setOnClickListener { onRemove(item) }
        }
    }
}
