package io.github.mangi.eta.agent.reminder

import android.content.Context
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.ProotCommandBuilder
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.runOneShotShell
import io.github.mangi.eta.agent.terminal.shellQuote
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.io.File

/**
 * Eta 启动时自动拉起「浙食链工具台」（MCP 查询服务，127.0.0.1:8790）。
 *
 * 从共享挂载里定位 yqsign，在 Linux 环境执行 `sh 工具台.sh start`（脚本自身幂等：
 * 已在运行会直接返回）。前提不满足（没有挂载 / 环境未就绪 / PRoot 不可用）时静默跳过，
 * 不影响 App 启动。
 */
internal object DeliveryToolboxBootstrap {

    private const val TOOLBOX_SCRIPT = "工具台.sh"

    /** 挂载源可能是 zsl-delivery、智能体或 Download 三层中的任意一层，逐一探测。 */
    private val RELATIVE_CANDIDATES = listOf(
        "yqsign",
        "zsl-delivery/yqsign",
        "智能体/zsl-delivery/yqsign",
    )

    private val supervisor = ShellProcessSupervisor()

    /** 同步阻塞，调用方负责切换 IO 线程。 */
    fun ensure(context: Context) {
        val appContext = context.applicationContext

        val found = SharedFolderMounts.current().firstNotNullOfOrNull { mount ->
            val relative = RELATIVE_CANDIDATES.firstOrNull { candidate ->
                File(mount.sourcePath, "$candidate/$TOOLBOX_SCRIPT").isFile
            } ?: return@firstNotNullOfOrNull null
            mount to relative
        } ?: return

        val distribution = LinuxEnvironmentSettingsRepository.current(appContext)
        val backend = LinuxEnvironmentSettingsRepository.backend(appContext, distribution)
        val rootfsDir = if (backend == LinuxExecutionBackend.PROOT) {
            LinuxEnvironmentPaths.rootfsDir(appContext, distribution, backend)
        } else {
            LinuxEnvironmentPaths.legacyRootfsDir(appContext, distribution)
        }
        if (!LinuxEnvironmentPaths.rootfsReady(rootfsDir.absolutePath)) return
        if (backend == LinuxExecutionBackend.PROOT && !ProotCommandBuilder.available()) return

        val linuxDir = "/workspace/mounts/${found.first.name}/${found.second}"
        val script = "cd ${shellQuote(linuxDir)} && sh $TOOLBOX_SCRIPT start 2>&1 | tail -n 1"
        val result = runCatching {
            runOneShotShell(
                processSupervisor = supervisor,
                identity = if (backend == LinuxExecutionBackend.PROOT) "user" else "root",
                command = script,
                timeoutSeconds = 45,
                environment = if (backend == LinuxExecutionBackend.PROOT) {
                    TerminalEnvironment.ALPINE
                } else {
                    TerminalEnvironment.ANDROID
                },
                linuxRootfsPath = rootfsDir.absolutePath,
                linuxSharedMounts = SharedFolderMounts.current(),
            )
        }.getOrNull() ?: return

        AndroidAgentLogger.info(
            "Delivery toolbox bootstrap: exit=${result.exitCode} " +
                "out=${result.output.decodeToString().trim().take(120)}"
        )
    }
}
