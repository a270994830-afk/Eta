package io.github.mangi.eta.agent.tool

import org.json.JSONArray
import org.json.JSONObject

internal enum class RootRequirement { NONE, PARTIAL, REQUIRED }
internal enum class LsposedRequirement { NONE, OPTIONAL, REQUIRED }

internal enum class ToolSystemAccess { NONE, NOTIFICATIONS, USAGE, LOCATION }

internal data class LocalToolRequirement(
    val rootRequirement: RootRequirement,
    val lsposedRequirement: LsposedRequirement = LsposedRequirement.NONE,
    val accessibility: Boolean = false,
    val systemAccess: ToolSystemAccess = ToolSystemAccess.NONE,
    val colorOs: Boolean = false,
)

/** 展示、模型目录与执行边界共同使用的本地工具能力合同。未登记的工具不能发布。 */
internal object AgentToolRequirements {
    private val definitions = buildMap {
        fun register(root: RootRequirement, vararg names: String) {
            names.forEach { name ->
                check(put(name, LocalToolRequirement(root)) == null) { "Duplicate tool: $name" }
            }
        }
        register(
            RootRequirement.NONE,
            "get_current_context", "search_apps", "launch_app", "open_uri", "browser_use", "ocr_screen",
            "observe_screen", "tap", "tap_area", "tap_element", "long_press",
            "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
            "replace_text", "clear_text", "set_clipboard", "get_clipboard", "paste_text",
            "wait", "wait_for_text", "wait_for_package", "open_system_panel",
            "set_alarm", "set_timer", "device_status", "media_control", "set_volume",
            "play_music", "play_video", "web_search", "open_device_action",
            "order_food", "stock", "short_video", "navigate", "flashlight",
            "send_sms", "set_brightness",
            "call",
            "reply_notification",
            "flow_start_recording", "flow_stop_recording", "flows_list", "flow_run", "flow_update", "flow_delete",
            "tap_text",
            "search_notification_history", "recent_app_activity", "app_usage_summary",
            "get_current_location", "get_device_environment", "memory_get", "memory_write",
            "character_memory_get", "character_memory_write",
            "skills_list", "skills_read", "skills_read_resource", "skills_list_curated",
            "skills_inspect_github", "skills_install_from_github",
            "double_tap", "note_add", "note_list", "note_clear",
        )
        register(
            RootRequirement.PARTIAL,
            "press_key", "network_info", "get_setting", "recent_notifications",
            "search_personal_orders", "terminal", "run_command", "read_file",
            "write_file", "list_directory", "read_image",
        )
        register(
            RootRequirement.REQUIRED,
            "top_memory_apps", "top_storage_apps", "wifi_credentials", "read_sms_code",
            "get_logcat", "set_setting", "set_device_state", "app_state_control", "create_calendar_event",
            "list_alarms", "list_active_timers", "get_health_summary", "search_clipboard_history",
            "search_media", "search_audio", "search_recordings", "search_files",
            "search_calendar_events", "search_contacts", "search_call_history", "search_messages",
            "search_downloads", "search_coloros_notes", "search_coloros_recordings",
            "search_recording_summaries", "search_coloros_memories", "search_saved_places",
            "search_qq_chat_images", "search_wechat_chat_images",
        )
        // 影子屏（无头虚拟屏）工具链：需要 root/carrier。
        // 未登记会让 capabilities.project() 里 rootRequirement() 的 requireNotNull 抛
        // "Missing tool requirements"，整轮 run 在工具目录构建阶段就崩。
        register(
            RootRequirement.REQUIRED,
            "shadow_open", "shadow_close", "shadow_status", "shadow_launch",
            "shadow_tap", "shadow_swipe", "shadow_input_text", "shadow_press_key",
            "shadow_screenshot", "shadow_ui_tree",
        )
        // 文件工具族（terminal 开关控制下发）
        register(
            RootRequirement.NONE,
            "file_exists", "file_copy", "file_find", "file_grep", "file_edit",
            "file_mkdir", "file_zip", "file_unzip", "file_download",
        )
        register(RootRequirement.PARTIAL, "file_move", "file_delete")
        // 集成/系统工具族
        register(
            RootRequirement.NONE,
            "http_request", "calculate", "toast", "send_notification",
            "list_installed_apps", "execute_intent", "send_broadcast", "update_check",
        )
        register(RootRequirement.PARTIAL, "install_app")
        register(RootRequirement.REQUIRED, "uninstall_app")
        // 管理/会话/媒体工具族
        // 管理/会话/媒体/蓝牙工具族
        // Shizuku（免 Root 的 ADB 级特权通道）
        register(
            RootRequirement.NONE,
            "shizuku_status", "shizuku_request_permission", "shizuku_shell", "shizuku_list_apps",
            "privileged_status",
        )
        // 更新与录屏
        register(RootRequirement.NONE, "update_download", "update_verify")
        register(RootRequirement.PARTIAL, "update_install")
        register(
            RootRequirement.NONE,
            "screen_record_start", "screen_record_stop", "screen_record_status", "screen_record_list",
        )
        // 桌面宠物（虚拟形象）：纯表现层，无特权/无障碍依赖
        register(
            RootRequirement.NONE,
            "pet_status", "pet_show", "pet_hide", "pet_state", "pet_say",
        )
        // 应用 UI 文档（读元素档案，供一次生成多步 flow；未登记会被目录过滤掉）
        register(RootRequirement.NONE, "app_ui_doc")
        // 插件包管理（pack_* 动态工具走 dynamicRequirement 兜底）
        register(
            RootRequirement.NONE,
            "tool_pack_list", "tool_pack_install", "tool_pack_remove",
            "tool_pack_reload", "tool_pack_show",
        )
        // 工作流编排
        register(
            RootRequirement.NONE,
            "workflow_create", "workflow_list", "workflow_get", "workflow_delete",
            "workflow_run", "workflow_schedule", "workflow_unschedule",
        )
        // 偏好类（语音配置/角色卡/数据自检/MCP 运维/工具授权）
        register(
            RootRequirement.NONE,
            "speech_config_get", "speech_config_set",
            "character_list", "character_get", "data_health",
            "mcp_servers_list", "mcp_server_refresh",
            "tool_permission_approve",
        )
        register(
            RootRequirement.NONE,
            "bt_status", "bt_paired_devices", "bt_scan",
            "bt_spp_connect", "bt_spp_send", "bt_spp_close",
            "bt_ble_scan", "bt_ble_connect", "bt_ble_write", "bt_ble_read",
        )
        register(
            RootRequirement.NONE,
            "model_config_list", "model_config_test", "usage_stats",
            "tool_permission_list", "tool_permission_set", "rate_limit_status",
            "conversation_list", "conversation_read", "conversation_delete",
            "music_status", "music_pause", "music_resume", "music_stop", "music_seek",
            "search_tools",
        )
        listOf(
            "observe_screen", "ocr_screen", "tap", "tap_text", "tap_area", "tap_element", "long_press",
            "double_tap",
            "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
            "replace_text", "clear_text", "paste_text", "press_key", "open_system_panel",
            "wait_for_text", "wait_for_package",
        ).forEach { name -> put(name, getValue(name).copy(accessibility = true)) }
        mapOf(
            "recent_notifications" to ToolSystemAccess.NOTIFICATIONS,
            "search_notification_history" to ToolSystemAccess.NOTIFICATIONS,
            "search_personal_orders" to ToolSystemAccess.NOTIFICATIONS,
            "recent_app_activity" to ToolSystemAccess.USAGE,
            "app_usage_summary" to ToolSystemAccess.USAGE,
            "get_current_location" to ToolSystemAccess.LOCATION,
        ).forEach { (name, access) -> put(name, getValue(name).copy(systemAccess = access)) }
        listOf(
            "search_coloros_notes", "search_coloros_recordings", "search_recording_summaries",
            "search_coloros_memories", "search_saved_places",
        ).forEach { name -> put(name, getValue(name).copy(colorOs = true)) }
        // 系统记忆优先使用 Hook 桥接，框架失联时仍有独立的 Root 快照来源。
        listOf("search_coloros_memories", "search_saved_places", "search_personal_orders").forEach { name ->
            put(name, getValue(name).copy(lsposedRequirement = LsposedRequirement.OPTIONAL))
        }
    }

