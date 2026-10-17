package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 声明「轻量插件包（Tool Pack）」管理工具；不含执行逻辑（执行见 AgentToolPackToolSet）。 */
internal object AgentToolPackToolCatalog {
    val TOOL_NAMES: List<String> = listOf(
        "tool_pack_list",
        "tool_pack_install",
        "tool_pack_remove",
        "tool_pack_reload",
        "tool_pack_show",
    )

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "tool_pack_list",
                    description = "列出已安装的轻量插件包：包名、说明、版本、文件 SHA-256、是否解析成功，以及每个包对外暴露的工具名" +
                        "（形如 pack_<包名>_<工具名>）。插件包用 JSON 声明工具，热加载后即可直接调用；用它查看当前有哪些扩展工具。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject()),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "tool_pack_install",
                    description = "安装或覆盖一个轻量插件包。来源二选一：url（http/https 下载，上限 512KB）或 json（内联包内容）。" +
                        "包体必须是合法 JSON，含 name 与非空 tools 数组；可选的 name 参数覆盖包内 name 并决定文件名。" +
                        "安装后立即热加载，包内工具以下发名 pack_<包名>_<工具名> 可调用。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("url", JSONObject().put("type", "string").put("description", "包 JSON 的 http/https 地址。"))
                                .put("json", JSONObject().put("type", "string").put("description", "内联的包 JSON 文本。"))
                                .put("name", JSONObject().put("type", "string").put("description", "可选，覆盖包名（字母/数字/下划线/连字符/点）。")),
                        ),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "tool_pack_remove",
                    description = "删除一个已安装的插件包文件并热加载。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("name", JSONObject().put("type", "string").put("description", "要删除的包名。")),
                        )
                        .put("required", JSONArray().put("name")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "tool_pack_reload",
                    description = "重新扫描插件包目录并重建工具索引（外部改了包文件后用它生效），返回包数与工具数。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject()),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "tool_pack_show",
                    description = "查看指定插件包的详情：包信息、暴露的工具及其完整参数 schema（OpenAI function 形式）。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("name", JSONObject().put("type", "string").put("description", "要查看的包名。")),
                        )
                        .put("required", JSONArray().put("name")),
                ),
            )
    }
}
