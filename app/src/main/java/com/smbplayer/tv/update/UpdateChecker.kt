package com.smbplayer.tv.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Release 检查。
 *
 * 电视上装应用要靠 U 盘，更新一次非常麻烦，所以版本号直接从仓库的
 * `releases/latest` 读：tag 形如 `v1.9-tv`，安装包放 release 的 assets（.apk）。
 *
 * 只读公开仓库，不需要 token；GitHub 对未认证请求按 IP 限流（每小时 60 次），
 * 因此启动检查按 [UpdateFlow] 里的间隔节流。
 */
object UpdateChecker {

    /** 发布仓库：改这里就能指向别的 fork */
    private const val OWNER = "zero6363"
    private const val REPO = "smbPlayerTV"

    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val USER_AGENT = "smbPlayerTV-updater"

    /** 一个可安装的 release（只保留更新需要的字段） */
    data class Release(
        /** 去掉前缀 v 的版本号，如 "1.9-tv" */
        val version: String,
        /** release 正文（更新说明），可能为空 */
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        /** 服务端给的字节数，0 表示未知 */
        val apkSize: Long,
        /** assets 的 sha256（GitHub 新接口才给），空表示不校验 */
        val sha256: String,
    )

    /**
     * 取最新正式 release。
     *
     * @return null 表示仓库还没有任何已发布 release（404），或最新 release 没挂 APK
     * @throws IOException 网络/解析失败，调用方按「检查失败」处理
     */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) { fetchLatest() }

    private fun fetchLatest(): Release? {
        val conn = (URL(API_LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            when (val code = conn.responseCode) {
                200 -> Unit
                404 -> return null // 还没有发布过 release
                else -> throw IOException("GitHub 返回 HTTP $code")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            // latest 接口本身已排除 draft / prerelease，这里再挡一次，防止仓库改了默认分支策略
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null

            val tag = json.optString("tag_name").ifBlank { json.optString("name") }
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                return Release(
                    version = normalize(tag),
                    notes = json.optString("body").orEmpty(),
                    apkUrl = asset.optString("browser_download_url"),
                    apkName = name,
                    apkSize = asset.optLong("size"),
                    sha256 = asset.optString("digest").removePrefix("sha256:").trim(),
                )
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** "v1.9-tv" -> "1.9-tv"，方便和本地 versionName 直接比较 */
    fun normalize(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /** remote 是否比 local 新 */
    fun isNewer(remote: String, local: String): Boolean = compare(remote, local) > 0

    /** a 是否不低于 b（用于判断「待安装版本已经装上了」） */
    fun isSameOrNewer(a: String, b: String): Boolean = compare(a, b) >= 0

    /**
     * 按数字段逐段比较："1.10-tv" > "1.9-tv"（按字符串比会反过来）。
     * 非数字字符（-tv 等后缀）忽略。
     */
    fun compare(a: String, b: String): Int {
        val left = numbers(a)
        val right = numbers(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 } - right.getOrElse(i) { 0 }
            if (diff != 0) return if (diff > 0) 1 else -1
        }
        return 0
    }

    private fun numbers(version: String): List<Int> =
        Regex("\\d+").findAll(version).map { it.value.toIntOrNull() ?: 0 }.toList()
}
