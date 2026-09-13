package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 语义流程（录制/回放）工具 schema。 */
internal object AgentFlowToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "flow_start_recording",
                    description = "开始录制 GUI 操作序列。处理重复性多步任务（如每天打开某 App 完成固定操作）时：第一次开启录制，用 observe_screen/tap_element/input_text 等普通 GUI 工具完成任务，成功后调用 flow_stop_recording 保存为流程；下次同类任务直接 flow_run 秒级回放，无需再逐步观察。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("name", JSONObject().put("type", "string").put("description", "流程名，简短且可复用，如 order-milk-tea、check-attendance。"))
                                .put("description", JSONObject().put("type", "string").put("description", "流程用途说明。"))
                        )
                        .put("required", JSONArray().put("name"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "flow_stop_recording",
                    description = "结束录制。save=true 保存已录制的步骤为可回放流程；save=false 丢弃。任务成功完成后才保存。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("save", JSONObject().put("type", "boolean").put("description", "是否保存，默认 true。"))
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "flows_list",
                    description = "列出已保存的语义流程。用户要求重复之前的操作时，先查这里有没有匹配流程。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "flow_run",
                    description = "回放已保存的流程。步骤按语义自适应执行：按文本在当前屏幕找元素而不是固定坐标，能容忍界面小幅变化。某一步失败会中止并返回详情，此时应改用 observe_screen + tap_element 手动继续完成剩余部分。params 中的键会替换步骤参数里的 {键} 占位符。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("name", JSONObject().put("type", "string").put("description", "要回放的流程名。"))
                                .put(
                                    "params",
                                    JSONObject()
                                        .put("type", "object")
                                        .put("description", "可选：替换步骤中的 {键} 占位符，如 {query}。")
                                )
                        )
                        .put("required", JSONArray().put("name"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "flow_delete",
                    description = "删除一个已保存的流程。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("name", JSONObject().put("type", "string"))
                        )
                        .put("required", JSONArray().put("name"))
                )
            )
    }
}
