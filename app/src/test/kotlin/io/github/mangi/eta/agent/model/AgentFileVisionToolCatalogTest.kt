package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFileVisionToolCatalogTest {
    @Test
    fun readImageFollowsFileToolsInsteadOfPersonalDataReads() {
        assertFalse("read_image" in names(terminalTools = false, sensitiveReads = true))
        assertTrue("read_image" in names(terminalTools = true, sensitiveReads = false))
        assertTrue(readImageDescription().contains("同一轮最多调用一次"))
        assertTrue(readImageDescription().contains("下一轮读取下一张"))
    }

    @Test
    fun readImageSchemaSupportsBoundedBatch() {
        assertTrue(readImageDescription().contains("最多 3 张"))
        val properties = readImageFunction()
            .getJSONObject("parameters")
            .getJSONObject("properties")
        assertTrue(properties.has("path"))
        val paths = properties.getJSONObject("paths")
        assertEquals("array", paths.getString("type"))
        assertEquals(3, paths.getInt("maxItems"))
        assertEquals(1, paths.getInt("minItems"))
        assertTrue(paths.getBoolean("uniqueItems"))
    }

    private fun readImageFunction(): JSONObject {
        val tools = AgentToolCatalog.build(terminalTools = true, browserTools = false)
        return (0 until tools.length())
            .map { tools.getJSONObject(it).getJSONObject("function") }
            .first { it.getString("name") == "read_image" }
    }

    private fun readImageDescription(): String = readImageFunction().getString("description")

    private fun names(terminalTools: Boolean, sensitiveReads: Boolean): Set<String> =
        AgentToolCatalog.build(
            terminalTools = terminalTools,
            browserTools = false,
            deviceDirectTools = false,
            deviceSensitiveReadTools = sensitiveReads,
            deviceSensitiveActionTools = false,
        ).toolNames()

    private fun JSONArray.toolNames(): Set<String> =
        (0 until length()).mapTo(mutableSetOf()) { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }
}
