package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 屏幕录制工具的模型可见声明。
 *
 * 让智能体把设备屏幕录制成 mp4（可选录麦克风），用于留存操作过程、复现问题等。
 * 受系统录屏授权限制：无有效授权时 screen_record_start 返回 NEEDS_CONSENT，
 * 需要拉起授权 Activity 由用户确认后才能开录。
 */
internal object AgentRecordingToolCatalog {
    val TOOL_NAMES: Set<String> = setOf(
        "screen_record_start",
        "screen_record_stop",
        "screen_record_status",
        "screen_record_list",
    )

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "screen_record_start",
                    description = "开始录制设备屏幕到 mp4 文件（可选录麦克风，默认录 300 秒，到时自动停止）。" +
                        "若返回 code=NEEDS_CONSENT，表示需要用户授权：启动返回中的 activity 组件（root/am start 或用户点按）" +
                        "并在系统弹窗确认后会自动开始录制。已有录制进行中会返回 ALREADY_RECORDING。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "width",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 320)
                                        .put("maximum", 4320)
                                        .put("description", "录制宽度，默认取设备屏幕真实宽度。"),
                                )
                                .put(
                                    "height",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 320)
                                        .put("maximum", 4320)
                                        .put("description", "录制高度，默认取设备屏幕真实高度。"),
                                )
                                .put(
                                    "bit_rate",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1000000)
                                        .put("maximum", 50000000)
                                        .put("description", "视频码率（比特/秒），默认 8000000（8Mbps）。"),
                                )
                                .put(
                                    "mic",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "是否同时录制麦克风，默认 false。需要 RECORD_AUDIO 权限，且可能与语音识别冲突。"),
                                )
                                .put(
                                    "max_seconds",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("maximum", 3600)
                                        .put("description", "最长录制秒数，到时自动停止，默认 300。"),
                                )
                                .put(
                                    "name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "可选文件名前缀（实际文件名会追加时间戳），默认 rec_<时间戳>.mp4。"),
                                ),
                        )
                        .put("required", JSONArray()),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "screen_record_stop",
                    description = "停止当前屏幕录制并释放资源，返回文件路径、时长（毫秒）与文件大小（字节）。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "handle",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "可选，screen_record_start 返回的 handle（文件路径）；不传则停止当前录制。"),
                            ),
                        )
                        .put("required", JSONArray()),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "screen_record_status",
                    description = "查询屏幕录制状态：是否正在录制、已录时长（毫秒）、目标文件、是否已具备可用授权。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "handle",
                                JSONObject().put("type", "string").put("description", "可选，指定要查询的录制 handle。"),
                            ),
                        )
                        .put("required", JSONArray()),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "screen_record_list",
                    description = "列出已录制的视频文件（按修改时间倒序），含文件名、路径、大小与修改时间。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray()),
                ),
            )
    }
}
