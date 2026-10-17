package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.schedule.EtaScheduleScheduler
import io.github.mangi.eta.agent.schedule.EtaScheduleStore
import io.github.mangi.eta.agent.workflow.AgentWorkflowStore
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工作流编排工具：定义多步（tool / prompt）、带条件与定时触发的自动化并一键运行。
 *
 * 步骤执行委托给主代理注入的两个回调：stepExecutor 接到真实工具分发，promptExecutor 接到模型对话。
 * 本类只负责参数解析、顺序/条件控制、进度与结果汇总，所有异常都吞成 {"ok":false,...}。
 */
internal class AgentWorkflowToolSet(
    private val context: Context,
    private val logger: AgentLogger,
    private val stepExecutor: (String, JSONObject) -> String,
    private val promptExecutor: (String) -> String,
) {

    fun execute(name: String, args: JSONObject): String? {
        if (name !in TOOL_NAMES) return null
        return runCatching {
            when (name) {
                "workflow_create" -> create(args)
                "workflow_list" -> list()
                "workflow_get" -> get(args)
                "workflow_delete" -> delete(args)
                "workflow_run" -> run(args)
                "workflow_schedule" -> schedule(args)
                "workflow_unschedule" -> unschedule(args)
                else -> null
            }
        }.getOrElse { throwable ->
            logger.warn("AgentWorkflowToolSet $name failed: ${throwable.javaClass.simpleName}: ${throwable.message}")
            error("TOOL_INTERNAL_ERROR", throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    private fun create(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return error("INVALID_ARGUMENT", "缺少 name")
        val stepsArray = args.optJSONArray("steps")
        if (stepsArray == null || stepsArray.length() == 0) return error("INVALID_ARGUMENT", "缺少 steps")
        if (stepsArray.length() > AgentWorkflowStore.MAX_STEPS) {
            return error("TOO_MANY_STEPS", "steps 超过上限 ${AgentWorkflowStore.MAX_STEPS}")
        }
        val steps = ArrayList<AgentWorkflowStore.Step>(stepsArray.length())
        for (index in 0 until stepsArray.length()) {
            val obj = stepsArray.optJSONObject(index)
                ?: return error("INVALID_STEP", "第 ${index + 1} 步不是对象")
            val tool = obj.optString("tool").trim().takeIf { it.isNotEmpty() }
            val prompt = obj.optString("prompt").trim().takeIf { it.isNotEmpty() }
            if (tool == null && prompt == null) {
                return error("INVALID_STEP", "第 ${index + 1} 步缺少 tool 或 prompt")
            }
            val onError = when (obj.optString("on_error").trim()) {
                AgentWorkflowStore.ON_ERROR_CONTINUE -> AgentWorkflowStore.ON_ERROR_CONTINUE
                AgentWorkflowStore.ON_ERROR_RETRY -> AgentWorkflowStore.ON_ERROR_RETRY
                else -> AgentWorkflowStore.ON_ERROR_STOP
            }
            steps += AgentWorkflowStore.Step(
                tool = tool,
                prompt = if (tool == null) prompt else null,
                args = obj.optJSONObject("args") ?: JSONObject(),
                onError = onError,
                condition = obj.optString("condition").trim(),
                retries = obj.optInt("retries", AgentWorkflowStore.DEFAULT_RETRIES)
                    .coerceIn(AgentWorkflowStore.MIN_RETRIES, AgentWorkflowStore.MAX_RETRIES),
                retryDelayMs = obj.optInt("retry_delay_ms", AgentWorkflowStore.DEFAULT_RETRY_DELAY_MS)
                    .coerceIn(0, AgentWorkflowStore.MAX_RETRY_DELAY_MS),
                goto = AgentWorkflowStore.normalizeGoto(obj.optString("goto")),
                gotoWhen = AgentWorkflowStore.normalizeGotoWhen(obj.optString("goto_when")),
                rollback = AgentWorkflowStore.Compensation.fromJson(obj.optJSONObject("rollback")),
            )
        }
        val trigger = args.optJSONObject("trigger")
        val workflow = AgentWorkflowStore.create(context, name, args.optString("description"), steps, trigger)
            ?: return error(
                "WORKFLOW_LIMIT_REACHED",
                "流程数量已达上限 ${AgentWorkflowStore.MAX_WORKFLOWS}，请先删除无用流程",
            )
        return ok(
            JSONObject()
                .put("workflow", workflow.summaryJson())
                .put("trigger", trigger ?: JSONObject.NULL),
        )
    }

    private fun list(): String {
        val array = JSONArray()
        AgentWorkflowStore.list(context).forEach { array.put(it.summaryJson()) }
        return ok(JSONObject().put("workflows", array).put("count", array.length()))
    }

    private fun get(args: JSONObject): String {
        val key = keyOf(args) ?: return error("INVALID_ARGUMENT", "缺少 name 或 id")
        val workflow = AgentWorkflowStore.find(context, key)
            ?: return error("WORKFLOW_NOT_FOUND", "未找到工作流: $key")
        return ok(JSONObject().put("workflow", workflow.toJson()))
    }

    private fun delete(args: JSONObject): String {
        val key = keyOf(args) ?: return error("INVALID_ARGUMENT", "缺少 name 或 id")
        val workflow = AgentWorkflowStore.delete(context, key)
            ?: return error("WORKFLOW_NOT_FOUND", "未找到工作流: $key")
        removeScheduledTask(workflow.name)
        return ok(JSONObject().put("id", workflow.id).put("name", workflow.name).put("deleted", true))
    }

    private fun run(args: JSONObject): String {
        val key = keyOf(args) ?: return error("INVALID_ARGUMENT", "缺少 name 或 id")
        val workflow = AgentWorkflowStore.find(context, key)
            ?: return error("WORKFLOW_NOT_FOUND", "未找到工作流: $key")
        val steps = workflow.steps
        val start = args.optInt("from_step", 0).coerceIn(0, steps.size)
        val dryRun = args.optBoolean("dry_run", false)

        if (dryRun) {
            val plan = JSONArray()
            for (index in start until steps.size) plan.put(stepView(index, steps[index]))
            return ok(
                JSONObject()
                    .put("dry_run", true)
                    .put("name", workflow.name)
                    .put("from_step", start)
                    .put("step_count", plan.length())
                    .put("steps", plan),
            )
        }

        val results = JSONArray()
        var lastOk = true
        var lastText = ""
        var executed = 0
        var stopped = false
        var stoppedReason: String? = null
        var failureIndex = -1
        var failureName = ""
        var jumps = 0
        val executedIndices = ArrayList<Int>()
        var index = start
        while (index < steps.size) {
            if (executed >= AgentWorkflowStore.MAX_STEP_EXECUTIONS) {
                stopped = true
                stoppedReason = AgentWorkflowStore.STOPPED_LOOP_GUARD
                break
            }
            val step = steps[index]
            val condition = evaluateCondition(step.condition, lastOk, index, steps.size, lastText)
            if (!condition.value) {
                results.put(
                    JSONObject()
                        .put("index", index)
                        .put("kind", step.kind)
                        .put("name", step.name())
                        .put("skipped", true)
                        .put("reason", "condition_false")
                        .put("condition", step.condition),
                )
                index++
                continue
            }
            val maxAttempts = if (step.onError == AgentWorkflowStore.ON_ERROR_RETRY) {
                step.retries.coerceIn(AgentWorkflowStore.MIN_RETRIES, AgentWorkflowStore.MAX_RETRIES) + 1
            } else {
                1
            }
            var attempt = 0
            var outcome = ""
            var okStep = false
            var elapsed = 0L
            while (attempt < maxAttempts) {
                attempt++
                val startedAt = System.currentTimeMillis()
                outcome = runCatching {
                    if (step.tool != null) {
                        stepExecutor(step.tool, step.args)
                    } else {
                        promptExecutor(step.prompt ?: "")
                    }
                }.getOrElse { throwable ->
                    error("STEP_EXCEPTION", throwable.message ?: throwable.javaClass.simpleName)
                }
                elapsed += System.currentTimeMillis() - startedAt
                okStep = isSuccess(outcome)
                if (okStep || attempt >= maxAttempts) break
                val delay = step.retryDelayMs.coerceIn(0, AgentWorkflowStore.MAX_RETRY_DELAY_MS)
                if (delay > 0) runCatching { Thread.sleep(delay.toLong()) }
            }
            lastOk = okStep
            lastText = summarizeText(outcome)
            executed++
            executedIndices += index
            val stepResult = JSONObject()
                .put("index", index)
                .put("kind", step.kind)
                .put("name", step.name())
                .put("ok", okStep)
                .put("elapsed_ms", elapsed)
                .put("result", summarize(outcome))
            if (maxAttempts > 1) stepResult.put("attempts", attempt)
            if (condition.error != null) stepResult.put("condition_error", condition.error)
            results.put(stepResult)
            val branch = AgentWorkflowStore.branchTarget(step.goto, step.gotoWhen, okStep, steps.size)
            if (branch != null) {
                jumps++
                index = branch
                continue
            }
            if (step.goto.isNotBlank() && AgentWorkflowStore.gotoMatches(step.gotoWhen, okStep)) {
                stepResult.put("goto_ignored", step.goto)
            }
            if (!okStep && step.onError != AgentWorkflowStore.ON_ERROR_CONTINUE) {
                stopped = true
                stoppedReason = AgentWorkflowStore.STOPPED_STEP_FAILED
                failureIndex = index
                failureName = step.name()
                break
            }
            index++
        }

        val rollbackEnabled = args.optBoolean("rollback", true)
        val rollbackResults = JSONArray()
        if (stopped && rollbackEnabled) {
            val plan = AgentWorkflowStore.rollbackPlan(executedIndices, steps.map { it.rollback })
            for (stepIndex in plan) {
                val compensation = steps[stepIndex].rollback ?: continue
                val startedAt = System.currentTimeMillis()
                val outcome = runCatching {
                    if (compensation.tool != null) {
                        stepExecutor(compensation.tool, compensation.args)
                    } else {
                        promptExecutor(compensation.prompt ?: "")
                    }
                }.getOrElse { throwable ->
                    error("ROLLBACK_EXCEPTION", throwable.message ?: throwable.javaClass.simpleName)
                }
                rollbackResults.put(
                    JSONObject()
                        .put("index", stepIndex)
                        .put("kind", compensation.kind())
                        .put("name", compensation.name())
                        .put("ok", isSuccess(outcome))
                        .put("elapsed_ms", System.currentTimeMillis() - startedAt)
                        .put("result", summarize(outcome)),
                )
            }
        }

        val status = if (stopped) AgentWorkflowStore.STATUS_FAILED else AgentWorkflowStore.STATUS_SUCCESS
        AgentWorkflowStore.markRun(context, workflow.id, status)
        val failedStep = if (failureIndex >= 0) {
            JSONObject().put("index", failureIndex).put("name", failureName)
        } else {
            JSONObject.NULL
        }
        return ok(
            JSONObject()
                .put("id", workflow.id)
                .put("name", workflow.name)
                .put("from_step", start)
                .put("executed", executed)
                .put("stopped", stopped)
                .put("stopped_reason", stoppedReason ?: JSONObject.NULL)
                .put("jumps", jumps)
                .put("rollback_enabled", rollbackEnabled)
                .put("rollbacks", rollbackResults)
                .put("status", status)
                .put("failed_step", failedStep)
                .put("next_step", failureIndex)
                .put("results", results),
        )
    }

    private fun schedule(args: JSONObject): String {
        val key = keyOf(args) ?: return error("INVALID_ARGUMENT", "缺少 name 或 id")
        val workflow = AgentWorkflowStore.find(context, key)
            ?: return error("WORKFLOW_NOT_FOUND", "未找到工作流: $key")
        val time = parseTime(args)
            ?: return error("INVALID_ARGUMENT", "缺少有效时间：需 hour + minute，或 time=HH:MM")
        val enabled = args.optBoolean("enabled", true)
        val taskId = scheduleTaskId(workflow.name)
        val prompt = "执行工作流 ${workflow.name}".take(EtaScheduleStore.MAX_PROMPT_CHARS)
        return runCatching {
            val current = EtaScheduleStore.load(context)
            val without = current.filterNot { it.id == taskId }
            if (without.size >= EtaScheduleStore.MAX_TASKS) {
                return@runCatching error("SCHEDULE_LIMIT_REACHED", "定时任务已达上限 ${EtaScheduleStore.MAX_TASKS}")
            }
            val updated = without + EtaScheduleStore.Task(
                id = taskId,
                hour = time.first,
                minute = time.second,
                prompt = prompt,
                enabled = enabled,
            )
            EtaScheduleStore.save(context, updated)
            EtaScheduleScheduler.sync(context)
            ok(
                JSONObject()
                    .put("scheduled", true)
                    .put("workflow", workflow.name)
                    .put("task_id", taskId)
                    .put("hour", time.first)
                    .put("minute", time.second)
                    .put("time", "%02d:%02d".format(time.first, time.second))
                    .put("enabled", enabled)
                    .put("prompt", prompt),
            )
        }.getOrElse { throwable ->
            logger.warn("AgentWorkflowToolSet workflow_schedule failed: ${throwable.javaClass.simpleName}: ${throwable.message}")
            error("SCHEDULE_UNAVAILABLE", throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    private fun unschedule(args: JSONObject): String {
        val key = keyOf(args) ?: return error("INVALID_ARGUMENT", "缺少 name 或 id")
        val workflow = AgentWorkflowStore.find(context, key)
        val names = setOfNotNull(workflow?.name?.trim(), key)
        val taskId = workflow?.let { scheduleTaskId(it.name) }
        return runCatching {
            val current = EtaScheduleStore.load(context)
            val removed = current.filter { task ->
                (taskId != null && task.id == taskId) || names.any { task.prompt == "执行工作流 $it" }
            }
            if (removed.isEmpty()) {
                return@runCatching ok(JSONObject().put("removed", 0).put("message", "没有找到该工作流的定时任务"))
            }
            EtaScheduleStore.save(context, current.filterNot { it in removed })
            EtaScheduleScheduler.sync(context)
            ok(
                JSONObject()
                    .put("removed", removed.size)
                    .put("ids", JSONArray().also { array -> removed.forEach { array.put(it.id) } }),
            )
        }.getOrElse { throwable ->
            logger.warn("AgentWorkflowToolSet workflow_unschedule failed: ${throwable.javaClass.simpleName}: ${throwable.message}")
            error("SCHEDULE_UNAVAILABLE", throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    private fun removeScheduledTask(name: String) {
        runCatching {
            val taskId = scheduleTaskId(name)
            val current = EtaScheduleStore.load(context)
            val removed = current.filter { it.id == taskId || it.prompt == "执行工作流 $name" }
            if (removed.isEmpty()) return@runCatching
            EtaScheduleStore.save(context, current.filterNot { it in removed })
            EtaScheduleScheduler.sync(context)
        }
    }

    private fun stepView(index: Int, step: AgentWorkflowStore.Step): JSONObject {
        val view = JSONObject()
            .put("index", index)
            .put("kind", step.kind)
            .put("name", step.name())
            .put("condition", step.condition)
            .put("on_error", step.onError)
            .put("args", step.args)
        if (step.onError == AgentWorkflowStore.ON_ERROR_RETRY) {
            view.put("retries", step.retries).put("retry_delay_ms", step.retryDelayMs)
        }
        return view
    }

    /**
     * 条件求值入口：空表达式视为「执行」。求值失败（语法/类型/未知构造）按 fail-open（执行）处理，
     * 错误码通过 [ConditionEval.error] 返回，由调用方写进步骤结果的 condition_error。
     */
    private fun evaluateCondition(
        condition: String,
        lastOk: Boolean,
        index: Int,
        total: Int,
        lastText: String,
    ): ConditionEval {
        val expr = condition.trim()
        if (expr.isEmpty()) return ConditionEval(value = true, error = null)
        val variables = mapOf(
            "last_ok" to ConditionValue.Bool(lastOk),
            "last_failed" to ConditionValue.Bool(!lastOk),
            "last_text" to ConditionValue.Str(lastText.take(MAX_CONDITION_TEXT)),
            "index" to ConditionValue.Num(index.toLong()),
            "step_count" to ConditionValue.Num(total.toLong()),
        )
        return try {
            ConditionEval(value = ConditionExpression(expr, variables).evaluate(), error = null)
        } catch (t: Throwable) {
            val reason = if (t is ConditionSyntaxException) {
                "${t.code}: ${t.message}"
            } else {
                "CONDITION_EVAL_FAILED: " + (t.message ?: t.javaClass.simpleName)
            }
            ConditionEval(value = true, error = reason)
        }
    }

    private fun summarizeText(text: String?): String {
        val value = text.orEmpty()
        return if (value.length <= MAX_CONDITION_TEXT) value else value.take(MAX_CONDITION_TEXT)
    }

    private fun parseTime(args: JSONObject): Pair<Int, Int>? {
        val raw = args.optString("time").trim()
        if (raw.isNotEmpty()) {
            val parts = raw.split(":")
            if (parts.size < 2) return null
            val hour = parts[0].trim().toIntOrNull() ?: return null
            val minute = parts[1].trim().toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return hour to minute
        }
        if (!args.has("hour") && !args.has("minute")) return null
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    private fun keyOf(args: JSONObject): String? {
        val key = args.optString("name").trim().ifEmpty { args.optString("id").trim() }
        return key.takeIf { it.isNotEmpty() }
    }

    /** 由流程名派生稳定的定时任务 id，便于 schedule/unschedule/delete 按名对应同一条定时。 */
    private fun scheduleTaskId(name: String): String =
        "wf-" + Integer.toHexString(name.trim().lowercase().hashCode())

    private fun isSuccess(result: String?): Boolean {
        val trimmed = result?.trim().orEmpty()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return true
        return runCatching { JSONObject(trimmed).optBoolean("ok", true) }.getOrDefault(true)
    }

    private fun summarize(text: String?): String {
        val value = text.orEmpty()
        return if (value.length <= MAX_SUMMARY_CHARS) value else value.take(MAX_SUMMARY_CHARS) + "…"
    }

    private fun ok(body: JSONObject): String = body.put("ok", true).toString()

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    companion object {
        val TOOL_NAMES: Set<String> = setOf(
            "workflow_create",
            "workflow_list",
            "workflow_get",
            "workflow_delete",
            "workflow_run",
            "workflow_schedule",
            "workflow_unschedule",
        )

        private const val MAX_SUMMARY_CHARS = 600
        private const val MAX_CONDITION_TEXT = 2000
    }
}

/** 条件求值结果：value 为最终布尔；error 非空表示求值失败（此时 value 已按 fail-open 置 true）。 */
private data class ConditionEval(val value: Boolean, val error: String?)

private sealed interface ConditionValue {
    data class Bool(val value: Boolean) : ConditionValue
    data class Num(val value: Long) : ConditionValue
    data class Str(val value: String) : ConditionValue
}

private class ConditionSyntaxException(val code: String, message: String) : Exception(message)

/**
 * 小型条件表达式求值器（递归下降），不引入脚本引擎、不 eval。
 *
 * 文法（优先级低→高）：or(`||`) → and(`&&`) → not(`!`) → comparison → primary。
 * primary 支持括号、数字、单/双引号字符串、true/false、变量；未命中变量的裸标识符按字符串字面量处理
 * （满足「字符串比较引号可省」）。任何语法/类型错误都抛 [ConditionSyntaxException]，由调用方转成 fail-open。
 */
private class ConditionExpression(
    private val expression: String,
    private val variables: Map<String, ConditionValue>,
) {
    private var pos = 0

    fun evaluate(): Boolean {
        skipSpaces()
        if (pos >= expression.length) throw ConditionSyntaxException("CONDITION_EMPTY", "条件表达式为空")
        val value = parseOr()
        skipSpaces()
        if (pos < expression.length) {
            throw ConditionSyntaxException("CONDITION_TRAILING_INPUT", "存在多余字符: '${expression.substring(pos)}'")
        }
        return truthy(value)
    }

    private fun parseOr(): ConditionValue {
        var left = parseAnd()
        while (consume("||")) {
            val right = parseAnd()
            left = ConditionValue.Bool(truthy(left) || truthy(right))
        }
        return left
    }

    private fun parseAnd(): ConditionValue {
        var left = parseComparison()
        while (consume("&&")) {
            val right = parseComparison()
            left = ConditionValue.Bool(truthy(left) && truthy(right))
        }
        return left
    }

    private fun parseComparison(): ConditionValue {
        val left = parseUnary()
        skipSpaces()
        val operator = currentComparisonOperator() ?: return left
        pos += operator.length
        val right = parseUnary()
        return ConditionValue.Bool(compare(operator, left, right))
    }

    private fun parseUnary(): ConditionValue {
        skipSpaces()
        if (expression.startsWith("!", pos) && !expression.startsWith("!=", pos)) {
            pos++
            return ConditionValue.Bool(!truthy(parseUnary()))
        }
        if (expression.startsWith("(", pos)) {
            pos++
            val inner = parseOr()
            skipSpaces()
            if (!expression.startsWith(")", pos)) throw ConditionSyntaxException("CONDITION_UNBALANCED", "缺少右括号")
            pos++
            return inner
        }
        return parsePrimary()
    }

    private fun parsePrimary(): ConditionValue {
        skipSpaces()
        if (pos >= expression.length) throw ConditionSyntaxException("CONDITION_UNEXPECTED_END", "表达式意外结束")
        val ch = expression[pos]
        if (ch == '"' || ch == '\'') return ConditionValue.Str(readQuoted(ch))
        if (ch.isDigit() || (ch == '-' && pos + 1 < expression.length && expression[pos + 1].isDigit())) {
            return ConditionValue.Num(readNumber())
        }
        if (ch.isLetter() || ch == '_') {
            val ident = readIdentifier()
            variables[ident]?.let { return it }
            return when (ident.lowercase()) {
                "true" -> ConditionValue.Bool(true)
                "false" -> ConditionValue.Bool(false)
                else -> ConditionValue.Str(ident)
            }
        }
        throw ConditionSyntaxException("CONDITION_UNEXPECTED_CHAR", "无法识别的字符 '$ch'")
    }

    private fun compare(operator: String, left: ConditionValue, right: ConditionValue): Boolean = when (operator) {
        "==" -> equalsValue(left, right)
        "!=" -> !equalsValue(left, right)
        ">", ">=", "<", "<=" -> {
            val a = asNumber(left)
            val b = asNumber(right)
            when (operator) {
                ">" -> a > b
                ">=" -> a >= b
                "<" -> a < b
                else -> a <= b
            }
        }
        else -> throw ConditionSyntaxException("CONDITION_BAD_OPERATOR", "不支持的运算符 '$operator'")
    }

    private fun equalsValue(left: ConditionValue, right: ConditionValue): Boolean {
        val leftNumber = numericOrNull(left)
        val rightNumber = numericOrNull(right)
        if (leftNumber != null && rightNumber != null) return leftNumber == rightNumber
        if (left is ConditionValue.Bool && right is ConditionValue.Bool) return left.value == right.value
        return scalarText(left) == scalarText(right)
    }

    private fun truthy(value: ConditionValue): Boolean = when (value) {
        is ConditionValue.Bool -> value.value
        is ConditionValue.Num -> value.value != 0L
        is ConditionValue.Str -> value.value.isNotEmpty() && !value.value.equals("false", ignoreCase = true)
    }

    private fun asNumber(value: ConditionValue): Long = when (value) {
        is ConditionValue.Num -> value.value
        is ConditionValue.Str -> value.value.trim().toLongOrNull()
            ?: throw ConditionSyntaxException("CONDITION_TYPE_ERROR", "'${value.value}' 不是数字，无法参与大小比较")
        is ConditionValue.Bool -> throw ConditionSyntaxException("CONDITION_TYPE_ERROR", "布尔值无法参与大小比较")
    }

    private fun numericOrNull(value: ConditionValue): Long? = when (value) {
        is ConditionValue.Num -> value.value
        is ConditionValue.Str -> value.value.trim().toLongOrNull()
        is ConditionValue.Bool -> null
    }

    private fun scalarText(value: ConditionValue): String = when (value) {
        is ConditionValue.Bool -> value.value.toString()
        is ConditionValue.Num -> value.value.toString()
        is ConditionValue.Str -> value.value
    }

    private fun currentComparisonOperator(): String? {
        val candidates = listOf("==", "!=", ">=", "<=", ">", "<")
        return candidates.firstOrNull { expression.startsWith(it, pos) }
    }

    private fun consume(token: String): Boolean {
        skipSpaces()
        if (expression.startsWith(token, pos)) {
            pos += token.length
            return true
        }
        return false
    }

    private fun skipSpaces() {
        while (pos < expression.length && expression[pos].isWhitespace()) pos++
    }

    private fun readIdentifier(): String {
        val start = pos
        pos++
        while (pos < expression.length && (expression[pos].isLetterOrDigit() || expression[pos] == '_')) pos++
        return expression.substring(start, pos)
    }

    private fun readNumber(): Long {
        val start = pos
        if (expression[pos] == '-') pos++
        while (pos < expression.length && expression[pos].isDigit()) pos++
        return expression.substring(start, pos).toLongOrNull()
            ?: throw ConditionSyntaxException("CONDITION_BAD_NUMBER", "无法解析数字 '${expression.substring(start, pos)}'")
    }

    private fun readQuoted(quote: Char): String {
        pos++
        val builder = StringBuilder()
        while (pos < expression.length) {
            val ch = expression[pos]
            if (ch == '\\' && pos + 1 < expression.length) {
                val escaped = expression[pos + 1]
                builder.append(if (escaped == 'n') '\n' else if (escaped == 't') '\t' else escaped)
                pos += 2
                continue
            }
            if (ch == quote) {
                pos++
                return builder.toString()
            }
            builder.append(ch)
            pos++
        }
        throw ConditionSyntaxException("CONDITION_UNTERMINATED_STRING", "字符串缺少结束引号")
    }
}
