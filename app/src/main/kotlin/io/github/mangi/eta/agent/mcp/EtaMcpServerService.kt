package io.github.mangi.eta.agent.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityKeeper
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.tool.AgentAuthPolicy
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.MainActivity
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * EtaMcpServer 的前台 Service 宿主 —— 在用户主动开启 MCP 服务期间保持 HTTP server 存活。
 *
 * 启动方式：
 *   - Prefs.MCP_SERVER_ENABLED=true → 启动时自启
 *   - 显式 Intent (ACTION_START / ACTION_STOP) —— UI 控制
 *
 * 监听地址：仅 127.0.0.1（IPv4） + ::1（IPv6）。
 * 端口：Prefs.MCP_SERVER_PORT（默认 18181）。
 *
 * 协议：单端点 /mcp，接受 POST application/json，按 EtaMcpServer 的 handle() 派发。
 */
internal class EtaMcpServerService : Service() {

    private val running = AtomicBoolean(false)
    private val serverRef = AtomicReference<ServerSocket?>(null)
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "EtaMcpServer-Http").apply { isDaemon = true }
    }
    private var toolExecutor: AgentLocalTools? = null
    private var mcpServer: EtaMcpServer? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.mcp_server_channel), NotificationManager.IMPORTANCE_LOW),
        )
        toolExecutor = newToolExecutor()
        mcpServer = EtaMcpServer(
            toolExecutor = toolExecutor!!,
            // MCP_SERVER_TOKEN 是 UI 通过 Prefs.putLocal 写到 localAgent 的；
            // Prefs.getString 读的是 remote（Xposed 服务下发的远端 pref），
            // 两者是不同 SharedPreferences 文件。这里直接走 localAgent.getStringCompat，
            // 既保留 ClassCastException 兜底，又对齐写入路径。
            tokenProvider = { readLocalToken() },
            serverVersion = resolveAppVersion(),
        )
    }

    /** MCP serverInfo.version：跟随安装包版本名，不写死。 */
    private fun resolveAppVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
    }.getOrNull() ?: "unknown"

    /**
     * 直接从 localAgent SharedPreferences 读 token，类型不匹配时返回空串。
     *
     * 用 inline try-catch 而不是依赖 Prefs.kt 里的 private extension，
     * 避免跨文件可见性差异。
     */
    private fun readLocalToken(): String {
        val prefs = Prefs.localAgentPreferences() ?: return ""
        return try {
            prefs.getString(Prefs.Keys.MCP_SERVER_TOKEN, "").orEmpty()
        } catch (e: ClassCastException) {
            // 兼容：旧版本可能把 MCP_SERVER_TOKEN 误写成 Boolean 类型
            runCatching { prefs.edit().remove(Prefs.Keys.MCP_SERVER_TOKEN).apply() }
            ""
        }
    }

    private fun newToolExecutor(): AgentLocalTools {
        // 与聊天侧 runtime 保持一致的技能服务注入；缺了这些，MCP 通道调用
        // skills_list / skills_read 等会报「技能服务未初始化」。
        val skillIndexService = SkillRuntime.createIndexService(applicationContext)
        val skillLoader = SkillRuntime.createLoader(applicationContext)
        val skillResourceReader = SkillRuntime.createResourceReader(applicationContext)
        val skillPackageInstaller = SkillRuntime.createPackageInstaller(applicationContext)
        val githubSkillSource = PublicGitHubSkillSource(
            cacheRoot = applicationContext.cacheDir,
            baseClient = AgentHttpClient.client,
        )
        val availableSkillIdsProvider: () -> Set<String> = {
            runCatching {
                skillIndexService.listInstalledSkills()
                    .filter { SkillCompatibilityChecker.evaluate(it).available }
                    .mapTo(mutableSetOf()) { it.id }
            }.getOrDefault(mutableSetOf())
        }
        return AgentLocalTools(
            context = applicationContext,
            logger = io.github.mangi.eta.core.AndroidAgentLogger,
            browserToolsEnabled = { Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS) }, // 尊重用户开关（外部客户端与聊天同权限）
            terminalToolsEnabled = { true },  // 文件/终端是 MCP 客户端最常调用的能力
            deviceDirectToolsEnabled = { true },
            deviceSensitiveReadToolsEnabled = {
                Prefs.isEnabled(Prefs.Keys.MCP_SERVER_ALLOW_SENSITIVE) &&
                    Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS)
            },
            deviceSensitiveActionToolsEnabled = {
                Prefs.isEnabled(Prefs.Keys.MCP_SERVER_ALLOW_SENSITIVE) &&
                    Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS)
            },
            shadowToolsEnabled = { true },
            shadowSessionActive = { false },
            authSource = { AgentAuthPolicy.Source.EXTERNAL_MCP },
            skillIndexService = skillIndexService,
            skillLoader = skillLoader,
            skillResourceReader = skillResourceReader,
            githubSkillSource = githubSkillSource,
            skillPackageInstaller = skillPackageInstaller,
            runAvailableSkillIdsProvider = availableSkillIdsProvider,
            // 与聊天侧 AgentRuntimeRunExecutor 对齐：MCP 通道同样先过无障碍闸门，防外部客户端绕过 GUI 操作前置检查。
            beforeToolExecution = { toolName ->
                if (!AgentToolRequirements.requiresAccessibility(toolName)) {
                    ToolExecutionDecision.Allow
                } else {
                    val accessibility = AgentAccessibilityKeeper.ensureEnabledForGuiOperation(applicationContext)
                    if (!accessibility.available) {
                        ToolExecutionDecision.Reject(
                            code = accessibility.code,
                            message = accessibility.message,
                        )
                    } else {
                        ToolExecutionDecision.Allow
                    }
                }
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (!running.get()) startServer()
                else refreshNotification()
            }
        }
        // 常驻：被系统回收后自动重建（重建时 intent=null 走上面的启动分支），
        // 让「Eta 进程在跑 = MCP 一定在服务」。主动停止走 ACTION_STOP，不复活。
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServer()
        mcpServer?.close()
        super.onDestroy()
    }

    private fun startServer() {
        if (!running.compareAndSet(false, true)) return
        val port = Prefs.getLocalAgentInt(Prefs.Keys.MCP_SERVER_PORT, 18181)
            .coerceIn(1024, 65535)
        try {
            val sock = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
            serverRef.set(sock)
            running.set(true)
            AndroidAgentLogger.info("MCP Server listening on 127.0.0.1:$port")
            executor.submit { acceptLoop(sock) }
            refreshNotification()
        } catch (e: IOException) {
            running.set(false)
            AndroidAgentLogger.warn("MCP Server bind failed: ${e.message}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopServer() {
        if (!running.compareAndSet(true, false)) return
        serverRef.getAndSet(null)?.close()
        AndroidAgentLogger.info("MCP Server stopped")
    }

    private fun acceptLoop(sock: ServerSocket) {
        while (running.get() && !sock.isClosed) {
            val client = try {
                sock.accept()
            } catch (e: IOException) {
                if (running.get()) AndroidAgentLogger.warn("MCP accept failed: ${e.message}")
                return
            }
            executor.submit { handleConnection(client) }
        }
    }

    private fun handleConnection(client: Socket) {
        client.use { socket ->
            try {
                val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ", limit = 3)
                if (parts.size < 2) return writeError(socket, 400, "Malformed request line")
                val method = parts[0]
                val rawTarget = parts[1]
                if (method != "POST") return writeError(socket, 405, "Only POST allowed")

                // 解析目标路径与 query
                val targetNoQuery = rawTarget.substringBefore('?')
                if (targetNoQuery != "/mcp") return writeError(socket, 404, "Path not found: $targetNoQuery")

                // 读取 headers
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = input.readLine() ?: return
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx < 0) continue
                    val key = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    headers[key] = value
                }

                // 解析 Content-Length 与 Bearer Token
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength <= 0 || contentLength > MAX_REQUEST_BYTES) {
                    return writeError(socket, 413, "Content-Length invalid or too large: $contentLength")
                }
                val bearer = headers["authorization"]?.removePrefix("Bearer ")?.trim()
                val body = readBody(input, contentLength)

                // 派发到 MCP server
                val remoteAddr = socket.inetAddress?.hostAddress ?: "127.0.0.1"
                val response = try {
                    mcpServer?.handle(body, bearer, remoteAddr)
                } catch (e: McpAuthException) {
                    return writeError(socket, 401, e.message ?: "Unauthorized")
                }
                if (response == null) {
                    // notification 类请求无需回复
                    writeEmpty(socket, 204)
                } else {
                    writeJson(socket, 200, response)
                }
            } catch (e: Exception) {
                AndroidAgentLogger.warn("MCP connection error: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { writeError(socket, 500, "Internal error: ${e.message}") }
            }
        }
    }

    private fun readBody(reader: BufferedReader, length: Int): String {
        val buf = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    private fun writeJson(socket: Socket, status: Int, json: String) {
        val body = json.toByteArray(StandardCharsets.UTF_8)
        val msg = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(msg.toByteArray(StandardCharsets.UTF_8))
            write(body)
            flush()
        }
    }

    private fun writeEmpty(socket: Socket, status: Int) {
        val msg = "HTTP/1.1 $status ${statusText(status)}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().write(msg.toByteArray(StandardCharsets.UTF_8))
        socket.getOutputStream().flush()
    }

    private fun writeError(socket: Socket, status: Int, message: String) {
        val encoded = URLDecoder.decode(message, "UTF-8")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
        val body = """{"error":"$encoded"}"""
        writeJson(socket, status, body)
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        else -> "Status $status"
    }

    private fun ensureForeground() {
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: RuntimeException) {
            AndroidAgentLogger.warn("MCP Server foreground failed: ${e.javaClass.simpleName}: ${e.message}")
            stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val port = Prefs.getLocalAgentInt(Prefs.Keys.MCP_SERVER_PORT, 18181)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EtaMcpServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stats = mcpServer?.snapshot()?.toString() ?: "init=0 call=0"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.mcp_server_notification_title))
            .setContentText(getString(R.string.mcp_server_notification_text, port, stats))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.mcp_server_stop_action), stop)
            .build()
    }

    companion object {
        const val CHANNEL = "eta_mcp_server"
        const val NOTIFICATION_ID = 0x4D43_5001 // "MCPS"
        const val ACTION_START = "io.github.mangi.eta.action.MCP_START"
        const val ACTION_STOP = "io.github.mangi.eta.action.MCP_STOP"
        private const val MAX_REQUEST_BYTES = 8 * 1024 * 1024

        fun start(context: Context) {
            val intent = Intent(context, EtaMcpServerService::class.java).setAction(ACTION_START)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                AndroidAgentLogger.warn("MCP Service start failed: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, EtaMcpServerService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
