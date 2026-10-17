package io.github.mangi.eta.agent.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工作流真分支的跳转判定（纯函数，不依赖 Android / JSON）。 */
class AgentWorkflowBranchTest {

    @Test
    fun alwaysJumpsRegardlessOfOutcome() {
        assertEquals(3, AgentWorkflowStore.branchTarget("3", AgentWorkflowStore.GOTO_WHEN_ALWAYS, ok = true, stepCount = 5))
        assertEquals(3, AgentWorkflowStore.branchTarget("3", AgentWorkflowStore.GOTO_WHEN_ALWAYS, ok = false, stepCount = 5))
    }

    @Test
    fun failOnlyJumpsWhenStepFailed() {
        assertEquals(2, AgentWorkflowStore.branchTarget("2", AgentWorkflowStore.GOTO_WHEN_FAIL, ok = false, stepCount = 4))
        assertNull(AgentWorkflowStore.branchTarget("2", AgentWorkflowStore.GOTO_WHEN_FAIL, ok = true, stepCount = 4))
    }

    @Test
    fun okOnlyJumpsWhenStepSucceeded() {
        assertEquals(1, AgentWorkflowStore.branchTarget("1", AgentWorkflowStore.GOTO_WHEN_OK, ok = true, stepCount = 4))
        assertNull(AgentWorkflowStore.branchTarget("1", AgentWorkflowStore.GOTO_WHEN_OK, ok = false, stepCount = 4))
    }

    @Test
    fun blankOrNonIntegerTargetDoesNotJump() {
        assertNull(AgentWorkflowStore.branchTarget("", AgentWorkflowStore.GOTO_WHEN_ALWAYS, ok = true, stepCount = 4))
        assertNull(AgentWorkflowStore.branchTarget("   ", AgentWorkflowStore.GOTO_WHEN_ALWAYS, ok = true, stepCount = 4))
        assertNull(AgentWorkflowStore.branchTarget("step2", AgentWorkflowStore.GOTO_WHEN_ALWAYS, ok = true, stepCount = 4))
    }

    @Test
    fun outOfRangeTargetDoesNotJump() {
        assertNull(AgentWorkflowStore.branchTarget("-1", AgentWorkflowStore.GOTO_WHEN_ALWAYS, ok = true, stepCount = 4))
        assertNull(AgentWorkflowStore.branchTarget("4", AgentWorkflowStore.GOTO_WHEN_ALWAYS, ok = true, stepCount = 4))
    }

    @Test
    fun targetIsTrimmedAndZeroIsValid() {
        assertEquals(0, AgentWorkflowStore.branchTarget(" 0 ", AgentWorkflowStore.GOTO_WHEN_ALWAYS, ok = true, stepCount = 1))
    }

    @Test
    fun unknownGotoWhenFallsBackToAlways() {
        assertEquals(2, AgentWorkflowStore.branchTarget("2", "whatever", ok = false, stepCount = 3))
        assertTrue(AgentWorkflowStore.gotoMatches("whatever", ok = false))
    }

    @Test
    fun gotoMatchesDecidesBranchGating() {
        assertTrue(AgentWorkflowStore.gotoMatches(AgentWorkflowStore.GOTO_WHEN_FAIL, ok = false))
        assertFalse(AgentWorkflowStore.gotoMatches(AgentWorkflowStore.GOTO_WHEN_FAIL, ok = true))
        assertTrue(AgentWorkflowStore.gotoMatches(AgentWorkflowStore.GOTO_WHEN_OK, ok = true))
        assertFalse(AgentWorkflowStore.gotoMatches(AgentWorkflowStore.GOTO_WHEN_OK, ok = false))
    }

    @Test
    fun normalizeHelpersAreTheSingleSourceOfTruthForParsing() {
        assertEquals("", AgentWorkflowStore.normalizeGoto(null))
        assertEquals("3", AgentWorkflowStore.normalizeGoto(" 3 "))
        assertEquals(AgentWorkflowStore.GOTO_WHEN_ALWAYS, AgentWorkflowStore.normalizeGotoWhen(null))
        assertEquals(AgentWorkflowStore.GOTO_WHEN_FAIL, AgentWorkflowStore.normalizeGotoWhen(" fail "))
        assertEquals(AgentWorkflowStore.GOTO_WHEN_ALWAYS, AgentWorkflowStore.normalizeGotoWhen("nonsense"))
    }
}
