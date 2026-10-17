package io.github.mangi.eta.agent.model

import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.ModelRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository

/**
 * R3 主备模型：主源“永久失败”（配额/鉴权/模型不存在/服务端不可用）时切备用（对齐 Operit 的 ModelFailoverRegistry 语义）。
 * 备用凭 id 配置在本地 Agent preferences：agent_fallback_provider_id / agent_fallback_model_id。
 */
internal object AgentModelFallback {
    private const val KEY_PROVIDER = "agent_fallback_provider_id"
    private const val KEY_MODEL = "agent_fallback_model_id"

    fun configuredIds(): Pair<String, String>? {
        val p = Prefs.localAgentPreferences() ?: return null
        val pid = runCatching { p.getString(KEY_PROVIDER, "") }.getOrNull().orEmpty().trim()
        val mid = runCatching { p.getString(KEY_MODEL, "") }.getOrNull().orEmpty().trim()
        return if (pid.isNotEmpty() && mid.isNotEmpty()) pid to mid else null
    }

    /** 哪些失败值得切备用；限流/网络瞬时类交给原有重试。 */
    fun shouldSwitch(failure: AgentModelFailure): Boolean {
        if (failure.retryable) return false
        val code = failure.code.uppercase()
        return listOf(
            "QUOTA", "AUTH", "PERMISSION", "NOT_FOUND", "UNAVAILABLE", "SERVER", "BILLING", "BALANCE",
        ).any { code.contains(it) }
    }

    /** 解析备用模型配置；不可用/与主同源时返回 null。 */
    suspend fun resolve(currentProviderId: String): AgentModelClient.ModelConfig? {
        val (pid, mid) = configuredIds() ?: return null
        if (pid == currentProviderId) return null
        val provider = ProviderRepository.providerById(pid) ?: return null
        val model = ModelRepository.modelsByProvider(pid).firstOrNull { it.id == mid || it.modelId == mid } ?: return null
        return RuntimeConfigRepository.buildRuntimeConfig(provider, model)
    }
}
