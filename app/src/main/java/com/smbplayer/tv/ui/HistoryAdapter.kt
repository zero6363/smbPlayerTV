package com.smbplayer.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.smbplayer.tv.R
import com.smbplayer.tv.data.HistoryEntry
import com.smbplayer.tv.databinding.ItemHistoryBinding
import com.smbplayer.tv.util.formatDuration

/**
 * 主页「最近播放」条目：点击续播，长按（遥控器按住确认键）删除记录。
 */
class HistoryAdapter(
    private val onClick: (HistoryEntry) -> Unit,
    private val onLongClick: (HistoryEntry) -> Unit,
) : RecyclerView.Adapter<HistoryAdapter.Holder>() {

    private val items = mutableListOf<HistoryEntry>()

    fun submit(list: List<HistoryEntry>) {
        // TV：数据没变就不重建列表，避免返回主页时焦点被重置
        if (items == list) return
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: HistoryEntry) = with(binding) {
            val progress = entry.progress
            tvName.text = entry.fileName
            if (progress.durationMs > 0L) {
                tvDuration.visibility = View.VISIBLE
                tvDuration.text = formatDuration(progress.durationMs)
            } else {
                tvDuration.visibility = View.GONE
            }
            tvProgress.text = when {
                progress.completed -> root.context.getString(R.string.watched_done)
                progress.positionMs > 0L -> root.context.getString(
                    R.string.history_watch_progress,
                    formatDuration(progress.positionMs),
                    formatDuration(progress.durationMs),
                )
                else -> root.context.getString(R.string.watched_not_started)
            }
            if (progress.displayFraction > 0f) {
                progressView.visibility = View.VISIBLE
                progressView.progress =
                    (progress.displayFraction.coerceIn(0f, 1f) * progressView.max).toInt()
            } else {
                progressView.visibility = View.GONE
            }

            root.setOnClickListener { onClick(entry) }
            root.setOnLongClickListener {
                onLongClick(entry)
                true
            }
        }
    }
}
