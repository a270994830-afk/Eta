package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentWorkflowToolSet
import org.json.JSONArray
import org.json.JSONObject

/** 工作流编排（多步 / 条件 / 定时）工具 schema。 */
internal object AgentWorkflowToolCatalog {

    val TOOL_NAMES: Set<String> = AgentWorkflowToolSet.TOOL_NAMES

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "workflow_create",
                    description = "创建（或按同名覆盖）一条可复用的工作流：由多个步骤组成，每个步骤是调用某个工具，或向模型发起一段提示。适合多步且步骤相对固定的自动化（如先打开某 App、再逐项填写、最后提交）。创建后可 workflow_run 一键运行、workflow_schedule 挂定时。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("name", JSONObject().put("type", "string").put("description", "工作流名，简短可复用；同名视为更新。"))
                                .put("description", JSONObject().put("type", "string").put("description", "用途说明。"))
                                .put(
                                    "steps",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("description", "步骤数组（最多 100 步）。每步二选一：{\"tool\":\"tap_text\",\"args\":{\"text\":\"确定\"}} 调用工具，或 {\"prompt\":\"总结今天的通知\"} 让模型处理。可用 condition 控制该步是否执行，on_error 控制失败策略（stop / continue / retry），goto + goto_when 做跳转（if/else 与循环），rollback 做失败补偿。")
                                        .put(
                                            "items",
                                            JSONObject()
                                                .put("type", "object")
                                                .put(
                                                    "properties",
                                                    JSONObject()
                                                        .put("tool", JSONObject().put("type", "string").put("description", "要调用的工具名。与 prompt 二选一。"))
                                                        .put("prompt", JSONObject().put("type", "string").put("description", "交给模型执行的提示文本。与 tool 二选一。"))
                                                        .put("args", JSONObject().put("type", "object").put("description", "工具入参对象；prompt 步可省略。"))
                                                        .put("on_error", JSONObject().put("type", "string").put("enum", JSONArray().put("stop").put("continue").put("retry")).put("description", "失败策略：stop=失败即中止（默认），continue=失败继续下一步，retry=失败后按 retries / retry_delay_ms 重试该步，仍失败再按 stop 处理（中止并回填 next_step）。"))
                                                        .put("condition", JSONObject().put("type", "string").put("description", "可选执行条件，留空表示总执行。变量：last_ok / last_failed（布尔）、last_text（上一步结果文本，截断 2000 字）、index / step_count（整数）；运算：== != > >= < <= && || ! 与括号，字符串比较用 == / != 且引号可省。例：last_ok、index >= 2、(last_ok || last_failed) && step_count > 1、last_text == 成功。求值失败时按 fail-open（执行该步）处理并在结果里给出 condition_error。"))
                                                        .put("retries", JSONObject().put("type", "integer").put("description", "on_error=retry 时的重试次数，1-3，默认 1。"))
                                                        .put("retry_delay_ms", JSONObject().put("type", "integer").put("description", "on_error=retry 时的重试间隔毫秒，默认 500，上限 5000。"))
                                                        .put("goto", JSONObject().put("type", "string").put("description", "可选的跳转目标：步序（0 基）十进制数字。命中 goto_when 时改为执行该步（顺序、向后、向前都可），配上 condition 能做 if/else 与循环。非整数或越界则不跳转，并在步骤结果里给 goto_ignored。"))
                                                        .put("goto_when", JSONObject().put("type", "string").put("enum", JSONArray().put("always").put("ok").put("fail")).put("description", "何时跳转：always=总是（默认），ok=该步成功时，fail=该步失败时（失败分支优先于 on_error=stop 的中止）。"))
                                                        .put("rollback", JSONObject().put("type", "object").put("description", "可选补偿动作（saga 回滚）：{tool, args} 或 {prompt}，同构于步骤。当流程因失败/循环保护中止时，已执行步骤的补偿按逆序依次执行；单个补偿失败不影响其余补偿继续执行。").put("properties", JSONObject().put("tool", JSONObject().put("type", "string")).put("args", JSONObject().put("type", "object")).put("prompt", JSONObject().put("type", "string"))))
                                                ),
                                        )
                                )
                                .put(
                                    "trigger",
                                    JSONObject()
                                        .put("type", "object")
                                        .put("description", "可选触发定义，仅记录；定时触发请用 workflow_schedule。")
                                        .put(
                                            "properties",
                                            JSONObject()
                                                .put("type", JSONObject().put("type", "string").put("enum", JSONArray().put("manual").put("interval").put("time")))
                                                .put("value", JSONObject().put("type", "string").put("description", "触发值，如 manual 留空、interval 为分钟数、time 为 HH:MM。"))
                                        )
                                )
                        )
                        .put("required", JSONArray().put("name").put("steps"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "workflow_list",
                    description = "列出已保存的所有工作流及其步数、上次运行状态。运行前先看这里确认名称。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "workflow_get",
                    description = "读取一条工作流的完整定义（含每步的工具/prompt、条件与失败策略）。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("name", JSONObject().put("type", "string").put("description", "工作流名。"))
                                .put("id", JSONObject().put("type", "string").put("description", "工作流 id（与 name 二选一）。"))
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "workflow_delete",
                    description = "删除一条工作流，并移除它对应的时间定时。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("name", JSONObject().put("type", "string").put("description", "工作流名。"))
                                .put("id", JSONObject().put("type", "string").put("description", "工作流 id（与 name 二选一）。"))
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "workflow_run",
                    description = "按序执行一条工作流。tool 步用 args 调真实工具，prompt 步交给模型处理；每步满足 condition 才执行，on_error=stop 时失败即停并返回已完成步骤。失败后可用 from_step 从失败步续跑。步骤可带 goto（0 基步序）+ goto_when（always/ok/fail）实现真分支：失败跳转到恢复分支、成功后跳过 else 段、或向前跳转做循环（单次运行最多执行 200 步，超出以 stopped_reason=loop_guard 中止）。步骤可带 rollback 补偿动作：流程中止时已执行步骤的补偿按逆序执行，结果在 rollbacks 里。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("name", JSONObject().put("type", "string").put("description", "工作流名。"))
                                .put("id", JSONObject().put("type", "string").put("description", "工作流 id（与 name 二选一）。"))
                                .put("from_step", JSONObject().put("type", "integer").put("description", "从第几步（0 起）开始，默认 0；续跑时填上次返回的 next_step。"))
                                .put("dry_run", JSONObject().put("type", "boolean").put("description", "为 true 时只列出将执行的步骤，不真正执行。"))
                                .put("rollback", JSONObject().put("type", "boolean").put("description", "为 false 时不执行补偿动作（默认 true）。排查问题时可用它跳过回滚。"))
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "workflow_schedule",
                    description = "把一条工作流挂到每天固定时分自动运行（写入系统定时任务，到点自动执行「执行工作流 <名字>」）。同名流程重复挂会覆盖旧的定时。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("name", JSONObject().put("type", "string").put("description", "工作流名。"))
                                .put("id", JSONObject().put("type", "string").put("description", "工作流 id（与 name 二选一）。"))
                                .put("hour", JSONObject().put("type", "integer").put("description", "小时 0-23。"))
                                .put("minute", JSONObject().put("type", "integer").put("description", "分钟 0-59。"))
                                .put("time", JSONObject().put("type", "string").put("description", "时间 HH:MM；与 hour/minute 二选一。"))
                                .put("enabled", JSONObject().put("type", "boolean").put("description", "是否立即启用，默认 true。"))
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "workflow_unschedule",
                    description = "移除一条工作流对应的时间定时任务（不影响流程定义本身）。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("name", JSONObject().put("type", "string").put("description", "工作流名。"))
                                .put("id", JSONObject().put("type", "string").put("description", "工作流 id（与 name 二选一）。"))
                        )
                )
            )
    }
}
