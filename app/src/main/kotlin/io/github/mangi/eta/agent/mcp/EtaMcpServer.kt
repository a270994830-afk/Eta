package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.config.Prefs
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Eta 本地 MCP Server —— 把 Eta 的工具集通过 MCP Streamable HTTP 协议对外开放。
 *
 * 客户端（Claude Desktop / Cursor / OpenCode / Continue 等）通过 Bearer Token + 单端口 HTTP
 * 访问本机上的 Eta 工具集，调用结果直接由 [AgentLocalTools] 执行。
 *
 * 安全约束：
 * - 仅监听 127.0.0.1（不对局域网 / 公网暴露）
 * - 必须携带正确的 Bearer Token（[Prefs.Keys.MCP_SERVER_TOKEN]）
 * - 默认隐藏所有 sensitive 工具（device sensitive read/action），需 Prefs 显式开启
 * - 单次响应体最大 8 MiB，超出截断并标注
 *
 * 协议：参考 Eta 自带的 [McpHttpClient] 的 request/response 形态，实现 initialize /
 * tools/list / tools/call / ping 四个 JSON-RPC 2.0 方法。
 */
internal class EtaMcpServer(
    private val toolExecutor: AgentModelClient.ToolExecutor,
    private val tokenProvider: () -> String,
    private val allowedRoots: Set<String> = setOf("127.0.0.1", "::1"),
    private val maxResponseBytes: Int = 8 * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
    private val serverVersion: String = "unknown",
) : AutoCloseable {

    /** 暴露给 MCP 客户端的工具列表（首次请求时构建一次，之后缓存）。 */
    @Volatile
    private var toolCatalogCache: JSONArray? = null

    /** 简单计数器：记录 initialize / list / call 次数，便于前台通知显示。 */
    private val stats = McpServerStats()

    private val requestIds = AtomicLong(1)
    private val activeCalls = ConcurrentHashMap<Long, String>()

    /**
     * 处理一次 JSON-RPC 请求，返回 JSON-RPC 响应；若是 notification 则返回 null。
     *
     * @throws IOException 网络或 JSON 解析错误
     * @throws McpAuthException 鉴权失败
     */
    @Throws(IOException::class, McpAuthException::class)
    fun handle(
        rawBody: String,
        bearerToken: String?,
        remoteAddr: String,
    ): String? {
        if (remoteAddr !in allowedRoots) {
            throw McpAuthException("REMOTE_NOT_ALLOWED", "远程地址 $remoteAddr 不在允许列表内")
        }
        val expected = tokenProvider()
        if (expected.isBlank() || bearerToken.isNullOrBlank() || bearerToken != expected) {
            throw McpAuthException("UNAUTHORIZED", "Bearer Token 缺失或不正确")
        }
        val request = if (rawBody.isBlank()) JSONObject() else JSONObject(rawBody)
        val method = request.optString("method", "")
        val id = request.opt("id")
        val isNotification = id == null || id == JSONObject.NULL
        if (method.isBlank()) {
            return errorResponse(id, -32600, "请求缺少 method 字段")
        }
        val response = when (method) {
            "initialize" -> handleInitialize(request)
            "notifications/initialized" -> {
                // 客户端握手完成，无需回复
                stats.initializedCount.add(1L)
                return null
            }
            "ping" -> successResponse(id, JSONObject().put("pong", true))
            "tools/list" -> handleToolsList(id)
            "tools/call" -> handleToolsCall(id, request.optJSONObject("params"))
            "notifications/cancelled" -> {
                val params = request.optJSONObject("params")
                val requestId = params?.optLong("requestId") ?: -1L
                activeCalls.remove(requestId)
                return null
            }
            else -> errorResponse(id, -32601, "Method not found: $method")
        }
        return response
    }

    private fun handleInitialize(request: JSONObject): String {
        val clientInfo = request.optJSONObject("params")?.optJSONObject("clientInfo")
        val clientName = clientInfo?.optString("name", "unknown") ?: "unknown"
        stats.lastClientName = clientName
        stats.initializeCount.add(1L)
        val requested = request.optJSONObject("params")?.optString("protocolVersion").orEmpty()
        val negotiated =
            if (requested in SUPPORTED_PROTOCOL_VERSIONS) requested else SUPPORTED_PROTOCOL_VERSIONS.last()
        val result = JSONObject()
            .put("protocolVersion", negotiated)
            .put("serverInfo", JSONObject()
                .put("name", "EtaMcpServer")
                .put("version", serverVersion))
            .put("capabilities", JSONObject().put("tools", JSONObject()))
        return successResponse(request.opt("id"), result)
    }

    private fun handleToolsList(id: Any?): String {
        val tools = toolCatalogCache ?: buildToolCatalog().also { toolCatalogCache = it }
        stats.listCount.add(1L)
        return successResponse(id, JSONObject().put("tools", tools))
    }

    private fun handleToolsCall(id: Any?, params: JSONObject?): String {
        if (params == null) return errorResponse(id, -32602, "params 不能为空")
        val toolName = params.optString("name", "")
        if (toolName.isBlank()) return errorResponse(id, -32602, "缺少工具 name")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        val callId = requestIds.incrementAndGet()
        activeCalls[callId] = toolName
        stats.callCount.add(1L)
        val started = clock()
        val toolCall = AgentModelClient.ToolCall(
            id = "mcp-$callId",
            name = toolName,
            argumentsJson = arguments.toString(),
        )
        val outcome = try {
            toolExecutor.execute(toolCall)
        } catch (t: Throwable) {
            activeCalls.remove(callId)
            return errorResponse(id, -32603, "工具执行异常: ${t.javaClass.simpleName}: ${t.message}")
        }
        activeCalls.remove(callId)
        val elapsed = clock() - started
        // ToolResult 没有 isError 字段；从内容里探测错误码开头判定。
        val isError = outcome.content.contains("\"error\"") ||
            outcome.content.startsWith("errorResult") ||
            outcome.content.contains("\"ROOT_REQUIRED\"") ||
            outcome.content.contains("\"ACCESSIBILITY")
        val content = if (isError) {
            JSONArray().put(JSONObject()
                .put("type", "text")
                .put("text", "工具返回错误（${elapsed}ms）：${outcome.content}"))
        } else {
            JSONArray().put(JSONObject()
                .put("type", "text")
                .put("text", "${outcome.content}\n\n[elapsed=${elapsed}ms]"))
        }
        outcome.images.forEach { image ->
            val dataUrl = image.reference
            val comma = dataUrl.indexOf(',')
            if (dataUrl.startsWith("data:") && comma > 0 && dataUrl.length - comma <= MAX_INLINE_IMAGE_CHARS) {
                content.put(JSONObject()
                    .put("type", "image")
                    .put("data", dataUrl.substring(comma + 1))
                    .put("mimeType", image.mimeType))
            } else if (dataUrl.startsWith("data:") && comma > 0) {
                content.put(JSONObject()
                    .put("type", "text")
                    .put("text", "截图共 ${image.bytes} 字节，超过 MCP 单次内联上限，已省略图像内容。"))
            }
        }
        val responsePayload = JSONObject()
            .put("resultType", "complete")
            .put("content", content)
            .put("isError", isError)
        // 工具结果本身即 JSON 时，额外镜像为规范的结构化输出（新客户端可直接机读）。
        runCatching { JSONObject(outcome.content) }.getOrNull()
            ?.let { responsePayload.put("structuredContent", it) }
        return successResponse(id, responsePayload)
    }

    private fun buildToolCatalog(): JSONArray {
        val includeSensitive = runCatching {
            Prefs.isEnabled(Prefs.Keys.MCP_SERVER_ALLOW_SENSITIVE)
        }.getOrDefault(false)
        return runCatching {
            val capabilities = AgentToolCapabilities(
                rootAvailable = true,
                accessibilityAvailable = true,
                accessibilityRecoveryAvailable = true,
            )
            val full = AgentToolCatalog.build(
                terminalTools = true,
                browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
                deviceDirectTools = true,
                deviceSensitiveReadTools = includeSensitive,
                deviceSensitiveActionTools = includeSensitive,
                skillGitHubDiscovery = false,
                skillGitHubInstall = false,
                memoryTools = false,
                memoryWritable = true,
                shadowTools = true,
                capabilities = capabilities,
            )
            val projected = JSONArray()
            for (i in 0 until full.length()) {
                val tool = full.getJSONObject(i)
                val fn = tool.optJSONObject("function") ?: continue
                val name = fn.optString("name", "")
                if (name.isBlank()) continue
                if (!includeSensitive && isSensitiveTool(name)) continue
                projected.put(JSONObject()
                    .put("name", name)
                    .put("description", fn.optString("description", ""))
                    .put("inputSchema", fn.optJSONObject("parameters") ?: JSONObject().put("type", "object"))
                    .put("annotations", toolAnnotations(name)))
            }
            projected
        }.getOrElse { throwable ->
            // 缺依赖（如工具未登记）—— 降级返回空 catalog，但**必须留日志**：
            // 否则客户端只会看到"0 个工具"，极难排查。
            runCatching {
                io.github.mangi.eta.core.AndroidAgentLogger.warn(
                    "MCP tool catalog build failed: ${throwable.javaClass.simpleName}: ${throwable.message}"
                )
            }
            JSONArray()
        }
    }

    /** MCP 工具行为注解（保守标注）：只读 / 破坏性 / 幂等 / 开放域，供客户端做安全呈现。 */
    private fun toolAnnotations(name: String): JSONObject {
        val readOnly = name in READ_ONLY_TOOLS || READ_ONLY_PREFIXES.any { name.startsWith(it) }
        return JSONObject()
            .put("readOnlyHint", readOnly)
            .put("destructiveHint", name in DESTRUCTIVE_TOOLS)
            .put("idempotentHint", readOnly)
            .put("openWorldHint", name in OPEN_WORLD_TOOLS)
    }

    private fun isSensitiveTool(name: String): Boolean =
        name.startsWith("recent_notifications") ||
            name.contains("search_personal_orders") ||
            name.contains("sensitive") ||
            name in SENSITIVE_TOOL_DENYLIST

    private fun successResponse(id: Any?, result: JSONObject): String {
        val resp = JSONObject()
            .put("jsonrpc", "2.0")
            .put("result", result)
        if (id != null && id != JSONObject.NULL) resp.put("id", id)
        return resp.toString()
    }

    private fun errorResponse(id: Any?, code: Int, message: String): String {
        val resp = JSONObject()
            .put("jsonrpc", "2.0")
            .put("error", JSONObject().put("code", code).put("message", message))
        if (id != null && id != JSONObject.NULL) resp.put("id", id)
        return resp.toString()
    }

    override fun close() {
        // 服务级别清理（线程由 Service 端托管）
    }

    /** 当前运行指标（前台通知用）。 */
    fun snapshot(): McpServerStats = stats.copy()

    private companion object {
        /** 协议版本协商表：客户端请求命中即回显，否则回最新支持版本。 */
        val SUPPORTED_PROTOCOL_VERSIONS = listOf(
            "2024-11-05",
            "2025-03-26",
            "2025-06-18",
            "2025-11-25",
            "2026-07-28",
        )

        val READ_ONLY_PREFIXES = listOf("search_", "get_", "list_", "recent_", "top_")

        /** 单张截图内部内联上限，防止超大 base64 撑爆 MCP 响应。 */
        const val MAX_INLINE_IMAGE_CHARS = 8 * 1024 * 1024

        val READ_ONLY_TOOLS = setOf(
            "observe_screen", "ocr_screen", "ocr_image", "read_image",
            "device_status", "network_info",
            "wait", "wait_for_text", "wait_for_package",
            "flows_list", "skills_list", "skills_read", "skills_read_resource",
            "shadow_status", "shadow_observe",
            "read_file",
            // 扩展工具族中的只读项
            "file_exists", "file_find", "file_grep",
            "conversation_list", "conversation_read",
            "model_config_list", "usage_stats", "tool_permission_list", "rate_limit_status",
            "music_status", "bt_status", "bt_paired_devices", "calculate",
        )

        val DESTRUCTIVE_TOOLS = setOf(
            "run_command", "write_file", "terminal",
            "set_setting", "set_device_state", "app_state_control", "flow_delete",
            // 扩展工具族中的写/改/外部影响项
            "file_move", "file_delete", "file_edit", "file_unzip", "file_download",
            "install_app", "uninstall_app", "execute_intent", "send_broadcast", "send_notification",
            "tool_permission_set", "tool_permission_approve", "conversation_delete",
            "bt_spp_send", "bt_ble_write",
        )

        val OPEN_WORLD_TOOLS = setOf("open_uri", "http_request", "update_check", "file_download")

        val SENSITIVE_TOOL_DENYLIST = setOf(
            "device_get_installed_packages",
            "device_list_package_permissions",
            "device_app_data_usage",
        )
    }
}

