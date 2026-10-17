package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.model.McpTransport
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通过子进程 stdio 与本地 MCP 服务器通信（换行分隔的 JSON-RPC，UTF-8，每行一条消息）。
 *
 * url 形态为 `stdio://<command>`，可选 `?cwd=<dir>` 指定工作目录；command 支持 percent-encode。
 * 会话按 url 单例缓存，进程退出后自动标记失效，下一次调用会重启进程并重新 initialize。
 */
internal class McpStdioSession private constructor(
    private val url: String,
    private val command: String,
    private val workingDir: String?,
) : AutoCloseable {

    private class Handle(
        val process: Process,
        val stdin: BufferedWriter,
        val responses: LinkedBlockingQueue<String>,
        val alive: AtomicBoolean,
    ) {
        val stderrTail: ArrayDeque<String> = ArrayDeque()
    }

    private val lifecycleLock = Any()
    private val ioLock = Any()
    private val requestIds = AtomicLong(1)

    @Volatile
    private var handle: Handle? = null

    @Volatile
    private var negotiatedVersion: String? = null

    @Volatile
    private var closed = false

    /** 完成 initialize 握手并返回协商后的协议版本；已初始化时直接返回。 */
    fun initialize(): String {
        synchronized(ioLock) {
            negotiatedVersion?.let { return it }
            val active = ensureProcess()
            val id = requestIds.getAndIncrement()
            write(
                active,
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", id)
                    .put("method", "initialize")
                    .put(
                        "params",
                        JSONObject()
                            .put("protocolVersion", McpProtocolMode.LEGACY)
                            .put("capabilities", JSONObject())
                            .put(
                                "clientInfo",
                                JSONObject().put("name", "Eta").put("version", CLIENT_VERSION),
                            ),
                    ),
            )
            val result = awaitResult(active, id, INIT_TIMEOUT_MS)
            val version = result.optString("protocolVersion").ifBlank { McpProtocolMode.LEGACY }
            negotiatedVersion = version
            write(
                active,
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("method", "notifications/initialized")
                    .put("params", JSONObject()),
            )
            return version
        }
    }

    /** 发送一次请求并返回 JSON-RPC 的 result 对象；传输失败会自动重启进程并重试一次。 */
    fun request(
        method: String,
        params: JSONObject,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): JSONObject {
        return try {
            initialize()
            requestOnce(method, params, timeoutMs)
        } catch (transport: McpStdioTransportException) {
            reset()
            initialize()
            requestOnce(method, params, timeoutMs)
        }
    }

    /** 工具发现（对齐 McpHttpClient.discoverTools 的返回形状）。 */
    fun discoverTools(): McpHttpClient.Discovery {
        val version = initialize()
        val result = request("tools/list", JSONObject())
        val tools = mutableListOf<McpToolDefinition>()
        val page = result.optJSONArray("tools") ?: JSONArray()
        for (index in 0 until page.length()) {
            page.optJSONObject(index)?.let { tools += parseTool(it) }
        }
        return McpHttpClient.Discovery(protocolVersion = version, tools = tools, cacheTtlMs = null)
    }

    /** 调用工具（对齐 McpHttpClient.callTool）。 */
    fun callTool(tool: McpToolDefinition, arguments: JSONObject): JSONObject = request(
        method = "tools/call",
        params = JSONObject().put("name", tool.name).put("arguments", arguments),
        timeoutMs = CALL_TIMEOUT_MS,
    )

    private fun parseTool(source: JSONObject): McpToolDefinition {
        val schema = source.optJSONObject("inputSchema")
        val annotations = source.optJSONObject("annotations")
        return McpToolDefinition(
            name = source.optString("name").trim(),
            title = source.optString("title").ifBlank {
                annotations?.optString("title").orEmpty()
            }.take(MAX_TITLE_CHARS),
            description = source.optString("description").take(MAX_DESCRIPTION_CHARS),
            inputSchemaJson = (schema ?: JSONObject()).toString(),
            readOnlyHint = annotations?.let { if (it.has("readOnlyHint")) it.optBoolean("readOnlyHint") else null },
            destructiveHint = annotations?.let { if (it.has("destructiveHint")) it.optBoolean("destructiveHint") else null },
            idempotentHint = annotations?.let { if (it.has("idempotentHint")) it.optBoolean("idempotentHint") else null },
            openWorldHint = annotations?.let { if (it.has("openWorldHint")) it.optBoolean("openWorldHint") else null },
        )
    }

    private fun requestOnce(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
        synchronized(ioLock) {
            val active = ensureProcess()
            val id = requestIds.getAndIncrement()
            write(
                active,
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", id)
                    .put("method", method)
                    .put("params", params),
            )
            return awaitResult(active, id, timeoutMs)
        }
    }

    override fun close() {
        val active = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            val current = handle
            handle = null
            negotiatedVersion = null
            current
        }
        active?.let { terminate(it) }
        CACHE.remove(url, this)
    }

    private fun ensureProcess(): Handle = synchronized(lifecycleLock) {
        check(!closed) { "MCP stdio 会话已关闭" }
        handle?.let { existing ->
            if (existing.alive.get()) return existing
            terminate(existing)
        }
        negotiatedVersion = null
        startProcess().also { handle = it }
    }

    private fun startProcess(): Handle {
        val builder = ProcessBuilder("sh", "-c", command)
        workingDir?.let { builder.directory(java.io.File(it)) }
        val process = try {
            builder.start()
        } catch (failure: IOException) {
            throw McpStdioTransportException(
                "MCP stdio 进程启动失败：${failure.message ?: "未知错误"}",
                failure,
            )
        }
        val active = Handle(
            process = process,
            stdin = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8)),
            responses = LinkedBlockingQueue(),
            alive = AtomicBoolean(true),
        )
        pumpStdout(active)
        pumpStderr(active)
        return active
    }

    private fun pumpStdout(active: Handle) {
        Thread({
            val reader = BufferedReader(InputStreamReader(active.process.inputStream, Charsets.UTF_8))
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) active.responses.offer(line)
                }
            } catch (_: IOException) {
            } finally {
                active.alive.set(false)
                active.responses.offer(EOF_SENTINEL)
            }
        }, "mcp-stdio-stdout").apply { isDaemon = true }.start()
    }

    private fun pumpStderr(active: Handle) {
        Thread({
            val reader = BufferedReader(InputStreamReader(active.process.errorStream, Charsets.UTF_8))
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    synchronized(active.stderrTail) {
                        if (active.stderrTail.size >= MAX_STDERR_LINES) active.stderrTail.removeFirstOrNull()
                        active.stderrTail.addLast(line)
                    }
                }
            } catch (_: IOException) {
            }
        }, "mcp-stdio-stderr").apply { isDaemon = true }.start()
    }

    private fun write(active: Handle, payload: JSONObject) {
        synchronized(active.stdin) {
            try {
                active.stdin.write(payload.toString())
                active.stdin.write("\n")
                active.stdin.flush()
            } catch (failure: IOException) {
                active.alive.set(false)
                throw McpStdioTransportException(
                    "MCP stdio 写入失败：${failure.message ?: "进程可能已退出"}",
                    failure,
                )
            }
        }
    }

    private fun awaitResult(active: Handle, id: Long, timeoutMs: Long): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) {
                throw McpStdioTransportException("MCP stdio 响应超时（${timeoutMs}ms）")
            }
            val line = try {
                active.responses.poll(remaining, TimeUnit.NANOSECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw McpStdioTransportException("MCP stdio 调用被中断", interrupted)
            } ?: throw McpStdioTransportException("MCP stdio 响应超时（${timeoutMs}ms）")
            if (line == EOF_SENTINEL) {
                throw McpStdioTransportException("MCP stdio 进程已退出${stderrSummary(active)}")
            }
            val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
            // 忽略通知与服务端发起的其他消息，只认本次请求的 id。
            if (json.opt("id")?.toString() != id.toString()) continue
            json.optJSONObject("error")?.let { error ->
                throw McpJsonRpcException(
                    code = error.optInt("code"),
                    safeMessage = error.optString("message").take(MAX_ERROR_CHARS),
                )
            }
            return json.optJSONObject("result")
                ?: throw McpStdioTransportException("MCP stdio 响应缺少 result")
        }
    }

    private fun stderrSummary(active: Handle): String {
        val lines = synchronized(active.stderrTail) { active.stderrTail.toList() }
        if (lines.isEmpty()) return ""
        return "，stderr：" + lines.joinToString(" | ").take(MAX_ERROR_CHARS)
    }

    private fun reset() {
        val active = synchronized(lifecycleLock) {
            val current = handle
            handle = null
            negotiatedVersion = null
            current
        }
        active?.let { terminate(it) }
    }

    private fun terminate(active: Handle) {
        active.alive.set(false)
        runCatching { active.stdin.close() }
        runCatching { active.process.destroy() }
        Thread({
            runCatching {
                if (!active.process.waitFor(2, TimeUnit.SECONDS)) {
                    active.process.destroyForcibly()
                }
            }
        }, "mcp-stdio-kill").apply { isDaemon = true }.start()
    }

    companion object {
        const val CALL_TIMEOUT_MS = 120_000L

        private const val CLIENT_VERSION = "1"
        private const val INIT_TIMEOUT_MS = 20_000L
        private const val DEFAULT_TIMEOUT_MS = 20_000L
        private const val MAX_STDERR_LINES = 20
        private const val MAX_ERROR_CHARS = 200
        private const val CWD_MARKER = "?cwd="
        private const val MAX_TITLE_CHARS = 512
        private const val MAX_DESCRIPTION_CHARS = 4_000
        private const val EOF_SENTINEL = "\u0000eta-stdio-eof"

        private val CACHE = ConcurrentHashMap<String, McpStdioSession>()

        /** 按 url 复用会话；url 非法时抛出 IllegalArgumentException。 */
        fun of(rawUrl: String): McpStdioSession {
            val normalized = rawUrl.trim()
            return CACHE.computeIfAbsent(normalized) { url ->
                val (command, directory) = parseSpec(url)
                McpStdioSession(url, command, directory)
            }
        }

        private fun parseSpec(url: String): Pair<String, String?> {
            val body = url.removePrefix(McpTransport.STDIO_PREFIX).trim()
            require(body.isNotBlank()) { "MCP stdio 命令不能为空" }
            val marker = body.indexOf(CWD_MARKER)
            val rawCommand: String
            val rawDirectory: String?
            if (marker >= 0) {
                rawCommand = body.substring(0, marker)
                rawDirectory = body.substring(marker + CWD_MARKER.length)
            } else {
                rawCommand = body
                rawDirectory = null
            }
            val command = decodeIfEncoded(rawCommand).trim()
            require(command.isNotBlank()) { "MCP stdio 命令不能为空" }
            val directory = decodeIfEncoded(rawDirectory.orEmpty()).trim().ifBlank { null }
            return command to directory
        }

        /** 仅当内容含 `%` 时才按 percent-encoding 解码，避免破坏直接书写的命令。 */
        private fun decodeIfEncoded(value: String): String {
            if (!value.contains('%')) return value
            return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        }
    }
}

internal class McpStdioTransportException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
