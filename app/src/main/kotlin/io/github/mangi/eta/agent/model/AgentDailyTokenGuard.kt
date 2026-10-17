package io.github.mangi.eta.agent.model

import io.github.mangi.eta.config.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每日 Token 用量限额（R3 护栏，语义对齐 Operit 的 SpendGuard；0=不限）。
 *
 * 数据存本地 Agent SharedPreferences（与设置页同一份），跨天自动重置。
 */
internal object AgentDailyTokenGuard {
    private const val KEY_LIMIT = "agent_daily_token_limit"
    private const val KEY_DATE = "agent_daily_token_date"
    private const val KEY_USED = "agent_daily_token_used"

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun prefs() = Prefs.localAgentPreferences()

    fun limit(): Int =
        runCatching { prefs()?.getString(KEY_LIMIT, "0")?.trim()?.toIntOrNull() ?: 0 }.getOrDefault(0)

    fun usedToday(): Int {
        val p = prefs() ?: return 0
        return runCatching {
            if (p.getString(KEY_DATE, "") != today()) 0
            else p.getString(KEY_USED, "0")?.trim()?.toIntOrNull() ?: 0
        }.getOrDefault(0)
    }

    /** 模型调用前检查；超限抛永久失败（不重试，避免空转烧额度）。 */
    fun checkOrThrow() {
        val limit = limit()
        if (limit <= 0) return
        val used = usedToday()
        if (used >= limit) {
            throw AgentModelFailure(
                "DAILY_TOKEN_LIMIT",
                false,
                "已触发每日 Token 用量限额（今日已用 $used / 上限 $limit）。可在设置里调整上限，或次日自动重置。",
            )
        }
    }

    /** 记录一次模型调用的用量（输入+输出+推理）。 */
    fun record(inputTokens: Int?, outputTokens: Int?, reasoningTokens: Int?) {
        val p = prefs() ?: return
        val add = (inputTokens ?: 0) + (outputTokens ?: 0) + (reasoningTokens ?: 0)
        if (add <= 0) return
        runCatching {
            val date = today()
            val current = if (p.getString(KEY_DATE, "") == date) {
                p.getString(KEY_USED, "0")?.trim()?.toIntOrNull() ?: 0
            } else 0
            p.edit()
                .putString(KEY_DATE, date)
                .putString(KEY_USED, (current + add).toString())
                .apply()
        }
    }
}
