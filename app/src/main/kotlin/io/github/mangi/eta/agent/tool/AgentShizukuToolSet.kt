package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.device.shizuku.ShizukuShell
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shizuku 工具族门面（对齐 Operit 的 DEBUGGER 权限层级）。
 *
 * 契约与其它 ToolSet 一致：命中返回结果 JSON 字符串，未命中返回 null。
 * 结果形如 `{"ok":true,...}` 或 `{"ok":false,"code","message"}`。
 */
internal class AgentShizukuToolSet(
    @Suppress("unused") private val logger: AgentLogger,
) {
    fun execute(name: String, args: JSONObject): String? = when (name) {
        "shizuku_status" -> status()
        "shizuku_request_permission" -> requestPermission(args)
        "shizuku_shell" -> shell(args)
        "shizuku_list_apps" -> listApps(args)
        else -> null
    }

    private fun status(): String {
        val state = ShizukuShell.status()
        return JSONObject()
            .put("ok", true)
            .put("available", state.optBoolean("available"))
            .put("permission", state.optBoolean("permission"))
            .put("version", state.optInt("version", -1))
            .put("backend", state.optString("backend"))
            .put("uid", state.optInt("uid", -1))
            .put("pre_v11", state.optBoolean("pre_v11"))
            .put("selinux", state.opt("selinux"))
            .put("should_show_rationale", state.optBoolean("should_show_rationale"))
            .toString()
    }

    private fun requestPermission(args: JSONObject): String {
        val requestCode = args.optInt("request_code", ShizukuShell.DEFAULT_REQUEST_CODE)
        if (!ShizukuShell.available()) {
            return error(ShizukuShell.ERROR_UNAVAILABLE, "Shizuku 服务不可用：请先安装并启动 Shizuku（或 Sui）后重试")
        }
        if (ShizukuShell.hasPermission()) {
            return JSONObject()
                .put("ok", true)
                .put("granted", true)
                .put("message", "已获得 Shizuku 权限，可直接调用 shizuku_shell")
                .toString()
        }
        val rationale = ShizukuShell.shouldShowRationale()
        ShizukuShell.requestPermission(requestCode)
        return JSONObject()
            .put("ok", true)
            .put("granted", false)
            .put("requested", true)
            .put("request_code", requestCode)
            .put("should_show_rationale", rationale)
            .put(
                "message",
                "已发起 Shizuku 授权请求（requestCode=$requestCode），请在弹出的界面中允许。" +
                    "授权结果是异步回调：主代理需在 Application/Activity 里注册 " +
                    "Shizuku.addRequestPermissionResultListener 才能收到 onRequestPermissionResult(requestCode, grantResult)，" +
                    "并在销毁时用 Shizuku.removeRequestPermissionResultListener 注销；" +
                    "未注册回调则无法感知结果，只能稍后再用 shizuku_status 轮询。",
            )
            .toString()
    }

    private fun shell(args: JSONObject): String {
        val command = args.optString("command").trim()
        if (command.isEmpty()) return error("INVALID_ARGUMENT", "缺少 command 参数")
        val result = ShizukuShell.run(command, timeoutMillis(args))
        if (result.errorCode.isNotBlank()) return error(result.errorCode, messageFor(result.errorCode))
        return JSONObject()
            .put("ok", result.ok)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
            .put("exit_code", result.exitCode)
            .put("timed_out", result.timedOut)
            .put("truncated", result.truncated)
            .toString()
    }

    private fun listApps(args: JSONObject): String {
        val filter = args.optString("filter").trim()
        val result = ShizukuShell.run("pm list packages", timeoutMillis(args))
        if (result.errorCode.isNotBlank()) return error(result.errorCode, messageFor(result.errorCode))
        val packages = result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(PACKAGE_PREFIX) }
            .map { it.removePrefix(PACKAGE_PREFIX) }
            .filter { it.isNotEmpty() }
            .filter { filter.isEmpty() || it.contains(filter, ignoreCase = true) }
            .distinct()
            .sorted()
            .toList()
        return JSONObject()
            .put("ok", !result.timedOut && result.exitCode == 0)
            .put("count", packages.size)
            .put("filter", filter)
            .put("timed_out", result.timedOut)
            .put("packages", JSONArray(packages))
            .toString()
    }

    private fun timeoutMillis(args: JSONObject): Long {
        val seconds = args.optDouble("timeout_seconds", DEFAULT_TIMEOUT_SECONDS)
        return if (seconds.isFinite() && seconds > 0) {
            (seconds * 1000).toLong()
        } else {
            (DEFAULT_TIMEOUT_SECONDS * 1000).toLong()
        }
    }

    private fun messageFor(code: String): String = when (code) {
        ShizukuShell.ERROR_UNAVAILABLE ->
            "Shizuku 服务不可用：请先安装并启动 Shizuku（或 Sui）"
        ShizukuShell.ERROR_PERMISSION_REQUIRED ->
            "尚未获得 Shizuku 权限：请先调用 shizuku_request_permission 并允许授权"
        else -> "Shizuku 执行失败"
    }

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    companion object {
        /** 供执行层与目录层复用的工具名集合。 */
        val TOOL_NAMES: Set<String> = setOf(
            "shizuku_status",
            "shizuku_request_permission",
            "shizuku_shell",
            "shizuku_list_apps",
        )

        private const val DEFAULT_TIMEOUT_SECONDS = 30.0
        private const val PACKAGE_PREFIX = "package:"
    }
}
