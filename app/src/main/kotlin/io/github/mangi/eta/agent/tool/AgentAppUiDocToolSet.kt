package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.AppUiDocument
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONObject

/**
 * 应用 UI 文档工具：读某个应用沉淀下来的元素档案。
 *
 * 目的是让模型**一次写出多步 flow**（再由 flow_run 回放），而不是每步都重新观察屏幕——
 * 对齐 AutoDroid-V2 的 "app document + script" 思路，把模型调用次数从 5~20 次压到 1 次。
 */
internal class AgentAppUiDocToolSet(
    @Suppress("unused") private val logger: AgentLogger,
) {
    fun execute(name: String, args: JSONObject): String? = when (name) {
        "app_ui_doc" -> appUiDoc(args)
        else -> null
    }

    private fun appUiDoc(args: JSONObject): String {
        val service = AgentAccessibilityService.current()
        val packageName = args.optString("package").trim().ifBlank {
            service?.currentPackageName().orEmpty()
        }
        val limit = args.optInt("limit", 60)
        return AppUiDocument.snapshot(service, packageName, limit).toString()
    }

    companion object {
        val TOOL_NAMES: Set<String> = setOf("app_ui_doc")
    }
}
