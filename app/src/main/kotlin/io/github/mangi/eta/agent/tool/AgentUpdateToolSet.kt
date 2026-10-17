package io.github.mangi.eta.agent.tool

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.model.AgentUpdateToolCatalog
import io.github.mangi.eta.core.AgentLogger
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * OTA 更新工具族：下载、校验（APK 合法性/版本比较）与安装新版本 APK。
 *
 * 约定：execute 只认 [TOOL_NAMES]；未知名字返回 null。任何异常都在内部转换为错误 JSON，
 * 不向调用方抛出，避免单个工具失败中断整轮 Agent 执行。
 */
internal class AgentUpdateToolSet(
    private val context: Context,
    private val logger: AgentLogger,
) {

    fun execute(name: String, args: JSONObject): String? {
        if (name !in TOOL_NAMES) return null
        return try {
            when (name) {
                "update_download" -> updateDownload(args)
                "update_verify" -> updateVerify(args)
                "update_install" -> updateInstall(args)
                else -> null
            }
        } catch (e: Exception) {
            logger.warn("Agent update tool action=$name outcome=failed error=${e.message}")
            error("TOOL_FAILED", "工具执行失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    // -------------------------------------------------------------- 下载

    private fun updateDownload(args: JSONObject): String {
        val requestedUrl = args.optString("url").trim()
        var url = requestedUrl
        var version = ""
        if (url.isEmpty()) {
            val release = try {
                fetchLatestRelease()
            } catch (e: SocketTimeoutException) {
                return error("TIMEOUT", "查询 GitHub Release 超时")
            } catch (e: IOException) {
                return error("NETWORK_ERROR", "查询 GitHub Release 失败：" + (e.message ?: e.javaClass.simpleName))
            }
            url = release.downloadUrl
            version = release.version
        }
        val scheme = runCatching { URI(url).scheme?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        if (scheme != "http" && scheme != "https") {
            return error("UNSUPPORTED_SCHEME", "只允许 http/https 下载，收到的 scheme=" + scheme.ifEmpty { "(无)" })
        }

        val destination = args.optString("destination").trim().ifEmpty {
            if (version.isEmpty()) {
                version = runCatching { fetchLatestRelease().version }.getOrDefault("")
            }
            DEFAULT_DOWNLOAD_DIR + "/eta-update-" + version.ifEmpty { "latest" } + ".apk"
        }
        val target = File(destination)
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            return error("DESTINATION_UNAVAILABLE", "无法创建目标目录：" + parent.absolutePath)
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val client = AgentHttpClient.client.newBuilder()
            .callTimeout(DOWNLOAD_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .build()

        var bytes = 0L
        val digest = MessageDigest.getInstance("MD5")
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 403 || response.code == 429) {
                    return error("RATE_LIMITED", "下载被限流（HTTP " + response.code + "），请稍后重试")
                }
                if (!response.isSuccessful) {
                    return error("HTTP_ERROR", "下载失败，服务器返回 HTTP " + response.code)
                }
                val declaredLength = response.body.contentLength()
                if (declaredLength > MAX_APK_BYTES) {
                    return error("FILE_TOO_LARGE", "APK 超过大小上限 " + MAX_APK_BYTES + " 字节")
                }
                response.body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(READ_CHUNK_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            bytes += read
                            if (bytes > MAX_APK_BYTES) throw IOException("APK 超过大小上限 " + MAX_APK_BYTES + " 字节")
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            target.delete()
            return error("TIMEOUT", "下载超时（" + DOWNLOAD_TIMEOUT_SECONDS / 60 + " 分钟）")
        } catch (e: IOException) {
            target.delete()
            return error("DOWNLOAD_FAILED", "下载失败：" + (e.message ?: e.javaClass.simpleName))
        }

        if (!target.isFile) return error("DOWNLOAD_FAILED", "下载后未生成文件：" + destination)
        if (target.length() <= MIN_APK_BYTES) {
            val size = target.length()
            target.delete()
            return error("INVALID_APK", "下载文件过小（" + size + " 字节），可能不是有效 APK")
        }
        if (!target.name.endsWith(".apk", ignoreCase = true)) {
            target.delete()
            return error("INVALID_APK", "目标文件名不是 .apk：" + target.name)
        }

        val md5 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        logger.info("Agent update tool action=update_download outcome=downloaded bytes=$bytes")
        return ok("update_download")
            .put("path", target.absolutePath)
            .put("bytes", bytes)
            .put("md5", md5)
            .apply { if (version.isNotEmpty()) put("version", version) }
            .put("url", url)
            .toString()
    }

    private data class LatestRelease(val version: String, val downloadUrl: String)

    private fun fetchLatestRelease(): LatestRelease {
        val request = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", GITHUB_ACCEPT)
            .header("User-Agent", USER_AGENT)
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .get()
            .build()
        val client = AgentHttpClient.client.newBuilder()
            .callTimeout(RELEASE_QUERY_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> throw IOException("该仓库还没有发布任何 Release")
                response.code == 403 || response.code == 429 -> throw IOException("GitHub API 限流，请稍后重试")
                !response.isSuccessful -> throw IOException("GitHub 返回 HTTP " + response.code)
            }
            val payload = response.body.string()
            val json = runCatching { JSONObject(payload) }.getOrNull()
                ?: throw IOException("无法解析 GitHub 返回内容")
            val version = json.optString("tag_name").trim().ifEmpty { json.optString("name").trim() }
                .trimStart('v', 'V')
            val assets = json.optJSONArray("assets") ?: JSONArray()
            val apkAsset = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            val downloadUrl = apkAsset?.optString("browser_download_url")?.takeIf(String::isNotBlank)
                ?: throw IOException("该 Release 没有可下载的 APK 资产")
            return LatestRelease(version, downloadUrl)
        }
    }

    // -------------------------------------------------------------- 校验

    private fun updateVerify(args: JSONObject): String {
        val path = args.optString("path").trim()
        if (path.isEmpty()) return error("INVALID_ARGUMENT", "缺少 path")
        val file = File(path)
        if (!file.isFile) return error("APK_NOT_FOUND", "找不到文件：" + path)

        val header = ByteArray(ZIP_MAGIC.size)
        val headRead = runCatching { FileInputStream(file).use { it.read(header) } }.getOrDefault(-1)
        if (headRead < ZIP_MAGIC.size || !header.contentEquals(ZIP_MAGIC)) {
            return error("INVALID_APK", "文件不是有效的 APK（ZIP 魔数不匹配）")
        }
        val hasManifest = runCatching {
            ZipFile(file).use { zip -> zip.getEntry("AndroidManifest.xml") != null }
        }.getOrDefault(false)
        if (!hasManifest) return error("INVALID_APK", "APK 内缺少 AndroidManifest.xml")

        val info = runCatching {
            context.packageManager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0L))
        }.getOrNull() ?: return error("INVALID_APK", "无法解析 APK 的包信息")
        val packageName = info.packageName?.takeIf(String::isNotBlank)
            ?: return error("INVALID_APK", "APK 缺少包名")
        val versionCode = info.longVersionCode
        val currentVersionCode = runCatching {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L)).longVersionCode
        }.getOrNull()
        val isNewer = currentVersionCode == null || versionCode > currentVersionCode

        return ok("update_verify")
            .put("path", path)
            .put("package_name", packageName)
            .put("version_name", info.versionName.orEmpty())
            .put("version_code", versionCode)
            .put("current_version_code", currentVersionCode ?: JSONObject.NULL)
            .put("is_newer", isNewer)
            .toString()
    }

    // -------------------------------------------------------------- 安装

    private fun updateInstall(args: JSONObject): String {
        val path = args.optString("path").trim()
        if (path.isEmpty()) return error("INVALID_ARGUMENT", "缺少 path")
        val file = File(path)
        if (!file.isFile) return error("APK_NOT_FOUND", "找不到 APK 文件：" + path)

        // ① 有 root 时用 su 静默安装；su 不存在或安装失败都降级到 ②
        val root = tryRootInstall(path)
        if (root.ran && root.success) {
            logger.info("Agent update tool action=update_install outcome=installed mode=root")
            return ok("update_install")
                .put("mode", "root")
                .put("installed", true)
                .put("path", path)
                .toString()
        }

        // ② 无 root / 静默安装失败：拉起系统安装器，由用户在界面上确认
        //    用 FileProvider 暴露下载目录里的 APK（file:// 在 targetSdk 36 会被 FileUriExposedException 拦）
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrElse { Uri.fromFile(file) }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val launched = runCatching { context.startActivity(intent) }
        return launched.fold(
            onSuccess = {
                ok("update_install")
                    .put("mode", "user_confirm")
                    .put("installed", false)
                    .put("path", path)
                    .put("root_attempted", root.ran)
                    .apply { if (root.ran) put("root_detail", root.detail.take(300)) }
                    .toString()
            },
            onFailure = { e ->
                error(
                    "NEEDS_USER_INSTALL",
                    "无法调起系统安装器，请手动安装该 APK：" + path +
                        "（原因：" + (e.message ?: e.javaClass.simpleName) + "）",
                    path,
                )
            },
        )
    }

    private data class RootInstallOutcome(val ran: Boolean, val success: Boolean, val detail: String)

    private fun tryRootInstall(path: String): RootInstallOutcome {
        val command = "pm install -r -d " + shellQuote(path)
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return RootInstallOutcome(false, false, e.message ?: "su 不可用")
        } catch (e: SecurityException) {
            return RootInstallOutcome(false, false, e.message ?: "su 不可用")
        }
        val finished = process.waitFor(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return RootInstallOutcome(true, false, "安装超时")
        }
        val output = runCatching {
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("").trim()
        return RootInstallOutcome(true, output.contains("Success"), output.ifEmpty { "exit=" + process.exitValue() })
    }

    // -------------------------------------------------------------- 公共

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun ok(tool: String): JSONObject = JSONObject().put("ok", true).put("tool", tool)

    private fun error(code: String, message: String, path: String? = null): String {
        val json = JSONObject().put("ok", false).put("code", code).put("message", message)
        if (path != null) json.put("path", path)
        return json.toString()
    }

    companion object {
        /** 与 AgentUpdateToolCatalog.TOOL_NAMES 保持同源。 */
        val TOOL_NAMES: Set<String> = AgentUpdateToolCatalog.TOOL_NAMES

        private const val USER_AGENT = "Eta-Agent-Update"
        private const val GITHUB_ACCEPT = "application/vnd.github+json"
        private const val GITHUB_API_VERSION = "2022-11-28"
        private const val REPOSITORY_OWNER = "Mangi-11"
        private const val REPOSITORY_NAME = "Eta"
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/" + REPOSITORY_OWNER + "/" + REPOSITORY_NAME + "/releases/latest"

        private const val DEFAULT_DOWNLOAD_DIR = "/sdcard/Download/智能体"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        private const val DOWNLOAD_TIMEOUT_SECONDS = 600
        private const val RELEASE_QUERY_TIMEOUT_SECONDS = 30
        private const val INSTALL_TIMEOUT_SECONDS = 120L
        private const val MAX_APK_BYTES = 300L * 1024 * 1024
        private const val MIN_APK_BYTES = 1024L * 1024
        private const val READ_CHUNK_BYTES = 64 * 1024

        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    }
}
