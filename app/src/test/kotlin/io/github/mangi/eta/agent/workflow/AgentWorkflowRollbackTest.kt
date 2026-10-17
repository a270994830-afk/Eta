package io.github.mangi.eta.agent.workflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 失败补偿（saga 回滚）的执行计划：逆序 + 跳过无补偿的步骤。 */
class AgentWorkflowRollbackTest {

    private fun compensation(tool: String) = AgentWorkflowStore.Compensation(
        tool = tool,
        prompt = null,
        args = JSONObject(),
    )

    @Test
    fun planRunsCompensationsInReverseOrderOfExecution() {
        val compensations = listOf(compensation("a"), compensation("b"), compensation("c"))
        val plan = AgentWorkflowStore.rollbackPlan(listOf(0, 1, 2), compensations)
        assertEquals(listOf(2, 1, 0), plan)
    }

    @Test
    fun stepsWithoutCompensationAreSkipped() {
        val compensations = listOf(compensation("a"), null, compensation("c"))
        assertEquals(listOf(2, 0), AgentWorkflowStore.rollbackPlan(listOf(0, 1, 2), compensations))
    }

    @Test
    fun onlyExecutedStepsAreCompensated() {
        val compensations = listOf(compensation("a"), compensation("b"), compensation("c"))
        // 第 2 步因 condition 未执行（或被分支跳过），不应回滚
        assertEquals(listOf(1, 0), AgentWorkflowStore.rollbackPlan(listOf(0, 1), compensations))
    }

    @Test
    fun outOfRangeExecutedIndexIsIgnored() {
        val compensations = listOf(compensation("a"))
        assertEquals(listOf(0), AgentWorkflowStore.rollbackPlan(listOf(0, 7), compensations))
    }

    @Test
    fun emptyPlanWhenNothingExecuted() {
        assertTrue(AgentWorkflowStore.rollbackPlan(emptyList(), listOf(compensation("a"))).isEmpty())
    }

    @Test
    fun compensationJsonRoundTripKeepsToolOrPrompt() {
        val toolSide = AgentWorkflowStore.Compensation.fromJson(JSONObject().put("tool", "file_delete").put("args", JSONObject().put("path", "/tmp/x")))
        assertEquals("tool", toolSide?.kind())
        assertEquals("file_delete", toolSide?.name())
        val promptSide = AgentWorkflowStore.Compensation.fromJson(JSONObject().put("prompt", "把刚才的改动撤回"))
        assertEquals("prompt", promptSide?.kind())
        assertEquals("把刚才的改动撤回", promptSide?.name())
        assertEquals(null, AgentWorkflowStore.Compensation.fromJson(JSONObject()))
    }
}
