package io.github.mangi.eta.agent.device.shadow

import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.core.AgentLogger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit


/**
 * 影子屏（虚拟显示）控制器。
 *
 * 设计要点：
 * - 虚拟屏由独立的 root sidecar 进程持有（[ShadowCarrier]），生命周期不绑定 Eta 进程，
 *   这样智能体在后台跑长任务时不会因为 UI 进程回收而丢失屏幕。
 * - 宿主通过心跳租约（heartbeat lease）维持 sidecar 存活；一旦宿主停止刷新，
 *   sidecar 会在宽限期内自行释放虚拟屏并退出，避免留下孤儿显示。
 * - 所有 root 命令都经 [BoundedRootCommandExecutor] 执行，不把模型参数拼进脚本。
 */
internal class ShadowDisplayManager(
    private val logger: AgentLogger,
    private val rootCommandExecutor: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean,
) {
    /** 心跳续租线程：会话活跃期间定时刷新载体租约，避免任务中途因租约过期自毁。 */
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "eta-shadow-heartbeat").apply { isDaemon = true }
    }

    @Volatile
    private var heartbeatTask: ScheduledFuture<*>? = null
    /** 影子屏会话状态。 */
    sealed interface SessionState {
        data object Idle : SessionState
        data class Active(
            val displayId: Int,
            val surfaceFlingerId: String,
            val width: Int,
            val height: Int,
            val dpi: Int,
            val name: String,
        ) : SessionState

        data class Failed(val code: String, val message: String) : SessionState
    }

    /**
     * 确保影子屏处于可用状态；已存在则直接复用。
     *
     * @return 成功时返回 [SessionState.Active]，失败时返回 [SessionState.Failed]。
     */
    fun ensureStarted(
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        dpi: Int = DEFAULT_DPI,
        name: String = DISPLAY_NAME,
    ): SessionState {
        if (!rootAvailable()) return failure("ROOT_REQUIRED", "创建影子屏需要 Root 授权")

        currentSession()?.let { return it }

        val carrier = ShadowCarrier(logger, rootCommandExecutor, rootAvailable)
        val launched = carrier.launch(width, height, dpi, name)
        if (launched is ShadowCarrier.LaunchResult.Failed) {
            return failure(launched.code, launched.message)
        }

        val session = awaitSession(EXPECT_TIMEOUT_MS)
        return session ?: failure(
            code = "SHADOW_DISPLAY_TIMEOUT",
            message = "影子屏创建超时；请检查 Root 授权与 carrier.log",
        )
    }

    /** 读取当前影子屏会话；未创建或已失效时返回 null。 */
    fun currentSession(): SessionState.Active? {
        if (!rootAvailable()) return null
        val result = rootCommandExecutor.execute(SESSION_PROBE_COMMAND, timeoutMillis = PROBE_TIMEOUT_MS)
        if (!result.ok) return null
        val session = parseSession(result.stdout)
        if (session != null) startHeartbeat()
        return session
    }

    /** 刷新心跳租约；由内部续租线程周期调用。 */
    fun heartbeat(): Boolean {
        if (!rootAvailable()) return false
        val result = rootCommandExecutor.execute(
            command = ShadowCarrier.heartbeatCommand(),
            timeoutMillis = PROBE_TIMEOUT_MS,
        )
        return result.ok
    }

    @Synchronized
    private fun startHeartbeat() {
        val current = heartbeatTask
        if (current != null && !current.isCancelled && !current.isDone) return
        heartbeatTask = heartbeatExecutor.scheduleWithFixedDelay(
            { runCatching { heartbeat() } },
            HEARTBEAT_INTERVAL_MS,
            HEARTBEAT_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    @Synchronized
    private fun stopHeartbeat() {
        heartbeatTask?.cancel(false)
        heartbeatTask = null
    }

    /** 释放影子屏；先优雅停止，超时后强制回收。 */
    fun release(): Boolean {
        stopHeartbeat()
        if (!rootAvailable()) return true
        val carrier = ShadowCarrier(logger, rootCommandExecutor, rootAvailable)
        return carrier.reap()
    }

    /** 清理可能残留的孤儿 sidecar 与临时文件。 */
    fun reapOrphans(): Boolean = release()

    private fun awaitSession(timeoutMs: Long): SessionState.Active? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            currentSession()?.let { return it }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    private fun parseSession(output: String): SessionState.Active? {
        // 约定输出格式：displayId|surfaceFlingerId|width|height|dpi|name
        val line = output.lineSequence().firstOrNull { it.contains(FIELD_SEPARATOR) } ?: return null
        val parts = line.trim().split(FIELD_SEPARATOR)
        if (parts.size < 6) return null
        val displayId = parts[0].toIntOrNull() ?: return null
        return SessionState.Active(
            displayId = displayId,
            surfaceFlingerId = parts[1],
            width = parts[2].toIntOrNull() ?: DEFAULT_WIDTH,
            height = parts[3].toIntOrNull() ?: DEFAULT_HEIGHT,
            dpi = parts[4].toIntOrNull() ?: DEFAULT_DPI,
            name = parts[5],
        )
    }

    private fun failure(code: String, message: String): SessionState.Failed {
        logger.warn("Agent shadow display outcome=failed code=$code message=$message")
        return SessionState.Failed(code, message)
    }

    internal companion object {
        const val DISPLAY_NAME = "EtaShadowDisplay"
        const val DEFAULT_WIDTH = 1080
        const val DEFAULT_HEIGHT = 2340
        const val DEFAULT_DPI = 440

        private const val FIELD_SEPARATOR = "|"
        private const val EXPECT_TIMEOUT_MS = 12_000L
        private const val PROBE_TIMEOUT_MS = 6_000L
        private const val POLL_INTERVAL_MS = 400L
        private const val HEARTBEAT_INTERVAL_MS = 30_000L

        /** 查询 sidecar 是否存活，并回传当前影子屏几何信息。 */
        private val SESSION_PROBE_COMMAND: String = buildString {
            append("if [ -f ").append(ShadowPaths.PID_FILE).append(" ]; then ")
            append("pid=\$(cat ").append(ShadowPaths.PID_FILE).append("); ")
            append("if [ -n \"\$pid\" ] && [ -d \"/proc/\$pid\" ] && ")
            // --nice-name 会把 /proc/<pid>/cmdline 改成只剩进程名（不再含 VdCarrier 类名），两个都认
            append("tr '\\0' ' ' < \"/proc/\$pid/cmdline\" 2>/dev/null | grep -qE '[V]dCarrier|eta_shadow_carrier'; then ")
            append("if [ -f ").append(ShadowPaths.SESSION_FILE).append(" ]; then ")
            append("cat ").append(ShadowPaths.SESSION_FILE).append("; ")
            append("fi; ")
            append("fi; ")
            append("fi")
        }
    }
}

/**
 * 影子屏临时文件布局；与 sidecar 端约定一致。
 *
 * 路径常量同时以字符串（供 shell 命令拼接）和 File（供本地读写）两种形式提供。
 */
internal object ShadowPaths {
    const val BASE_DIR = "/data/local/tmp/eta_shadow"
    const val CARRIER_DEX = "$BASE_DIR/carrier.dex"
    const val PID_FILE = "$BASE_DIR/carrier.pid"
    const val SESSION_FILE = "$BASE_DIR/carrier.session"
    const val HEARTBEAT_FILE = "$BASE_DIR/carrier.hb"
    const val STOP_FILE = "$BASE_DIR/carrier.stop"
    const val LOG_FILE = "$BASE_DIR/carrier.log"
    const val UI_DUMP_FILE = "$BASE_DIR/ui.xml"
    const val SCREENSHOT_FILE = "$BASE_DIR/screen.png"
    const val SNAPSHOT_REQUEST_FILE = "$BASE_DIR/carrier.snap"
    const val CARRIER_CLASS = "io.github.mangi.eta.shadow.carrier.VdCarrier"
}
