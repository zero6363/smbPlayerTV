package com.smbplayer.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smbplayer.tv.R
import com.smbplayer.tv.data.FavoritePath
import com.smbplayer.tv.data.FavoriteRepository
import com.smbplayer.tv.data.HistoryEntry
import com.smbplayer.tv.data.PlaybackProgressRepository
import com.smbplayer.tv.data.ServerRepository
import com.smbplayer.tv.data.SmbServer
import com.smbplayer.tv.databinding.ActivityMainBinding
import com.smbplayer.tv.databinding.DialogServerEditBinding
import com.smbplayer.tv.player.PlayerActivity
import com.smbplayer.tv.smb.SmbGateway
import com.smbplayer.tv.util.SmbPath
import com.smbplayer.tv.util.toast

/**
 * 主页（TV 版）：最近播放（10 条，点击续播）+ 收藏路径 + 服务器列表。
 *
 * 遥控器操作：上下键在各列表间滚动；确认键进入；
 * 长按确认键对当前条目做「编辑 / 删除」类操作。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ServerAdapter
    private lateinit var favoriteAdapter: FavoriteAdapter
    private lateinit var historyAdapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ServerAdapter(
            onClick = { openBrowse(it) },
            onLongClick = { showEditDialog(it) },
            onEdit = { showEditDialog(it) },
            onDelete = { confirmDelete(it) },
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        favoriteAdapter = FavoriteAdapter(
            onClick = { openFavorite(it) },
            onLongClick = { confirmDeleteFavorite(it) },
            onRemove = { confirmDeleteFavorite(it) },
        )
        binding.recyclerFavorite.layoutManager = LinearLayoutManager(this)
        binding.recyclerFavorite.adapter = favoriteAdapter

        historyAdapter = HistoryAdapter(
            onClick = { openHistory(it) },
            onLongClick = { confirmDeleteHistory(it) },
        )
        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = historyAdapter

        // 工具栏：添加服务器（遥控器直达）+ 设置；副标题显示版本号
        binding.toolbar.subtitle = "v${appVersion()}"
        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_add_server -> {
                    showEditDialog(null)
                    true
                }
                R.id.action_settings -> {
                    startActivity(SettingsActivity.intent(this))
                    true
                }
                else -> false
            }
        }

        refresh()
    }

    /** 当前 APK 版本号（versionName），显示在工具栏副标题 */
    private fun appVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = ServerRepository.getAll()
        adapter.submit(list)
        binding.emptyView.visibility =
            if (list.isEmpty()) View.VISIBLE else View.GONE
        refreshFavorites()
        refreshHistory()
    }

    private fun refreshFavorites() {
        val favorites = FavoriteRepository.getAll()
        favoriteAdapter.submit(favorites)
        binding.favoriteSection.visibility =
            if (favorites.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun refreshHistory() {
        val history = PlaybackProgressRepository.recent(HISTORY_LIMIT)
        historyAdapter.submit(history)
        binding.historySection.visibility =
            if (history.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openBrowse(server: SmbServer) {
        startActivity(BrowseActivity.intent(this, server.id))
    }

    /** 点击「最近播放」：直接进播放页续播（文件不存在时由播放列表定位逻辑兜底） */
    private fun openHistory(entry: HistoryEntry) {
        val server = ServerRepository.get(entry.serverId)
        if (server == null) {
            toast(R.string.history_server_missing)
            return
        }
        startActivity(
            PlayerActivity.intent(
                context = this,
                serverId = server.id,
                share = entry.shareName,
                directory = entry.directory,
                index = 0,
                startFile = entry.fileName,
            )
        )
    }

    /** 长按「最近播放」条目：从列表里移除这条记录（不影响服务器上的视频文件） */
    private fun confirmDeleteHistory(entry: HistoryEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_delete_title)
            .setMessage(getString(R.string.history_delete_message, entry.fileName))
            .setPositiveButton(R.string.delete_confirm) { _, _ ->
                PlaybackProgressRepository.remove(entry.serverId, entry.shareName, entry.path)
                refreshHistory()
            }
            .setNegativeButton(R.string.server_cancel, null)
            .show()
    }

    /** 点击收藏路径，直接进入该目录（带上收藏时记录的共享名） */
    private fun openFavorite(favorite: FavoritePath) {
        startActivity(
            BrowseActivity.intent(
                context = this,
                serverId = favorite.serverId,
                startPath = favorite.path,
                startShare = favorite.shareName,
            )
        )
    }

    private fun confirmDelete(server: SmbServer) {
        val label = server.name.ifBlank { server.displayHost }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.server_delete_title)
            .setMessage(getString(R.string.server_delete_message, label))
            .setPositiveButton(R.string.delete_confirm) { _, _ ->
                ServerRepository.delete(server.id)
                SmbGateway.invalidate(server.id)
                refresh()
                toast(getString(R.string.deleted, label))
            }
            .setNegativeButton(R.string.server_cancel, null)
            .show()
    }

    private fun confirmDeleteFavorite(favorite: FavoritePath) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.favorite_delete_title)
            .setMessage(getString(R.string.favorite_delete_message, favorite.label))
            .setPositiveButton(R.string.delete_confirm) { _, _ ->
                FavoriteRepository.delete(favorite.id)
                refreshFavorites()
                toast(getString(R.string.deleted, favorite.label))
            }
            .setNegativeButton(R.string.server_cancel, null)
            .show()
    }

    /** 新增（server == null）或编辑服务器；prefillHost/prefillName 供局域网扫描结果预填 */
    private fun showEditDialog(server: SmbServer?, prefillHost: String = "", prefillName: String = "") {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_server_edit, null)
        val binding = DialogServerEditBinding.bind(view)

        binding.etName.setText(server?.name ?: prefillName)
        binding.etHost.setText(server?.host ?: prefillHost)
        binding.etPort.setText((server?.port ?: 445).toString())
        binding.etShare.setText(server?.share ?: "")
        binding.etUsername.setText(server?.username ?: "")
        binding.etPassword.setText(server?.password ?: "")
        binding.swAnonymous.isChecked = server?.anonymous ?: true
        binding.credentialGroup.visibility =
            if (binding.swAnonymous.isChecked) View.GONE else View.VISIBLE

        binding.swAnonymous.setOnCheckedChangeListener { _, checked ->
            binding.credentialGroup.visibility =
                if (checked) View.GONE else View.VISIBLE
        }

        val title = if (server == null) R.string.action_add_server else R.string.action_edit
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.server_save, null)
            .setNegativeButton(R.string.server_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val hostRaw = binding.etHost.text?.toString()?.trim().orEmpty()
                if (hostRaw.isEmpty()) {
                    binding.etHost.error = getString(R.string.validation_host_required)
                    binding.etHost.requestFocus()
                    return@setOnClickListener
                }
                var port = binding.etPort.text?.toString()?.trim()?.toIntOrNull() ?: 445
                if (port !in 1..65535) {
                    binding.etPort.error = getString(R.string.validation_port_required)
                    binding.etPort.requestFocus()
                    return@setOnClickListener
                }

                val anonymous = binding.swAnonymous.isChecked
                var host = hostRaw
                var share = binding.etShare.text?.toString()?.trim().orEmpty()
                var startPath = ""
                var username = if (anonymous) "" else binding.etUsername.text?.toString()?.trim().orEmpty()
                var password = if (anonymous) "" else binding.etPassword.text?.toString().orEmpty()

                if (SmbPath.isFullPath(hostRaw)) {
                    // 主机框里粘贴了完整路径（\\host\share\dir 或 smb://user:pass@host:port/share/dir）：
                    // 拆成 主机/端口/共享/子目录 分别存放，打开时直达该目录
                    val parts = SmbPath.parse(hostRaw)
                    if (parts == null) {
                        binding.etHost.error = getString(R.string.validation_path_invalid)
                        binding.etHost.requestFocus()
                        return@setOnClickListener
                    }
                    host = parts.host
                    port = parts.port
                    share = parts.share
                    startPath = parts.path
                    if (!anonymous) {
                        parts.username?.let { username = it }
                        parts.password?.let { password = it }
                    }
                    // 回显拆解结果：之后再编辑看到的是规整好的字段
                    binding.etHost.setText(host)
                    binding.etPort.setText(port.toString())
                    binding.etShare.setText(share)
                    if (parts.username != null) {
                        binding.etUsername.setText(parts.username)
                        binding.etPassword.setText(parts.password.orEmpty())
                    }
                    toast(getString(R.string.path_parsed, share, startPath.ifEmpty { "/" }))
                } else {
                    // 共享框里粘了子路径（video/纪录片）：拆成共享名 + 直达目录
                    val (s, p) = SmbPath.splitShareInput(share)
                    if (p.isNotEmpty()) {
                        share = s
                        startPath = p
                        binding.etShare.setText(share)
                        toast(getString(R.string.path_parsed, share, startPath))
                    }
                }

                val newServer = SmbServer(
                    id = server?.id ?: java.util.UUID.randomUUID().toString(),
                    name = binding.etName.text?.toString()?.trim().orEmpty(),
                    host = host,
                    port = port,
                    share = share,
                    startPath = startPath,
                    username = username,
                    password = password,
                    anonymous = anonymous,
                )
                ServerRepository.save(newServer)
                // 配置变了，作废旧连接
                SmbGateway.invalidate(newServer.id)
                refresh()
                toast(getString(R.string.server_save))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    companion object {
        /** 主页「最近播放」展示条数 */
        private const val HISTORY_LIMIT = 10

        fun intent(context: Context): Intent = Intent(context, MainActivity::class.java)
    }
}
