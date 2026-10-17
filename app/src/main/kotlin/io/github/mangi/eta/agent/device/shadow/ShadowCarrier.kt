package io.github.mangi.eta.agent.device.shadow

import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.core.AgentLogger

/**
 * 影子屏 sidecar 进程控制器。
 *
 * sidecar 通过 `app_process` 承载一段独立 dex（[ShadowDisplayManager.DISPLAY_NAME] 的持有者），
 * 由一个脱离 Eta 进程树的 root 进程负责创建并保持虚拟屏，从而不受 Eta UI 进程存活影响。
 *
 * 控制协议（与 carrier 端约定，全部为固定命令，不接受模型输入）：
 * - 启动：写入宽度/高度/dpi/名称参数并拉起进程，输出 PID
 * - 心跳：[heartbeatCommand] 刷新租约文件时间戳
 * - 停止：[stopCommand] 写入停止信号，carrier 在下一个 tick 自行释放
 * - 回收：[reap] 先优雅停止，超时后强杀并清理控制文件
 */
internal class ShadowCarrier(
    private val logger: AgentLogger,
    private val rootCommandExecutor: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean,
) {
    sealed interface LaunchResult {
        data class Started(val pid: Int) : LaunchResult
        data class Failed(val code: String, val message: String) : LaunchResult
    }

    /**
     * 拉起 sidecar。
     *
     * carrier.dex 由构建期生成并打入 assets，运行时解包到 [ShadowPaths.BASE_DIR] 下。
     */
    fun launch(width: Int, height: Int, dpi: Int, name: String): LaunchResult {
        if (!rootAvailable()) return LaunchResult.Failed("ROOT_REQUIRED", "启动影子屏需要 Root 授权")

        // 已在运行则直接复用
        runningPid()?.let { return LaunchResult.Started(it) }

        val result = rootCommandExecutor.execute(
            command = launchCommand(width, height, dpi, name),
            timeoutMillis = LAUNCH_TIMEOUT_MS,
        )
        if (!result.ok) {
            val code = result.errorCode.ifBlank { "SHADOW_CARRIER_LAUNCH_FAILED" }
            logger.warn("Agent shadow carrier outcome=launch_failed code=$code stderr=${result.stderr.take(200)}")
            return LaunchResult.Failed(code, launchFailureMessage(code, result.stderr))
        }
        val pid = result.stdout.lineSequence()
            .mapNotNull { it.trim().toIntOrNull() }
            .firstOrNull()
            ?: return LaunchResult.Failed("SHADOW_CARRIER_NO_PID", "carrier 已启动但未返回 PID")
        logger.info("Agent shadow carrier outcome=started pid=$pid ${width}x$height@$dpi")
        return LaunchResult.Started(pid)
    }

    /** 当前 sidecar 的 PID；未运行时返回 null。 */
    fun runningPid(): Int? {
        if (!rootAvailable()) return null
        val result = rootCommandExecutor.execute(SELF_PID_COMMAND, timeoutMillis = PROBE_TIMEOUT_MS)
        if (!result.ok) return null
        val pid = result.stdout.lineSequence().mapNotNull { it.trim().toIntOrNull() }.firstOrNull() ?: return null
        return pid.takeIf { it > 0 }
    }

    /** 优雅停止并回收 sidecar 与全部控制文件。 */
    fun reap(): Boolean {
        if (!rootAvailable()) return true
        // 无存活载体时直接返回：残留控制文件会在下次启动前被清理，避免空跑多次 su 拖慢收尾。
        if (runningPid() == null) return true
        // 1) 写入停止信号，给 carrier 一个 tick 的时间自行 release 虚拟屏
        rootCommandExecutor.execute(stopCommand(), timeoutMillis = PROBE_TIMEOUT_MS)
        val deadline = System.currentTimeMillis() + GRACEFUL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (runningPid() == null) break
            Thread.sleep(POLL_INTERVAL_MS)
        }
        // 2) 仍在运行则强杀，并清理控制文件
        rootCommandExecutor.execute(REAP_COMMAND, timeoutMillis = PROBE_TIMEOUT_MS)
        val remaining = runningPid()
        if (remaining != null) {
            logger.warn("Agent shadow carrier outcome=reap_incomplete pid=$remaining")
            return false
        }
        logger.info("Agent shadow carrier outcome=reaped")
        return true
    }

    private fun launchCommand(width: Int, height: Int, dpi: Int, name: String): String = buildString {
        append("mkdir -p ").append(ShadowPaths.BASE_DIR).append("; ")
        append("rm -f ").append(ShadowPaths.PID_FILE).append(' ')
        append(ShadowPaths.SESSION_FILE).append(' ')
        append(ShadowPaths.STOP_FILE).append(' ')
        append(ShadowPaths.HEARTBEAT_FILE).append("; ")
        append("cd ").append(ShadowPaths.BASE_DIR).append("; ")
        append("CLASSPATH=").append(ShadowPaths.BASE_DIR).append("/carrier.dex ")
        append("nohup app_process /system/bin ")
        append("--nice-name=eta_shadow_carrier ")
        append(ShadowPaths.CARRIER_CLASS).append(' ')
        append("--width=").append(width.coerceIn(MIN_DIMENSION, MAX_DIMENSION)).append(' ')
        append("--height=").append(height.coerceIn(MIN_DIMENSION, MAX_DIMENSION)).append(' ')
        append("--dpi=").append(dpi.coerceIn(MIN_DPI, MAX_DPI)).append(' ')
        append("--name=").append(sanitizeName(name)).append(' ')
        append(">> ").append(ShadowPaths.LOG_FILE).append(" 2>&1 & ")
        append("echo \$! > ").append(ShadowPaths.PID_FILE).append("; ")
        append("sleep 1; cat ").append(ShadowPaths.PID_FILE)
    }

    private fun launchFailureMessage(code: String, stderr: String): String = when (code) {
        "ROOT_REQUIRED" -> "创建影子屏需要 Root 授权"
        else -> "carrier 启动失败：${code}${if (stderr.isBlank()) "" else " (${stderr.take(160)})"}"
    }

    private fun sanitizeName(name: String): String =
        name.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank {
            ShadowDisplayManager.DISPLAY_NAME
        }

    internal companion object {
        private const val LAUNCH_TIMEOUT_MS = 20_000L
        private const val PROBE_TIMEOUT_MS = 5_000L
        private const val GRACEFUL_TIMEOUT_MS = 8_000L
        private const val POLL_INTERVAL_MS = 400L
        private const val MIN_DIMENSION = 320
        private const val MAX_DIMENSION = 4_320
        private const val MIN_DPI = 120
        private const val MAX_DPI = 640

        /** 读出存活 sidecar 的 PID；用 /proc 条目 + 命令行内容双重校验，避免 PID 复用误判。 */
        private val SELF_PID_COMMAND: String = buildString {
            append("if [ -f ").append(ShadowPaths.PID_FILE).append(" ]; then ")
            append("pid=\$(cat ").append(ShadowPaths.PID_FILE).append("); ")
            append("if [ -n \"\$pid\" ] && [ -d \"/proc/\$pid\" ] && ")
            // --nice-name 会把 cmdline 改成只剩进程名，两种都认
            append("tr '\\0' ' ' < \"/proc/\$pid/cmdline\" 2>/dev/null | grep -qE '[V]dCarrier|eta_shadow_carrier'; then echo \$pid; fi; ")
            append("fi")
        }

        /** 写入停止信号文件；时间戳便于 carrier 侧日志排序。 */
        fun stopCommand(): String = buildString {
            append("mkdir -p ").append(ShadowPaths.BASE_DIR).append("; ")
            append("date +%s%3N > ").append(ShadowPaths.STOP_FILE).append(" 2>/dev/null || ")
            append("echo \$(date +%s)000 > ").append(ShadowPaths.STOP_FILE)
        }

        /** 刷新心跳租约；carrier 侧据此延后自毁。 */
        fun heartbeatCommand(): String = buildString {
            append("mkdir -p ").append(ShadowPaths.BASE_DIR).append("; ")
            append("date +%s%3N > ").append(ShadowPaths.HEARTBEAT_FILE).append(" 2>/dev/null || ")
            append("echo \$(date +%s)000 > ").append(ShadowPaths.HEARTBEAT_FILE)
        }

        /** 强制回收：SIGKILL 后清理全部控制文件。 */
        private val REAP_COMMAND: String = buildString {
            append("pid=\$(cat ").append(ShadowPaths.PID_FILE).append(" 2>/dev/null); ")
            append("if [ -n \"\$pid\" ] && tr '\\0' ' ' < \"/proc/\$pid/cmdline\" 2>/dev/null | grep -q '[V]dCarrier'; then ")
            append("kill -9 \$pid 2>/dev/null; fi; ")
            append("pkill -9 -f '[V]dCarrier' 2>/dev/null; ")
            append("sleep 1; ")
            append("rm -f ").append(ShadowPaths.PID_FILE).append(' ')
            append(ShadowPaths.SESSION_FILE).append(' ')
            append(ShadowPaths.STOP_FILE).append(' ')
            append(ShadowPaths.HEARTBEAT_FILE).append(' ')
            append(ShadowPaths.BASE_DIR).append("/carrier.dex")
        }
    }
}
