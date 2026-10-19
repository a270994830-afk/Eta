package io.github.mangi.eta.agent.tool

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolSearchCompactTest {

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
    fun compactKeepsCoreToolsAndAddsSearchTool() {
        val catalog = JSONArray()
            .put(tool("read_file"))
            .put(tool("mcp_calendar"))
            .put(tool("browser_open"))

        val compacted = AgentToolSearch.compact(catalog)
        val result = names(compacted)

        assertTrue(result.contains("read_file"))
        assertTrue(result.contains("search_tools"))
        assertFalse(result.contains("mcp_calendar"))
        assertFalse(result.contains("browser_open"))
    }

    @Test
    fun compactDoesNotDuplicateSearchTool() {
        val catalog = JSONArray()
            .put(tool("search_tools"))
            .put(tool("write_file"))

        val result = names(AgentToolSearch.compact(catalog))

        assertEquals(1, result.count { it == "search_tools" })
        assertTrue(result.contains("write_file"))
    }

    @Test
    fun compactFallsBackWhenNoCoreToolMatches() {
        val catalog = JSONArray()
            .put(tool("mcp_calendar"))
            .put(tool("browser_open"))

        assertSame(catalog, AgentToolSearch.compact(catalog))
    }
}
