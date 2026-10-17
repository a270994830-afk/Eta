package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.repository.McpServerRepository

internal object McpServerManager {
    /** stdio:// 前缀表示本地 stdio MCP 服务器（HTTP/SSE 之外的传输）。 */
    const val STDIO_PREFIX = "stdio://"
    fun discover(
        server: McpServerSetting,
        bearerToken: String?,
        customHeaders: Map<String, String> = emptyMap(),
    ): McpServerSetting {
        if (server.url.startsWith(STDIO_PREFIX)) {
            val discovery = McpStdioSession.of(server.url).use { it.discoverTools() }
            val availableNames = discovery.tools.mapTo(mutableSetOf()) { it.name }
            return server.copy(
                tools = discovery.tools,
                enabledToolNames = server.enabledToolNames.intersect(availableNames),
                lastRefreshedAt = System.currentTimeMillis(),
                lastProtocolVersion = discovery.protocolVersion,
                toolsExpireAt = null,
            )
        }
        return McpHttpClient(server, bearerToken, customHeaders).use { client ->
        val discovery = client.discoverTools()
        val availableNames = discovery.tools.mapTo(mutableSetOf()) { it.name }
        val refreshedAt = System.currentTimeMillis()
        server.copy(
            tools = discovery.tools,
            enabledToolNames = server.enabledToolNames.intersect(availableNames),
            lastRefreshedAt = refreshedAt,
            lastProtocolVersion = discovery.protocolVersion,
            toolsExpireAt = discovery.cacheTtlMs?.let { ttl ->
                if (ttl > Long.MAX_VALUE - refreshedAt) Long.MAX_VALUE else refreshedAt + ttl
            },
        )
    }
    }

    suspend fun refresh(serverId: String): McpServerSetting {
        val server = requireNotNull(McpServerRepository.serverById(serverId)) {
            "MCP 服务器不存在"
        }
        val refreshed = discover(
            server,
            McpServerRepository.bearerToken(serverId),
            McpServerRepository.customHeaders(serverId),
        )
        McpServerRepository.update(refreshed)
        return refreshed
    }
}
