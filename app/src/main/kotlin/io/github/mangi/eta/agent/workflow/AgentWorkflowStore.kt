package io.github.mangi.eta.agent.workflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 工作流（顺序步骤 + 条件 + 定时触发的最小 DAG）本地存储。
 *
 * 单文件 filesDir/agent_workflows.json，原子写（tmp + rename）；上限 50 条流程、每条 100 步。
 * 读到损坏或越界内容一律降级为空/截断，不向上抛异常。
 */
internal object AgentWorkflowStore {

    const val MAX_WORKFLOWS = 50
    const val MAX_STEPS = 100
    const val ON_ERROR_STOP = "stop"
    const val ON_ERROR_CONTINUE = "continue"
    const val ON_ERROR_RETRY = "retry"
    const val GOTO_WHEN_ALWAYS = "always"
    const val GOTO_WHEN_OK = "ok"
    const val GOTO_WHEN_FAIL = "fail"

    /** 单次运行最多执行多少步（含跳转重复执行）；超出即中止，防条件分支写成死循环。 */
    const val MAX_STEP_EXECUTIONS = 200
    const val STOPPED_STEP_FAILED = "step_failed"
    const val STOPPED_LOOP_GUARD = "loop_guard"
    const val MIN_RETRIES = 1
    const val MAX_RETRIES = 3
    const val DEFAULT_RETRIES = 1
    const val DEFAULT_RETRY_DELAY_MS = 500
    const val MAX_RETRY_DELAY_MS = 5000
    const val STATUS_SUCCESS = "success"
    const val STATUS_FAILED = "failed"

    private const val FILE_NAME = "agent_workflows.json"

    /** 失败后按逆序执行的补偿动作：与步骤同构的 tool 或 prompt 二选一。 */
    data class Compensation(
        val tool: String?,
        val prompt: String?,
        val args: JSONObject,
    ) {
        fun kind(): String = if (tool != null) "tool" else "prompt"

        fun name(): String = tool ?: prompt ?: ""

        fun toJson(): JSONObject {
            val obj = JSONObject().put("args", args)
            if (tool != null) obj.put("tool", tool) else obj.put("prompt", prompt ?: "")
            return obj
        }

        companion object {
            fun fromJson(obj: JSONObject?): Compensation? {
                if (obj == null) return null
                val tool = obj.optString("tool").trim().takeIf { it.isNotEmpty() }
                val prompt = obj.optString("prompt").trim().takeIf { it.isNotEmpty() }
                if (tool == null && prompt == null) return null
                return Compensation(
                    tool = tool,
                    prompt = if (tool == null) prompt else null,
                    args = obj.optJSONObject("args") ?: JSONObject(),
                )
            }
        }
    }

    /**
     * 补偿执行计划：按已执行步骤的**逆序**列出带补偿动作的步序。
     * 纯函数，便于单测；越界或未定义补偿的步骤直接跳过。
     */
    fun rollbackPlan(executed: List<Int>, compensations: List<Compensation?>): List<Int> =
        executed.asReversed().filter { index -> index in compensations.indices && compensations[index] != null }

    data class Step(
        val tool: String?,
        val prompt: String?,
        val args: JSONObject,
        val onError: String,
        val condition: String,
        val retries: Int = DEFAULT_RETRIES,
        val retryDelayMs: Int = DEFAULT_RETRY_DELAY_MS,
        /** 执行后跳转到的步序（0 基）；空串表示顺序执行下一步。配上 gotoWhen 即可实现 if/else 与循环。 */
        val goto: String = "",
        val gotoWhen: String = GOTO_WHEN_ALWAYS,
        /** 该步失败导致流程中止时，用于回滚的补偿动作；为空表示无需补偿。 */
        val rollback: Compensation? = null,
    ) {
        val kind: String get() = if (tool != null) "tool" else "prompt"

        fun name(): String = tool ?: prompt ?: ""

        fun toJson(): JSONObject {
            val obj = JSONObject()
                .put("args", args)
                .put("on_error", onError)
                .put("condition", condition)
            if (onError == ON_ERROR_RETRY) {
                obj.put("retries", retries.coerceIn(MIN_RETRIES, MAX_RETRIES))
                obj.put("retry_delay_ms", retryDelayMs.coerceIn(0, MAX_RETRY_DELAY_MS))
            }
            if (tool != null) obj.put("tool", tool) else obj.put("prompt", prompt ?: "")
            if (goto.isNotBlank()) {
                obj.put("goto", goto)
                obj.put("goto_when", gotoWhen)
            }
            rollback?.let { obj.put("rollback", it.toJson()) }
            return obj
        }

        companion object {
            fun fromJson(obj: JSONObject): Step? {
                val tool = obj.optString("tool").trim().takeIf { it.isNotEmpty() }
                val prompt = obj.optString("prompt").trim().takeIf { it.isNotEmpty() }
                if (tool == null && prompt == null) return null
                val onError = when (obj.optString("on_error").trim()) {
                    ON_ERROR_CONTINUE -> ON_ERROR_CONTINUE
                    ON_ERROR_RETRY -> ON_ERROR_RETRY
                    else -> ON_ERROR_STOP
                }
                val goto = normalizeGoto(obj.optString("goto"))
                val gotoWhen = normalizeGotoWhen(obj.optString("goto_when"))
                return Step(
                    tool = tool,
                    prompt = if (tool == null) prompt else null,
                    args = obj.optJSONObject("args") ?: JSONObject(),
                    onError = onError,
                    condition = obj.optString("condition").trim(),
                    retries = obj.optInt("retries", DEFAULT_RETRIES).coerceIn(MIN_RETRIES, MAX_RETRIES),
                    retryDelayMs = obj.optInt("retry_delay_ms", DEFAULT_RETRY_DELAY_MS)
                        .coerceIn(0, MAX_RETRY_DELAY_MS),
                    goto = goto,
                    gotoWhen = gotoWhen,
                    rollback = Compensation.fromJson(obj.optJSONObject("rollback")),
                )
            }
        }
    }

