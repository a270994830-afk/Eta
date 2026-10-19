package io.github.mangi.eta.agent.tool

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolSearchUnlockTest {

    private fun tool(name: String): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", name)
                .put("parameters", JSONObject().put("type", "object")),
        )

    private fun names(catalog: JSONArray): List<String> =
        (0 until catalog.length()).map {
            catalog.getJSONObject(it).getJSONObject("function").getString("name")
        }

    @Test
    fun unlockAddsHitToRunTools() {
        val target = JSONArray().put(tool("search_tools"))
        val hits = JSONArray().put(tool("shadow_status"))

        AgentToolSearch.unlock(target, hits)

        assertEquals(listOf("search_tools", "shadow_status"), names(target))
    }

    @Test
    fun unlockKeepsCoreToolsOutOfRunTools() {
        val target = JSONArray()
        val hits = JSONArray().put(tool("read_file")).put(tool("shadow_open"))

        AgentToolSearch.unlock(target, hits)

        assertEquals(listOf("shadow_open"), names(target))
    }

    @Test
    fun unlockIsIdempotent() {
        val target = JSONArray()
        val hits = JSONArray().put(tool("shadow_status"))

        AgentToolSearch.unlock(target, hits)
        AgentToolSearch.unlock(target, hits)

        assertEquals(1, names(target).count { it == "shadow_status" })
    }

    @Test
    fun hitAndRunToolStayUnique() {
        val target = JSONArray().put(tool("shadow_status"))
        val hits = JSONArray().put(tool("shadow_status"))

        AgentToolSearch.unlock(target, hits)

        assertEquals(1, target.length())
        assertTrue(names(target).contains("shadow_status"))
        assertFalse(names(target).contains("shadow_open"))
    }
}
