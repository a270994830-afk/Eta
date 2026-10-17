package io.github.mangi.eta.agent.device.shizuku

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * Shizuku 特权 shell 通道（免 Root 的 ADB/Root 权限，对齐 Operit 的 DEBUGGER 层级）。
 *
 * 平台约束：dev.rikka.shizuku:api 13.1.5 已把 `Shizuku.newProcess` 降级为 `private static`
 * （官方 changelog 13.1.1 起宣布准备移除，建议改用 UserService）。这里不做反射、也不依赖
 * 私有方法（R8 release 会裁掉无引用的私有方法，反射会失效），而是走它底层的等价公开通道：
 * `Shizuku.getBinder()` + AIDL `moe.shizuku.server.IShizukuService.newProcess(...)`，
 * 语义与旧 `Shizuku.newProcess` 完全一致（同一台 Shizuku 服务的同一个事务）。
 *
 * 所有公开方法都不抛异常：出错时返回明确错误码或 `false`。
 */
internal object ShizukuShell {

    const val ERROR_UNAVAILABLE = "SHIZUKU_UNAVAILABLE"
    const val ERROR_PERMISSION_REQUIRED = "SHIZUKU_PERMISSION_REQUIRED"
    const val ERROR_ERROR = "SHIZUKU_ERROR"

    /** 默认授权请求码；App 层注册回调时需用同一个码匹配。 */
    const val DEFAULT_REQUEST_CODE = 4001

    /** Shizuku 服务是否可用（binder 存活且服务版本可读）。 */
    fun available(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.getVersion() > 0
    }.getOrDefault(false)

    /** 本应用是否已获得 Shizuku 授权。 */
    fun hasPermission(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 是否应向用户解释后再申请（用户此前选过「拒绝且不再询问」时为 true）。 */
    fun shouldShowRationale(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.shouldShowRequestPermissionRationale()
    }.getOrDefault(false)

    /**
     * 发起 Shizuku 授权请求。结果通过 `Shizuku.addRequestPermissionResultListener` 异步回调，
     * 回调注册在 App 层（Application/Activity），本方法只负责发起。
     */
    fun requestPermission(requestCode: Int = DEFAULT_REQUEST_CODE) {
        runCatching {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return@runCatching
            Shizuku.requestPermission(requestCode)
        }.onFailure {
            AndroidAgentLogger.warn("Shizuku requestPermission failed: ${it.javaClass.simpleName}")
        }
    }

    /** 可用性 / 权限 / 版本 / 后端（adb=uid 2000、root=uid 0）。 */
    fun status(): JSONObject {
        val available = available()
        val permission = hasPermission()
        val version = if (available) runCatching { Shizuku.getVersion() }.getOrDefault(-1) else -1
        val uid = if (available) runCatching { Shizuku.getUid() }.getOrDefault(-1) else -1
        val selinux = if (available) runCatching { Shizuku.getSELinuxContext() }.getOrNull() else null
        return JSONObject()
            .put("available", available)
            .put("permission", permission)
            .put("pre_v11", runCatching { Shizuku.isPreV11() }.getOrDefault(false))
            .put("version", version)
            .put("backend", backend(uid))
            .put("uid", uid)
            .put("selinux", selinux ?: JSONObject.NULL)
            .put("should_show_rationale", shouldShowRationale())
    }

    /** 以特权身份执行 `sh -c command`，stdout/stderr 各截断到 256KB，超时后销毁子进程。 */
    fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result {
        if (!available()) return Result.failed(ERROR_UNAVAILABLE)
        if (!hasPermission()) return Result.failed(ERROR_PERMISSION_REQUIRED)
        val binder = runCatching { Shizuku.getBinder() }.getOrNull()
            ?: return Result.failed(ERROR_UNAVAILABLE)
        val service = runCatching { IShizukuService.Stub.asInterface(binder) }.getOrNull()
            ?: return Result.failed(ERROR_UNAVAILABLE)
        val remote = runCatching { service.newProcess(arrayOf("sh", "-c", command), null, null) }
            .onFailure {
                AndroidAgentLogger.warn("Shizuku newProcess failed: ${it.javaClass.simpleName}")
            }
            .getOrElse { return Result.failed(ERROR_ERROR) }
        return execute(remote, timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS))
    }

