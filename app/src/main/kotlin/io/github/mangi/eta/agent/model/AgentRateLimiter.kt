package io.github.mangi.eta.agent.model

import android.content.Context
import io.github.mangi.eta.config.Prefs
import java.io.File
import org.json.JSONObject

/**
 * 每 Provider 的分钟级请求限流（滑动窗口的简化固定窗口实现）。
 *
 * - 阈值来自本地 Agent 配置 `agent_rate_limit_per_minute`（0 或缺失 = 不限流）。
 * - 状态落盘 filesDir/rate_limit.json，供 rate_limit_status 工具读取。
 * - 超限抛 [AgentModelFailure]（retryable=true，让上层重试退避而不是直接失败）。
 */
internal object AgentRateLimiter {
    private const val FILE_NAME = "rate_limit.json"
    private const val WINDOW_MS = 60_000L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var storageDir: java.io.File? = null

    /** 单测用：覆盖限流阈值来源。 */
    @Volatile
    internal var limitOverride: (() -> Int)? = null

    /** providerKey -> [windowStartMs, used] */
    private val windows = HashMap<String, LongArray>()

    fun init(context: Context) {
        appContext = context.applicationContext
        storageDir = context.applicationContext.filesDir
    }

    /** 单测注入存储目录（不依赖 Android Context）。 */
    internal fun attachStorageDir(dir: java.io.File?) {
        storageDir = dir
    }

    /** 单测清空窗口状态。 */
    internal fun resetForTests() {
        windows.clear()
    }

    fun limitPerMinute(): Int {
        limitOverride?.let { return it() }
        return runCatching {
            Prefs.localAgentPreferences()
                ?.getString("agent_rate_limit_per_minute", "0")?.trim()?.toIntOrNull() ?: 0
        }.getOrDefault(0)
    }

    @Synchronized
    fun checkOrThrow(providerKey: String) {
        val limit = limitPerMinute()
        if (limit <= 0) return
        val now = System.currentTimeMillis()
        val window = windows.getOrPut(providerKey) { longArrayOf(now, 0L) }
        if (now - window[0] >= WINDOW_MS) {
            window[0] = now
            window[1] = 0L
        }
        if (window[1] >= limit) {
            val waitMs = (window[0] + WINDOW_MS - now).coerceAtLeast(0L)
            persist()
            throw AgentModelFailure(
                "RATE_LIMITED",
                true,
                "本地限流：provider=$providerKey 每分钟上限 $limit 次已用满，约 ${(waitMs / 1000L) + 1} 秒后重试。",
            )
        }
        window[1] += 1
        persist()
    }

    @Synchronized
    fun statusJson(): JSONObject {
        val providers = JSONObject()
        val limit = limitPerMinute()
        val now = System.currentTimeMillis()
        windows.forEach { (key, window) ->
            val active = now - window[0] < WINDOW_MS
            providers.put(
                key,
                JSONObject()
                    .put("limit_per_minute", limit)
                    .put("window_start_ms", window[0])
                    .put("used", if (active) window[1] else 0L)
                    .put("remaining", if (limit > 0) (limit - if (active) window[1] else 0L).coerceAtLeast(0L) else -1),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("enabled", limit > 0)
            .put("limit_per_minute", limit)
            .put("providers", providers)
    }

    private fun persist() {
        runCatching {
            val dir = storageDir ?: return
            val root = statusJson()
            root.put("updated_at_ms", System.currentTimeMillis())
            val f = File(dir, FILE_NAME)
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(root.toString())
                tmp.delete()
            }
        }
    }
}
