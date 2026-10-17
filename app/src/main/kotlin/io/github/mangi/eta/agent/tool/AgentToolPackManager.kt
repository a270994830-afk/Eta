package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.device.RootSuResolver
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 轻量插件包（Tool Pack）：用 JSON 声明新工具，热加载后即可像内置工具一样调用。
 *
 * 包目录：`filesDir/tool_packs/<packName>.json`，每个文件一个包。对模型暴露的工具名固定为
 * `pack_<packName>_<toolName>`（小写，非字母数字转下划线），由 [toolDefinitions] 下发给目录，
 * 调用时 [execute] 按该名字反查并执行。
 *
 * 不引入 JS 引擎：每个工具只有一条 `exec` 声明（`shell` 或 `http`），用 `{{arg}}` 模板把参数
 * 拼进命令 / URL。参数一律安全转义（shell 单引号，http URL 编码），缺失参数返回 MISSING_ARGUMENT。
 */
internal object AgentToolPackManager {

    private const val PREFIX = "pack_"
    private const val DIR_NAME = "tool_packs"

    /** 单个包文件大小上限（下载 / 安装时校验）。 */
    const val MAX_PACK_BYTES = 512 * 1024
    private const val MAX_HTTP_MAX_BYTES = 1024 * 1024
    private const val DEFAULT_HTTP_MAX_BYTES = 20_000
    private const val MAX_SHELL_OUTPUT_BYTES = 256 * 1024
    private const val DEFAULT_TIMEOUT_SECONDS = 15
    private const val MAX_TIMEOUT_SECONDS = 120

    private val logger: AgentLogger = AndroidAgentLogger

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var storageDir: File? = null

    @Volatile
    private var loaded: List<LoadedPack>? = null

    @Volatile
    private var indexedTools: Map<String, PackTool> = emptyMap()

    fun init(context: Context) {
        appContext = context.applicationContext
        if (storageDir == null) {
            storageDir = File(context.applicationContext.filesDir, DIR_NAME)
        }
    }

    /** 单测注入存储目录（不依赖 Android Context）。 */
    internal fun attachStorageDir(dir: File?) {
        storageDir = dir
        synchronized(this) {
            loaded = null
            indexedTools = emptyMap()
        }
    }

    // ------------------------------------------------------------------ 目录查询

    /** 全部包（含解析失败项）的描述数组。 */
    fun listPacks(): JSONArray {
        ensureLoaded()
        return summarize(loaded.orEmpty())
    }

