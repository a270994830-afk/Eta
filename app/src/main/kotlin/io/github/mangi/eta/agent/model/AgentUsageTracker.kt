package io.github.mangi.eta.agent.model

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 模型调用用量统计（按天 + 按模型 + 当月汇总），落盘到 filesDir/agent_usage.json；
 * 供 usage_stats 工具与设置页读取。0 依赖，纯本地文件。
 */
internal object AgentUsageTracker {
    private const val FILE_NAME = "agent_usage.json"
    private const val MAX_DAYS = 120

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var storageDir: java.io.File? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        storageDir = context.applicationContext.filesDir
    }

    /** 单测注入存储目录（不依赖 Android Context）。 */
    internal fun attachStorageDir(dir: java.io.File?) {
        storageDir = dir
    }

    private fun file(): File? = storageDir?.let { File(it, FILE_NAME) }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun monthKey(): String = today().substring(0, 7)

    private fun load(): JSONObject = runCatching {
        val f = file() ?: return JSONObject()
        if (!f.exists()) JSONObject() else JSONObject(f.readText())
    }.getOrDefault(JSONObject())

    @Synchronized
    private fun save(root: JSONObject) {
        runCatching {
            val f = file() ?: return
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(root.toString())
                tmp.delete()
            }
        }
    }

    /** 记录一次请求的用量（token 均为可选，兜底 0）。 */
    fun record(modelId: String, inputTokens: Int?, outputTokens: Int?, reasoningTokens: Int?) {
        val input = (inputTokens ?: 0).coerceAtLeast(0)
        val output = ((outputTokens ?: 0) + (reasoningTokens ?: 0)).coerceAtLeast(0)
        if (input == 0 && output == 0) return
        runCatching {
            val root = load()
            val days = root.optJSONObject("days") ?: JSONObject().also { root.put("days", it) }
            val key = today()
            val day = days.optJSONObject(key) ?: JSONObject().also { days.put(key, it) }
            bump(day, input, output, request = true)
            val byModel = day.optJSONObject("by_model") ?: JSONObject().also { day.put("by_model", it) }
            val modelName = modelId.ifBlank { "unknown" }
            val model = byModel.optJSONObject(modelName) ?: JSONObject().also { byModel.put(modelName, it) }
            bump(model, input, output, request = true)

            val monthLabel = monthKey()
            val month = root.optJSONObject("month")
                ?.takeIf { it.optString("month") == monthLabel }
                ?: JSONObject().put("month", monthLabel)
            root.put("month", month)
            bump(month, input, output, request = true)

            pruneDays(days)
            save(root)
        }
    }

    private fun bump(target: JSONObject, input: Int, output: Int, request: Boolean) {
        target.put("prompt_tokens", target.optInt("prompt_tokens") + input)
        target.put("completion_tokens", target.optInt("completion_tokens") + output)
        target.put("total_tokens", target.optInt("total_tokens") + input + output)
        if (request) target.put("requests", target.optInt("requests") + 1)
    }

    private fun pruneDays(days: JSONObject) {
        val keys = days.keys().asSequence().toList().sorted()
        if (keys.size <= MAX_DAYS) return
        (keys.dropLast(MAX_DAYS)).forEach { days.remove(it) }
    }

    /** 返回最近 [days] 天与当月的汇总，供 usage_stats 工具直接使用。 */
    fun snapshot(days: Int): JSONObject {
        val safeDays = days.coerceIn(1, 90)
        val root = load()
        val all = root.optJSONObject("days") ?: JSONObject()
        val recent = JSONObject()
        val ordered = all.keys().asSequence().toList().sortedDescending().take(safeDays)
        val totalPrompt = ordered.sumOf { all.optJSONObject(it)?.optInt("prompt_tokens") ?: 0 }
        val totalCompletion = ordered.sumOf { all.optJSONObject(it)?.optInt("completion_tokens") ?: 0 }
        val totalRequests = ordered.sumOf { all.optJSONObject(it)?.optInt("requests") ?: 0 }
        ordered.forEach { key -> all.optJSONObject(key)?.let { recent.put(key, it) } }
        val month = root.optJSONObject("month")
            ?.takeIf { it.optString("month") == monthKey() }
            ?: JSONObject().put("month", monthKey())
        return JSONObject()
            .put("ok", true)
            .put("days", recent)
            .put("recent_days", safeDays)
            .put(
                "recent_totals",
                JSONObject()
                    .put("prompt_tokens", totalPrompt)
                    .put("completion_tokens", totalCompletion)
                    .put("total_tokens", totalPrompt + totalCompletion)
                    .put("requests", totalRequests),
            )
            .put("month", month)
            .put("daily_limit", AgentDailyTokenGuard.limit())
            .put("used_today", AgentDailyTokenGuard.usedToday())
    }
}
