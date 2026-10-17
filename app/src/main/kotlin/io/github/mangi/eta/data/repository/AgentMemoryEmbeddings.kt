package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 记忆小节的向量索引：按内容哈希缓存并落盘（换模型即整体失效），
 * 每次只对缺失项补算且最多一批；任何失败都只是「这次没有语义召回」，不影响关键词检索。
 */
internal class AgentMemoryEmbeddingIndex(
    private val cacheFile: File,
    private val embed: (List<String>) -> List<FloatArray>?,
) {
    /**
     * 取 [texts] 的向量（key 由调用方决定，value 是待嵌入文本）。
     * [budget] 限制本次最多新建多少条（含查询本身），命中缓存的不计入。
     */
    fun vectors(modelKey: String, texts: Map<String, String>, budget: Int): Map<String, FloatArray> {
        if (texts.isEmpty()) return emptyMap()
        val cached = synchronized(lock) { load(modelKey) }
        val result = HashMap<String, FloatArray>()
        val pending = ArrayList<Pending>()
        for ((key, text) in texts) {
            val hash = hash(text)
            val vector = cached[hash]
            if (vector != null) result[key] = vector else pending += Pending(key, hash, text)
        }
        if (pending.isEmpty() || budget <= 0) return result
        val batch = pending.take(budget)
        val fresh = runCatching { embed(batch.map { it.text }) }.getOrNull() ?: return result
        if (fresh.size != batch.size) return result
        synchronized(lock) {
            val updated = load(modelKey).toMutableMap()
            batch.forEachIndexed { index, item ->
                updated[item.hash] = fresh[index]
                result[item.key] = fresh[index]
            }
            save(modelKey, updated)
        }
        return result
    }

    private data class Pending(val key: String, val hash: String, val text: String)

    private fun load(modelKey: String): Map<String, FloatArray> {
        val text = runCatching { if (cacheFile.isFile) cacheFile.readText() else "" }.getOrDefault("")
        if (text.isBlank()) return emptyMap()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        if (json.optString("model") != modelKey) return emptyMap()
        val vectors = json.optJSONObject("vectors") ?: return emptyMap()
        val result = HashMap<String, FloatArray>()
        vectors.keys().forEach { hash ->
            decode(vectors.optString(hash))?.let { result[hash] = it }
        }
        return result
    }

    private fun save(modelKey: String, vectors: Map<String, FloatArray>) {
        val json = JSONObject().put("model", modelKey)
        val encoded = JSONObject()
        for ((hash, vector) in vectors) {
            encoded.put(hash, encode(vector))
        }
        json.put("vectors", encoded)
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val temp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(cacheFile)) {
                cacheFile.writeText(json.toString())
                temp.delete()
            }
        }
    }

    private fun encode(vector: FloatArray): String {
        val buffer = java.nio.ByteBuffer.allocate(vector.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buffer.putFloat(it) }
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    private fun decode(value: String): FloatArray? = runCatching {
        val bytes = Base64.getDecoder().decode(value)
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        FloatArray(bytes.size / 4) { buffer.getFloat() }
    }.getOrNull()

    private fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private val lock = Any()
}

/** OpenAI 兼容的 embedding 调用；失败一律返回 null，由调用方降级。 */
internal object AgentMemoryEmbeddingClient {
    private const val TIMEOUT_MS = 2_500L
    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun modelKey(config: AgentModelClient.ModelConfig): String =
        "${config.providerId}|${endpoint(config)}|${config.model}"

    private fun endpoint(config: AgentModelClient.ModelConfig): String {
        val base = config.baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/completions")
        return base.trimEnd('/')
    }

    fun embed(config: AgentModelClient.ModelConfig, texts: List<String>): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        val base = endpoint(config)
        if (base.isEmpty() || config.apiKey.isBlank() || config.model.isBlank()) return null
        val input = JSONArray()
        texts.forEach { input.put(it) }
        val payload = JSONObject().put("model", config.model).put("input", input).toString()
        val request = Request.Builder()
            .url("$base/embeddings")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(payload.toRequestBody(JSON))
            .build()
        val client = AgentHttpClient.client.newBuilder()
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body.string()
                val data = JSONObject(body).optJSONArray("data") ?: return@use null
                val vectors = ArrayList<FloatArray>(data.length())
                for (index in 0 until data.length()) {
                    val array = data.getJSONObject(index).optJSONArray("embedding") ?: return@use null
                    vectors += FloatArray(array.length()) { position -> array.optDouble(position, 0.0).toFloat() }
                }
                if (vectors.isEmpty()) null else vectors
            }
        }.getOrNull()
    }
}
