package io.github.mangi.eta.agent.device.shadow

import android.content.Context
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.core.AgentLogger
import java.io.File

/**
 * 把打包在 assets 中的 sidecar 载体部署到设备临时目录。
 *
 * 部署策略：
 * - assets/carrier/carrier.dex 先落到应用私有目录，再经 root 复制到 [ShadowPaths.BASE_DIR]，
 *   因为 `app_process` 需要 root 可读的路径。
 * - 以 SHA-256 作为版本标记，内容未变时跳过复制，避免每次启动都写盘。
 */
internal object ShadowCarrierDeployer {

    private const val ASSET_PATH = "carrier/carrier.dex"
    private const val ASSET_DIR = "carrier"
    private const val DEPLOYED_NAME = "carrier.dex"

    sealed interface Result {
        data object Ready : Result
        data class Failed(val code: String, val message: String) : Result
    }

    /** 确保 [ShadowPaths.BASE_DIR]/carrier.dex 存在且与 assets 一致。 */
    fun ensureDeployed(
        context: Context,
        logger: AgentLogger,
        rootCommandExecutor: BoundedRootCommandExecutor,
        rootAvailable: () -> Boolean,
    ): Result {
        if (!rootAvailable()) {
            return Result.Failed("ROOT_REQUIRED", "部署影子屏载体需要 Root 授权")
        }

        val staged = runCatching {
            File(context.cacheDir, ASSET_DIR).also { it.mkdirs() }
        }.getOrElse {
            return Result.Failed("SHADOW_STAGE_FAILED", "无法创建载体暂存目录：${it.message}")
        }

        val localDex = File(staged, DEPLOYED_NAME)
        val digest = runCatching { cacheAsset(context, localDex) }.getOrElse {
            logger.warn("Agent shadow carrier outcome=asset_read_failed message=${it.message}")
            return Result.Failed("SHADOW_ASSET_MISSING", "APK 内缺少 $ASSET_PATH")
        }

        val deployedDigest = deployedDigest(rootCommandExecutor)
        if (deployedDigest == digest) {
            return Result.Ready
        }

        val target = ShadowPaths.CARRIER_DEX
        val result = rootCommandExecutor.execute(
            command = deployCommand(localDex.absolutePath, target),
            timeoutMillis = DEPLOY_TIMEOUT_MS,
        )
        if (!result.ok) {
            return Result.Failed(
                code = result.errorCode.ifBlank { "SHADOW_DEPLOY_FAILED" },
                message = "载体部署失败：${result.stderr.take(160)}",
            )
        }
        logger.info("Agent shadow carrier outcome=deployed digest=${digest.take(12)}")
        return Result.Ready
    }

    private fun cacheAsset(context: Context, target: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        context.assets.open(ASSET_PATH).use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 读取设备上已部署载体的摘要；缺失时返回空串。 */
    private fun deployedDigest(rootCommandExecutor: BoundedRootCommandExecutor): String {
        val result = rootCommandExecutor.execute(
            command = "sha256sum ${ShadowPaths.CARRIER_DEX} 2>/dev/null | cut -d' ' -f1",
            timeoutMillis = 5_000L,
        )
        if (!result.ok) return ""
        return result.stdout.trim().lowercase()
    }

    private fun deployCommand(sourcePath: String, targetPath: String): String = buildString {
        append("mkdir -p ").append(ShadowPaths.BASE_DIR).append("; ")
        append("cp -f ").append(quote(sourcePath)).append(' ').append(targetPath).append("; ")
        append("chmod 644 ").append(targetPath).append("; ")
        append("ls -l ").append(targetPath)
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private const val DEPLOY_TIMEOUT_MS = 15_000L
}
