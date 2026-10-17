package io.github.mangi.eta.config

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

/**
 * 模块配置中枢。
 *
 * - Hook 进程（system_server / SystemUI / Google / 系统助手等）在模块加载时调用
 *   [attachRemote]，缓存框架提供的只读 [SharedPreferences]；之后所有拦截回调用 [isEnabled]
 *   读取当前进程持有的 remote preferences。
 * - Eta Runtime 自己消费的开关保存在 App 私有配置中，不依赖 Xposed Service。
 * - Hook 消费的开关通过 [remotePreferencesForUi] 写入 RemotePreferences；
 *   XposedService 未就绪时不提供本地假 fallback。
 *
 * 基于 libxposed API 102 的 [io.github.libxposed.api.XposedInterface.getRemotePreferences]
 * 与 service 102 的 [XposedService.getRemotePreferences]，两端共用同一 group。
 */
internal object Prefs {

    /** 远程配置组名，UI 写入与 Hook 读取必须一致。 */
    const val GROUP = "eta_prefs"

    private const val LOCAL_AGENT_GROUP = "eta_agent_preferences"

    /** 所有功能开关 key。默认值按功能风险独立定义。 */
    object Keys {
        const val POWER_KEY_ASSISTANT_TARGET = "power_key_assistant_target"
        // 兼容旧版布尔协议；新 UI 不再写入，缺少三态配置时 true 仍表示 Gemini。
        const val POWER_KEY_TAKEOVER = "power_key_takeover"
        const val ASSISTANT_AUTO_CONFIG = "assistant_auto_config"
        const val HOTWORD_SELF_HEAL = "hotword_self_heal"
        const val GESTURE_BAR_CIRCLE_TO_SEARCH = "gesture_bar_circle_to_search"
        const val DOUBLE_FINGER_CIRCLE_TO_SEARCH = "double_finger_circle_to_search"
        const val LOCKSCREEN_VOICE_COMMAND = "lockscreen_voice_command"
        const val SCREEN_ON_VOICE_COMMAND = "screen_on_voice_command"
        const val AGENT_CUSTOM_MODEL = "agent_custom_model"
        const val AGENT_REQUIRE_PREFIX = "agent_require_prefix"
        const val AGENT_TERMINAL_TOOLS = "agent_terminal_tools"
        const val AGENT_BROWSER_TOOLS = "agent_browser_tools"
        const val AGENT_DEVICE_DIRECT_TOOLS = "agent_device_direct_tools"
        const val AGENT_DEVICE_SENSITIVE_READ_TOOLS = "agent_device_sensitive_read_tools"
        const val AGENT_DEVICE_SENSITIVE_ACTION_TOOLS = "agent_device_sensitive_action_tools"
        const val AGENT_SHADOW_TOOLS = "agent_shadow_tools"
        const val AGENT_THINKING_ENABLED = "agent_thinking_enabled"
        const val AGENT_RUNTIME_CONFIG_JSON = "agent_runtime_config_json"
        const val ETA_HOTWORD_ENABLED = "eta_hotword_enabled"
        const val ETA_HOTWORD_KEYWORD = "eta_hotword_keyword"
        const val ETA_VOICE_CONTINUOUS = "eta_voice_continuous"
        const val ETA_TTS_ENABLED = "eta_tts_enabled"
        const val ETA_REMINDER_KAIGONG = "eta_reminder_kaigong"
        const val ETA_REMINDER_WINDOW = "eta_reminder_window"
        // R3 护栏：每日 Token 限额（0=不限）与主备模型（故障自动切换）
        const val AGENT_DAILY_TOKEN_LIMIT = "agent_daily_token_limit"
        const val AGENT_FALLBACK_PROVIDER_ID = "agent_fallback_provider_id"
        const val AGENT_FALLBACK_MODEL_ID = "agent_fallback_model_id"

        /** 每 Provider 每分钟请求上限（0=不限）。 */
        const val AGENT_RATE_LIMIT_PER_MINUTE = "agent_rate_limit_per_minute"

        /** 工具精简模式（CLI）：只向模型下发核心工具 + search_tools，其余按需检索。 */
        const val AGENT_TOOL_CLI_MODE = "agent_tool_cli_mode"

        /** 按功能分配模型：{"SUMMARY":"providerId/modelId", ...}。 */
        const val AGENT_FUNCTION_MODEL_MAP = "agent_function_model_map"

        // 外部聊天通道（HTTP + A2A，仅回环）
        const val EXTERNAL_CHAT_ENABLED = "external_chat_enabled"
        const val EXTERNAL_CHAT_PORT = "external_chat_port"
        const val EXTERNAL_CHAT_TOKEN = "external_chat_token"

