package io.github.mangi.eta.agent.external

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
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.MainActivity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * [EtaExternalChatServer] 的前台 Service 宿主 —— 在用户主动开启对外聊天通道期间保持 HTTP server 存活。
 *
 * 启动方式：
 * - [Prefs.Keys.EXTERNAL_CHAT_ENABLED]=true → 启动时自启（由 EtaApp 调用 [start]）
 * - 显式 Intent (ACTION_START / ACTION_STOP) —— UI 控制
 *
 * 监听地址：仅 127.0.0.1（IPv4）。
 * 端口：[Prefs.Keys.EXTERNAL_CHAT_PORT]（默认 8094）。
 * Token：[Prefs.Keys.EXTERNAL_CHAT_TOKEN]（缺失或空则拒绝启动）。
 */
internal class EtaExternalChatService : Service() {

    private val running = AtomicBoolean(false)
    private var server: EtaExternalChatServer? = null
    private val chatLock = ReentrantLock()
    private val sessions = ConcurrentHashMap<String, List<AgentModelClient.ConversationMessage>>()

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
                val enabled = runCatching {
                    Prefs.isEnabled(Prefs.Keys.EXTERNAL_CHAT_ENABLED)
                }.getOrDefault(false)
                if (!enabled) {
                    AndroidAgentLogger.info("External chat service not started: disabled in prefs")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!running.get()) startServer() else refreshNotification()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun startServer() {
        if (!running.compareAndSet(false, true)) return
        if (readToken().isEmpty()) {
            AndroidAgentLogger.warn("External chat server not started: token missing")
            running.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val port = Prefs.getLocalAgentInt(Prefs.Keys.EXTERNAL_CHAT_PORT, DEFAULT_PORT)
            .takeIf { it in 1024..65535 } ?: DEFAULT_PORT
        val instance = EtaExternalChatServer(
            tokenProvider = { readToken() },
            chatRunner = EtaExternalChatServer.ChatRunner { prompt, sessionId -> runAgent(prompt, sessionId) },
            serverVersion = resolveAppVersion(),
        )
        if (instance.start(port)) {
            server = instance
            refreshNotification()
        } else {
            running.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopServer() {
        if (!running.compareAndSet(true, false)) return
        server?.stop()
        server = null
        AndroidAgentLogger.info("External chat service stopped")
    }

    /**
     * 跑一轮 agent 并返回最终文本。
     *
     * 底层用的是 [AgentRuntimeClient.run]，与定时任务 [io.github.mangi.eta.agent.schedule.EtaScheduleService]
     * 完全一致：绑定 AgentRuntimeService → 发一次 RunRequest → 阻塞等待 RunResult（含 assistant 文本）。
     * 必须在非主线程调用（本方法运行在 HTTP 连接线程上）。
     */
    private fun runAgent(prompt: String, sessionId: String): EtaExternalChatServer.ChatOutcome {
        if (!chatLock.tryLock()) {
            return EtaExternalChatServer.ChatOutcome(
                ok = false,
                errorCode = "BUSY",
                errorMessage = "上一条外部消息仍在处理中",
            )
        }
        try {
            val client = AgentRuntimeClient(this, AndroidAgentLogger)
            val active = client.queryActiveRun()
            val busy = (active as? AgentRuntimeClient.ActiveRunQuery.Known)?.runId?.isNotBlank() == true
            if (busy) {
                return EtaExternalChatServer.ChatOutcome(
                    ok = false,
                    errorCode = "BUSY",
                    errorMessage = "Eta 正在处理另一个任务",
                )
            }
            val runId = UUID.randomUUID().toString()
            val effectiveSessionId = sessionId.ifBlank { runId }
            val history = if (sessionId.isNotBlank()) sessions[sessionId].orEmpty() else emptyList()
            val result = client.run(
                request = AgentRuntimeWire.RunRequest(
                    runId = runId,
                    prompt = prompt,
                    config = AgentModelClient.loadConfig(),
                    images = emptyList(),
                    history = history,
                    modelSessionId = effectiveSessionId,
                ),
                onEvent = {},
            )
            client.ackResult(runId)
            if (result.ok && sessionId.isNotBlank()) {
                rememberSession(sessionId, history, prompt, result)
            }
            return if (result.ok) {
                EtaExternalChatServer.ChatOutcome(
                    ok = true,
                    text = result.content,
                    sessionId = effectiveSessionId,
                )
            } else {
                EtaExternalChatServer.ChatOutcome(
                    ok = false,
                    errorCode = "RUN_FAILED",
                    errorMessage = result.error?.takeIf { it.isNotBlank() } ?: "Agent 运行失败",
                )
            }
        } catch (t: Throwable) {
            AndroidAgentLogger.warn("External chat run failed: type=${t.safeLogType()}")
            return EtaExternalChatServer.ChatOutcome(
                ok = false,
                errorCode = "RUN_FAILED",
                errorMessage = t.message ?: t.javaClass.simpleName,
            )
        } finally {
            chatLock.unlock()
        }
    }

    /** 维护按 sessionId 区分的多轮历史；会话数超出上限时淘汰最早的一个。 */
    private fun rememberSession(
        sessionId: String,
        history: List<AgentModelClient.ConversationMessage>,
        prompt: String,
        result: AgentRuntimeWire.RunResult,
    ) {
        val next = result.contextSnapshot?.messages
            ?: (history + AgentModelClient.buildUserHistoryMessage(prompt, emptyList()) + result.transcript)
        if (sessions.size >= MAX_SESSIONS && !sessions.containsKey(sessionId)) {
            sessions.keys.firstOrNull()?.let { sessions.remove(it) }
        }
        sessions[sessionId] = next
    }

    private fun readToken(): String = runCatching {
        Prefs.getLocalString(Prefs.Keys.EXTERNAL_CHAT_TOKEN, "").trim()
    }.getOrDefault("")

    private fun resolveAppVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
    }.getOrNull() ?: "unknown"

    private fun ensureForeground() {
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: RuntimeException) {
            AndroidAgentLogger.warn("External chat foreground failed: ${e.javaClass.simpleName}: ${e.message}")
            stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val port = server?.port()?.takeIf { it > 0 }
            ?: Prefs.getLocalAgentInt(Prefs.Keys.EXTERNAL_CHAT_PORT, DEFAULT_PORT)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, EtaExternalChatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_TEXT_PREFIX + port)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, NOTIFICATION_STOP, stop)
            .build()
    }

    companion object {
        const val CHANNEL = "eta_external_chat"
        const val NOTIFICATION_ID = 0x4558_4348 // "EXCH"
        const val ACTION_START = "io.github.mangi.eta.action.EXTERNAL_CHAT_START"
        const val ACTION_STOP = "io.github.mangi.eta.action.EXTERNAL_CHAT_STOP"
        const val DEFAULT_PORT = 8094

        private const val MAX_SESSIONS = 32
        private const val NOTIFICATION_CHANNEL_NAME = "Eta 对外聊天通道"
        private const val NOTIFICATION_TITLE = "Eta 对外聊天通道运行中"
        private const val NOTIFICATION_TEXT_PREFIX = "监听 127.0.0.1:"
        private const val NOTIFICATION_STOP = "停止"

        /** 启动前台服务；由 EtaApp（开机自启）与设置页调用。 */
        fun start(context: Context) {
            val intent = Intent(context, EtaExternalChatService::class.java).setAction(ACTION_START)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                AndroidAgentLogger.warn("External chat service start failed: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, EtaExternalChatService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
