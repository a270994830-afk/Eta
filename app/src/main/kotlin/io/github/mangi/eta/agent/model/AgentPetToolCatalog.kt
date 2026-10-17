package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 桌面宠物（虚拟形象）的模型可见声明：显示/隐藏、切换情绪、让它说话。
 *
 * 桌宠是用户可见的屏幕悬浮窗，属于"表现层"工具：不读写用户数据，也不需要特权通道。
 */
internal object AgentPetToolCatalog {
    val TOOL_NAMES: Set<String> = setOf("pet_status", "pet_show", "pet_hide", "pet_state", "pet_say")

    private val MOODS = "idle（空闲）/sleep（睡觉）/thinking（思考）/working（干活）/success（成功）/error（失败）"

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "pet_status",
                description = "查询桌面宠物（虚拟形象）状态：是否显示、当前情绪、正在说的话、以及悬浮窗权限是否具备。",
                parameters = emptyObject(),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "pet_show",
                description = "在屏幕上显示桌面宠物悬浮窗（用户可见）。需要「显示在其他应用上层」权限，缺权限会返回可读原因。",
                parameters = emptyObject(),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "pet_hide",
                description = "隐藏桌面宠物悬浮窗（不删除任何数据）。",
                parameters = emptyObject(),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "pet_state",
                description = "切换桌面宠物的情绪/动作，用于把任务进展可视化。可用值：$MOODS。例如开始长任务前 thinking，执行中 working，成功后 success 并配合 pet_say。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "mood",
                            JSONObject().put("type", "string")
                                .put("description", "情绪：idle/sleep/thinking/working/success/error"),
                        ),
                    )
                    .put("required", JSONArray().put("mood")),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "pet_say",
                description = "让桌面宠物说一句话（气泡显示，最长 120 字符；传空字符串清空）。适合报一句简短进展或结果。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "text",
                            JSONObject().put("type", "string")
                                .put("description", "要说的话；空字符串表示清空气泡"),
                        ),
                    )
                    .put("required", JSONArray().put("text")),
            ),
        )
    }

    private fun emptyObject(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject())
        .put("required", JSONArray())
}
