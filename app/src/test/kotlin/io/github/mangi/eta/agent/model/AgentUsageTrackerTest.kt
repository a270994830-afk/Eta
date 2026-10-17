package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentUsageTrackerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        AgentUsageTracker.attachStorageDir(tmp.newFolder())
    }

    @Test
    fun recordAccumulatesTokensAndRequests() {
        AgentUsageTracker.record("deepseek-v4-flash", 100, 40, 10)
        AgentUsageTracker.record("deepseek-v4-flash", 50, 20, 0)

        val totals = AgentUsageTracker.snapshot(7).getJSONObject("recent_totals")
        assertEquals(150, totals.getInt("prompt_tokens"))
        assertEquals(70, totals.getInt("completion_tokens"))
        assertEquals(220, totals.getInt("total_tokens"))
        assertEquals(2, totals.getInt("requests"))
    }

    @Test
    fun recordSkipsEmptyUsage() {
        AgentUsageTracker.record("m", 0, 0, 0)
        assertEquals(0, AgentUsageTracker.snapshot(7).getJSONObject("recent_totals").getInt("requests"))
    }

    @Test
    fun perModelBreakdownIsRecorded() {
        AgentUsageTracker.record("model-a", 10, 5, 0)
        AgentUsageTracker.record("model-b", 20, 1, 0)
        val days = AgentUsageTracker.snapshot(7).getJSONObject("days")
        val dayKey = days.keys().asSequence().first()
        val byModel = days.getJSONObject(dayKey).getJSONObject("by_model")
        assertTrue(byModel.has("model-a"))
        assertTrue(byModel.has("model-b"))
        assertEquals(10, byModel.getJSONObject("model-a").getInt("prompt_tokens"))
        assertEquals(20, byModel.getJSONObject("model-b").getInt("prompt_tokens"))
    }

    @Test
    fun monthBucketCarriesCurrentMonthLabel() {
        AgentUsageTracker.record("m", 7, 3, 0)
        val month = AgentUsageTracker.snapshot(7).getJSONObject("month")
        assertEquals(10, month.getInt("total_tokens"))
        assertTrue(month.getString("month").matches(Regex("\\d{4}-\\d{2}")))
    }
}
