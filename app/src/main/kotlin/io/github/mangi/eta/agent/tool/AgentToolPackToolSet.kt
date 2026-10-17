package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONObject

/**
 * 工具包（Tool Pack）管理工具族：列出 / 安装 / 移除 / 重载 / 查看。
 *
 * 真正的加载与执行在 [AgentToolPackManager]。安装来源二选一：`url` 下载，或 `json` 内联内容。
 */
internal class AgentToolPackToolSet(
    context: Context,
    @Suppress("unused") private val logger: AgentLogger,
) {
    init {
        AgentToolPackManager.init(context)
    }

    fun execute(name: String, args: JSONObject): String? = when (name) {
        "tool_pack_list" -> listPacks()
        "tool_pack_install" -> install(args)
        "tool_pack_remove" -> remove(args)
        "tool_pack_reload" -> AgentToolPackManager.reload()
        "tool_pack_show" -> show(args)
        else -> null
    }

    private fun listPacks(): String = JSONObject()
        .put("ok", true)
        .put("packs", AgentToolPackManager.listPacks())
        .toString()

    private fun install(args: JSONObject): String {
        val url = args.optString("url", "").trim()
        val json = args.optString("json", "").trim()
        val name = args.optString("name", "").trim().takeIf { it.isNotEmpty() }
        if (url.isEmpty() && json.isEmpty()) {
            return error("INVALID_ARGUMENT", "必须提供 url 或 json 之一")
        }
        if (url.isNotEmpty() && json.isNotEmpty()) {
            return error("INVALID_ARGUMENT", "url 与 json 只能二选一")
        }
        return if (url.isNotEmpty()) {
            AgentToolPackManager.installFromUrl(url, name)
        } else {
            AgentToolPackManager.installFromJson(json, name)
        }
    }

    private fun remove(args: JSONObject): String {
        val name = args.optString("name", "").trim()
        if (name.isEmpty()) return error("INVALID_ARGUMENT", "缺少 name")
        return AgentToolPackManager.remove(name)
    }

    private fun show(args: JSONObject): String {
        val name = args.optString("name", "").trim()
        if (name.isEmpty()) return error("INVALID_ARGUMENT", "缺少 name")
        val packs = AgentToolPackManager.listPacks()
        for (index in 0 until packs.length()) {
            val pack = packs.optJSONObject(index) ?: continue
            if (pack.optString("name") == name) {
                return JSONObject()
                    .put("ok", true)
                    .put("pack", pack)
                    .put("tool_schemas", AgentToolPackManager.toolDefinitions(name))
                    .toString()
            }
        }
        return error("PACK_NOT_FOUND", "未找到工具包: $name")
    }

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    companion object {
        val TOOL_NAMES: List<String> = listOf(
            "tool_pack_list",
            "tool_pack_install",
            "tool_pack_remove",
            "tool_pack_reload",
            "tool_pack_show",
        )
    }
}