    /** 跳转条件是否命中；未知值按 always 处理。 */
    fun gotoMatches(gotoWhen: String, ok: Boolean): Boolean = when (gotoWhen) {
        GOTO_WHEN_OK -> ok
        GOTO_WHEN_FAIL -> !ok
        else -> true
    }

    /** 归一化 goto：去空白，空串表示不跳转。 */
    fun normalizeGoto(value: String?): String = value.orEmpty().trim()

    /** 归一化 goto_when：未知值回退 always。 */
    fun normalizeGotoWhen(value: String?): String = when (value.orEmpty().trim()) {
        GOTO_WHEN_OK -> GOTO_WHEN_OK
        GOTO_WHEN_FAIL -> GOTO_WHEN_FAIL
        else -> GOTO_WHEN_ALWAYS
    }

    /**
     * 解析步骤跳转目标：命中 [gotoWhen] 条件时返回目标步序（0 基），否则 null（顺序执行下一步）。
     * 目标非整数或越界一律不跳转。纯函数，便于单测。
     */
    fun branchTarget(goto: String, gotoWhen: String, ok: Boolean, stepCount: Int): Int? {
        if (goto.isBlank()) return null
        if (!gotoMatches(gotoWhen, ok)) return null
        val target = goto.trim().toIntOrNull() ?: return null
        if (target < 0 || target >= stepCount) return null
        return target
    }

    data class Workflow(
        val id: String,
        val name: String,
        val description: String,
        val steps: List<Step>,
        val enabled: Boolean,
        val trigger: JSONObject?,
        val createdAt: Long,
        val updatedAt: Long,
        val lastRunAt: Long,
        val lastStatus: String,
    ) {
        fun toJson(): JSONObject {
            val obj = JSONObject()
                .put("id", id)
                .put("name", name)
                .put("description", description)
                .put("steps", JSONArray().also { array -> steps.forEach { array.put(it.toJson()) } })
                .put("enabled", enabled)
                .put("created_at", createdAt)
                .put("updated_at", updatedAt)
                .put("last_run_at", lastRunAt)
                .put("last_status", lastStatus)
            trigger?.let { obj.put("trigger", it) }
            return obj
        }

        fun summaryJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("description", description)
            .put("step_count", steps.size)
            .put("enabled", enabled)
            .put("last_run_at", lastRunAt)
            .put("last_status", lastStatus)
    }

    fun storeFile(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun list(context: Context): List<Workflow> = read(context)

    @Synchronized
    fun find(context: Context, idOrName: String): Workflow? {
        val key = idOrName.trim()
        if (key.isEmpty()) return null
        return read(context).firstOrNull { it.id == key || it.name.equals(key, ignoreCase = true) }
    }

    /** 同名流程执行更新（保留 id/created_at），否则新建；达到上限且为新流程时返回 null。 */
    @Synchronized
    fun create(
        context: Context,
        name: String,
        description: String,
        steps: List<Step>,
        trigger: JSONObject?,
    ): Workflow? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || steps.isEmpty()) return null
        val all = read(context)
        val existing = all.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        if (existing == null && all.size >= MAX_WORKFLOWS) return null
        val now = System.currentTimeMillis()
        val workflow = Workflow(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = trimmed,
            description = description.trim(),
            steps = steps.take(MAX_STEPS),
            enabled = existing?.enabled ?: true,
            trigger = trigger,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            lastRunAt = existing?.lastRunAt ?: 0L,
            lastStatus = existing?.lastStatus ?: "",
        )
        val updated = if (existing == null) {
            all + workflow
        } else {
            all.map { if (it.id == existing.id) workflow else it }
        }
        write(context, updated)
        return workflow
    }

    @Synchronized
    fun delete(context: Context, idOrName: String): Workflow? {
        val target = find(context, idOrName) ?: return null
        write(context, read(context).filterNot { it.id == target.id })
        return target
    }

    @Synchronized
    fun markRun(context: Context, id: String, status: String, at: Long = System.currentTimeMillis()) {
        write(context, read(context).map { if (it.id == id) it.copy(lastRunAt = at, lastStatus = status) else it })
    }

    private fun read(context: Context): List<Workflow> = runCatching {
        val file = storeFile(context)
        if (!file.exists()) return emptyList()
        val root = JSONObject(file.readText())
        val array = root.optJSONArray("workflows") ?: JSONArray()
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = obj.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val stepsJson = obj.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until stepsJson.length()).mapNotNull { stepIndex ->
                stepsJson.optJSONObject(stepIndex)?.let { Step.fromJson(it) }
            }.take(MAX_STEPS)
            if (steps.isEmpty()) return@mapNotNull null
            Workflow(
                id = id,
                name = name,
                description = obj.optString("description"),
                steps = steps,
                enabled = obj.optBoolean("enabled", true),
                trigger = obj.optJSONObject("trigger"),
                createdAt = obj.optLong("created_at"),
                updatedAt = obj.optLong("updated_at"),
                lastRunAt = obj.optLong("last_run_at"),
                lastStatus = obj.optString("last_status"),
            )
        }.take(MAX_WORKFLOWS)
    }.getOrElse { emptyList() }

    private fun write(context: Context, workflows: List<Workflow>) {
        runCatching {
            val file = storeFile(context)
            val root = JSONObject().put(
                "workflows",
                JSONArray().also { array -> workflows.take(MAX_WORKFLOWS).forEach { array.put(it.toJson()) } },
            )
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        }
    }
}
