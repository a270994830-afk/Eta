package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.model.ProviderRequestHeaders
import io.github.mangi.eta.agent.model.ProviderUrls
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.runtimeProviderType
import io.github.mangi.eta.data.repository.ProviderRepository
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 管理类工具：模型配置、用量、工具权限、限流状态。
 *
 * 只读/写 App 私有文件与 Room 配置，不做 UI 弹窗；任何未预期异常都被吞成错误 JSON。
 */
internal class AgentAdminToolSet(
    private val context: Context,
    private val logger: AgentLogger,
) {
    fun execute(name: String, args: JSONObject): String? {
        if (name !in TOOL_NAMES) return null
        return runCatching {
            when (name) {
                "model_config_list" -> modelConfigList()
                "model_config_test" -> modelConfigTest(args)
                "usage_stats" -> usageStats(args)
                "tool_permission_list" -> toolPermissionList()
                "tool_permission_set" -> toolPermissionSet(args)
                "rate_limit_status" -> rateLimitStatus()
                else -> null
            }
        }.getOrElse { throwable ->
            logger.warn("AgentAdminToolSet $name failed: ${throwable.javaClass.simpleName}: ${throwable.message}")
            error("TOOL_INTERNAL_ERROR", throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    private fun modelConfigList(): String {
        val providers = runBlocking { ProviderRepository.allProviders() }
        val selection = runBlocking { SettingsDataStore.settings() }
        val providerArray = JSONArray()
        providers.forEach { provider ->
            val modelArray = JSONArray()
            provider.models.forEach { model ->
                modelArray.put(
                    JSONObject()
                        .put("id", model.id)
                        .put("model_id", model.modelId)
                        .put("display_name", model.displayName)
                        .put("context_window", model.effectiveContextWindow ?: JSONObject.NULL)
                        .put("enabled", model.isEnabled)
                        .put(
                            "is_current",
                            provider.id == selection.selectedProviderId && model.id == selection.selectedModelId,
                        ),
                )
            }
            providerArray.put(
                JSONObject()
                    .put("id", provider.id)
                    .put("name", provider.name)
                    .put("type", provider.runtimeProviderType)
                    .put("base_url", provider.baseUrl)
                    .put("has_api_key", provider.apiKey.isNotBlank())
                    .put("enabled", provider.isEnabled)
                    .put("is_built_in", provider.isBuiltIn)
                    .put("is_current", provider.id == selection.selectedProviderId)
                    .put("models", modelArray),
            )
        }
        return ok(
            JSONObject()
                .put("providers", providerArray)
                .put("provider_count", providerArray.length())
                .put("current_provider_id", selection.selectedProviderId ?: JSONObject.NULL)
                .put("current_model_id", selection.selectedModelId ?: JSONObject.NULL),
        )
    }

    private fun modelConfigTest(args: JSONObject): String {
        val providerId = args.optString("provider_id").trim()
        val modelArg = args.optString("model_id").trim().ifBlank { args.optString("model").trim() }
        var baseUrl = args.optString("base_url").trim()
        var apiKey = args.optString("api_key").trim()
        var providerType = ProviderTypes.OPENAI_COMPATIBLE
        var endpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS
        var anthropicVersion = AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION
        var customHeaders: List<CustomHeader> = emptyList()
        var modelId = modelArg

        if (providerId.isNotBlank()) {
            val provider = runBlocking { ProviderRepository.providerById(providerId) }
                ?: return error("PROVIDER_NOT_FOUND", "未找到 Provider: $providerId")
            baseUrl = provider.baseUrl
            apiKey = provider.apiKey
            providerType = provider.runtimeProviderType
            customHeaders = provider.customHeaders
            when (provider) {
                is AnthropicProviderSetting -> anthropicVersion = provider.anthropicVersion
                is OpenAiCompatibleProviderSetting -> endpointMode = provider.endpointMode
                is CustomProviderSetting -> endpointMode = provider.endpointMode
            }
            val matched = provider.models.firstOrNull { it.id == modelArg || it.modelId == modelArg }
            modelId = matched?.modelId ?: modelArg.ifBlank { provider.models.firstOrNull()?.modelId.orEmpty() }
        }

        if (baseUrl.isBlank()) return error("INVALID_ARGUMENT", "缺少 base_url 或 provider_id")
        val request = buildTestRequest(
            providerType = providerType,
            endpointMode = endpointMode,
            baseUrl = baseUrl,
            apiKey = apiKey,
            anthropicVersion = anthropicVersion,
            customHeaders = customHeaders,
            modelId = modelId,
        ) ?: return error("INVALID_ARGUMENT", "缺少可测试的模型名，请提供 model_id/model 或 provider_id")

        return runBlocking(Dispatchers.IO) {
            runCatching {
                val client = AgentHttpClient.client.newBuilder()
                    .callTimeout(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (response.isSuccessful) {
                        ok(
                            JSONObject()
                                .put("status_code", response.code)
                                .put("endpoint", request.url.toString())
                                .put("message", "连接成功"),
                        )
                    } else {
                        JSONObject()
                            .put("ok", false)
                            .put("code", "MODEL_TEST_HTTP_ERROR")
                            .put("status_code", response.code)
                            .put("endpoint", request.url.toString())
                            .put("message", "HTTP ${response.code}: ${body.compactJson()}")
                            .toString()
                    }
                }
            }.getOrElse { throwable ->
                error("MODEL_TEST_FAILED", throwable.message ?: throwable.javaClass.simpleName)
            }
        }
    }

    private fun buildTestRequest(
        providerType: String,
        endpointMode: String,
        baseUrl: String,
        apiKey: String,
        anthropicVersion: String,
        customHeaders: List<CustomHeader>,
        modelId: String,
    ): Request? {
        if (providerType == ProviderTypes.ANTHROPIC) {
            val headers = Headers.Builder()
                .add("Accept", "application/json")
                .add("anthropic-version", anthropicVersion)
                .apply {
                    if (apiKey.isNotBlank()) add("x-api-key", apiKey)
                    ProviderRequestHeaders.mergeInto(this, baseUrl, customHeaders)
                }
                .build()
            return Request.Builder()
                .url(ProviderUrls.anthropicModelsUrl(baseUrl))
                .headers(headers)
                .get()
                .build()
        }

        val headers = Headers.Builder()
            .add("Accept", "application/json")
            .apply {
                if (apiKey.isNotBlank()) add("Authorization", "Bearer $apiKey")
                ProviderRequestHeaders.mergeInto(this, baseUrl, customHeaders)
            }
            .build()

        if (endpointMode == OpenAiEndpointMode.RESPONSES || modelId.isBlank()) {
            return Request.Builder()
                .url(ProviderUrls.openAiModelsUrl(baseUrl))
                .headers(headers)
                .get()
                .build()
        }

        val payload = JSONObject()
            .put("model", modelId)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
            .put("max_tokens", 1)
            .put("stream", false)
        return Request.Builder()
            .url(ProviderUrls.openAiChatCompletionsUrl(baseUrl))
            .headers(headers)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun usageStats(args: JSONObject): String {
        val requestedDays = args.optInt("days", 7).coerceIn(1, 90)
        val root = readJsonFile(USAGE_FILE) ?: JSONObject()
        val allDays = root.optJSONObject("days") ?: JSONObject()
        val recent = JSONObject()
        var prompt = 0L
        var completion = 0L
        var requests = 0L
        allDays.keys().asSequence().sortedDescending().take(requestedDays).forEach { key ->
            val day = allDays.optJSONObject(key) ?: return@forEach
            recent.put(key, day)
            prompt += day.optLong("prompt_tokens")
            completion += day.optLong("completion_tokens")
            requests += day.optLong("requests")
        }
        return ok(
            JSONObject()
                .put("days", recent)
                .put("days_requested", requestedDays)
                .put(
                    "totals",
                    JSONObject()
                        .put("prompt_tokens", prompt)
                        .put("completion_tokens", completion)
                        .put("total_tokens", prompt + completion)
                        .put("requests", requests),
                )
                .put("month", root.optJSONObject("month") ?: JSONObject()),
        )
    }

    private fun toolPermissionList(): String {
        val root = readJsonFile(TOOL_PERMISSIONS_FILE) ?: JSONObject()
        return ok(
            JSONObject()
                .put("default", root.optString("default").takeIf { it.isNotBlank() } ?: DEFAULT_POLICY)
                .put("tools", root.optJSONObject("tools") ?: JSONObject())
                .put("temporary", AgentToolPermissionStore.temporarySnapshot()),
        )
    }

    private fun toolPermissionSet(args: JSONObject): String {
        val policy = args.optString("policy").trim().lowercase()
        if (policy !in POLICIES) return error("INVALID_ARGUMENT", "policy 必须是 allow/ask/forbid")
        val toolName = args.optString("tool_name").trim()
        val root = readJsonFile(TOOL_PERMISSIONS_FILE) ?: JSONObject()
        if (toolName.isBlank()) {
            root.put("default", policy)
        } else {
            val tools = root.optJSONObject("tools") ?: JSONObject().also { root.put("tools", it) }
            tools.put(toolName, policy)
        }
        if (!writeJsonFileAtomic(TOOL_PERMISSIONS_FILE, root)) {
            return error("WRITE_FAILED", "工具权限文件写入失败")
        }
        return ok(
            JSONObject()
                .put("target", if (toolName.isBlank()) "default" else toolName)
                .put("policy", policy)
                .put("default", root.optString("default").takeIf { it.isNotBlank() } ?: DEFAULT_POLICY)
                .put("tools", root.optJSONObject("tools") ?: JSONObject()),
        )
    }

    private fun rateLimitStatus(): String {
        val root = readJsonFile(RATE_LIMIT_FILE) ?: JSONObject()
        val providers = root.optJSONObject("providers") ?: JSONObject()
        return ok(JSONObject().put("providers", providers).put("provider_count", providers.length()))
    }

    private fun readJsonFile(name: String): JSONObject? = runCatching {
        val file = File(context.filesDir, name)
        if (file.exists()) JSONObject(file.readText()) else null
    }.getOrNull()

    private fun writeJsonFileAtomic(name: String, value: JSONObject): Boolean = runCatching {
        val file = File(context.filesDir, name)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "$name.tmp")
        tmp.writeText(value.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(value.toString())
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    private fun ok(body: JSONObject): String = body.put("ok", true).toString()

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun String.compactJson(): String =
        replace('\n', ' ').replace('\r', ' ').let { if (it.length > MAX_ERROR_CHARS) it.take(MAX_ERROR_CHARS) + "..." else it }

    companion object {
        val TOOL_NAMES: List<String> = listOf(
            "model_config_list",
            "model_config_test",
            "usage_stats",
            "tool_permission_list",
            "tool_permission_set",
            "rate_limit_status",
        )

        private const val USAGE_FILE = "agent_usage.json"
        private const val TOOL_PERMISSIONS_FILE = "tool_permissions.json"
        private const val RATE_LIMIT_FILE = "rate_limit.json"
        private const val DEFAULT_POLICY = "allow"
        private const val TEST_TIMEOUT_SECONDS = 15L
        private const val MAX_ERROR_CHARS = 600
        private val POLICIES = setOf("allow", "ask", "forbid")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
