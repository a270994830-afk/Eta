package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentMediaToolCatalog {
    val TOOL_NAMES: Set<String> = setOf(
        "music_status",
        "music_pause",
        "music_resume",
        "music_stop",
        "music_seek",
    )

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "music_status",
                    description = "查询当前音乐/媒体播放状态：正在播放的应用、状态（playing/paused/…）、曲目标题/歌手/专辑、进度与时长、系统媒体音量。控制播放前可先查这个。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                        .put("required", JSONArray()),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "music_pause",
                    description = "暂停当前媒体播放（优先直接控制媒体会话，失败退回媒体按键）。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray()),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "music_resume",
                    description = "继续/恢复当前媒体播放。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray()),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "music_stop",
                    description = "停止当前媒体播放并释放会话。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray()),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "music_seek",
                    description = "跳转到当前曲目的指定位置（毫秒）。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "position_ms",
                                JSONObject().put("type", "integer").put("description", "目标位置，毫秒，>=0。"),
                            ),
                        )
                        .put("required", JSONArray().put("position_ms")),
                )
            )
    }
}