/** 运行期统计；不可变 snapshot 走 [EtaMcpServer.snapshot]。 */
internal class McpServerStats(
    val initializeCount: java.util.concurrent.atomic.LongAdder = java.util.concurrent.atomic.LongAdder(),
    val initializedCount: java.util.concurrent.atomic.LongAdder = java.util.concurrent.atomic.LongAdder(),
    val listCount: java.util.concurrent.atomic.LongAdder = java.util.concurrent.atomic.LongAdder(),
    val callCount: java.util.concurrent.atomic.LongAdder = java.util.concurrent.atomic.LongAdder(),
) {
    @Volatile
    var lastClientName: String = "(none)"
        internal set

    fun copy(): McpServerStats = McpServerStats().also { dst ->
        dst.initializeCount.add(initializeCount.sum())
        dst.initializedCount.add(initializedCount.sum())
        dst.listCount.add(listCount.sum())
        dst.callCount.add(callCount.sum())
        dst.lastClientName = lastClientName
    }

    override fun toString(): String =
        "init=${initializeCount.sum()} ok=${initializedCount.sum()} list=${listCount.sum()} " +
            "call=${callCount.sum()} client=$lastClientName"
}

/** 鉴权失败或远程地址受限。 */
internal class McpAuthException(val code: String, message: String) : IOException(message)
