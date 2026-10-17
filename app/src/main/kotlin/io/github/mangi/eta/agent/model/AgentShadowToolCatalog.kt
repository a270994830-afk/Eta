package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 影子屏工具的模型可见声明。
 *
 * 这组工具让智能体在一块独立的虚拟显示上操作应用：启动、点击、滑动、输入、截图，
 * 全程不干扰用户正在使用的物理屏幕，适合在后台跑长流程任务。
 */
internal object AgentShadowToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "shadow_open",
                    description = "开启影子屏（一块独立的虚拟显示），并可选地在其上启动一个应用。" +
                        "影子屏上的操作不会显示在用户当前屏幕上，适合后台执行自动化任务。" +
                        "重复调用会复用已存在的影子屏。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "可选，开启后立即在影子屏上启动的应用包名。")
                                )
                                .put(
                                    "width",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 320)
                                        .put("maximum", 4320)
                                        .put("description", "影子屏宽度，默认沿用设备屏幕宽度。")
                                )
                                .put(
                                    "height",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 320)
                                        .put("maximum", 4320)
                                        .put("description", "影子屏高度，默认沿用设备屏幕高度。")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "shadow_close",
                    description = "关闭影子屏并回收其独立进程。任务结束后应调用以释放资源。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "shadow_status",
                    description = "查询影子屏当前状态：是否已开启、分辨率、承载进程是否存活。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "shadow_launch",
                    description = "在影子屏上启动一个应用（不会显示在用户屏幕上）。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "package_name",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "要启动的应用包名，例如 com.tencent.mm。")
                            )
                        )
                        .put("required", JSONArray().put("package_name"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "shadow_tap",
                    description = "在影子屏上点击指定坐标。坐标系与 shadow_screenshot 返回的图像一致。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                        )
                        .put("required", JSONArray().put("x").put("y"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "shadow_swipe",
                    description = "在影子屏上从一点滑动到另一点。向上滑动会让列表向下滚动。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 100)
                                        .put("maximum", 3000)
                                        .put("description", "滑动时长，默认 500 毫秒。")
                                )
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "shadow_input_text",
                    description = "向影子屏当前聚焦的输入框输入文本。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "text",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "要输入的文本。")
                            )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "shadow_press_key",
                    description = "向影子屏发送一个按键事件，例如 BACK、HOME、ENTER。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("key", JSONObject().put("type", "string"))
                        )
                        .put("required", JSONArray().put("key"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "shadow_screenshot",
                    description = "截取影子屏当前画面并作为图像返回，用于判断界面状态。" +
                        "返回图像的像素坐标可直接用于 shadow_tap。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "shadow_ui_tree",
                    description = "读取影子屏当前界面的 UI 节点结构，用于定位可点击、可输入的控件。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject())
                )
            )
    }

    /** 影子屏工具名集合，供执行层与权限层复用。 */
    val TOOL_NAMES: Set<String> = setOf(
        "shadow_open",
        "shadow_close",
        "shadow_status",
        "shadow_launch",
        "shadow_tap",
        "shadow_swipe",
        "shadow_input_text",
        "shadow_press_key",
        "shadow_screenshot",
        "shadow_ui_tree",
    )
}
