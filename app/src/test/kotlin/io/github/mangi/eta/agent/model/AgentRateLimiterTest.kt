package io.github.mangi.eta.agent.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentRateLimiterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        AgentRateLimiter.attachStorageDir(tmp.newFolder())
        AgentRateLimiter.resetForTests()
        AgentRateLimiter.limitOverride = { 0 }
    }

    @After
    fun tearDown() {
        AgentRateLimiter.limitOverride = null
        AgentRateLimiter.resetForTests()
    }

    @Test
    fun zeroLimitMeansUnlimited() {
        repeat(50) { AgentRateLimiter.checkOrThrow("unit-test-provider") }
        assertEquals(0, AgentRateLimiter.limitPerMinute())
    }

    @Test
    fun limitBlocksAfterThreshold() {
        AgentRateLimiter.limitOverride = { 3 }
        AgentRateLimiter.checkOrThrow("unit-test-provider")
        AgentRateLimiter.checkOrThrow("unit-test-provider")
        AgentRateLimiter.checkOrThrow("unit-test-provider")
        try {
            AgentRateLimiter.checkOrThrow("unit-test-provider")
            fail("第 4 次应触发本地限流")
        } catch (failure: AgentModelFailure) {
            assertEquals("RATE_LIMITED", failure.code)
            assertTrue(failure.retryable)
        }
    }

    @Test
    fun differentProvidersHaveSeparateWindows() {
        AgentRateLimiter.limitOverride = { 1 }
        AgentRateLimiter.checkOrThrow("provider-a")
        AgentRateLimiter.checkOrThrow("provider-b")
        val status = AgentRateLimiter.statusJson()
        assertEquals(true, status.getBoolean("ok"))
        assertTrue(status.getJSONObject("providers").has("provider-a"))
        assertTrue(status.getJSONObject("providers").has("provider-b"))
        assertEquals(1, status.getJSONObject("providers").getJSONObject("provider-a").getInt("used"))
    }
}