        // MCP 服务器：把 Eta 工具集通过 MCP Streamable HTTP 对外开放（默认关闭）
        const val MCP_SERVER_ENABLED = "mcp_server_enabled"
        /** 显式要求把 MCP 工具直接塞进本轮 prompt（默认关闭 = 延迟加载，靠 search_tools 检索）。 */
        const val AGENT_MCP_TOOLS_EAGER = "agent_mcp_tools_eager"
        const val MCP_SERVER_PORT = "mcp_server_port"
        const val MCP_SERVER_TOKEN = "mcp_server_token"
        const val MCP_SERVER_ALLOW_SENSITIVE = "mcp_server_allow_sensitive"
        const val MCP_CONFIRM_TRADES = "mcp_confirm_trades"
        const val MCP_SERVER_PORT_DEFAULT = 18181
        const val MCP_SERVER_TOKEN_DEFAULT = ""

        /** 全部布尔开关及其默认值。 */
        val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
            POWER_KEY_TAKEOVER to false,
            ASSISTANT_AUTO_CONFIG to false,
            HOTWORD_SELF_HEAL to false,
            GESTURE_BAR_CIRCLE_TO_SEARCH to true,
            DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
            LOCKSCREEN_VOICE_COMMAND to false,
            SCREEN_ON_VOICE_COMMAND to false,
            AGENT_CUSTOM_MODEL to true,
            AGENT_REQUIRE_PREFIX to false,
            AGENT_TERMINAL_TOOLS to true,
            AGENT_BROWSER_TOOLS to true,
            AGENT_DEVICE_DIRECT_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
            AGENT_SHADOW_TOOLS to false,
            AGENT_THINKING_ENABLED to true,
            ETA_HOTWORD_ENABLED to false,
            ETA_VOICE_CONTINUOUS to false,
            ETA_TTS_ENABLED to false,
            ETA_REMINDER_KAIGONG to false,
            ETA_REMINDER_WINDOW to false,
            MCP_SERVER_ENABLED to false,
            MCP_SERVER_ALLOW_SENSITIVE to false,
            MCP_CONFIRM_TRADES to true,
            AGENT_TOOL_CLI_MODE to false,
            AGENT_MCP_TOOLS_EAGER to false,
            EXTERNAL_CHAT_ENABLED to false,
        )

