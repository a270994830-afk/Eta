package io.github.mangi.eta.agent.schedule

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 定时任务的本地存储：每个任务每天在指定时间触发一次 Agent 运行。 */
internal object EtaScheduleStore {
    data class Task(
        val id: String,
        val hour: Int,
        val minute: Int,
        val prompt: String,
        val enabled: Boolean = true,
    )

    const val MAX_TASKS = 8
    const val MAX_PROMPT_CHARS = 2_000

    private const val PREFS = "eta_schedule_tasks"
    private const val KEY_TASKS = "tasks"

    fun load(context: Context): List<Task> {
        val raw = preferences(context).getString(KEY_TASKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val prompt = item.optString("prompt").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Task(
                    id = id,
                    hour = item.optInt("hour", 0).coerceIn(0, 23),
                    minute = item.optInt("minute", 0).coerceIn(0, 59),
                    prompt = prompt,
                    enabled = item.optBoolean("enabled", true),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, tasks: List<Task>) {
        val array = JSONArray()
        tasks.take(MAX_TASKS).forEach { task ->
            array.put(
                JSONObject()
                    .put("id", task.id)
                    .put("hour", task.hour)
                    .put("minute", task.minute)
                    .put("prompt", task.prompt)
                    .put("enabled", task.enabled),
            )
        }
        preferences(context).edit().putString(KEY_TASKS, array.toString()).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