    val toolNames: Set<String> get() = definitions.keys

    fun find(name: String): LocalToolRequirement? = definitions[name] ?: dynamicRequirement(name)

    /**
     * 动态工具（插件包 `pack_*`）不在静态表里，默认按「无 Root 需求」处理；
     * 若直接返回 null，capabilities.project() 里的 requireNotNull 会抛异常、整轮工具目录作废。
     */
    private fun dynamicRequirement(name: String): LocalToolRequirement? =
        if (name.startsWith("pack_")) LocalToolRequirement(RootRequirement.NONE) else null

    /**
     * 读取某工具需要的能力等级。
     *
     * **容错语义**：未登记的工具按 NONE 处理（fail-open），不再 requireNotNull 抛异常——
     * 曾经的严格写法在 capabilities.project() 里会让**整个模型工具目录构建失败**
     * （MCP 端被 runCatching 吞掉后静默返回 0 个工具，极难排查）。
     */
    fun rootRequirement(name: String): RootRequirement =
        find(name)?.rootRequirement ?: RootRequirement.NONE

    fun requiresAccessibility(name: String): Boolean = find(name)?.accessibility == true

    fun rootDenied(name: String, arguments: JSONObject, rootAvailable: Boolean): Boolean {
        if (rootAvailable) return false
        if (rootRequirement(name) == RootRequirement.REQUIRED) return true
        return when (name) {
            "terminal" -> arguments.optString("identity").equals("root", ignoreCase = true)
            "press_key" -> arguments.optString("button").equals("PASTE", ignoreCase = true)
            else -> false
        }
    }

