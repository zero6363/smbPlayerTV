package com.smbplayer.tv.update

import android.content.Context
import com.smbplayer.tv.util.logInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 更新包下载。
 *
 * 落在 `cacheDir/update/`（应用私有、不需要任何存储权限，系统清缓存时也会顺带回收）。
 * 先写 `.part` 再改名，避免网络中断留下一个「看着像完整包」的半截 APK 被拿去安装。
 *
 * 国内直连 github.com 的 release 附件经常被重置（api.github.com 能通、附件域名被断），
 * 所以直连失败后依次换公共加速镜像重试；最终仍以 release 里的 sha256 为准，
 * 镜像返回错误页/被篡改都会在校验阶段被丢掉。
 */
object UpdateDownloader {

    private const val DIR = "update"
    private const val USER_AGENT = "smbPlayerTV-updater"

    /** 下载加速前缀：直连失败时按顺序套在原始地址前面（只对 GitHub 附件地址生效） */
    private val MIRROR_PREFIXES = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

    fun dir(context: Context): File = File(context.cacheDir, DIR)

    /** 指定版本的安装包路径（按版本命名，换版本自然换文件名） */
    fun fileFor(context: Context, version: String): File =
        File(dir(context), "smbPlayerTV_${version}.apk")

    /**
     * 下载 [release] 的 APK：先直连，失败换镜像。
     *
     * @param onProgress 0..100，在 IO 线程回调，UI 层自己切主线程
     * @return 下载完成的文件
     * @throws IOException 所有源都失败、包不完整或 sha256 对不上
     */
    suspend fun download(
        context: Context,
        release: UpdateChecker.Release,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        val urls = candidateUrls(release)
        for ((index, url) in urls.withIndex()) {
            try {
                return@withContext downloadFrom(context, release, url, onProgress)
            } catch (e: Exception) {
                lastError = e
                // 直连是 index 0，后面都是镜像；失败换下一个源，进度归零重来
                logInfo("更新包下载失败（源 ${index + 1}/${urls.size}）：${e.message}")
                onProgress(0)
            }
        }
        throw IOException("所有下载源都失败：${lastError?.message ?: "网络不可用"}", lastError)
    }

    private fun candidateUrls(release: UpdateChecker.Release): List<String> {
        val urls = mutableListOf(release.apkUrl)
        if (release.apkUrl.startsWith("https://github.com/")) {
            MIRROR_PREFIXES.forEach { prefix -> urls.add(prefix + release.apkUrl) }
        }
        return urls
    }

    private fun downloadFrom(
        context: Context,
        release: UpdateChecker.Release,
        url: String,
        onProgress: (Int) -> Unit,
    ): File {
        val dest = fileFor(context, release.version)
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        part.delete()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")

            // Content-Length 可能缺失（GitHub 走 CDN 时偶尔没有），用 API 里给的大小兜底
            val total = conn.getHeaderField("Content-Length")?.toLongOrNull()
                ?.takeIf { it > 0 } ?: release.apkSize
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var lastPercent = -1

            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = ((read * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }

            if (total > 0 && read != total) {
                throw IOException("安装包不完整（$read/$total 字节）")
            }
            if (release.sha256.isNotEmpty()) {
                val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                if (!actual.equals(release.sha256, ignoreCase = true)) {
                    throw IOException("安装包校验失败")
                }
            }

            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            onProgress(100)
            return dest
        } finally {
            conn.disconnect()
            // 失败路径：半截文件不留在缓存里
            if (part.exists() && !dest.exists()) part.delete()
        }
    }
}
