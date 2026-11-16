package com.smbplayer.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smbplayer.tv.R
import com.smbplayer.tv.data.SettingsRepository
import com.smbplayer.tv.databinding.ActivitySettingsBinding
import com.smbplayer.tv.update.UpdateChecker
import com.smbplayer.tv.update.UpdateFlow
import com.smbplayer.tv.update.UpdateInstaller
import com.smbplayer.tv.update.UpdateManager
import com.smbplayer.tv.util.Changelog
import com.smbplayer.tv.util.toast

/**
 * 设置页（TV 版）：后台播放开关、字幕大小、遥控器操作说明、更新日志。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings_title)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 后台播放
        binding.swBackgroundPlay.isChecked = SettingsRepository.backgroundPlay
        binding.swBackgroundPlay.setOnCheckedChangeListener { _, isChecked ->
            SettingsRepository.backgroundPlay = isChecked
        }

        // 字幕大小
        updateSubtitleSizeValue()
        binding.rowSubtitleSize.setOnClickListener { showSubtitleSizePicker() }

        // 遥控器操作说明
        binding.rowTvHelp.setOnClickListener { showTvHelp() }

        // 检查更新（Android TV 没应用商店，更新入口只有这里和主页自动检查）
        // 后台下载中显示百分比，已下好显示「点此安装」，否则显示当前版本
        renderUpdateStatus(UpdateManager.state.value)
        binding.rowCheckUpdate.setOnClickListener { UpdateFlow.manualCheck(this) }
        // 订阅后台下载状态：进度随时刷新，下载完成的安装提示也在这里弹
        UpdateFlow.attach(this) { renderUpdateStatus(it) }

        // 更新日志
        binding.rowChangelog.setOnClickListener { showChangelog() }
    }

    /** 「检查更新」那一行的说明文字 */
    private fun renderUpdateStatus(state: UpdateManager.State) {
        binding.tvUpdateVersion.text = when (state) {
            is UpdateManager.State.Downloading ->
                getString(R.string.settings_update_downloading, state.version, state.percent)
            is UpdateManager.State.Ready ->
                getString(R.string.settings_update_ready, state.version)
            is UpdateManager.State.Failed ->
                getString(R.string.settings_update_failed)
            else -> {
                // 上次会话下好但没装（换了进程，内存状态没了）：同样提示可直接安装
                val cached = UpdateInstaller.downloadedApk(this)
                val cachedVersion = SettingsRepository.downloadedUpdateVersion
                if (cached != null && cachedVersion.isNotEmpty() &&
                    UpdateChecker.isNewer(cachedVersion, appVersion)
                ) {
                    getString(R.string.settings_update_ready, cachedVersion)
                } else {
                    getString(R.string.settings_update_version, appVersion)
                }
            }
        }
    }

    /** 字幕档位标签（顺序与 data/SubtitleSizes 的档位下标一致） */
    private val subtitleLabels: Array<String>
        get() = resources.getStringArray(R.array.subtitle_size_labels)

    private fun updateSubtitleSizeValue() {
        val level = SettingsRepository.subtitleSize.coerceIn(0, subtitleLabels.lastIndex)
        binding.tvSubtitleSizeValue.text = subtitleLabels[level]
    }

    /**
     * 字幕大小：小 / 中 / 大三档单选。
     *
     * 选中即保存并关闭——TV 上用遥控器少按一次「确定」更顺手；
     * 播放页按档位把字号换算成屏幕高度的百分比，换台电视不用重调。
     */
    private fun showSubtitleSizePicker() {
        val labels = subtitleLabels
        val current = SettingsRepository.subtitleSize.coerceIn(0, labels.lastIndex)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_subtitle_size)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                SettingsRepository.subtitleSize = which
                updateSubtitleSizeValue()
                dialog.dismiss()
            }
            .show()
    }

    /** 遥控器键位速查（与播放页实际键位一致） */
    private fun showTvHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tv_help_title)
            .setMessage(R.string.tv_help_message)
            .setPositiveButton(R.string.changelog_ok, null)
            .show()
    }

    /** 更新日志（内容见 util/Changelog.kt，发版时同步维护） */
    private fun showChangelog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_changelog, null)
        com.smbplayer.tv.databinding.DialogChangelogBinding.bind(view).tvText.text =
            Changelog.asText()
        val version = appVersion
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (version.isEmpty()) {
                    getString(R.string.action_changelog)
                } else {
                    getString(R.string.changelog_title, version)
                }
            )
            .setView(view)
            .setPositiveButton(R.string.changelog_ok, null)
            .show()
    }

    /** 当前版本号；缓存起来供「检查更新」行与更新日志标题复用 */
    private val appVersion: String by lazy {
        runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}
