package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 声明「管理与会话工具族」中的管理类工具；不含执行逻辑。 */
internal object AgentAdminToolCatalog {
    val TOOL_NAMES: List<String> = listOf(
        "model_config_list",
        "model_config_test",
        "usage_stats",
        "tool_permission_list",
        "tool_permission_set",
        "rate_limit_status",
    )

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "model_config_list",
                    description = "列出所有模型 Provider（id、名称、类型、base_url、是否已配置 API Key）及其模型" +
                        "（id、显示名、上下文窗口），并标记当前正在使用的主 Provider 与主模型。用于回答配置现状、排查选错模型。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject()),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "model_config_test",
                    description = "测试模型连通性。用 provider_id（可配合 model_id）复用已保存配置；或直接给 base_url/api_key/model。" +
                        "OpenAI 兼容平台发最小 chat/completions 请求，Anthropic 或 responses 模式改查模型列表；超时 15 秒。" +
                        "返回成功与否、HTTP 状态码与错误消息。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "provider_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "已保存 Provider 的 id；给出时忽略 base_url/api_key。"),
                                )
                                .put(
                                    "model_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "模型 id（Provider 内记录 id 或上游 model 名）；省略时取该 Provider 首个模型。"),
                                )
                                .put("base_url", JSONObject().put("type", "string").put("description", "直接测试时的 API 基础地址。"))
                                .put("api_key", JSONObject().put("type", "string").put("description", "直接测试时的 API Key。"))
                                .put("model", JSONObject().put("type", "string").put("description", "model_id 的别名，直接测试时使用。")),
                        ),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "usage_stats",
                    description = "读取本地模型用量统计，返回最近 N 天（默认 7）以及当月的 prompt/completion token 数与请求数。" +
                        "文件不存在时全部为 0。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "days",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("minimum", 1)
                                    .put("maximum", 90)
                                    .put("description", "返回最近多少天的统计，默认 7。"),
                            ),
                        ),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "tool_permission_list",
                    description = "读取工具级权限策略：默认策略（allow/ask/forbid）与全部显式设置的工具项。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject()),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "tool_permission_set",
                    description = "设置工具级权限：给 tool_name 指定 allow/ask/forbid，或省略 tool_name 设置默认策略。" +
                        "写回权限文件后返回新的默认策略与显式项列表。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "tool_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "目标工具名；省略时修改默认策略。"),
                                )
                                .put(
                                    "policy",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("allow").put("ask").put("forbid"))
                                        .put("description", "allow 直接执行，ask 需用户确认，forbid 禁止。"),
                                ),
                        )
                        .put("required", JSONArray().put("policy")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "rate_limit_status",
                    description = "查看各 Provider 的本地分钟级限流窗口状态（上限、窗口起点、已用次数）。未启用或文件缺失时返回空表。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject()),
                ),
            )
    }
}
