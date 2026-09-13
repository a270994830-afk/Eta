package io.github.mangi.eta.agent.model

import org.json.JSONArray
import io.github.mangi.eta.agent.tool.AgentToolCapabilities

/** 声明模型可见的工具及其 JSON Schema；不包含任何执行逻辑。 */
internal object AgentToolCatalog {
    fun build(
        terminalTools: Boolean,
        browserTools: Boolean,
        deviceDirectTools: Boolean = true,
        deviceSensitiveReadTools: Boolean = false,
        deviceSensitiveActionTools: Boolean = false,
        skillGitHubDiscovery: Boolean = false,
        skillGitHubInstall: Boolean = false,
        memoryTools: Boolean = false,
        memoryWritable: Boolean = true,
        skillTools: Boolean = true,
        toolsEnabled: Boolean = true,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = true),
    ): JSONArray {
        // 本地小模型（本机/内网推理服务）的 prompt 预算很小：直接不声明任何工具，
        // 避免工具 schema 占满上下文导致请求超限或处理极慢。
        if (!toolsEnabled) return JSONArray()
        return capabilities.project(JSONArray().also { tools ->
            AgentContextAppToolCatalog.appendTo(tools)
            AgentGestureToolCatalog.appendTo(tools)
            AgentTextSystemToolCatalog.appendTo(tools)
            AgentDeviceToolCatalog.appendTo(
                tools,
                directTools = deviceDirectTools,
                sensitiveReadTools = deviceSensitiveReadTools,
                sensitiveActionTools = deviceSensitiveActionTools,
            )
            if (browserTools) AgentBrowserToolCatalog.appendTo(tools)
            if (skillTools) {
                AgentSkillToolCatalog.appendTo(
                    tools,
                    githubDiscovery = skillGitHubDiscovery,
                    githubInstall = skillGitHubInstall,
                )
            }
            if (memoryTools) AgentMemoryToolCatalog.appendTo(tools, writable = memoryWritable)
            if (terminalTools) {
                AgentFileVisionToolCatalog.appendTo(tools)
                AgentTerminalToolCatalog.appendTo(tools)
            }
        })
    }
}
