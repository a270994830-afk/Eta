package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * `app_ui_doc` 的模型可见声明：读应用 UI 档案，用来一次生成多步 flow。
 */
internal object AgentAppUiDocToolCatalog {
    val TOOL_NAMES: Set<String> = setOf("app_ui_doc")

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "app_ui_doc",
                description = "读取某个应用沉淀下来的 UI 元素档案（id/文字/描述/是否可点可输）。" +
                    "用法：先用 observe_screen 观察一两次（会自动沉淀），再用本工具一次拿到该应用的可操作元素，" +
                    "据此**一次写出多步 flow**，然后 flow_run 回放——这样不用每一步都观察屏幕、也少调很多次模型。" +
                    "不传 package 时默认当前前台应用。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "package",
                                JSONObject().put("type", "string")
                                    .put("description", "目标应用包名；省略则用当前前台应用"),
                            )
                            .put(
                                "limit",
                                JSONObject().put("type", "integer")
                                    .put("minimum", 5)
                                    .put("maximum", 200)
                                    .put("description", "返回的元素上限，默认 60"),
                            ),
                    )
                    .put("required", JSONArray()),
            ),
        )
    }
}
