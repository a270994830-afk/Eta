package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentPreferenceToolCatalog {
    val TOOL_NAMES: Set<String> = setOf(
        "speech_config_get",
        "speech_config_set",
        "character_list",
        "character_get",
        "data_health",
        "mcp_servers_list",
        "mcp_server_refresh",
        "tool_permission_approve",
    )

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "speech_config_get",
                    description = "读取当前语音服务配置：识别/合成引擎（asr/tts）、是否自动朗读、语言、音色，以及哪些云服务密钥已配置（只返回是否已配置，不返回密钥内容）。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray()),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "speech_config_set",
                    description = "修改语音服务配置（不涉及密钥）。只传需要改的字段；asr 取值 SYSTEM/QWEN_REALTIME/QWEN_FLASH/QWEN_FILE/DOUBAO，tts 取值 NONE/QWEN/DOUBAO。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("asr", JSONObject().put("type", "string").put("description", "语音识别引擎。"))
                                .put("tts", JSONObject().put("type", "string").put("description", "语音合成引擎。"))
                                .put("auto_speak", JSONObject().put("type", "boolean").put("description", "回复是否自动朗读。"))
                                .put("language", JSONObject().put("type", "string").put("description", "识别语言，如 zh-CN。"))
                                .put("qwen_voice", JSONObject().put("type", "string").put("description", "千问音色名，默认 Cherry。"))
                                .put("doubao_voice", JSONObject().put("type", "string").put("description", "豆包音色名。")),
                        ),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "character_list",
                    description = "列出本机角色卡（id/名称/是否有头像/更新时间）。涉及角色扮演角色时先查这里。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "limit",
                                JSONObject().put("type", "integer").put("description", "最多返回 1-100 条，默认 20。"),
                            ),
                        ),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "character_get",
                    description = "按 id 或名称读取单个角色卡的名称与设定描述。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("id", JSONObject().put("type", "string").put("description", "角色 id，与 name 二选一。"))
                                .put("name", JSONObject().put("type", "string").put("description", "角色名称，与 id 二选一。")),
                        ),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "data_health",
                    description = "数据健康自检（只读）：数据库 quick_check 结果、会话/消息等表行数、关键文件体积。排查数据异常或做体检时使用。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray()),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "mcp_servers_list",
                    description = "列出已配置的 MCP 客户端服务器及其工具发现状态（传输类型/是否启用/已发现工具数/已启用工具数/上次刷新时间/协议版本）。排查「外部 MCP 工具为什么不可用」时先看这里。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray()),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "mcp_server_refresh",
                    description = "重新发现指定 MCP 服务器的工具（按 id 或名称）。返回发现的工具名列表；失败时返回具体错误信息，便于定位连接/权限问题。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("id", JSONObject().put("type", "string").put("description", "服务器 id，与 name 二选一。"))
                                .put("name", JSONObject().put("type", "string").put("description", "服务器名称，与 id 二选一。")),
                        ),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "tool_permission_approve",
                    description = "对某个 ask 策略的工具授权放行（替代卡片点击，供外部/MCP 客户端在无卡片 UI 时使用）：" +
                        "scope=once 只放行一次；session 本会话内一直放行；always 持久写入 allow（等价 tool_permission_set）。" +
                        "返回授权后的权限快照（含临时放行状态）。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "tool_name",
                                    JSONObject().put("type", "string").put("description", "被 ask 拦截的工具名。"),
                                )
                                .put(
                                    "scope",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("once").put("session").put("always"))
                                        .put("description", "授权范围，默认 once。"),
                                ),
                        )
                        .put("required", JSONArray().put("tool_name")),
                )
            )
    }
}
