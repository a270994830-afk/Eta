package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.device.BoundedFileCopy
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.core.AgentLogger
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 结构化文件工具族。
 *
 * 设计约束：能用 java.io 完成的操作优先用应用身份完成；仅在应用身份不可读/不可写时，
 * 且 Root 可用时，才回退到由本类固定构造的 Root 命令（绝不把模型参数拼成脚本，路径统一 shellQuote）。
 */
internal class AgentFileToolSet(
    private val context: Context,
    private val logger: AgentLogger,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) {

    fun execute(name: String, args: JSONObject): String? {
        if (name !in TOOL_NAMES) return null
        val result = runCatching { dispatch(name, args) }
            .getOrElse { errorResult("FILE_TOOL_FAILED", it.message ?: it.javaClass.simpleName) }
        logger.debug { "Agent file tool name=$name chars=${result.length}" }
        return result
    }

    private fun dispatch(name: String, args: JSONObject): String = when (name) {
        "file_exists" -> fileExists(args)
        "file_move" -> fileMove(args)
        "file_copy" -> fileCopy(args)
        "file_delete" -> fileDelete(args)
        "file_mkdir" -> fileMkdir(args)
        "file_find" -> fileFind(args)
        "file_grep" -> fileGrep(args)
        "file_edit" -> fileEdit(args)
        "file_zip" -> fileZip(args)
        "file_unzip" -> fileUnzip(args)
        "file_download" -> fileDownload(args)
        else -> errorResult("UNKNOWN_TOOL", "未知文件工具：$name")
    }

    // ---------------------------------------------------------------- file_exists

    private fun fileExists(args: JSONObject): String {
        val path = resolvePath(args.getString("path"))
        val state = pathState(path)
        val json = okResult("file_exists").put("path", path.absolutePath)
        if (!state.exists) {
            return json.put("exists", false)
                .put("type", "missing")
                .put("size_bytes", JSONObject.NULL)
                .put("modified_at", JSONObject.NULL)
                .toString()
        }
        var size: Long? = null
        var modified: Long? = null
        if (path.exists()) {
            if (state.type == "file") size = path.length()
            modified = path.lastModified().takeIf { it > 0 }
        } else if (rootAvailable()) {
            val q = shellQuote(path.absolutePath)
            val result = root.execute(
                "printf '%s %s' \"\$(stat -c %s $q 2>/dev/null)\" \"\$(stat -c %Y $q 2>/dev/null)\"",
                ROOT_SHORT_TIMEOUT_MS,
                4096,
            )
            if (result.ok) {
                val parts = result.stdout.trim().split(' ')
                size = parts.getOrNull(0)?.toLongOrNull()
                modified = parts.getOrNull(1)?.toLongOrNull()?.times(1000L)
            }
        }
        return json.put("exists", true)
            .put("type", state.type)
            .put("size_bytes", size ?: JSONObject.NULL)
            .put("modified_at", modified ?: JSONObject.NULL)
            .toString()
    }

    // ---------------------------------------------------------------- file_move

    private fun fileMove(args: JSONObject): String {
        val source = resolvePath(args.getString("source"))
        val destination = resolvePath(args.getString("destination"))
        val overwrite = args.optBoolean("overwrite", false)
        val state = pathState(source)
        if (!state.exists) return errorResult("FILE_NOT_FOUND", "源不存在：${source.absolutePath}")
        if (pathState(destination).exists) {
            if (!overwrite) return errorResult("DESTINATION_EXISTS", "目标已存在：${destination.absolutePath}")
            if (!deletePath(destination, recursive = true)) {
                return errorResult("DESTINATION_DELETE_FAILED", "无法覆盖已存在的目标：${destination.absolutePath}")
            }
        }
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return errorResult("DESTINATION_PARENT_FAILED", "无法创建目标目录：${parent.absolutePath}")
            }
        }
        if (runCatching { source.renameTo(destination) }.getOrDefault(false)) {
            return okResult("file_move").put("source", source.absolutePath)
                .put("destination", destination.absolutePath).put("mode", "rename").toString()
        }
        if (copyPath(source, destination)) {
            if (!deletePath(source, recursive = true)) {
                return errorResult("SOURCE_DELETE_FAILED", "已复制到目标，但无法删除源：${source.absolutePath}")
            }
            return okResult("file_move").put("source", source.absolutePath)
                .put("destination", destination.absolutePath).put("mode", "copy_delete").toString()
        }
        if (rootAvailable()) {
            val parent = destination.parentFile?.absolutePath ?: "/"
            val result = root.execute(
                "mkdir -p ${shellQuote(parent)} && mv -f ${shellQuote(source.absolutePath)} ${shellQuote(destination.absolutePath)}",
                ROOT_LONG_TIMEOUT_MS,
                ROOT_OUTPUT_BYTES,
            )
            if (result.ok) {
                return okResult("file_move").put("source", source.absolutePath)
                    .put("destination", destination.absolutePath).put("mode", "root_mv").toString()
            }
            return errorResult("MOVE_FAILED", result.stderr.ifBlank { "exit=${result.exitCode}" })
        }
        return errorResult("MOVE_FAILED", "移动失败：${source.absolutePath} -> ${destination.absolutePath}")
    }

    // ---------------------------------------------------------------- file_copy

    private fun fileCopy(args: JSONObject): String {
        val source = resolvePath(args.getString("source"))
        val destination = resolvePath(args.getString("destination"))
        val overwrite = args.optBoolean("overwrite", false)
        if (!pathState(source).exists) return errorResult("FILE_NOT_FOUND", "源不存在：${source.absolutePath}")
        if (pathState(destination).exists) {
            if (!overwrite) return errorResult("DESTINATION_EXISTS", "目标已存在：${destination.absolutePath}")
            if (!deletePath(destination, recursive = true)) {
                return errorResult("DESTINATION_DELETE_FAILED", "无法覆盖已存在的目标：${destination.absolutePath}")
            }
        }
        if (!copyPath(source, destination)) {
            return errorResult("COPY_FAILED", "复制失败：${source.absolutePath} -> ${destination.absolutePath}")
        }
        return okResult("file_copy").put("source", source.absolutePath)
            .put("destination", destination.absolutePath).put("overwrite", overwrite).toString()
    }

    // ---------------------------------------------------------------- file_delete

    private fun fileDelete(args: JSONObject): String {
        val path = resolvePath(args.getString("path"))
        val recursive = args.optBoolean("recursive", false)
        if (isDangerousDeleteTarget(path)) {
            return errorResult("REFUSED_DANGEROUS_PATH", "拒绝对危险路径执行删除：${path.absolutePath}")
        }
        val state = pathState(path)
        if (!state.exists) return errorResult("FILE_NOT_FOUND", "路径不存在：${path.absolutePath}")
        if (state.type == "directory" && !recursive) {
            return errorResult("RECURSIVE_REQUIRED", "删除目录必须显式设置 recursive=true")
        }
        if (!deletePath(path, recursive)) {
            return errorResult("DELETE_FAILED", "无法删除：${path.absolutePath}")
        }
        return okResult("file_delete").put("path", path.absolutePath).put("recursive", recursive).toString()
    }

    // ---------------------------------------------------------------- file_mkdir

    private fun fileMkdir(args: JSONObject): String {
        val path = resolvePath(args.getString("path"))
        val parents = args.optBoolean("parents", false)
        if (path.isDirectory) {
            return okResult("file_mkdir").put("path", path.absolutePath).put("created", false).toString()
        }
        if (path.exists()) return errorResult("NOT_A_DIRECTORY", "路径已存在且不是目录：${path.absolutePath}")
        val created = runCatching { if (parents) path.mkdirs() else path.mkdir() }.getOrDefault(false)
        if (!created && !path.isDirectory) {
            if (!rootAvailable()) return errorResult("MKDIR_FAILED", "无法创建目录：${path.absolutePath}")
            val flag = if (parents) "-p " else ""
            val result = root.execute(
                "mkdir $flag${shellQuote(path.absolutePath)}",
                ROOT_SHORT_TIMEOUT_MS,
                4096,
            )
            if (!result.ok) return errorResult("MKDIR_FAILED", result.stderr.ifBlank { "exit=${result.exitCode}" })
        }
        return okResult("file_mkdir").put("path", path.absolutePath).put("created", true).toString()
    }

    // ---------------------------------------------------------------- file_find

    private fun fileFind(args: JSONObject): String {
        val base = resolvePath(args.getString("path"))
        val name = args.optString("name").trim()
        if (name.isEmpty()) return errorResult("INVALID_ARGUMENT", "name 不能为空")
        val maxDepth = args.optInt("max_depth", 8).coerceIn(1, 64)
        val limit = args.optInt("limit", 200).coerceIn(1, 2000)
        if (!base.isDirectory) {
            if (pathState(base).exists && base.isFile) {
                return okResult("file_find").put("path", base.absolutePath)
                    .put("matches", JSONArray().put(base.absolutePath)).put("count", 1)
                    .put("scanned", 1).put("truncated", false).toString()
            }
            if (rootAvailable()) {
                val pattern = if (isGlob(name)) name else "*$name*"
                val result = root.execute(
                    "find ${shellQuote(base.absolutePath)} -maxdepth $maxDepth -name ${shellQuote(pattern)} 2>/dev/null | head -n $limit",
                    ROOT_LONG_TIMEOUT_MS,
                    ROOT_OUTPUT_BYTES,
                )
                if (!result.ok) return errorResult("FIND_FAILED", result.stderr.ifBlank { "exit=${result.exitCode}" })
                val matches = JSONArray()
                result.stdout.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { matches.put(it) }
                return okResult("file_find").put("path", base.absolutePath).put("matches", matches)
                    .put("count", matches.length()).put("truncated", result.truncated).toString()
            }
            return errorResult("NOT_A_DIRECTORY", "路径不是目录：${base.absolutePath}")
        }
        val matcher = nameMatcher(name)
        val matches = JSONArray()
        var scanned = 0
        var truncated = false
        fun walk(dir: File, depth: Int) {
            if (matches.length() >= limit || scanned >= MAX_SCAN_ENTRIES) {
                truncated = true
                return
            }
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (child in children) {
                if (matches.length() >= limit || scanned >= MAX_SCAN_ENTRIES) {
                    truncated = true
                    return
                }
                scanned++
                if (matcher(child.name)) matches.put(child.absolutePath)
                if (child.isDirectory && depth < maxDepth) walk(child, depth + 1)
            }
        }
        walk(base, 1)
        return okResult("file_find").put("path", base.absolutePath).put("name", name)
            .put("matches", matches).put("count", matches.length())
            .put("scanned", scanned).put("truncated", truncated).toString()
    }

    // ---------------------------------------------------------------- file_grep

    private fun fileGrep(args: JSONObject): String {
        val base = resolvePath(args.getString("path"))
        val patternText = args.optString("pattern")
        if (patternText.isEmpty()) return errorResult("INVALID_ARGUMENT", "pattern 不能为空")
        val glob = args.optString("glob").trim().takeIf { it.isNotEmpty() }
        val maxMatches = args.optInt("max_matches", 100).coerceIn(1, 500)
        val ignoreCase = args.optBoolean("ignore_case", false)
        val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
        val pattern = try {
            Pattern.compile(patternText, flags)
        } catch (failure: PatternSyntaxException) {
            return errorResult("INVALID_PATTERN", failure.message ?: "正则表达式无效")
        }
        val matcher = glob?.let { name -> nameMatcher(name) }
        val matches = JSONArray()
        var scanned = 0
        var truncated = false
        fun scan(file: File) {
            if (matches.length() >= maxMatches || scanned >= MAX_SCAN_ENTRIES) {
                truncated = true
                return
            }
            scanned++
            if (!file.isFile || !file.canRead()) return
            if (matcher != null && !matcher(file.name)) return
            if (file.length() > MAX_GREP_FILE_BYTES || isBinary(file)) return
            runCatching {
                file.bufferedReader().useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (matches.length() >= maxMatches) {
                            truncated = true
                            return@forEachIndexed
                        }
                        if (pattern.matcher(line).find()) {
                            matches.put(
                                JSONObject()
                                    .put("path", file.absolutePath)
                                    .put("line_number", index + 1)
                                    .put("text", line.take(MAX_GREP_LINE_CHARS)),
                            )
                        }
                    }
                }
            }
        }
        when {
            base.isFile -> scan(base)
            base.isDirectory -> walkFiles(base) { file ->
                scan(file)
                matches.length() < maxMatches && scanned < MAX_SCAN_ENTRIES
            }
            else -> return errorResult("FILE_NOT_FOUND", "路径不存在：${base.absolutePath}")
        }
        return okResult("file_grep").put("path", base.absolutePath).put("pattern", patternText)
            .put("matches", matches).put("count", matches.length())
            .put("scanned", scanned).put("truncated", truncated).toString()
    }

    // ---------------------------------------------------------------- file_edit

    private fun fileEdit(args: JSONObject): String {
        val path = resolvePath(args.getString("path"))
        val oldString = args.getString("old_string")
        val newString = args.optString("new_string")
        val all = args.optBoolean("all", false)
        if (oldString.isEmpty()) return errorResult("INVALID_ARGUMENT", "old_string 不能为空")
        val state = pathState(path)
        if (!state.exists) return errorResult("FILE_NOT_FOUND", "文件不存在：${path.absolutePath}")
        if (state.type == "directory") return errorResult("NOT_A_FILE", "路径是目录，不能编辑：${path.absolutePath}")
        val content = runCatching { path.readText() }
            .getOrElse { error -> return errorResult("READ_FAILED", error.message ?: "无法读取文件") }
        if (!content.contains(oldString)) return errorResult("OLD_STRING_NOT_FOUND", "未在文件中找到 old_string")
        val occurrences = countOccurrences(content, oldString)
        val replaced = if (all) occurrences else 1
        val updated = if (all) content.replace(oldString, newString) else content.replaceFirst(oldString, newString)
        val backup = File(path.parentFile, path.name + ".bak")
        runCatching { backup.writeText(content) }
            .getOrElse { error -> return errorResult("BACKUP_FAILED", error.message ?: "无法写入备份") }
        runCatching { path.writeText(updated) }
            .getOrElse { error -> return errorResult("WRITE_FAILED", error.message ?: "无法写入文件") }
        return okResult("file_edit").put("path", path.absolutePath)
            .put("backup", backup.absolutePath).put("replaced", replaced).toString()
    }

    // ---------------------------------------------------------------- file_zip

    private fun fileZip(args: JSONObject): String {
        val sourcesArray = args.optJSONArray("sources")
        if (sourcesArray == null || sourcesArray.length() == 0) {
            return errorResult("INVALID_ARGUMENT", "sources 不能为空")
        }
        val destination = resolvePath(args.getString("destination"))
        val sources = (0 until sourcesArray.length()).map { resolvePath(sourcesArray.getString(it)) }
        for (source in sources) {
            if (!pathState(source).exists) return errorResult("FILE_NOT_FOUND", "源不存在：${source.absolutePath}")
        }
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return errorResult("DESTINATION_PARENT_FAILED", "无法创建目标目录：${parent.absolutePath}")
            }
        }
        var entries = 0
        runCatching {
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                val written = mutableSetOf<String>()
                for (source in sources) {
                    if (source.isDirectory) {
                        entries += addDirectoryToZip(zip, source, source.parentFile, written)
                    } else {
                        addFileToZip(zip, source, source.name, written)
                        entries++
                    }
                }
            }
        }.getOrElse { failure -> return errorResult("ZIP_FAILED", failure.message ?: "打包失败") }
        return okResult("file_zip").put("destination", destination.absolutePath)
            .put("sources", JSONArray(sources.map { it.absolutePath })).put("entries", entries).toString()
    }

    private fun addFileToZip(zip: ZipOutputStream, file: File, entryName: String, written: MutableSet<String>) {
        val name = entryName.replace('\\', '/')
        if (name.isEmpty() || !written.add(name)) return
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun addDirectoryToZip(
        zip: ZipOutputStream,
        dir: File,
        base: File?,
        written: MutableSet<String>,
    ): Int {
        var count = 0
        val relative = relativeName(dir, base)
        if (relative.isNotEmpty()) {
            val name = "$relative/"
            if (written.add(name)) {
                zip.putNextEntry(ZipEntry(name))
                zip.closeEntry()
            }
        }
        val children = dir.listFiles() ?: return count
        for (child in children) {
            if (child.isDirectory) {
                count += addDirectoryToZip(zip, child, base, written)
            } else {
                addFileToZip(zip, child, relativeName(child, base), written)
                count++
            }
        }
        return count
    }

    private fun relativeName(file: File, base: File?): String {
        if (base == null) return file.name
        val basePath = base.absolutePath
        return file.absolutePath.removePrefix(basePath).trimStart('/')
    }

    // ---------------------------------------------------------------- file_unzip

    private fun fileUnzip(args: JSONObject): String {
        val archive = resolvePath(args.getString("path"))
        if (!archive.isFile) return errorResult("FILE_NOT_FOUND", "压缩包不存在：${archive.absolutePath}")
        val destination = resolvePath(args.getString("destination"))
        if (!destination.isDirectory && !destination.mkdirs()) {
            return errorResult("DESTINATION_FAILED", "无法创建解压目录：${destination.absolutePath}")
        }
        val destinationCanonical = destination.canonicalPath
        val extracted = JSONArray()
        var total = 0L
        var entries = 0
        runCatching {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ZIP_ENTRIES) throw IOException("压缩包条目过多")
                    val target = File(destination, entry.name).canonicalFile
                    val targetPath = target.path
                    if (targetPath != destinationCanonical &&
                        !targetPath.startsWith(destinationCanonical + File.separator)
                    ) {
                        throw IOException("检测到 zip-slip 路径：${entry.name}")
                    }
                    if (entry.isDirectory) {
                        if (!target.isDirectory && !target.mkdirs()) throw IOException("无法创建目录 ${targetPath}")
                    } else {
                        target.parentFile?.let { parent ->
                            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建目录 ${parent.path}")
                        }
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(8 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_UNZIP_BYTES) throw IOException("解压内容超过大小限制")
                                output.write(buffer, 0, read)
                            }
                        }
                        extracted.put(targetPath)
                    }
                    zip.closeEntry()
                }
            }
        }.getOrElse { failure -> return errorResult("UNZIP_FAILED", failure.message ?: "解压失败") }
        return okResult("file_unzip").put("path", archive.absolutePath)
            .put("destination", destination.absolutePath)
            .put("entries", extracted.length()).put("files", extracted).toString()
    }

    // ---------------------------------------------------------------- file_download

    private fun fileDownload(args: JSONObject): String {
        val urlText = args.optString("url").trim()
        if (urlText.isEmpty()) return errorResult("INVALID_ARGUMENT", "url 不能为空")
        val url = runCatching { urlText.toHttpUrlOrNull() }.getOrNull()
        if (url == null || (url.scheme != "http" && url.scheme != "https")) {
            return errorResult("INVALID_URL", "仅支持 http/https URL")
        }
        val destination = resolvePath(args.getString("destination"))
        val timeoutSeconds = args.optInt("timeout_seconds", 60).coerceIn(1, 300)
        val client = AgentHttpClient.client.newBuilder()
            .callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
        val tempFile = runCatching { File.createTempFile("eta-download-", ".tmp", context.cacheDir) }
            .getOrElse { return errorResult("DOWNLOAD_FAILED", "无法创建临时文件") }
        var bytes = 0L
        var contentType = ""
        var status = 0
        runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                status = response.code
                contentType = response.header("Content-Type").orEmpty()
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                tempFile.outputStream().buffered().use { output ->
                    response.body.byteStream().use { input ->
                        BoundedFileCopy.copy(input, output, MAX_DOWNLOAD_BYTES)
                    }
                }
                bytes = tempFile.length()
            }
        }.getOrElse { failure ->
            tempFile.delete()
            val code = if (failure is BoundedFileCopy.TooLargeException) "DOWNLOAD_TOO_LARGE" else "DOWNLOAD_FAILED"
            return errorResult(code, failure.message ?: failure.javaClass.simpleName)
        }
        destination.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                tempFile.delete()
                return errorResult("DESTINATION_FAILED", "无法创建目标目录：${parent.absolutePath}")
            }
        }
        runCatching {
            if (destination.exists()) destination.delete()
            if (!tempFile.renameTo(destination)) {
                tempFile.inputStream().use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                tempFile.delete()
            }
        }.getOrElse { failure ->
            tempFile.delete()
            return errorResult("DESTINATION_WRITE_FAILED", failure.message ?: "无法写入目标文件")
        }
        return okResult("file_download").put("url", urlText).put("destination", destination.absolutePath)
            .put("status", status).put("bytes_written", bytes).put("content_type", contentType).toString()
    }

    // ---------------------------------------------------------------- shared helpers

    private data class PathState(val exists: Boolean, val type: String)

    private fun pathState(path: File): PathState {
        if (path.exists()) {
            val type = when {
                path.isDirectory -> "directory"
                path.isFile -> "file"
                else -> "other"
            }
            return PathState(true, type)
        }
        val parent = path.parentFile
        if (parent == null || (parent.exists() && parent.canRead())) return PathState(false, "missing")
        if (!rootAvailable()) return PathState(false, "missing")
        val q = shellQuote(path.absolutePath)
        val result = root.execute(
            "if [ -e $q ]; then if [ -d $q ]; then echo directory; " +
                "elif [ -f $q ]; then echo file; else echo other; fi; else exit 21; fi",
            ROOT_SHORT_TIMEOUT_MS,
            4096,
        )
        if (result.exitCode == 21 || result.stdout.isBlank()) return PathState(false, "missing")
        return PathState(true, result.stdout.trim())
    }

    private fun copyPath(source: File, destination: File): Boolean {
        val copied = runCatching {
            copyRecursive(source, destination)
            true
        }.getOrDefault(false)
        if (copied) return true
        if (!rootAvailable()) return false
        val parent = destination.parentFile?.absolutePath ?: "/"
        val result = root.execute(
            "mkdir -p ${shellQuote(parent)} && cp -r ${shellQuote(source.absolutePath)} ${shellQuote(destination.absolutePath)}",
            ROOT_LONG_TIMEOUT_MS,
            ROOT_OUTPUT_BYTES,
        )
        return result.ok
    }

    private fun copyRecursive(source: File, destination: File) {
        if (source.isDirectory) {
            if (!destination.exists() && !destination.mkdirs()) {
                throw IOException("无法创建目录 ${destination.absolutePath}")
            }
            if (!destination.isDirectory) throw IOException("目标已存在且不是目录：${destination.absolutePath}")
            val children = source.listFiles() ?: throw IOException("无法读取目录 ${source.absolutePath}")
            for (child in children) copyRecursive(child, File(destination, child.name))
        } else {
            destination.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) throw IOException("无法创建目录 ${parent.absolutePath}")
            }
            source.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun deletePath(path: File, recursive: Boolean): Boolean {
        val deleted = runCatching {
            if (recursive) path.deleteRecursively() else path.delete()
        }.getOrDefault(false)
        if (deleted || !path.exists()) return true
        if (!rootAvailable()) return false
        val command = if (recursive) {
            "rm -rf ${shellQuote(path.absolutePath)}"
        } else {
            "rm -f ${shellQuote(path.absolutePath)}"
        }
        val result = root.execute(command, ROOT_LONG_TIMEOUT_MS, ROOT_OUTPUT_BYTES)
        return result.ok
    }

    private fun walkFiles(dir: File, visitor: (File) -> Boolean) {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (!visitor(child)) return
            if (child.isDirectory) walkFiles(child, visitor)
        }
    }

    private fun nameMatcher(name: String): (String) -> Boolean {
        if (isGlob(name)) {
            val regex = globToRegex(name)
            return { candidate -> regex.matches(candidate) }
        }
        return { candidate -> candidate.contains(name, ignoreCase = true) }
    }

    private fun isGlob(name: String): Boolean = name.any { it == '*' || it == '?' || it == '[' }

    private fun globToRegex(glob: String): Regex {
        val builder = StringBuilder("^")
        for (ch in glob) {
            when (ch) {
                '*' -> builder.append(".*")
                '?' -> builder.append('.')
                else -> builder.append(Regex.escape(ch.toString()))
            }
        }
        builder.append('$')
        return Regex(builder.toString())
    }

    private fun isBinary(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val buffer = ByteArray(1024)
            val read = input.read(buffer)
            if (read <= 0) false else {
                var hasZero = false
                for (index in 0 until read) {
                    if (buffer[index] == 0.toByte()) {
                        hasZero = true
                        break
                    }
                }
                hasZero
            }
        }
    }.getOrDefault(true)

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun isDangerousDeleteTarget(path: File): Boolean {
        val canonical = path.absolutePath
        return canonical == "/" || canonical in DANGEROUS_DELETE_PATHS
    }

    private fun resolvePath(raw: String): File {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "path 不能为空" }
        val effective = when {
            trimmed == "~" -> USER_STORAGE
            trimmed.startsWith("~/") -> "$USER_STORAGE/${trimmed.removePrefix("~/")}"
            trimmed.startsWith("/") -> trimmed
            else -> "$DEFAULT_CWD/$trimmed"
        }
        return File(effective).canonicalFile
    }

    private fun okResult(tool: String): JSONObject = JSONObject().put("ok", true).put("tool", tool)

    private fun errorResult(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message.take(300))
            .toString()

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        val TOOL_NAMES: Set<String> = setOf(
            "file_exists",
            "file_move",
            "file_copy",
            "file_delete",
            "file_mkdir",
            "file_find",
            "file_grep",
            "file_edit",
            "file_zip",
            "file_unzip",
            "file_download",
        )

        private const val DEFAULT_CWD = "/data/local/tmp/eta"
        private const val USER_STORAGE = "/storage/emulated/0"
        private const val ROOT_SHORT_TIMEOUT_MS = 8_000L
        private const val ROOT_LONG_TIMEOUT_MS = 25_000L
        private const val ROOT_OUTPUT_BYTES = 64 * 1024
        private const val MAX_SCAN_ENTRIES = 20_000
        private const val MAX_GREP_FILE_BYTES = 2L * 1024 * 1024
        private const val MAX_GREP_LINE_CHARS = 500
        private const val MAX_DOWNLOAD_BYTES = 32L * 1024 * 1024
        private const val MAX_UNZIP_BYTES = 512L * 1024 * 1024
        private const val MAX_ZIP_ENTRIES = 10_000

        private val DANGEROUS_DELETE_PATHS = setOf(
            "/",
            "/data",
            "/sdcard",
            "/storage",
            "/storage/emulated",
            "/storage/emulated/0",
            "/storage/self/primary",
            "/system",
            "/vendor",
            "/product",
            "/root",
            "/mnt",
            "/data/local/tmp",
        )
    }
}
