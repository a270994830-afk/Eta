package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.device.PrivilegedShell
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.RootSuResolver
import io.github.mangi.eta.agent.device.shizuku.ShizukuShell
import io.github.mangi.eta.core.AgentLogger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 特权兜底通道门面：把「root 优先，Shizuku 兜底」的整体状态暴露给模型。
 *
 * 契约与其它 ToolSet 一致：命中返回结果 JSON 字符串，未命中返回 null；
 * 结果形如 `{"ok":true,...}` 或 `{"ok":false,"code","message"}`。不返回任何密钥。
 */
internal class AgentPrivilegedToolSet(
    @Suppress("unused") private val logger: AgentLogger,
) {
    fun execute(name: String, args: JSONObject): String? = when (name) {
        "privileged_status" -> status()
        else -> null
    }

    private fun status(): String {
        // 主动重探一次并按上限等待，避免返回“首次探测失败后就永久未授权”的过期状态。
        RootAccess.recheck()?.let { job ->
            runBlocking { withTimeoutOrNull(4_000L) { job.join() } }
        }
        val shizuku = ShizukuShell.status()
        val root = JSONObject()
            .put("granted", PrivilegedShell.rootAvailable())
            .put("su_path", RootSuResolver.resolve() ?: JSONObject.NULL)
        val shizukuBlock = JSONObject()
            .put("available", shizuku.optBoolean("available"))
            .put("permission", shizuku.optBoolean("permission"))
            .put("version", shizuku.optInt("version", -1))
        return JSONObject()
            .put("ok", true)
            .put("root", root)
            .put("shizuku", shizukuBlock)
            .put("channel", PrivilegedShell.activeChannel())
            .put("privileged", PrivilegedShell.available())
            .toString()
    }

    companion object {
        val TOOL_NAMES: Set<String> = setOf("privileged_status")
    }
}
