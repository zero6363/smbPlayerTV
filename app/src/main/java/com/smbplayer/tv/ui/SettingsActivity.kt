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
import com.smbplayer.tv.util.Changelog
import com.smbplayer.tv.util.toast

/**
 * 设置页（TV 版）：后台播放开关、遥控器操作说明、更新日志。
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

        // 遥控器操作说明
        binding.rowTvHelp.setOnClickListener { showTvHelp() }

        // 更新日志
        binding.rowChangelog.setOnClickListener { showChangelog() }
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
        val version = appVersion()
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

    private fun appVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    companion object {
        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}
