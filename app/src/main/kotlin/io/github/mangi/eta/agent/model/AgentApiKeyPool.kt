package io.github.mangi.eta.agent.model

import io.github.mangi.eta.config.Prefs
import org.json.JSONObject

/**
 * 多 API Key 池：同一个 Provider 可配置多把 Key，按请求轮询（ROUND_ROBIN）使用，
 * 缓解单 Key 限流。配置存本地 Agent 配置：
 *
 * `agent_api_key_pool` = {"<providerId>":["key1","key2"]}
 * `agent_api_key_pool_index` = {"<providerId>":2}（轮询游标，落盘以便重启后继续）
 */
internal object AgentApiKeyPool {
    const val KEY_POOL = "agent_api_key_pool"
    const val KEY_INDEX = "agent_api_key_pool_index"

    private fun prefs() = Prefs.localAgentPreferences()

    fun keysFor(providerId: String): List<String> {
        if (providerId.isBlank()) return emptyList()
        val p = prefs() ?: return emptyList()
        return runCatching {
            val root = JSONObject(p.getString(KEY_POOL, "{}") ?: "{}")
            val array = root.optJSONArray(providerId) ?: return emptyList()
            (0 until array.length())
                .mapNotNull { index -> array.optString(index, "").trim().ifBlank { null } }
        }.getOrDefault(emptyList())
    }

    /** 把配置里的 apiKey 换成池里轮询到的那把；没有池或池为空时原样返回。 */
    fun apply(config: AgentModelClient.ModelConfig): AgentModelClient.ModelConfig {
        val keys = keysFor(config.providerId)
        if (keys.isEmpty()) return config
        val p = prefs() ?: return config
        val index = runCatching {
            val root = JSONObject(p.getString(KEY_INDEX, "{}") ?: "{}")
            val current = root.optInt(config.providerId, 0)
            root.put(config.providerId, (current + 1) % keys.size)
            p.edit().putString(KEY_INDEX, root.toString()).apply()
            current % keys.size
        }.getOrDefault(0)
        return config.copy(apiKey = keys[index])
    }

    /** 供设置页/工具展示的概要（不返回 Key 明文）。 */
    fun summary(): JSONObject {
        val root = JSONObject()
        val p = prefs() ?: return root
        runCatching {
            val pool = JSONObject(p.getString(KEY_POOL, "{}") ?: "{}")
            pool.keys().forEach { providerId ->
                val count = pool.optJSONArray(providerId)?.length() ?: 0
                root.put(providerId, count)
            }
        }
        return root
    }
}
