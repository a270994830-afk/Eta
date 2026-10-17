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
        shadowTools: Boolean = false,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = true),
    ): JSONArray =
        capabilities.project(JSONArray().also { tools ->
            AgentContextAppToolCatalog.appendTo(tools)
            AgentGestureToolCatalog.appendTo(tools)
            AgentFlowToolCatalog.appendTo(tools)
            AgentTextSystemToolCatalog.appendTo(tools)
            AgentDeviceToolCatalog.appendTo(
                tools,
                directTools = deviceDirectTools,
                sensitiveReadTools = deviceSensitiveReadTools,
                sensitiveActionTools = deviceSensitiveActionTools,
            )
            if (browserTools) AgentBrowserToolCatalog.appendTo(tools)
            if (shadowTools) AgentShadowToolCatalog.appendTo(tools)
            AgentSkillToolCatalog.appendTo(
                tools,
                githubDiscovery = skillGitHubDiscovery,
                githubInstall = skillGitHubInstall,
            )
            if (memoryTools) AgentMemoryToolCatalog.appendTo(tools, writable = memoryWritable)
            AgentAdminToolCatalog.appendTo(tools)
            AgentSessionToolCatalog.appendTo(tools)
            AgentMediaToolCatalog.appendTo(tools)
            AgentIntegrationToolCatalog.appendTo(tools)
            AgentPreferenceToolCatalog.appendTo(tools)
            AgentWorkflowToolCatalog.appendTo(tools)
            AgentBluetoothToolCatalog.appendTo(tools)
            AgentToolPackToolCatalog.appendTo(tools)
            AgentUpdateToolCatalog.appendTo(tools)
            AgentRecordingToolCatalog.appendTo(tools)
            AgentShizukuToolCatalog.appendTo(tools)
            AgentPrivilegedToolCatalog.appendTo(tools)
            AgentPetToolCatalog.appendTo(tools)
            AgentAppUiDocToolCatalog.appendTo(tools)
            // 插件包动态工具（tool_packs/*.json）：解析失败不影响内置目录
            runCatching {
                val packTools = io.github.mangi.eta.agent.tool.AgentToolPackManager.toolDefinitions()
                for (index in 0 until packTools.length()) {
                    tools.put(packTools.opt(index))
                }
            }
            if (terminalTools) {
                AgentFileVisionToolCatalog.appendTo(tools)
                AgentTerminalToolCatalog.appendTo(tools)
                AgentFileToolCatalog.appendTo(tools)
            }
        })
}
