package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.model.AgentToolSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具检索（对齐 Operit 的 CLI 精简模式）：小模型只下发核心工具，其余工具通过
 * `search_tools` 按关键词检索 schema，再按名字直接调用。
 */
internal object AgentToolSearch {
    /** CLI 模式下始终直出的核心工具。 */
    val CORE_TOOL_NAMES: Set<String> = setOf(
        "get_current_context",
        "observe_screen",
        "ocr_screen",
        "tap_text",
        "tap_element",
        "input_text",
        "press_key",
        "launch_app",
        "wait_for_text",
        "wait_for_package",
        "terminal",
        "run_command",
        "read_file",
        "write_file",
        "list_directory",
        "memory_get",
        "memory_write",
        "skills_list",
        "skills_read",
        "search_tools",
        "note_add",
        "note_list",
    )

    @Volatile
    private var cached: JSONArray? = null

    /** 全量工具目录（含所有开关，作为检索库）。 */
    fun fullCatalog(): JSONArray {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val built = runCatching {
                AgentToolCatalog.build(
                    terminalTools = true,
                    browserTools = true,
                    deviceDirectTools = true,
                    deviceSensitiveReadTools = true,
                    deviceSensitiveActionTools = true,
                    skillGitHubDiscovery = true,
                    skillGitHubInstall = true,
                    memoryTools = true,
                    memoryWritable = true,
                    shadowTools = true,
                    capabilities = AgentToolCapabilities(
                        rootAvailable = true,
                        accessibilityAvailable = true,
                        accessibilityRecoveryAvailable = true,
                    ),
                )
            }.getOrElse { JSONArray() }
            cached = built
            return built
        }
    }

    // ── 延迟工具（默认不注入 prompt，只进检索库）────────────────
    // 主要用途：MCP 服务器汇总进来的上百个 schema。全量注入会占掉几万 token、拖慢每一轮模型调用
    // （实测 344 个工具 / 约 10 万 token / 每轮 4s），改成本模块按需检索后能省掉绝大部分。
    @Volatile
    private var deferredProvider: (() -> JSONArray)? = null

    /** 注册“延迟工具”提供方（MCP 等）；置空缓存以便下次检索能看到新工具。 */
    fun registerDeferredTools(provider: (() -> JSONArray)?) {
        deferredProvider = provider
    }

    private fun deferredTools(): JSONArray = runCatching {
        deferredProvider?.invoke() ?: JSONArray()
    }.getOrDefault(JSONArray())

    /** 按关键词检索工具（匹配 name / description），返回精简 schema 列表。 */
    fun search(query: String, limit: Int): String {
        val keyword = query.trim().lowercase()
        val max = limit.coerceIn(1, 30)
        val matches = JSONArray()
        collectMatches(fullCatalog(), keyword, max, matches)
        // 延迟工具（MCP 等）也参与检索，但只在还有余量时补充
        if (matches.length() < max) collectMatches(deferredTools(), keyword, max, matches)
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", matches.length())
            .put(
                "hint",
                "拿到需要的工具 schema 后，直接用它的 name 发起一次普通工具调用即可（不需要再搜索）。" +
                    "MCP 服务器提供的工具也在本检索库里。",
            )
            .put("tools", matches)
            .toString()
    }

    private fun collectMatches(catalog: JSONArray, keyword: String, max: Int, out: JSONArray) {
        for (index in 0 until catalog.length()) {
            if (out.length() >= max) return
            val tool = catalog.optJSONObject(index) ?: continue
            val function = tool.optJSONObject("function") ?: continue
            val name = function.optString("name", "")
            val description = function.optString("description", "")
            if (keyword.isNotEmpty() &&
                !name.lowercase().contains(keyword) &&
                !description.lowercase().contains(keyword)
            ) {
                continue
            }
            out.put(
                JSONObject()
                    .put("name", name)
                    .put("description", description.take(400))
                    .put("parameters", function.optJSONObject("parameters") ?: JSONObject().put("type", "object")),
            )
        }
    }

    fun searchToolSchema(): JSONObject = AgentToolSchema.function(
        name = "search_tools",
        description = "按关键词检索可用工具（名字或用途）。当前处于工具精简模式时，除核心工具外的工具需要先在这里检索出 schema 再调用；返回匹配工具的名称、说明与参数定义。",
        parameters = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("query", JSONObject().put("type", "string").put("description", "关键词，例如「蓝牙」「文件移动」「日历」。留空则返回全部（上限 30 条）。"))
                    .put("limit", JSONObject().put("type", "integer").put("description", "最多返回 1-30 条，默认 12。")),
            )
            .put("required", JSONArray()),
    )

    /** CLI 模式下的工具目录：核心工具 + search_tools。 */
    fun cliTools(capabilities: AgentToolCapabilities): JSONArray {
        val full = fullCatalog()
        val result = JSONArray()
        for (index in 0 until full.length()) {
            val tool = full.optJSONObject(index) ?: continue
            val name = tool.optJSONObject("function")?.optString("name", "") ?: continue
            if (name in CORE_TOOL_NAMES) result.put(tool)
        }
        if (result.length() == 0) {
            // 检索库不可用时退回完整目录，避免模型无工具可用。
            return full
        }
        result.put(searchToolSchema())
        return result
    }
}
