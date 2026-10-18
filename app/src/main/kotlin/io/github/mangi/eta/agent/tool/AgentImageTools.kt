package io.github.mangi.eta.agent.tool

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.BoundedFileCopy
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.media.MAX_AGENT_IMAGE_BYTES
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentFunctionModelResolver
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import org.json.JSONArray
import org.json.JSONObject

/** 读取用户已明确指定的图片（单张或多张，最多 3 张），并以临时视觉附件交给当前模型回合。 */
internal class AgentImageTools(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) {
    fun readImage(args: JSONObject): AgentModelClient.ToolResult {
        val requested = requestedPaths(args)
        if (requested.isEmpty()) {
            return sensitive(error("IMAGE_PATH_REQUIRED", "请提供 path（单张）或 paths（最多 $MAX_IMAGES_PER_CALL 张）"))
        }
        if (requested.size > MAX_IMAGES_PER_CALL) {
            return sensitive(error("IMAGE_TOO_MANY", "一次最多读取 $MAX_IMAGES_PER_CALL 张图片"))
        }
        val images = mutableListOf<AgentModelClient.ModelImage>()
        val succeeded = mutableListOf<String>()
        val failures = mutableListOf<ImageFailure>()
        for (source in requested) {
            when (val read = readSingleImage(source)) {
                is SingleImageRead.Success -> {
                    images += read.image
                    succeeded += source
                }
                is SingleImageRead.Failure -> failures += ImageFailure(source, read.code, read.message)
            }
        }
        if (images.isEmpty()) {
            val first = failures.first()
            return sensitive(error(first.code, first.message))
        }
        // 若有 VISION 功能槽位（默认 Trae qwen-3.7-plus，实测最强视觉模型），
        // 用该模型独立识别图片内容返回，避免当前主模型不具备视觉能力而无法理解图像。
        if (images.size == 1) {
            recognizeWithVisionSlot(images.first())?.let { return it }
        }
        val content = JSONObject()
            .put("ok", true)
            .put("tool", "read_image")
            .put("image_attached", true)
            .put("paths", JSONArray(succeeded))
        if (failures.isNotEmpty()) {
            content.put("errors", JSONArray(failures.map { failure -> failure.toJson() }))
        }
        return sensitive(content = content.toString(), images = images)
    }

    /**
     * 尝试用 VISION 槽位模型（默认 Trae qwen-3.7-plus）独立识别单张图片；
     * 槽位不可解析或调用失败时返回 null，由调用方继续走“图片作为附件交给当前模型”。
     */
    private fun recognizeWithVisionSlot(image: AgentModelClient.ModelImage): AgentModelClient.ToolResult? {
        val config = AgentFunctionModelResolver.resolveSlot(AgentFunctionModelResolver.PURPOSE_VISION)
            ?: return null
        return runCatching {
            val prompt = "请识别这张图片：描述图中主要内容、醒目文字、数字与关键信息，尽量完整。"
            val response = kotlinx.coroutines.runBlocking {
                AgentModelClient.complete(
                    config = config,
                    prompt = prompt,
                    toolExecutor = AgentModelClient.ToolExecutor { _ ->
                        AgentModelClient.ToolResult(content = "")
                    },
                    images = listOf(image),
                )
            }
            val text = response.content.trim()
            if (text.isEmpty()) return null
            sensitive(
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", "read_image")
                    .put("image_attached", true)
                    .put("recognized", true)
                    .put("model", "${config.providerId}/${config.model}")
                    .put("paths", JSONArray(listOf(image.reference)))
                    .put("description", text)
                    .toString(),
                images = listOf(image),
            )
        }.getOrNull()
    }

    private fun requestedPaths(args: JSONObject): List<String> {
        val result = LinkedHashSet<String>()
        args.optString("path").trim().takeIf { it.isNotEmpty() }?.let(result::add)
        val paths = args.optJSONArray("paths") ?: return result.toList()
        for (index in 0 until paths.length()) {
            paths.optString(index).trim().takeIf { it.isNotEmpty() }?.let(result::add)
        }
        return result.toList()
    }