    /** 复制后收窄，不能修改下一轮或另一个 run 共用的原始 Schema。 */
    fun project(tools: JSONArray, rootAvailable: Boolean): JSONArray = JSONArray().also { result ->
        for (index in 0 until tools.length()) {
            val original = tools.getJSONObject(index)
            val name = original.getJSONObject("function").getString("name")
            val requirement = rootRequirement(name)
            if (!rootAvailable && requirement == RootRequirement.REQUIRED) continue
            val tool = JSONObject(original.toString())
            if (!rootAvailable) projectUnprivileged(tool.getJSONObject("function"))
            result.put(tool)
        }
    }

    private fun projectUnprivileged(function: JSONObject) {
        val properties = function.getJSONObject("parameters").optJSONObject("properties")
        when (function.getString("name")) {
            "terminal" -> {
                function.put("description", "在当前设备管理普通 Android Shell 或用户选择的 Linux 环境。" +
                    "以 App UID 执行，支持会话、异步任务和后台服务；Linux 内的模拟身份不提供 Android 系统特权。" +
                    "使用 open_and_exec 执行单次命令，open/exec 复用会话（wait=false 启动长任务/交互式命令后用 read_session 取输出、input 写输入），daemon_start/list/logs/stop 管理后台服务。")
                properties?.getJSONObject("identity")
                    ?.put("enum", JSONArray().put("user"))
                    ?.put("description", "宿主执行身份；当前仅支持 user，默认 user。")
                properties?.getJSONObject("environment")?.put("description",
                    "android 使用普通 Android Shell；linux 使用用户选择的发行版和免 Root 后端。默认 android。")
                properties?.getJSONObject("cwd")?.put("description",
                    "工作目录。Android 默认使用 Eta 私有工作区，Linux 默认 /workspace。")
            }
            "run_command" -> {
                function.put("description",
                    "通过普通 Android Shell 执行单次非交互命令，以 App UID 运行；只能访问当前应用有权访问的资源。")
                properties?.getJSONObject("cwd")?.put("description", "工作目录，默认使用 Eta 私有工作区。")
            }
            "list_directory" -> {
                function.put("description", "列出当前应用有权访问的目录，默认使用 Eta 私有工作区。")
                properties?.optJSONObject("path")?.apply {
                    put("description", "目录路径；未提供时使用 Eta 私有工作区。")
                    remove("default")
                }
            }
            "read_image" -> {
                properties?.getJSONObject("path")?.put("description",
                    "当前应用有权读取的绝对图片路径、file URI 或已授权的 content URI。")
                properties?.optJSONObject("paths")?.put("description",
                    "当前应用有权读取的绝对图片路径、file URI 或已授权的 content URI；最多 3 张。")
            }
            "press_key" -> properties?.getJSONObject("button")?.let { button ->
                val values = button.getJSONArray("enum")
                button.put("enum", JSONArray().also { allowed ->
                    for (i in 0 until values.length()) {
                        val value = values.getString(i)
                        if (!value.equals("PASTE", ignoreCase = true)) allowed.put(value)
                    }
                })
                button.put("description", "无障碍支持的系统按键；粘贴文本请使用 paste_text。")
            }
            "search_personal_orders" -> function.put("description",
                "从用户已授权保存的通知历史检索外卖、购物、快递、票券和出行订单。")
        }
    }
}