    /**
     * 给工具目录用的 OpenAI function schema 列表；[packName] 非空时只返回该包的工具。
     */
    fun toolDefinitions(packName: String? = null): JSONArray {
        val array = JSONArray()
        for (pack in ensureLoaded()) {
            if (packName != null && pack.name != packName) continue
            for (tool in pack.tools) {
                array.put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", tool.fullName)
                                .put(
                                    "description",
                                    tool.description.ifBlank {
                                        "插件包 ${tool.packName} 提供的工具 ${tool.toolName}"
                                    },
                                )
                                .put("parameters", tool.parameters),
                        ),
                )
            }
        }
        return array
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 执行一个插件工具；[fullName] 不是已知插件工具时返回 null（交给上层按「未知工具」处理）。
     */
    fun execute(fullName: String, args: JSONObject): String? {
        if (!fullName.startsWith(PREFIX)) return null
        val tool = indexedTool(fullName) ?: return null
        return runCatching { executeTool(tool, args) }.getOrElse { failure ->
            logger.warn("AgentToolPack $fullName failed: ${failure.javaClass.simpleName}: ${failure.message}")
            error("TOOL_INTERNAL_ERROR", failure.message ?: failure.javaClass.simpleName)
        }
    }

    private fun executeTool(tool: PackTool, args: JSONObject): String = when (tool.execType) {
        "shell" -> executeShell(tool, args)
        "http" -> executeHttp(tool, args)
        else -> error("UNSUPPORTED_EXEC_TYPE", "不支持的 exec.type: ${tool.execType}")
    }

    private fun executeShell(tool: PackTool, args: JSONObject): String {
        val template = tool.exec.optString("command", "")
        if (template.isBlank()) return error("INVALID_PACK", "shell 工具缺少 exec.command")
        val command = try {
            Template.render(template, args, ::shellQuote)
        } catch (missing: MissingArgumentException) {
            return missingArgument(missing.key)
        }
        val timeout = tool.exec.optInt("timeout_seconds", DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)
        val useRoot = tool.exec.optBoolean("root", false)
        return runShell(command, timeout, useRoot)
    }

    private fun executeHttp(tool: PackTool, args: JSONObject): String {
        val template = tool.exec.optString("url", "")
        if (template.isBlank()) return error("INVALID_PACK", "http 工具缺少 exec.url")
        val rendered = try {
            Template.render(template, args, ::urlEncode)
        } catch (missing: MissingArgumentException) {
            return missingArgument(missing.key)
        }
        val url = rendered.toHttpUrlOrNull()
        if (url == null || (url.scheme != "http" && url.scheme != "https")) {
            return error("INVALID_URL", "仅支持 http/https URL")
        }
        val method = tool.exec.optString("method", "GET").trim().uppercase().ifBlank { "GET" }
        val builder = Request.Builder().url(url)
        when (method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "DELETE" -> builder.delete()
            "POST" -> builder.post(ByteArray(0).toRequestBody())
            else -> return error("INVALID_ARGUMENT", "不支持的 http method: $method")
        }
        val maxBytes = tool.exec.optInt("max_bytes", DEFAULT_HTTP_MAX_BYTES)
            .coerceIn(1, MAX_HTTP_MAX_BYTES)
        val timeout = tool.exec.optInt("timeout_seconds", DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)
        val client = AgentHttpClient.client.newBuilder()
            .callTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .build()
        val response = runCatching { client.newCall(builder.build()).execute() }.getOrElse { failure ->
            return error("HTTP_FAILED", failure.message ?: failure.javaClass.simpleName)
        }
        response.use { resp ->
            val body = readBounded(resp.body.byteStream(), maxBytes)
            val result = JSONObject()
                .put("ok", resp.isSuccessful)
                .put("status", resp.code)
                .put("content_type", resp.header("Content-Type").orEmpty())
                .put("body", body.text)
                .put("truncated", body.truncated)
            if (!resp.isSuccessful) result.put("message", "HTTP ${resp.code}")
            return result.toString()
        }
    }

    private fun runShell(command: String, timeoutSeconds: Int, useRoot: Boolean): String {
        val process = if (useRoot) {
            val su = RootSuResolver.resolve()
                ?: return error("ROOT_UNAVAILABLE", "本机未检测到 su，无法以 root 执行；请去掉 exec.root 或改用普通 shell。")
            runCatching { ProcessBuilder(su, "-c", command).start() }.getOrElse { failure ->
                return error("ROOT_UNAVAILABLE", "启动 su 失败: ${failure.message}")
            }
        } else {
            runCatching { Runtime.getRuntime().exec(arrayOf("sh", "-c", command)) }.getOrElse { failure ->
                return error("SHELL_UNAVAILABLE", "启动 sh 失败: ${failure.message}")
            }
        }
        runCatching { process.outputStream.close() }
        var stdout = ""
        var stderr = ""
        var truncated = false
        val stdoutThread = Thread {
            val read = readBounded(process.inputStream, MAX_SHELL_OUTPUT_BYTES)
            stdout = read.text
            if (read.truncated) truncated = true
        }.apply { isDaemon = true }
        val stderrThread = Thread {
            val read = readBounded(process.errorStream, MAX_SHELL_OUTPUT_BYTES)
            stderr = read.text
            if (read.truncated) truncated = true
        }.apply { isDaemon = true }
        stdoutThread.start()
        stderrThread.start()
        val finished = runCatching {
            process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroy() }
            runCatching { process.waitFor(300L, TimeUnit.MILLISECONDS) }
            if (process.isAlive) runCatching { process.destroyForcibly() }
        }
        runCatching { stdoutThread.join(1000L) }
        runCatching { stderrThread.join(1000L) }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        val exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        return if (!finished) {
            JSONObject()
                .put("ok", false)
                .put("code", "TIMEOUT")
                .put("message", "命令执行超过 ${timeoutSeconds}s 已终止")
                .put("exit_code", exitCode)
                .put("stdout", stdout)
                .put("stderr", stderr)
                .toString()
        } else {
            JSONObject()
                .put("ok", true)
                .put("exit_code", exitCode)
                .put("stdout", stdout)
                .put("stderr", stderr)
                .put("truncated", truncated)
                .toString()
        }
    }

    // ------------------------------------------------------------------ 安装 / 移除 / 重载

    /** 从 URL 下载一个包并安装（大小上限 [MAX_PACK_BYTES]）。 */
    fun installFromUrl(url: String, packName: String? = null): String {
        val httpUrl = url.trim().toHttpUrlOrNull()
        if (httpUrl == null || (httpUrl.scheme != "http" && httpUrl.scheme != "https")) {
            return error("INVALID_URL", "仅支持 http/https URL")
        }
        val content = runCatching {
            AgentHttpClient.client.newCall(Request.Builder().url(httpUrl).get().build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = readBounded(response.body.byteStream(), MAX_PACK_BYTES + 1)
                if (body.truncated || body.bytes.size > MAX_PACK_BYTES) {
                    throw IOException("包体超过上限 ${MAX_PACK_BYTES / 1024}KB")
                }
                body.text
            }
        }.getOrElse { failure ->
            return error("PACK_DOWNLOAD_FAILED", failure.message ?: failure.javaClass.simpleName)
        }
        return installFromJson(content, packName)
    }

    /** 用内联 JSON 安装一个包；[packName] 非空时覆盖包内 `name`。 */
    fun installFromJson(content: String, packName: String? = null): String {
        val text = content.trim()
        if (text.isEmpty()) return error("INVALID_PACK", "包内容为空")
        val json = runCatching { JSONObject(text) }.getOrElse { failure ->
            return error("INVALID_JSON", "JSON 解析失败: ${failure.message}")
        }
        val declaredName = json.optString("name", "").trim()
        val name = (packName?.trim()?.takeIf { it.isNotEmpty() }) ?: declaredName
        if (name.isEmpty()) return error("INVALID_PACK", "缺少包名（json.name 或 name 参数）")
        val safeName = validateName(name)
            ?: return error("INVALID_PACK_NAME", "包名只能含字母、数字、下划线、连字符与点，且不能是 . 或 ..")
        val tools = json.optJSONArray("tools")
        if (tools == null || tools.length() == 0) return error("INVALID_PACK", "tools 必须是非空数组")
        val dir = packsDir() ?: return error("STORAGE_UNAVAILABLE", "工具包目录不可用")
        if (!dir.isDirectory && !dir.mkdirs()) return error("STORAGE_UNAVAILABLE", "无法创建工具包目录")
        if (safeName != declaredName) json.put("name", safeName)
        val payload = json.toString()
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_PACK_BYTES) {
            return error("PACK_TOO_LARGE", "包体超过上限 ${MAX_PACK_BYTES / 1024}KB")
        }
        val target = File(dir, "$safeName.json")
        runCatching {
            val tmp = File(dir, "$safeName.json.tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(target)) {
                target.writeText(payload)
                tmp.delete()
            }
        }.getOrElse { failure ->
            return error("PACK_WRITE_FAILED", failure.message ?: "写入失败")
        }
        reload()
        val pack = loaded.orEmpty().firstOrNull { it.name == safeName }
        return packResult(safeName, target, pack)
    }

    /** 删除一个包文件并重载。 */
    fun remove(name: String): String {
        val safeName = validateName(name) ?: return error("INVALID_PACK_NAME", "非法包名: $name")
        val dir = packsDir() ?: return error("STORAGE_UNAVAILABLE", "工具包目录不可用")
        val file = File(dir, "$safeName.json")
        if (!file.isFile) return error("PACK_NOT_FOUND", "未找到工具包: $safeName")
        if (!file.delete()) return error("PACK_DELETE_FAILED", "删除失败: ${file.absolutePath}")
        reload()
        return JSONObject().put("ok", true).put("name", safeName).put("file", file.absolutePath).toString()
    }

    /** 重新扫描包目录并重建工具索引；返回汇总 JSON。 */
    @Synchronized
    fun reload(): String {
        val dir = packsDir()
        val files = if (dir != null && dir.isDirectory) {
            dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
                ?.sortedBy { it.name }
                .orEmpty()
        } else {
            emptyList()
        }
        val packs = files.map { parsePack(it) }
        val index = LinkedHashMap<String, PackTool>()
        val warnings = JSONArray()
        for (pack in packs) {
            for (tool in pack.tools) {
                val existing = index[tool.fullName]
                if (existing != null) {
                    warnings.put("${pack.name}: 工具名 ${tool.fullName} 与包 ${existing.packName} 冲突，已忽略")
                    continue
                }
                index[tool.fullName] = tool
            }
        }
        loaded = packs
        indexedTools = index
        val summary = JSONObject()
            .put("ok", true)
            .put("directory", dir?.absolutePath ?: "")
            .put("pack_count", packs.size)
            .put("tool_count", index.size)
            .put("packs", summarize(packs))
        if (warnings.length() > 0) summary.put("warnings", warnings)
        return summary.toString()
    }

    // ------------------------------------------------------------------ 解析

    private fun parsePack(file: File): LoadedPack {
        val raw = runCatching { file.readBytes() }.getOrElse { failure ->
            return LoadedPack(
                name = file.name.removeSuffix(".json"),
                description = "",
                version = "",
                sha256 = "",
                file = file.name,
                tools = emptyList(),
                error = "读取失败: ${failure.message}",
                warnings = emptyList(),
            )
        }
        val hash = sha256(raw)
        val json = runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrElse { failure ->
            return LoadedPack(
                name = file.name.removeSuffix(".json"),
                description = "",
                version = "",
                sha256 = hash,
                file = file.name,
                tools = emptyList(),
                error = "JSON 解析失败: ${failure.message}",
                warnings = emptyList(),
            )
        }
        val name = json.optString("name", "").trim().ifBlank { file.name.removeSuffix(".json") }
        val description = json.optString("description", "").trim()
        val version = json.optString("version", "").trim()
        val toolsArray = json.optJSONArray("tools")
            ?: return LoadedPack(name, description, version, hash, file.name, emptyList(), "缺少 tools 数组", emptyList())
        val declaredHash = json.optString("sha256", "").trim().lowercase()
        if (declaredHash.isNotEmpty() && declaredHash != sha256(toolsArray.toString().toByteArray(Charsets.UTF_8))) {
            return LoadedPack(name, description, version, hash, file.name, emptyList(), "sha256 校验失败", emptyList())
        }
        val tools = ArrayList<PackTool>()
        val issues = ArrayList<String>()
        for (index in 0 until toolsArray.length()) {
            val entry = toolsArray.optJSONObject(index)
            if (entry == null) {
                issues.add("第 $index 项不是对象")
                continue
            }
            val toolName = entry.optString("name", "").trim()
            if (toolName.isEmpty()) {
                issues.add("第 $index 项缺少 name")
                continue
            }
            val exec = entry.optJSONObject("exec")
            if (exec == null) {
                issues.add("$toolName 缺少 exec")
                continue
            }
            val execType = exec.optString("type", "").trim().lowercase()
            if (execType != "shell" && execType != "http") {
                issues.add("$toolName 的 exec.type 仅支持 shell/http")
                continue
            }
            tools.add(
                PackTool(
                    packName = name,
                    toolName = toolName,
                    fullName = fullName(name, toolName),
                    description = entry.optString("description", "").trim(),
                    parameters = entry.optJSONObject("parameters")
                        ?: JSONObject().put("type", "object").put("properties", JSONObject()),
                    exec = exec,
                    execType = execType,
                ),
            )
        }
        if (tools.isEmpty()) {
            return LoadedPack(
                name = name,
                description = description,
                version = version,
                sha256 = hash,
                file = file.name,
                tools = emptyList(),
                error = issues.joinToString("；").ifBlank { "没有可用工具" },
                warnings = emptyList(),
            )
        }
        return LoadedPack(name, description, version, hash, file.name, tools, null, issues)
    }

    private fun summarize(packs: List<LoadedPack>): JSONArray {
        val array = JSONArray()
        for (pack in packs) {
            val tools = JSONArray()
            for (tool in pack.tools) {
                tools.put(
                    JSONObject()
                        .put("name", tool.toolName)
                        .put("full_name", tool.fullName)
                        .put("description", tool.description),
                )
            }
            val entry = JSONObject()
                .put("name", pack.name)
                .put("description", pack.description)
                .put("version", pack.version)
                .put("sha256", pack.sha256)
                .put("file", pack.file)
                .put("tool_count", pack.tools.size)
                .put("valid", pack.error == null)
                .put("tools", tools)
            pack.error?.let { entry.put("error", it) }
            if (pack.warnings.isNotEmpty()) {
                val warnings = JSONArray()
                pack.warnings.forEach { warnings.put(it) }
                entry.put("warnings", warnings)
            }
            array.put(entry)
        }
        return array
    }

    private fun packResult(name: String, file: File, pack: LoadedPack?): String {
        val tools = JSONArray()
        pack?.tools?.forEach { tool -> tools.put(tool.fullName) }
        val result = JSONObject()
            .put("ok", pack?.error == null)
            .put("name", name)
            .put("file", file.absolutePath)
            .put("tool_count", pack?.tools?.size ?: 0)
            .put("tools", tools)
        if (pack?.error != null) {
            result.put("code", "INVALID_PACK")
            result.put("message", pack.error)
        } else if (pack != null && pack.warnings.isNotEmpty()) {
            val warnings = JSONArray()
            pack.warnings.forEach { warnings.put(it) }
            result.put("warnings", warnings)
        }
        return result.toString()
    }

    @Synchronized
    private fun ensureLoaded(): List<LoadedPack> {
        loaded?.let { return it }
        reload()
        return loaded.orEmpty()
    }

    private fun indexedTool(fullName: String): PackTool? {
        ensureLoaded()
        return indexedTools[fullName]
    }

    private fun packsDir(): File? {
        storageDir?.let { return it }
        val context = appContext ?: return null
        val dir = File(context.filesDir, DIR_NAME)
        storageDir = dir
        return dir
    }

    // ------------------------------------------------------------------ 工具

    private fun validateName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return null
        val allowed = trimmed.all { ch ->
            (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '_' || ch == '-' || ch == '.'
        }
        return if (allowed) trimmed else null
    }

    private fun fullName(packName: String, toolName: String): String =
        PREFIX + sanitize(packName) + "_" + sanitize(toolName)

    private fun sanitize(value: String): String {
        val builder = StringBuilder(value.length)
        for (ch in value.lowercase()) {
            builder.append(if ((ch in 'a'..'z') || (ch in '0'..'9')) ch else '_')
        }
        return builder.toString()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            builder.append("%02x".format(byte.toInt() and 0xff))
        }
        return builder.toString()
    }

    private fun readBounded(stream: InputStream, limit: Int): BoundedBytes {
        val buffer = ByteArrayOutputStream(limit.coerceAtMost(64 * 1024))
        val chunk = ByteArray(8192)
        var total = 0
        var truncated = false
        try {
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                val remaining = limit - total
                if (remaining > 0) {
                    val take = minOf(read, remaining)
                    buffer.write(chunk, 0, take)
                    total += take
                    if (read > take) truncated = true
                } else {
                    truncated = true
                }
            }
        } catch (_: IOException) {
        }
        return BoundedBytes(buffer.toByteArray(), truncated)
    }

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun missingArgument(key: String): String =
        error("MISSING_ARGUMENT", "缺少参数: $key")

    // ------------------------------------------------------------------ 数据结构

    private class BoundedBytes(val bytes: ByteArray, val truncated: Boolean) {
        val text: String get() = String(bytes, Charsets.UTF_8)
    }

    private data class PackTool(
        val packName: String,
        val toolName: String,
        val fullName: String,
        val description: String,
        val parameters: JSONObject,
        val exec: JSONObject,
        val execType: String,
    )

    private data class LoadedPack(
        val name: String,
        val description: String,
        val version: String,
        val sha256: String,
        val file: String,
        val tools: List<PackTool>,
        val error: String?,
        val warnings: List<String>,
    )

    private class MissingArgumentException(val key: String) : Exception("missing argument: $key")

    /** `{{key}}` 模板渲染：命中参数则转义后替换，缺失则抛 [MissingArgumentException]。 */
    private object Template {
        private val PATTERN = Regex("""\{\{\s*([A-Za-z0-9_]+)\s*\}\}""")

        fun render(template: String, args: JSONObject, escape: (String) -> String): String =
            PATTERN.replace(template) { match ->
                val key = match.groupValues[1]
                val value = valueOf(args, key) ?: throw MissingArgumentException(key)
                escape(value)
            }

        private fun valueOf(args: JSONObject, key: String): String? {
            if (!args.has(key)) return null
            val value = args.opt(key) ?: return null
            if (value === JSONObject.NULL) return null
            return if (value is String) value else value.toString()
        }
    }
}
