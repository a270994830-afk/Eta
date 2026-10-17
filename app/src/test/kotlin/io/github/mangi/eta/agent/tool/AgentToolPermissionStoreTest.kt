package io.github.mangi.eta.agent.tool

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentToolPermissionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        AgentToolPermissionStore.attachStorageDir(tmp.newFolder())
        AgentToolPermissionStore.setPolicy(null, AgentToolPermissionStore.POLICY_ALLOW)
        AgentToolPermissionStore.setPolicy("unit_test_dummy_tool", AgentToolPermissionStore.POLICY_ALLOW)
    }

    @Test
    fun defaultPolicyIsAllowAndToolsPass() {
        assertEquals(AgentToolPermissionStore.POLICY_ALLOW, AgentToolPermissionStore.defaultPolicy())
        assertNull(AgentToolPermissionStore.checkError("unit_test_dummy_tool"))
    }

    @Test
    fun forbidBlocksExecution() {
        assertTrue(AgentToolPermissionStore.setPolicy("unit_test_dummy_tool", AgentToolPermissionStore.POLICY_FORBID))
        val error = AgentToolPermissionStore.checkError("unit_test_dummy_tool")
        assertNotNull(error)
        val json = JSONObject(error!!)
        assertEquals(false, json.getBoolean("ok"))
        assertEquals("TOOL_FORBIDDEN", json.getString("code"))
    }

    @Test
    fun askRequiresApprovalThenAllows() {
        assertTrue(AgentToolPermissionStore.setPolicy("unit_test_dummy_tool", AgentToolPermissionStore.POLICY_ASK))
        val error = AgentToolPermissionStore.checkError("unit_test_dummy_tool")
        assertNotNull(error)
        assertEquals("TOOL_NEEDS_APPROVAL", JSONObject(error!!).getString("code"))

        assertTrue(AgentToolPermissionStore.setPolicy("unit_test_dummy_tool", AgentToolPermissionStore.POLICY_ALLOW))
        assertNull(AgentToolPermissionStore.checkError("unit_test_dummy_tool"))
    }

    @Test
    fun invalidPolicyIsRejected() {
        assertEquals(false, AgentToolPermissionStore.setPolicy("unit_test_dummy_tool", "随便写"))
    }

    @Test
    fun defaultPolicyChangeAppliesToAllTools() {
        assertTrue(AgentToolPermissionStore.setPolicy(null, AgentToolPermissionStore.POLICY_FORBID))
        assertEquals(
            "TOOL_FORBIDDEN",
            JSONObject(AgentToolPermissionStore.checkError("some_random_tool")!!).getString("code"),
        )
        AgentToolPermissionStore.setPolicy(null, AgentToolPermissionStore.POLICY_ALLOW)
        assertNull(AgentToolPermissionStore.checkError("some_random_tool"))
    }
}