        /** 由 Eta Runtime 最终裁决、不要求 Xposed 框架在线的开关。 */
        val LOCAL_AGENT_KEYS: Set<String> = setOf(
            AGENT_TERMINAL_TOOLS,
            AGENT_BROWSER_TOOLS,
            AGENT_DEVICE_DIRECT_TOOLS,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
            AGENT_SHADOW_TOOLS,
            AGENT_THINKING_ENABLED,
            ETA_HOTWORD_ENABLED,
            ETA_VOICE_CONTINUOUS,
            ETA_TTS_ENABLED,
            ETA_REMINDER_KAIGONG,
            ETA_REMINDER_WINDOW,
            MCP_SERVER_ENABLED,
            MCP_SERVER_ALLOW_SENSITIVE,
            MCP_CONFIRM_TRADES,
            EXTERNAL_CHAT_ENABLED,
            AGENT_TOOL_CLI_MODE,
            AGENT_MCP_TOOLS_EAGER,
        )
    }

    /** Hook 进程缓存的只读 remote preferences，由 ModuleMain 在 onModuleLoaded 注入。 */
    @Volatile
    private var remote: SharedPreferences? = null

    @Volatile
    private var localAgent: SharedPreferences? = null

    /** App 进程调用：初始化不依赖 Xposed Service 的 Agent 配置。 */
    fun initLocal(context: Context) {
        if (localAgent == null) {
            synchronized(this) {
                if (localAgent == null) {
                    localAgent = context.applicationContext.getSharedPreferences(
                        LOCAL_AGENT_GROUP,
                        Context.MODE_PRIVATE,
                    )
                    // 清理旧版本误写成 Boolean 的 MCP key（曾导致服务启动时 ClassCastException 闪退）
                    purgeLegacyMcpKeys()
                }
            }
        }
    }

    /**
     * 删除旧版本误写到 SharedPreferences 的坏类型 MCP key（历史原因：MCP_SERVER_PORT /
     * MCP_SERVER_TOKEN 曾被当成 Boolean 写入，读取时抛 ClassCastException）。幂等。
     */
    private fun purgeLegacyMcpKeys() {
        val local = localAgent ?: return
        val polluted = setOf(Keys.MCP_SERVER_PORT, Keys.MCP_SERVER_TOKEN)
        val editor = local.edit()
        var changed = false
        for (key in polluted) {
            if (local.contains(key)) {
                val currentType = runCatching { local.all[key]?.javaClass?.simpleName }.getOrNull()
                if (currentType != null && currentType != "String") {
                    editor.remove(key)
                    changed = true
                }
            }
        }
        if (changed) editor.apply()
    }

    /** Hook 进程调用：缓存框架提供的只读 SharedPreferences。 */
    fun attachRemote(prefs: SharedPreferences?) {
        remote = prefs
    }

    /** Hook 进程监听框架下发的配置变化；listener 必须由调用方在进程生命周期内强引用。 */
    fun registerRemoteListener(listener: SharedPreferences.OnSharedPreferenceChangeListener): Boolean {
        val preferences = remote ?: return false
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return true
    }

    /**
     * 读取布尔开关。remote 不可用（框架未注入或调用失败）时回退各功能自己的默认值；
     * 默认值与设置页展示保持一致。
     */
    fun isEnabled(key: String): Boolean {
        val default = Keys.BOOLEAN_DEFAULTS[key] ?: true
        val preferences = if (key in Keys.LOCAL_AGENT_KEYS) localAgent ?: remote else remote
        return preferences?.getBoolean(key, default) ?: default
    }

    fun getString(key: String): String {
        return remote?.getString(key, "") ?: ""
    }

    /** 读取 Runtime 本地整数配置；缺省或非法时返回 [fallback]。 */
    fun getLocalAgentInt(key: String, fallback: Int): Int {
        val raw = localAgent?.getStringCompat(key, null) ?: remote?.getStringCompat(key, null)
        return raw?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: fallback
    }

    /** 读取 Runtime 本地长整数配置；缺省或非法时返回 [fallback]。 */
    fun getLocalAgentLong(key: String, fallback: Long): Long {
        val raw = localAgent?.getStringCompat(key, null) ?: remote?.getStringCompat(key, null)
        return raw?.trim()?.toLongOrNull()?.takeIf { it >= 0L } ?: fallback
    }

    /** 读取 Runtime 本地字符串配置；本地缺失时回退远端，再缺省时返回 [fallback]。 */
    fun getLocalString(key: String, fallback: String = ""): String =
        localAgent?.getStringCompat(key, null)
            ?: remote?.getStringCompat(key, null)
            ?: fallback

    /** 通用写本地 Agent 配置（设置页与 MCP/Mem0 配置 UI 走这条路径）。 */
    fun putLocal(key: String, value: Boolean) {
        val local = localAgent ?: return
        runCatching { local.edit().putBoolean(key, value).apply() }
    }

    fun putLocal(key: String, value: Int) {
        val local = localAgent ?: return
        runCatching { local.edit().putInt(key, value).apply() }
    }

    fun putLocal(key: String, value: String) {
        val local = localAgent ?: return
        runCatching { local.edit().putString(key, value).apply() }
    }

    fun powerAssistantTarget(): PowerAssistantTarget = powerAssistantTarget(remote)

    fun powerAssistantTarget(preferences: SharedPreferences?): PowerAssistantTarget {
        val persistedValue = runCatching {
            preferences?.getString(Keys.POWER_KEY_ASSISTANT_TARGET, null)
        }.getOrNull()
        val legacyDefault = Keys.BOOLEAN_DEFAULTS.getValue(Keys.POWER_KEY_TAKEOVER)
        val legacyTakeover = runCatching {
            preferences?.getBoolean(Keys.POWER_KEY_TAKEOVER, legacyDefault)
        }.getOrNull() ?: legacyDefault
        return PowerAssistantTarget.resolve(persistedValue, legacyTakeover)
    }

    /**
     * UI 进程获取可写的 RemotePreferences。
     *
     * [XposedService.getRemotePreferences] 的 commit 会同步等待 binder 提交到 LSPosed
     * 数据库，失败返回 false；service 未就绪时返回 null，让 UI 保持不可写。
     */
    fun remotePreferencesForUi(service: XposedService?): SharedPreferences? =
        runCatching { service?.getRemotePreferences(GROUP) }.getOrNull()

    /** Eta 设置页与 Runtime 使用的本地 Agent 配置，不依赖 LSPosed。 */
    fun localAgentPreferences(): SharedPreferences? = localAgent

    /**
     * 首次升级优先把已有 RemotePreferences 值迁入本地；之后本地值是事实源，并在框架
     * 可用时回写远端，让仍在目标进程中组装请求的 Hook 入口拿到一致的初始配置。
     */
    fun reconcileAgentPreferences(service: XposedService?) {
        val local = localAgent ?: return
        val remotePreferences = remotePreferencesForUi(service) ?: return
        val localEditor = local.edit()
        val remoteEditor = remotePreferences.edit()
        var updateLocal = false
        var updateRemote = false

        Keys.LOCAL_AGENT_KEYS.forEach { key ->
            val default = Keys.BOOLEAN_DEFAULTS.getValue(key)
            when {
                local.contains(key) -> {
                    remoteEditor.putBoolean(key, local.getBoolean(key, default))
                    updateRemote = true
                }
                remotePreferences.contains(key) -> {
                    localEditor.putBoolean(key, remotePreferences.getBoolean(key, default))
                    updateLocal = true
                }
            }
        }
        if (updateLocal) localEditor.commit()
        if (updateRemote) runCatching { remoteEditor.commit() }
    }
}

/**
 * 容错读取字符串：旧版本可能把 key 写成非 String 类型，直接 getString 会抛
 * ClassCastException；这里捕获异常、后台删除污染 key 并返回缺省值。
 */
private fun SharedPreferences.getStringCompat(key: String, defaultValue: String?): String? = try {
    getString(key, defaultValue)
} catch (e: ClassCastException) {
    runCatching { edit().remove(key).apply() }
    defaultValue
}