    private fun readSingleImage(rawSource: String): SingleImageRead {
        val source = rawSource.removePrefix("file://")
        val sourceKind = when {
            source.startsWith("content://") -> ImageSourceKind.ContentUri
            source.startsWith("/") && !source.contains('\u0000') -> ImageSourceKind.File
            else -> return SingleImageRead.Failure("IMAGE_PATH_DENIED", "图片路径必须是绝对路径、file URI 或已授权的 content URI")
        }
        val temporaryFile = runCatching {
            File.createTempFile("eta-read-image-", ".img", imageCacheDirectory())
        }.getOrElse {
            return SingleImageRead.Failure("IMAGE_TEMPORARY_FILE_FAILED", "无法创建图片临时文件")
        }
        return try {
            val staged = copyAsApp(source, sourceKind, temporaryFile)
            if (!staged) {
                if (!rootAvailable()) {
                    return SingleImageRead.Failure("IMAGE_ACCESS_DENIED", "Eta 无法读取此图片；请先通过文件选择器导入或授予读取权限")
                }
                val copyResult = root.execute(
                    imageCopyCommand(source, sourceKind, temporaryFile),
                    timeoutMillis = READ_TIMEOUT_MS,
                    maxOutputBytes = 8 * 1024,
                )
                if (!copyResult.ok) return copyFailure(copyResult)
            }
            val image = AgentImageCodec.fromToolFile(
                file = temporaryFile,
                source = "tool_read_image",
            ) ?: return SingleImageRead.Failure("IMAGE_UNSUPPORTED", "文件不是可识别的图片")
            SingleImageRead.Success(image)
        } catch (_: BoundedFileCopy.TooLargeException) {
            SingleImageRead.Failure("IMAGE_TOO_LARGE", "图片超过大小限制")
        } finally {
            temporaryFile.delete()
        }
    }

    private fun copyAsApp(source: String, sourceKind: ImageSourceKind, destination: File): Boolean = try {
        val input = when (sourceKind) {
            ImageSourceKind.File -> File(source).takeIf { it.isFile && it.canRead() }?.inputStream()
            ImageSourceKind.ContentUri -> context.contentResolver.openInputStream(Uri.parse(source))
        }
        if (input == null) {
            false
        } else {
            input.use { sourceStream ->
                destination.outputStream().use { target ->
                    BoundedFileCopy.copy(sourceStream, target, MAX_IMAGE_FILE_BYTES)
                }
            }
            true
        }
    } catch (tooLarge: BoundedFileCopy.TooLargeException) {
        throw tooLarge
    } catch (interrupted: InterruptedIOException) {
        throw interrupted
    } catch (_: SecurityException) {
        false
    } catch (_: IOException) {
        false
    }

    private fun imageCopyCommand(
        source: String,
        sourceKind: ImageSourceKind,
        destination: File,
    ): String = when (sourceKind) {
        ImageSourceKind.File -> "[ -f ${shellQuote(source)} ] || exit 21; " +
            "[ \"$(stat -c %s ${shellQuote(source)})\" -le $MAX_IMAGE_FILE_BYTES ] || exit 22; " +
            "cp ${shellQuote(source)} ${shellQuote(destination.absolutePath)} || exit 23"
        ImageSourceKind.ContentUri -> "content read --uri ${shellQuote(source)} 2>/dev/null | " +
            "head -c ${MAX_IMAGE_FILE_BYTES + 1L} > ${shellQuote(destination.absolutePath)} && " +
            "[ \"$(stat -c %s ${shellQuote(destination.absolutePath)})\" -le $MAX_IMAGE_FILE_BYTES ]"
    }

    private fun imageCacheDirectory(): File =
        context.externalCacheDir
            ?.takeIf { it.isDirectory || it.mkdirs() }
            ?: context.cacheDir

    private fun copyFailure(result: BoundedRootCommandExecutor.Result): SingleImageRead.Failure = when (result.exitCode) {
        21 -> SingleImageRead.Failure("IMAGE_SOURCE_UNAVAILABLE", "图片源文件不存在或当前不可读")
        22 -> SingleImageRead.Failure("IMAGE_TOO_LARGE", "图片超过大小限制")
        23 -> SingleImageRead.Failure("IMAGE_STAGE_FAILED", "Root 无法将图片复制到 Eta 临时缓存")
        else -> SingleImageRead.Failure("IMAGE_UNAVAILABLE", "图片读取失败")
    }

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun sensitive(
        content: String,
        images: List<AgentModelClient.ModelImage> = emptyList(),
    ) = AgentModelClient.ToolResult(content = content, images = images, sensitive = true)

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private data class ImageFailure(val source: String, val code: String, val message: String) {
        fun toJson(): JSONObject = JSONObject()
            .put("path", source)
            .put("code", code)
            .put("message", message)
    }

    private sealed interface SingleImageRead {
        data class Success(val image: AgentModelClient.ModelImage) : SingleImageRead
        data class Failure(val code: String, val message: String) : SingleImageRead
    }

    private enum class ImageSourceKind {
        File,
        ContentUri,
    }

    private companion object {
        const val READ_TIMEOUT_MS = 15_000L
        const val MAX_IMAGES_PER_CALL = 3
        const val MAX_IMAGE_FILE_BYTES = MAX_AGENT_IMAGE_BYTES.toLong()
    }
}
