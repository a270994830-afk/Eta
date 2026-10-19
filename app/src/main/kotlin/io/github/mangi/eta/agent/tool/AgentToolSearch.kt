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
        "tap",
        "tap_text",
        "tap_element",
        "long_press",
        "swipe",
        "scroll",
        "type_text",
        "press_key",
        "launch_app",
        "wait_for_text",
        "wait_for_package",
        "terminal",
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

    /**
     * 按关键词检索工具（匹配 name / description），返回命中的原始工具 schema。
     * 命中的工具会在运行内解锁到下一轮的工具目录——多数模型只肯调用目录里声明过的
     * 函数，只把 schema 写成文本它们不会据此发起调用。
     */
    fun searchHits(query: String, limit: Int): JSONArray {
        val keyword = query.trim().lowercase()
        val max = limit.coerceIn(1, 30)
        val matches = JSONArray()
        collectMatches(fullCatalog(), keyword, max, matches)
        // 延迟工具（MCP 等）也参与检索，但只在还有余量时补充
        if (matches.length() < max) collectMatches(deferredTools(), keyword, max, matches)
        return matches
    }

    /** 把检索结果渲染成给模型看的文本（名称、说明与参数）。 */
    fun renderHits(hits: JSONArray, query: String): String {
        val matches = JSONArray()
        for (index in 0 until hits.length()) {
            val function = hits.optJSONObject(index)?.optJSONObject("function") ?: continue
            matches.put(
                JSONObject()
                    .put("name", function.optString("name", ""))
                    .put("description", function.optString("description", "").take(400))
                    .put("parameters", function.optJSONObject("parameters") ?: JSONObject().put("type", "object")),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", matches.length())
            .put(
                "hint",
                "命中的工具已经出现在你下一轮的工具列表里，直接用它的 name 发起一次普通工具调用即可。" +
                    "MCP 服务器提供的工具也在本检索库里。",
            )
            .put("tools", matches)
            .toString()
    }

    /** 把命中的工具并入运行内工具列表（跳过核心工具与已存在的名字）。 */
    fun unlock(target: JSONArray, hits: JSONArray) {
        val existing = mutableSetOf<String>()
        for (index in 0 until target.length()) {
            nameOf(target.optJSONObject(index))?.let(existing::add)
        }
        for (index in 0 until hits.length()) {
            val tool = hits.optJSONObject(index) ?: continue
            val name = nameOf(tool) ?: continue
            if (name in CORE_TOOL_NAMES || !existing.add(name)) continue
            target.put(tool)
        }
    }

    private fun nameOf(tool: JSONObject?): String? =
        tool?.optJSONObject("function")?.optString("name", "")?.takeIf { it.isNotEmpty() }

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
            out.put(JSONObject(tool.toString()))
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

    /** 按 CLI 精简模式裁剪目录：核心工具 + search_tools，其余按需检索。 */
    fun compact(catalog: JSONArray): JSONArray {
        val result = JSONArray()
        var searchIncluded = false
        for (index in 0 until catalog.length()) {
            val tool = catalog.optJSONObject(index) ?: continue
            val name = tool.optJSONObject("function")?.optString("name", "") ?: continue
            if (name == "search_tools") searchIncluded = true
            if (name in CORE_TOOL_NAMES) result.put(tool)
        }
        if (result.length() == 0) {
            // 检索库不可用时退回完整目录，避免模型无工具可用。
            return catalog
        }
        if (!searchIncluded) result.put(searchToolSchema())
        return result
    }
}
