package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.repository.CharacterRepository
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 偏好类工具族：语音服务配置（读/写，不含密钥明文）、角色卡查询、数据健康自检。
 * 语义对齐 Operit 的 get/set_speech_services_config 与数据自检面板。
 */
internal class AgentPreferenceToolSet(
    private val context: Context,
    private val logger: AgentLogger,
) {
    fun execute(name: String, args: JSONObject): String? = when (name) {
        "speech_config_get" -> speechConfigGet()
        "speech_config_set" -> speechConfigSet(args)
        "character_list" -> characterList(args)
        "character_get" -> characterGet(args)
        "data_health" -> dataHealth()
        "mcp_servers_list" -> mcpServersList()
        "mcp_server_refresh" -> mcpServerRefresh(args)
        else -> null
    }

    /** 列出已配置的 MCP 客户端服务器与工具发现状态（不返回密钥）。 */
    private fun mcpServersList(): String = runCatching {
        val servers = runBlocking { io.github.mangi.eta.data.repository.McpServerRepository.servers() }
        val array = JSONArray()
        servers.forEach { server ->
            array.put(
                JSONObject()
                    .put("id", server.id)
                    .put("name", server.name)
                    .put("url_kind", if (server.url.startsWith("stdio://")) "stdio" else "http")
                    .put("enabled", server.enabled)
                    .put("discovered_tools", server.tools.size)
                    .put("enabled_tools", server.enabledToolNames.size)
                    .put("enabled_tool_names", JSONArray(server.enabledToolNames.toList()))
                    .put("last_refreshed_at", server.lastRefreshedAt ?: 0L)
                    .put("last_protocol_version", server.lastProtocolVersion ?: ""),
            )
        }
        JSONObject().put("ok", true).put("count", array.length()).put("servers", array).toString()
    }.getOrElse { error("MCP_LIST_FAILED", it.message ?: "读取 MCP 服务器失败") }

    /** 重新发现某台 MCP 服务器的工具（按 id 或名称）；失败时返回具体错误便于排查。 */
    private fun mcpServerRefresh(args: JSONObject): String = runCatching {
        val id = args.optString("id").trim()
        val name = args.optString("name").trim()
        if (id.isEmpty() && name.isEmpty()) return@runCatching error("INVALID_ARGUMENT", "需要 id 或 name")
        val servers = runBlocking { io.github.mangi.eta.data.repository.McpServerRepository.servers() }
        val target = servers.firstOrNull { it.id == id } ?: servers.firstOrNull { it.name == name }
            ?: return@runCatching error("MCP_SERVER_NOT_FOUND", "未找到该 MCP 服务器")
        val refreshed = runBlocking { io.github.mangi.eta.agent.mcp.McpServerManager.refresh(target.id) }
        JSONObject()
            .put("ok", true)
            .put("id", refreshed.id)
            .put("name", refreshed.name)
            .put("discovered_tools", refreshed.tools.size)
            .put("tool_names", JSONArray(refreshed.tools.map { it.name }))
            .put("protocol_version", refreshed.lastProtocolVersion ?: "")
            .toString()
    }.getOrElse { error("MCP_REFRESH_FAILED", it.message ?: "MCP 工具发现失败") }

    private fun speechConfigGet(): String = runCatching {
        val settings = runBlocking { SpeechSettingsRepository.settings() }
        val credentials = runCatching { runBlocking { SpeechSettingsRepository.credentials(context) } }.getOrNull()
        val credentialFlags = JSONObject()
            .put("qwen_asr", credentials?.qwenAsr?.isNotBlank() == true)
            .put("qwen_tts", credentials?.qwenTts?.isNotBlank() == true)
            .put("doubao_asr", credentials?.doubaoAsr?.isNotBlank() == true)
            .put("doubao_tts", credentials?.doubaoTts?.isNotBlank() == true)
        JSONObject()
            .put("ok", true)
            .put("asr", settings.asr.name)
            .put("tts", settings.tts.name)
            .put("auto_speak", settings.autoSpeak)
            .put("language", settings.language)
            .put("qwen_voice", settings.qwenVoice)
            .put("doubao_voice", settings.doubaoVoice)
            .put("credentials_configured", credentialFlags)
            .put("note", "密钥值不会返回；如需修改请在设置页或语音页填写。")
            .toString()
    }.getOrElse { error("SPEECH_CONFIG_FAILED", it.message ?: "读取语音配置失败") }

    private fun speechConfigSet(args: JSONObject): String = runCatching {
        val current = runBlocking { SpeechSettingsRepository.settings() }
        var updated: SpeechSettings = current
        args.optString("asr").trim().takeIf { it.isNotEmpty() }?.let { value ->
            runCatching { io.github.mangi.eta.data.model.AsrProvider.valueOf(value.uppercase()) }
                .getOrNull()?.let { updated = updated.copy(asr = it) }
                ?: return@runCatching error("INVALID_ARGUMENT", "未知 asr：$value")
        }
        args.optString("tts").trim().takeIf { it.isNotEmpty() }?.let { value ->
            runCatching { io.github.mangi.eta.data.model.TtsProvider.valueOf(value.uppercase()) }
                .getOrNull()?.let { updated = updated.copy(tts = it) }
                ?: return@runCatching error("INVALID_ARGUMENT", "未知 tts：$value")
        }
        if (args.has("auto_speak")) updated = updated.copy(autoSpeak = args.optBoolean("auto_speak"))
        args.optString("language").trim().takeIf { it.isNotEmpty() }?.let { updated = updated.copy(language = it) }
        args.optString("qwen_voice").trim().takeIf { it.isNotEmpty() }?.let { updated = updated.copy(qwenVoice = it) }
        args.optString("doubao_voice").trim().takeIf { it.isNotEmpty() }?.let { updated = updated.copy(doubaoVoice = it) }
        runBlocking { SpeechSettingsRepository.save(updated) }
        JSONObject()
            .put("ok", true)
            .put("asr", updated.asr.name)
            .put("tts", updated.tts.name)
            .put("auto_speak", updated.autoSpeak)
            .toString()
    }.getOrElse { error("SPEECH_CONFIG_FAILED", it.message ?: "写入语音配置失败") }

    private fun characterList(args: JSONObject): String = runCatching {
        val limit = args.optInt("limit", 20).coerceIn(1, 100)
        val profiles = runBlocking { CharacterRepository.list() }.take(limit)
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.card.name)
                    .put("has_avatar", profile.avatarPath != null)
                    .put("updated_at", profile.updatedAt),
            )
        }
        JSONObject().put("ok", true).put("count", array.length()).put("characters", array).toString()
    }.getOrElse { error("CHARACTER_LIST_FAILED", it.message ?: "读取角色列表失败") }

    private fun characterGet(args: JSONObject): String = runCatching {
        val id = args.optString("id").trim()
        val name = args.optString("name").trim()
        if (id.isEmpty() && name.isEmpty()) {
            return@runCatching error("INVALID_ARGUMENT", "需要 id 或 name")
        }
        val profiles = runBlocking { CharacterRepository.list() }
        val target = profiles.firstOrNull { it.id == id }
            ?: profiles.firstOrNull { it.card.name == name }
            ?: return@runCatching error("CHARACTER_NOT_FOUND", "未找到角色")
        JSONObject()
            .put("ok", true)
            .put("id", target.id)
            .put("name", target.card.name)
            .put("description", target.card.description.take(1000))
            .put("has_avatar", target.avatarPath != null)
            .put("updated_at", target.updatedAt)
            .toString()
    }.getOrElse { error("CHARACTER_GET_FAILED", it.message ?: "读取角色失败") }

    /** 数据健康自检：库表计数、PRAGMA quick_check、关键文件体积。只读，不修改任何数据。 */
    private fun dataHealth(): String = runCatching {
        val db = EtaDatabase.get(context)
        val database = db.openHelper.writableDatabase
        val integrity = runCatching {
            database.query("PRAGMA quick_check").use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else "unknown"
            }
        }.getOrElse { "check_failed: ${it.message}" }
        val counts = JSONObject()
        listOf(
            "conversations",
            "conversation_messages",
            "conversation_context_checkpoints",
            "mcp_servers",
        ).forEach { table ->
            runCatching {
                database.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    if (cursor.moveToFirst()) counts.put(table, cursor.getInt(0))
                }
            }
        }
        val files = JSONObject()
        listOf("eta.db", "eta.db-wal", "agent_usage.json", "tool_permissions.json", "rate_limit.json")
            .forEach { name ->
                runCatching {
                    val file = File(context.filesDir, name)
                    if (file.exists()) files.put(name, file.length())
                }
            }
        runCatching {
            val dbDir = context.getDatabasePath("eta.db").parentFile
            if (dbDir != null) {
                val size = dbDir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
                files.put("databases_total", size)
            }
        }
        JSONObject()
            .put("ok", true)
            .put("integrity", integrity)
            .put("healthy", integrity == "ok")
            .put("row_counts", counts)
            .put("file_bytes", files)
            .put("note", "只读自检；不含修复动作。")
            .toString()
    }.getOrElse { error("DATA_HEALTH_FAILED", it.message ?: "数据自检失败") }

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message.take(300)).toString()

    companion object {
        val TOOL_NAMES: Set<String> = setOf(
            "speech_config_get",
            "speech_config_set",
            "character_list",
            "character_get",
            "data_health",
            "mcp_servers_list",
            "mcp_server_refresh",
        )
    }
}
