package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话类工具：列出、读取、删除历史会话。
 *
 * 会话数据以 Room 为唯一事实源；删除走 ConversationDao.deleteConversationCascade，
 * 会话本身、消息与上下文检查点一次清理，必要时修复 conversation_state 选中项。
 */
internal class AgentSessionToolSet(
    private val context: Context,
    private val logger: AgentLogger,
) {
    fun execute(name: String, args: JSONObject): String? {
        if (name !in TOOL_NAMES) return null
        return runCatching {
            when (name) {
                "conversation_list" -> conversationList(args)
                "conversation_read" -> conversationRead(args)
                "conversation_delete" -> conversationDelete(args)
                else -> null
            }
        }.getOrElse { throwable ->
            logger.warn("AgentSessionToolSet $name failed: ${throwable.javaClass.simpleName}: ${throwable.message}")
            error("TOOL_INTERNAL_ERROR", throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    private fun conversationList(args: JSONObject): String {
        val limit = args.optInt("limit", 20).coerceIn(1, 200)
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val keyword = args.optString("keyword").trim()
        val dao = EtaDatabase.get(context).conversationDao()
        val all = runBlocking(Dispatchers.IO) { dao.conversations() }
        val filtered = if (keyword.isBlank()) {
            all
        } else {
            all.filter { it.title.contains(keyword, ignoreCase = true) || it.id.contains(keyword) }
        }
        val page = filtered.drop(offset).take(limit)
        val array = JSONArray()
        page.forEach { conversation ->
            val messageCount = runBlocking(Dispatchers.IO) { dao.messageCount(conversation.id) }
            array.put(
                JSONObject()
                    .put("id", conversation.id)
                    .put("title", conversation.title)
                    .put("created_at", conversation.createdAt)
                    .put("updated_at", conversation.updatedAt)
                    .put("message_count", messageCount),
            )
        }
        return ok(
            JSONObject()
                .put("conversations", array)
                .put("total", filtered.size)
                .put("offset", offset)
                .put("limit", limit)
                .put("has_more", offset + page.size < filtered.size),
        )
    }

    private fun conversationRead(args: JSONObject): String {
        val conversationId = args.optString("conversation_id").trim()
        if (conversationId.isBlank()) return error("INVALID_ARGUMENT", "缺少 conversation_id")
        val limit = args.optInt("limit", 50).coerceIn(1, 500)
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val dao = EtaDatabase.get(context).conversationDao()
        val exists = runBlocking(Dispatchers.IO) { dao.conversations().any { it.id == conversationId } }
        if (!exists) return error("CONVERSATION_NOT_FOUND", "未找到会话: $conversationId")
        val total = runBlocking(Dispatchers.IO) { dao.messageCount(conversationId) }
        val messages = runBlocking(Dispatchers.IO) { dao.messagesPage(conversationId, limit, offset) }
        val array = JSONArray()
        messages.forEach { message ->
            val fullText = message.content
            val entry = JSONObject()
                .put("id", message.id)
                .put("index", message.sortIndex)
                .put("type", message.type)
                .put("role", roleOf(message.type))
                .put("text", fullText.take(MAX_TEXT_CHARS))
                .put("truncated", fullText.length > MAX_TEXT_CHARS)
            message.toolName?.let { entry.put("tool_name", it) }
            message.toolStatus?.let { entry.put("tool_status", it) }
            message.inputTokens?.let { entry.put("input_tokens", it) }
            message.outputTokens?.let { entry.put("output_tokens", it) }
            array.put(entry)
        }
        return ok(
            JSONObject()
                .put("conversation_id", conversationId)
                .put("messages", array)
                .put("total_messages", total)
                .put("offset", offset)
                .put("limit", limit)
                .put("has_more", offset + messages.size < total),
        )
    }

    private fun conversationDelete(args: JSONObject): String {
        val conversationId = args.optString("conversation_id").trim()
        if (conversationId.isBlank()) return error("INVALID_ARGUMENT", "缺少 conversation_id")
        val dao = EtaDatabase.get(context).conversationDao()
        return runBlocking(Dispatchers.IO) {
            val target = dao.conversations().firstOrNull { it.id == conversationId }
                ?: return@runBlocking error("CONVERSATION_NOT_FOUND", "未找到会话: $conversationId")
            val removedMessages = dao.messageCount(conversationId)
            dao.deleteConversationCascade(conversationId)
            val remaining = dao.conversations()
            val state = dao.state()
            if (state != null && state.selectedConversationId == conversationId) {
                val next = remaining.maxByOrNull { it.updatedAt }
                if (next == null) dao.deleteState()
                else dao.insertState(ConversationStateEntity(selectedConversationId = next.id))
            }
            ok(
                JSONObject()
                    .put("conversation_id", conversationId)
                    .put("title", target.title)
                    .put("deleted_messages", removedMessages)
                    .put("remaining_conversations", remaining.size),
            )
        }
    }

    private fun roleOf(type: String): String = when (type) {
        "user" -> "user"
        "assistant", "thinking" -> "assistant"
        "tool", "tool_summary" -> "tool"
        "system_notice" -> "system"
        else -> type
    }

    private fun ok(body: JSONObject): String = body.put("ok", true).toString()

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    companion object {
        val TOOL_NAMES: List<String> = listOf(
            "conversation_list",
            "conversation_read",
            "conversation_delete",
        )

        private const val MAX_TEXT_CHARS = 2000
    }
}
