package io.github.mangi.eta.ui.screens.mcp

import io.github.mangi.eta.R
import java.net.URLEncoder

/**
 * 常用生活服务 MCP 的「快速添加」预设。
 *
 * endpointTemplate 的 URL 形态来自各服务官方接入文档（滴滴 MCP 平台 / 高德 MCP 文档），
 * 以官方最新说明为准；本预设只负责把 Key 拼进地址，不做任何网络预检。
 */
internal data class McpServerPreset(
    val key: String,
    val nameRes: Int,
    val descriptionRes: Int,
    val endpointTemplate: String,
    val docsUrl: String,
)

internal data class McpStdioPreset(
    val label: String,
    val command: String,
)

internal object McpServerPresets {

    const val KEY_PLACEHOLDER = "{key}"

    /**
     * stdio 本地服务器示例：在「自定义」里点一下即可把命令填进地址框。
     * 命令会在设备上用 `sh -c` 启动，需设备 PATH 中已有对应运行时（Node/Python 等）。
     */
    val STDIO_EXAMPLES: List<McpStdioPreset> = listOf(
        McpStdioPreset(
            label = "文件系统",
            command = "stdio://npx -y @modelcontextprotocol/server-filesystem /sdcard",
        ),
        McpStdioPreset(
            label = "记忆",
            command = "stdio://npx -y @modelcontextprotocol/server-memory",
        ),
    )

    val LIST: List<McpServerPreset> = listOf(
        McpServerPreset(
            key = "didi",
            nameRes = R.string.mcp_preset_didi_name,
            descriptionRes = R.string.mcp_preset_didi_desc,
            endpointTemplate = "https://mcp.didichuxing.com/mcp-servers?key=$KEY_PLACEHOLDER",
            docsUrl = "https://mcp.didichuxing.com",
        ),
        McpServerPreset(
            key = "amap",
            nameRes = R.string.mcp_preset_amap_name,
            descriptionRes = R.string.mcp_preset_amap_desc,
            endpointTemplate = "https://mcp.amap.com/mcp?key=$KEY_PLACEHOLDER",
            docsUrl = "https://lbs.amap.com/api/mcp-server/summary",
        ),
    )

    fun buildUrl(preset: McpServerPreset, apiKey: String): String =
        preset.endpointTemplate.replace(KEY_PLACEHOLDER, URLEncoder.encode(apiKey, "UTF-8"))
}
