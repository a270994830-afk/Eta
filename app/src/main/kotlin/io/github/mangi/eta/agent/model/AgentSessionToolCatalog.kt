package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 声明「管理与会话工具族」中的会话类工具；不含执行逻辑。 */
internal object AgentSessionToolCatalog {
    val TOOL_NAMES: List<String> = listOf(
        "conversation_list",
        "conversation_read",
        "conversation_delete",
    )

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "conversation_list",
                    description = "列出历史会话（id、标题、创建/更新时间、消息数），按更新时间倒序。" +
                        "keyword 可按标题或会话 id 过滤；limit/offset 分页。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("maximum", 200)
                                        .put("description", "返回条数，默认 20。"),
                                )
                                .put(
                                    "offset",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 0)
                                        .put("description", "跳过的条数，默认 0。"),
                                )
                                .put(
                                    "keyword",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "按标题或会话 id 大小写不敏感过滤。"),
                                ),
                        ),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "conversation_read",
                    description = "读取指定会话的消息（按顺序分页），返回每条的角色/类型与文本摘要，长文本截断到 2000 字符。" +
                        "limit 默认 50，配合 offset 续读。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "conversation_id",
                                    JSONObject().put("type", "string").put("description", "目标会话 id。"),
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("maximum", 500)
                                        .put("description", "单页消息数，默认 50。"),
                                )
                                .put(
                                    "offset",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 0)
                                        .put("description", "从第几条消息开始，默认 0。"),
                                ),
                        )
                        .put("required", JSONArray().put("conversation_id")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "conversation_delete",
                    description = "删除指定会话及其全部消息与上下文检查点。操作不可撤销，删除前应确认会话 id。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "conversation_id",
                                JSONObject().put("type", "string").put("description", "要删除的会话 id。"),
                            ),
                        )
                        .put("required", JSONArray().put("conversation_id")),
                ),
            )
    }
}