    private fun execute(remote: IRemoteProcess, timeoutMs: Long): Result {
        val ioPool = Executors.newFixedThreadPool(2)
        try {
            val stdoutFd = runCatching { remote.inputStream }.getOrNull()
            val stderrFd = runCatching { remote.errorStream }.getOrNull()
            if (stdoutFd == null || stderrFd == null) {
                runCatching { remote.destroy() }
                return Result.failed(ERROR_ERROR)
            }
            val stdoutFuture = ioPool.submit<BoundedOutput> {
                ParcelFileDescriptor.AutoCloseInputStream(stdoutFd).use { it.readBounded(MAX_OUTPUT_BYTES) }
            }
            val stderrFuture = ioPool.submit<BoundedOutput> {
                ParcelFileDescriptor.AutoCloseInputStream(stderrFd).use { it.readBounded(MAX_OUTPUT_BYTES) }
            }
            // 关闭子进程的 stdin，避免其等待输入而挂起。
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream).close() }
            val finished = runCatching { remote.waitForTimeout(timeoutMs, TIME_UNIT_MILLIS) }
                .getOrDefault(false)
            if (!finished) runCatching { remote.destroy() }
            val stdout = runCatching { stdoutFuture.get(IO_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .getOrDefault(BoundedOutput.EMPTY)
            val stderr = runCatching { stderrFuture.get(IO_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .getOrDefault(BoundedOutput.EMPTY)
            val exitCode = if (finished) runCatching { remote.exitValue() }.getOrDefault(-1) else -2
            return Result(
                exitCode = exitCode,
                stdout = stdout.text,
                stderr = stderr.text,
                timedOut = !finished,
                truncated = stdout.truncated || stderr.truncated,
            )
        } catch (throwable: Throwable) {
            AndroidAgentLogger.warn("Shizuku shell error: ${throwable.javaClass.simpleName}")
            return Result.failed(ERROR_ERROR)
        } finally {
            runCatching { remote.destroy() }
            ioPool.shutdownNow()
        }
    }

    private fun InputStream.readBounded(maxBytes: Int): BoundedOutput {
        val limit = maxBytes.coerceAtLeast(1)
        val collected = ByteArrayOutputStream(limit.coerceAtMost(32 * 1024))
        val buffer = ByteArray(8 * 1024)
        var truncated = false
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            val remaining = limit - collected.size()
            if (remaining > 0) collected.write(buffer, 0, read.coerceAtMost(remaining))
            if (read > remaining) truncated = true
        }
        return BoundedOutput(
            text = collected.toString(StandardCharsets.UTF_8.name()),
            truncated = truncated,
        )
    }

    private fun backend(uid: Int): String = when (uid) {
        0 -> "root"
        2000 -> "adb"
        -1 -> "unknown"
        else -> "uid:$uid"
    }

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val truncated: Boolean,
        val errorCode: String = "",
    ) {
        val ok: Boolean get() = errorCode.isBlank() && !timedOut && exitCode == 0

        companion object {
            fun failed(code: String): Result = Result(
                exitCode = -1,
                stdout = "",
                stderr = "",
                timedOut = false,
                truncated = false,
                errorCode = code,
            )
        }
    }

    private data class BoundedOutput(val text: String, val truncated: Boolean) {
        companion object {
            val EMPTY = BoundedOutput("", truncated = false)
        }
    }

    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val MIN_TIMEOUT_MS = 1_000L
    private const val MAX_TIMEOUT_MS = 300_000L
    private const val MAX_OUTPUT_BYTES = 256 * 1024
    private const val IO_JOIN_TIMEOUT_MS = 2_000L
    private const val TIME_UNIT_MILLIS = "MILLISECONDS"
}
