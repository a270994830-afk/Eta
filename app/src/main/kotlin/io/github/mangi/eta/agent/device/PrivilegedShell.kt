package io.github.mangi.eta.agent.device

import io.github.mangi.eta.agent.device.shizuku.ShizukuShell
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 特权命令兜底通道：优先 root（su），root 不可用或授权丢失时自动改走 Shizuku（ADB 级）。
 *
 * 上层（影子屏、root shell、文件/系统工具）只需读 [available] / [activeChannel] 判断能力，
 * 执行统一走 [execute]，不必各自区分 root 还是 Shizuku。
 *
 * 返回类型直接复用 [BoundedRootCommandExecutor.Result]：外层类为 internal，其嵌套 Result
 * 在模块外不可见、模块内可直接引用，与复制一份等价类型相比少一处需要同步的镜像。
 */
internal object PrivilegedShell {

    const val CHANNEL_ROOT = "root"
    const val CHANNEL_SHIZUKU = "shizuku"
    const val CHANNEL_NONE = "none"

    /** 与既有执行层一致的错误码：两条通道都给不出结果时返回它。 */
    const val ERROR_ROOT_REQUIRED = "ROOT_REQUIRED"

    private const val ROOT_ERROR_REQUIRED = "ROOT_REQUIRED"
    private const val ROOT_ERROR_UNAVAILABLE = "ROOT_UNAVAILABLE"

    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024

    fun rootAvailable(): Boolean = RootAccess.isGranted

    /** Shizuku 服务存活且本应用已获授权。 */
    fun shizukuAvailable(): Boolean =
        runCatching { ShizukuShell.available() && ShizukuShell.hasPermission() }.getOrDefault(false)

    fun available(): Boolean = rootAvailable() || shizukuAvailable()

    fun activeChannel(): String = when {
        rootAvailable() -> CHANNEL_ROOT
        shizukuAvailable() -> CHANNEL_SHIZUKU
        else -> CHANNEL_NONE
    }

    fun execute(
        command: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    ): BoundedRootCommandExecutor.Result {
        if (rootAvailable()) {
            val rootResult = runRoot(command, timeoutMillis, maxOutputBytes)
            // 只有「root 通道本身不可用/授权丢失」才降级；命令自身的非零退出与超时不降级。
            if (!shouldFallback(rootResult)) return rootResult
        }
        return shizukuFallback(command, timeoutMillis, maxOutputBytes)
            ?: BoundedRootCommandExecutor.Result.failed(ERROR_ROOT_REQUIRED)
    }

    /**
     * 供 [BoundedRootCommandExecutor] 在 rootAvailable()=false 时调用。
     * Shizuku 不可用/未授权返回 null（由调用方决定错误码），否则返回其执行结果。
     */
    fun shizukuFallback(
        command: String,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): BoundedRootCommandExecutor.Result? {
        if (!shizukuAvailable()) return null
        val result = ShizukuShell.run(command, timeoutMillis)
        val stdout = clamp(result.stdout, maxOutputBytes)
        val stderr = clamp(result.stderr, maxOutputBytes)
        return BoundedRootCommandExecutor.Result(
            exitCode = result.exitCode,
            stdout = stdout.text,
            stderr = stderr.text,
            timedOut = result.timedOut,
            truncated = result.truncated || stdout.truncated || stderr.truncated,
            errorCode = result.errorCode,
        )
    }

    private fun runRoot(
        command: String,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): BoundedRootCommandExecutor.Result =
        BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { true }).use {
            it.execute(command, timeoutMillis, maxOutputBytes)
        }

    private fun shouldFallback(result: BoundedRootCommandExecutor.Result): Boolean =
        result.errorCode == ROOT_ERROR_REQUIRED || result.errorCode == ROOT_ERROR_UNAVAILABLE

    /** ShizukuShell 固定按 256KB 截断；这里再按调用方要求的上限收紧（按字符近似）。 */
    private fun clamp(text: String, maxOutputBytes: Int): Clamped {
        if (maxOutputBytes <= 0 || text.length <= maxOutputBytes) return Clamped(text, truncated = false)
        return Clamped(text.substring(0, maxOutputBytes), truncated = true)
    }

    private data class Clamped(val text: String, val truncated: Boolean)
}
