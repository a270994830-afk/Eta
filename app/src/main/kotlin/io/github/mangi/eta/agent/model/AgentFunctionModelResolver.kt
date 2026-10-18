package io.github.mangi.eta.agent.model

import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.ModelRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import org.json.JSONObject

/**
 * 按功能分配模型（对齐 Operit 的 FunctionType 槽位）：摘要压缩、回复改写等非主对话请求
 * 可以走更便宜的模型，主对话仍用主模型。
 *
 * 配置存本地 Agent 配置 `agent_function_model_map`：
 * `{"COMPACTION":"<providerId>/<modelId>","REPLY_REWRITE":"<providerId>/<modelId>"}`
 */
internal object AgentFunctionModelResolver {
    const val KEY = "agent_function_model_map"

    const val PURPOSE_CHAT = "CHAT"
    const val PURPOSE_COMPACTION = "COMPACTION"
    const val PURPOSE_REPLY_REWRITE = "REPLY_REWRITE"
    const val PURPOSE_EMBEDDING = "EMBEDDING"
    const val PURPOSE_VISION = "VISION"

    /**
     * 视觉/识图默认候选链（按优先级）：WorkBuddy glm-5.3（首选，识图与 qwen-3.7-plus 打平）、
     * Trae qwen-3.7-plus（备选）。用户未在 agent_function_model_map 显式配置 VISION 时使用。
     */
    private val DEFAULT_VISION_CHAIN: List<String> = listOf(
        "builtin-workbuddy/cn:glm-5.3",
        "builtin-trae/qwen-3.7-plus",
    )

    fun slots(): JSONObject = runCatching {
        JSONObject(Prefs.localAgentPreferences()?.getString(KEY, "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    /**
     * 解析某个功能槽位（不依赖主模型）；未配置或无效时返回 null。
     * 用于 embedding 这类与主对话模型完全无关的功能。
     */
    fun resolveSlot(purpose: String): AgentModelClient.ModelConfig? {
        val raw = slots().optString(purpose, "").trim()
        if (raw.isEmpty() || '/' !in raw) return null
        val providerId = raw.substringBefore('/').trim()
        val modelId = raw.substringAfter('/', "").trim()
        if (providerId.isEmpty() || modelId.isEmpty()) return null
        return resolveSlotSpec("$providerId/$modelId")
    }

    /**
     * 视觉/识图候选链：显式配置的 VISION 槽位优先；否则按内置默认链依次尝试
     * （WorkBuddy glm-5.3 → Trae qwen-3.7-plus）。返回有序的可解析配置，可逐个调用直至成功。
     */
    fun resolveVisionCandidates(): List<AgentModelClient.ModelConfig> {
        val configured = slots().optString(PURPOSE_VISION, "").trim()
        val chain = if (configured.isNotEmpty() && '/' in configured) {
            listOf(configured)
        } else {
            DEFAULT_VISION_CHAIN
        }
        return chain.mapNotNull(this::resolveSlotSpec)
    }

    private fun resolveSlotSpec(spec: String): AgentModelClient.ModelConfig? {
        if (spec.isEmpty() || '/' !in spec) return null
        val providerId = spec.substringBefore('/').trim()
        val modelId = spec.substringAfter('/', "").trim()
        if (providerId.isEmpty() || modelId.isEmpty()) return null
        return runCatching {
            kotlinx.coroutines.runBlocking {
                val provider = ProviderRepository.providerById(providerId) ?: return@runBlocking null
                val model = ModelRepository.modelsByProvider(providerId)
                    .firstOrNull { it.id == modelId || it.modelId == modelId } ?: return@runBlocking null
                RuntimeConfigRepository.buildRuntimeConfig(provider, model)
            }
        }.getOrNull()
    }

    /** 解析该功能槽位对应的模型配置；未配置或解析失败时返回 [base]。 */
    fun resolve(base: AgentModelClient.ModelConfig, purpose: String): AgentModelClient.ModelConfig {
        val raw = slots().optString(purpose, "").trim()
        if (raw.isEmpty() || '/' !in raw) return base
        val providerId = raw.substringBefore('/').trim()
        val modelId = raw.substringAfter('/', "").trim()
        if (providerId.isEmpty() || modelId.isEmpty()) return base
        if (providerId == base.providerId && modelId == base.model) return base
        val resolved = runCatching {
            kotlinx.coroutines.runBlocking {
                val provider = ProviderRepository.providerById(providerId) ?: return@runBlocking null
                val model = ModelRepository.modelsByProvider(providerId)
                    .firstOrNull { it.id == modelId || it.modelId == modelId } ?: return@runBlocking null
                RuntimeConfigRepository.buildRuntimeConfig(provider, model)
            }
        }.getOrNull() ?: return base
        return resolved
    }
}
