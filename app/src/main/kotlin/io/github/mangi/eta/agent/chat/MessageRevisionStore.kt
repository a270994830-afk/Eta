package io.github.mangi.eta.agent.chat

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 普通对话（非角色扮演）的助手回复版本历史。
 *
 * 角色扮演会话的版本由 RoleplayMessageState 随 revisions_json 持久化；普通对话没有等价结构，
 * 又不能在不动表结构的前提下扩展 Room 实体，因此单独落一个 JSON 文件。
 *
 * 键为「会话 id + 该轮提问的用户消息 id」：用户消息在重新生成时保持不变，可作稳定的轮次锚点；
 * 助手的运行时消息 id 每次生成都会变化，不能用作键。
 */
internal object MessageRevisionStore {
    @Serializable
    data class Entry(
        val versions: List<String> = emptyList(),
        val selected: Int = 0,
        /**
         * 每个版本对应的「该轮之后的会话」快照，键为版本下标。
         * 缺失表示该版本没有可回滚的后续消息（切回时截断到该轮）。
         */
        val branches: Map<String, Branch> = emptyMap(),
    )

    /** 某个版本作为分支头时，其后所有轮次的会话快照。 */
    @Serializable
    data class Branch(
        val messages: List<BranchMessage> = emptyList(),
        /** 模型上下文（AgentConversationCodec 编码）尾段。 */
        val history: String = "",
        /** 完整历史（journal）尾段。 */
        val journal: String = "",
        /** 捕获时轮次锚点是否同时存在于模型上下文与完整历史；false 表示只能部分回滚。 */
        val complete: Boolean = true,
    ) {
        val isEmpty: Boolean
            get() = messages.isEmpty() && history.isBlank() && journal.isBlank()
    }

    /** UI 消息的可序列化投影；只覆盖会写入 Room 展示层的消息类型。 */
    @Serializable
    data class BranchMessage(
        val type: String,
        val id: String,
        val content: String = "",
        val images: List<String> = emptyList(),
        val isEdited: Boolean = false,
        val renderMarkdown: Boolean = true,
        val contextTokens: Int? = null,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val reasoningTokens: Int? = null,
        val cachedTokens: Int? = null,
        val detail: String? = null,
        val elapsedSeconds: Int? = null,
        val toolName: String = "",
        val toolStatus: String = "",
        val argumentsSummary: String = "",
        val command: String? = null,
        val resultSummary: String? = null,
        val imageCount: Int = 0,
        val tools: List<String> = emptyList(),
    )

    @Serializable
    private data class Payload(
        val conversations: Map<String, Map<String, Entry>> = emptyMap(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val persistMutex = Mutex()
    private val cache = mutableMapOf<String, MutableMap<String, Entry>>()

    @Volatile
    private var file: File? = null

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var persistSequence = 0L

    fun load(context: Context, scope: CoroutineScope) {
        this.scope = scope
        val target = File(context.applicationContext.filesDir, FILE_NAME)
        file = target
        val payload = try {
            runBlocking(Dispatchers.IO) {
                if (!target.exists()) Payload() else json.decodeFromString<Payload>(target.readText())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logFailure("load", failure)
            Payload()
        }
        cache.clear()
        payload.conversations.forEach { (conversationId, entries) ->
            val bucket = cache.getOrPut(conversationId) { mutableMapOf() }
            entries.forEach { (anchorId, entry) ->
                if (entry.versions.isEmpty()) return@forEach
                // 版本列表只增不减，但仍清理下标越界的孤立分支，避免脏数据长期驻留。
                val branches = entry.branches.filterKeys { key ->
                    key.toIntOrNull()?.let { it in entry.versions.indices } == true
                }
                bucket[anchorId] = if (branches.size == entry.branches.size) entry else entry.copy(branches = branches)
            }
        }
    }

    fun entry(conversationId: String, anchorMessageId: String): Entry? =
        cache[conversationId]?.get(anchorMessageId)

    fun hasRevisions(conversationId: String): Boolean = cache[conversationId].isNullOrEmpty().not()

    fun recordPrevious(conversationId: String, anchorMessageId: String, text: String): Entry =
        recordInternal(conversationId, anchorMessageId, text)

    fun recordGenerated(conversationId: String, anchorMessageId: String, text: String): Entry =
        recordInternal(conversationId, anchorMessageId, text)

    /** 保证 text 是该轮的一个版本并选中它：已存在则复用，不存在则追加，避免重复项。 */
    private fun recordInternal(conversationId: String, anchorMessageId: String, text: String): Entry {
        val bucket = cache.getOrPut(conversationId) { mutableMapOf() }
        val current = bucket[anchorMessageId]
        val next = when (current) {
            null -> Entry(versions = listOf(text), selected = 0)
            else -> {
                val existing = current.versions.indexOf(text)
                if (existing >= 0) current.copy(selected = existing)
                else current.copy(versions = current.versions + text, selected = current.versions.size)
            }
        }
        bucket[anchorMessageId] = next
        schedulePersist()
        return next
    }

    fun branch(conversationId: String, anchorMessageId: String, index: Int): Branch? =
        cache[conversationId]?.get(anchorMessageId)?.branches?.get(index.toString())

    /**
     * 记录某个版本对应的后续会话快照。
     *
     * preserveExistingWhenEmpty 供「重新生成前」的捕获使用：若当前已无后续轮次（例如上一次生成
     * 失败已经把后续轮次截断），保留先前捕获的分支，避免唯一可回滚的数据被空快照覆盖；
     * 切换版本时传 false，让快照如实反映当前会话（用户显式删除的轮次不会被复活）。
     */
    fun setBranch(
        conversationId: String,
        anchorMessageId: String,
        index: Int,
        branch: Branch,
        preserveExistingWhenEmpty: Boolean = false,
    ): Entry? {
        val bucket = cache[conversationId] ?: return null
        val current = bucket[anchorMessageId] ?: return null
        if (index !in current.versions.indices) return null
        val existing = current.branches[index.toString()]
        if (preserveExistingWhenEmpty && branch.isEmpty && existing != null && !existing.isEmpty) {
            return current
        }
        if (existing == branch) return current
        val updated = current.copy(branches = current.branches + (index.toString() to branch))
        bucket[anchorMessageId] = updated
        schedulePersist()
        return updated
    }

    fun select(conversationId: String, anchorMessageId: String, index: Int): Entry? {
        val bucket = cache[conversationId] ?: return null
        val current = bucket[anchorMessageId] ?: return null
        if (index !in current.versions.indices) return null
        if (index == current.selected) return current
        val next = current.copy(selected = index)
        bucket[anchorMessageId] = next
        schedulePersist()
        return next
    }

    fun forgetConversation(conversationId: String) {
        if (cache.remove(conversationId) != null) schedulePersist()
    }

    private fun schedulePersist() {
        val target = file ?: return
        val activeScope = scope ?: return
        val snapshot = cache.mapValues { (_, bucket) -> bucket.toMap() }
        val sequence = ++persistSequence
        activeScope.launch(Dispatchers.IO) {
            persistMutex.withLock {
                // 只落盘最新一次状态，避免并发写导致旧快照覆盖新快照。
                if (sequence != persistSequence) return@withLock
                try {
                    target.writeText(json.encodeToString(Payload(snapshot)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    logFailure("persist", failure)
                }
            }
        }
    }

    private fun logFailure(operation: String, failure: Exception) {
        AndroidAgentLogger.warnThrottled("message_revision_store_$operation") {
            "Message revision store $operation failed: type=${failure.safeLogType()}"
        }
    }

    private const val FILE_NAME = "message_revisions.json"
}
