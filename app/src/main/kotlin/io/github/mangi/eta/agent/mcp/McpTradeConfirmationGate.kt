package io.github.mangi.eta.agent.mcp

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「交易类工具执行前确认」闸门（v1 启发式）。
 *
 * 语义：命中名单的 MCP 工具在首次调用时被拦截一次并记录「待确认」；
 * 用户与模型完成一轮往返（新的 run）后，携带相同参数的再次调用才放行。
 * 同一 run 内重复调用、参数变化或超过有效期都会被再次拦截。
 */
internal object McpTradeConfirmationGate {

    internal sealed interface Decision {
        data object Allow : Decision
        data class Block(val message: String) : Decision
    }

    private data class Pending(
        val serverId: String,
        val toolName: String,
        val argsHash: String,
        val runId: String,
        val createdAt: Long,
    )

    // v1 启发式名单：工具名含动作词且不含读取词的未只读工具需要确认；误报可在设置中整体关闭。
    private val ACTION_TOKENS = setOf(
        "create", "cancel", "pay", "payment", "purchase", "checkout",
        "book", "booking", "reserve", "refund", "submit", "order",
    )
    private val READ_TOKENS = setOf(
        "query", "get", "list", "search", "estimate", "status", "detail",
        "info", "check", "preview", "calculate", "fetch", "read", "history",
    )

    private const val PENDING_TTL_MS = 10 * 60 * 1000L

    @Volatile
    private var pending: Pending? = null

    fun requiresConfirmation(toolName: String, readOnlyHint: Boolean?): Boolean {
        if (readOnlyHint == true) return false
        val tokens = toolName.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        if (tokens.none { it in ACTION_TOKENS }) return false
        return tokens.none { it in READ_TOKENS }
    }

    fun evaluate(
        enabled: Boolean,
        serverId: String,
        toolName: String,
        readOnlyHint: Boolean?,
        arguments: JSONObject,
        runId: String,
        now: Long = System.currentTimeMillis(),
    ): Decision {
        if (!enabled) return Decision.Allow
        if (!requiresConfirmation(toolName, readOnlyHint)) return Decision.Allow
        val argsHash = hashArguments(serverId, toolName, arguments)
        val previous = pending
        val confirmed = previous != null &&
            previous.serverId == serverId &&
            previous.toolName == toolName &&
            previous.argsHash == argsHash &&
            previous.runId != runId &&
            now - previous.createdAt in 0..PENDING_TTL_MS
        if (confirmed) {
            pending = null
            return Decision.Allow
        }
        pending = Pending(serverId, toolName, argsHash, runId, now)
        return Decision.Block(CONFIRMATION_MESSAGE)
    }

    /** 仅供测试复位。 */
    fun resetForTest() {
        pending = null
    }

    private fun hashArguments(serverId: String, toolName: String, arguments: JSONObject): String {
        val canonical = canonicalize(arguments).toString()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(serverId.toByteArray())
        digest.update(0)
        digest.update(toolName.toByteArray())
        digest.update(0)
        digest.update(canonical.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** 参数规范化：对象按 key 排序、数组保持顺序，消除键序差异导致的误判。 */
    private fun canonicalize(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { target ->
            value.keys().asSequence().sorted().forEach { key ->
                target.put(key, canonicalize(value.opt(key)))
            }
        }

        is JSONArray -> JSONArray().also { target ->
            for (index in 0 until value.length()) target.put(canonicalize(value.opt(index)))
        }

        else -> value
    }

    private const val CONFIRMATION_MESSAGE =
        "该工具会在外部服务产生真实操作（下单/取消/支付/预订等），本次调用已被安全闸门拦截。" +
            "请先向用户展示关键信息（费用、时间、起终点、数量等）并明确询问是否执行；" +
            "只有在用户确认后，才能以完全相同的参数再次调用本工具。不要自行重复调用，也不要修改参数绕过确认。"
}
