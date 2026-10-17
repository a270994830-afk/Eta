package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 特权兜底通道的模型可见声明：报告「当前有 root 还是 Shizuku 可用、走哪条通道」。
 *
 * 用于让模型在规划前判断特权能力是否可用（影子屏、root shell 等），
 * 不暴露任何密钥。
 */
internal object AgentPrivilegedToolCatalog {
    val TOOL_NAMES: Set<String> = setOf("privileged_status")

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "privileged_status",
                description = "查询本机特权执行通道：是否可用、以及当前走哪条通道（root=su / shizuku=免 Root 的 ADB 级）。" +
                    "返回 root.granted 与 root.su_path、shizuku.available/permission/version、channel（root/shizuku/none）、" +
                    "privileged（两条通道是否有任一条可用）。需要特权的能力（影子屏、root shell 等）会自动走可用通道，本工具用于提前确认。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject())
                    .put("required", JSONArray()),
            ),
        )
    }
}
