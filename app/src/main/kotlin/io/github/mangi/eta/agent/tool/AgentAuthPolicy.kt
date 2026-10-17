package io.github.mangi.eta.agent.tool

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 敏感操作授权策略（mimic 式简化版）。
 *
 * - 规则存储为 (动作类 × 目标App → allow/deny)，默认只拦「外部通道」的敏感动作（聊天内保留安装/卸载类）；
 * - 未授权动作返回 AUTH_REQUIRED 并附授权卡片（__CARD__），由用户在卡片上点击确认；
 * - 「铸造/吊销 = 物理接触」：授权回复只能由卡片点击产生（前缀文本 [AUTH_REPLY_PREFIX]），
 *   经 [interceptUserInput] 应用；超时（未点击）视为可重试，重新调用会重新弹卡。
 */
internal object AgentAuthPolicy {

    /** 工具执行来源：聊天（本机 agent）/ 外部 MCP 客户端 / Eta 调用的外部 MCP。 */
    enum class Source { CHAT, EXTERNAL_MCP, MCP_CLIENT }

    /** 动作类（参照 mimic 裁剪为 Eta 需要的子集）；TOOL 为工具级权限（tool_permission ask）。 */
    enum class ActionClass { READ, INTERACT, TYPE, LAUNCH, PACKAGES, TOOL }

    data class Rule(val action: ActionClass, val packageName: String, val allow: Boolean)

    data class Request(
        val id: String,
        val action: ActionClass,
        val packageName: String,
        val toolName: String,
        val detail: String,
    )

    sealed interface Decision {
        data object Allow : Decision
        data object Denied : Decision
        data class NeedsAuth(val request: Request) : Decision
    }

    data class Reply(val id: String, val decision: String)

    private data class PendingGrant(val request: Request, val createdAt: Long)

    private const val PENDING_TTL_MS = 10 * 60 * 1000L
    private const val SESSION_TTL_MS = 8 * 60 * 60 * 1000L
    private const val MAX_RULES = 200
    const val AUTH_REPLY_PREFIX = "__ETA_AUTH__"

    /** 高危动作类：卡片不提供「始终允许」，总是先确认。 */
    private val HIGH_RISK_ALWAYS_CONFIRM = setOf(ActionClass.PACKAGES)

    // ---------- 分类表 ----------

    private val READ_TOOLS = setOf(
        "get_current_context", "search_apps", "observe_screen", "ocr_screen",
        "wait", "wait_for_text", "wait_for_package", "device_status",
        "shadow_observe", "shadow_status",
    )
    private val PACKAGES_TOOLS = setOf("app_state_control")
    private val LAUNCH_TOOLS = setOf("launch_app", "open_uri", "shadow_app", "shadow_open_app", "shadow_launch")
    private val TYPE_TOOLS = setOf(
        "input_text", "replace_text", "clear_text", "paste_text", "set_clipboard",
        "shadow_input_text", "shadow_press_key",
    )
    private val INTERACT_TOOLS = setOf(
        "tap", "tap_text", "tap_area", "tap_element", "long_press", "long_press_element",
        "swipe", "scroll", "scroll_element", "open_system_panel", "media_control",
        "set_volume", "set_alarm", "set_timer", "press_key",
        "shadow_act", "shadow_auto_act", "shadow_tap", "shadow_long_press", "shadow_swipe",
    )

    fun classify(toolName: String): ActionClass? = when (toolName) {
        in READ_TOOLS -> ActionClass.READ
        in PACKAGES_TOOLS -> ActionClass.PACKAGES
        in LAUNCH_TOOLS -> ActionClass.LAUNCH
        in TYPE_TOOLS -> ActionClass.TYPE
        in INTERACT_TOOLS -> ActionClass.INTERACT
        else -> null
    }

    // ---------- 判定 ----------

    @Volatile
    private var pending: PendingGrant? = null

    @Volatile
    private var grantedOnce: PendingGrant? = null

    private val grantedSession = java.util.concurrent.ConcurrentHashMap<String, PendingGrant>()

    /** 工具级（tool_permission ask）等待中的授权请求，与动作类授权分开匹配。 */
    @Volatile
    private var pendingTool: PendingGrant? = null

    private fun sessionKey(action: ActionClass, packageName: String): String =
        action.name + "|" + packageName.lowercase()

    /** 仅供测试复位。 */
    fun resetForTest() {
        pending = null
        grantedOnce = null
        grantedSession.clear()
        pendingTool = null
    }

    /**
     * 判定一次工具调用。packageNameHint 为当前前台包名（取不到传空串）；
     * 工具参数里的 package_name / app_name 优先。
     */
    fun evaluate(
        enabled: Boolean,
        source: Source,
        toolName: String,
        args: JSONObject,
        packageNameHint: String,
        storeFile: File,
        now: Long = System.currentTimeMillis(),
    ): Decision {
        if (!enabled) return Decision.Allow
        val action = classify(toolName) ?: return Decision.Allow
        val packageName = packageNameFor(args, packageNameHint)
        // 1) 「允许一次」：匹配即消费
        val once = grantedOnce
        if (once != null &&
            once.request.action == action &&
            once.request.packageName.equals(packageName, ignoreCase = true) &&
            now - once.createdAt in 0..PENDING_TTL_MS
        ) {
            grantedOnce = null
            return Decision.Allow
        }
        // 1b) 「本次会话允许」：有效期内直接放行
        grantedSession[sessionKey(action, packageName)]?.let { sessionGrant ->
            if (now - sessionGrant.createdAt in 0..SESSION_TTL_MS) return Decision.Allow
            grantedSession.remove(sessionKey(action, packageName))
        }
        // 2) 显式规则（specific > 通配 ""）
        findRule(storeFile, action, packageName)?.let { return if (it.allow) Decision.Allow else Decision.Denied }
        // 3) 内置默认：外部通道的敏感动作需要授权；聊天仅拦安装/卸载类；READ 永远放行
        val sensitive = when (source) {
            Source.EXTERNAL_MCP -> action != ActionClass.READ
            Source.CHAT -> action == ActionClass.PACKAGES
            Source.MCP_CLIENT -> false
        }
        if (!sensitive) return Decision.Allow
        // 4) 同一目标等待中：复用同一请求，不重复弹卡
        val waiting = pending
        if (waiting != null &&
            waiting.request.action == action &&
            waiting.request.packageName.equals(packageName, ignoreCase = true) &&
            now - waiting.createdAt in 0..PENDING_TTL_MS
        ) {
            return Decision.NeedsAuth(waiting.request)
        }
        val request = Request(
            id = "auth-" + UUID.randomUUID().toString().take(8),
            action = action,
            packageName = packageName,
            toolName = toolName,
            detail = detailFor(toolName, args),
        )
        pending = PendingGrant(request, now)
        return Decision.NeedsAuth(request)
    }

    /**
     * 工具级（tool_permission ask）授权请求：与动作类授权共用卡片与 [AUTH_REPLY_PREFIX] 回复通道。
     * 同一工具在 TTL 内复用同一张卡片，避免连续弹卡。
     */
    fun requestToolApproval(toolName: String, now: Long = System.currentTimeMillis()): Request {
        val existing = pendingTool
        if (existing != null &&
            existing.request.toolName == toolName &&
            now - existing.createdAt in 0..PENDING_TTL_MS
        ) {
            return existing.request
        }
        val request = Request(
            id = "auth-" + UUID.randomUUID().toString().take(8),
            action = ActionClass.TOOL,
            packageName = "",
            toolName = toolName,
            detail = toolName,
        )
        pendingTool = PendingGrant(request, now)
        return request
    }

    // ---------- 卡片与回复 ----------

    fun buildCardPayload(request: Request): String = JSONObject()
        .put("type", "auth_request")
        .put("id", request.id)
        .put("action", request.action.name)
        .put("app", request.packageName)
        .put("tool", request.toolName)
        .put("detail", request.detail)
        .put("high_risk", request.action in HIGH_RISK_ALWAYS_CONFIRM)
        .put(
            "options",
            JSONArray().apply {
                put(JSONObject().put("label", "允许一次").put("decision", "allow_once"))
                put(JSONObject().put("label", "本次会话允许").put("decision", "allow_session"))
                if (request.action !in HIGH_RISK_ALWAYS_CONFIRM) {
                    put(JSONObject().put("label", "始终允许").put("decision", "allow_always"))
                }
                put(JSONObject().put("label", "拒绝").put("decision", "deny"))
            },
        )
        .toString()

    /** 卡片按钮点击产生的用户回复文本（物理接触=授权动作）。 */
    fun buildReplyText(requestId: String, decision: String): String =
        AUTH_REPLY_PREFIX + JSONObject().put("id", requestId).put("decision", decision).toString()

    fun parseReply(raw: String): Reply? {
        val text = raw.trim()
        if (!text.startsWith(AUTH_REPLY_PREFIX)) return null
        val json = runCatching { JSONObject(text.substring(AUTH_REPLY_PREFIX.length).trim()) }.getOrNull()
            ?: return null
        val id = json.optString("id")
        val decision = json.optString("decision")
        if (id.isBlank() || decision !in setOf("allow_once", "allow_session", "allow_always", "deny")) return null
        return Reply(id, decision)
    }

    /**
     * 用户输入拦截：若为授权回复则应用策略并返回替换后的（自然语言）输入；否则返回 null 原样传递。
     */
    fun interceptUserInput(storeFile: File, raw: String, now: Long = System.currentTimeMillis()): String? {
        val reply = parseReply(raw) ?: return null
        val waiting = pending?.takeIf { it.request.id == reply.id }
        val toolWaiting = if (waiting == null) pendingTool?.takeIf { it.request.id == reply.id } else null
        when (reply.decision) {
            "allow_once" -> if (waiting != null) grantedOnce = PendingGrant(waiting.request, now)
            "allow_session" -> if (waiting != null) {
                grantedSession[sessionKey(waiting.request.action, waiting.request.packageName)] =
                    PendingGrant(waiting.request, now)
            }
            "allow_always" -> if (waiting != null) {
                // 高危动作不允许固化「始终允许」
                if (waiting.request.action !in HIGH_RISK_ALWAYS_CONFIRM) {
                    upsertRule(
                        storeFile,
                        Rule(action = waiting.request.action, packageName = waiting.request.packageName, allow = true),
                    )
                }
            }
            else -> Unit
        }
        pending = null
        // 工具级授权：把结果登记回工具权限层（once/session 存内存，always 持久化）。
        if (toolWaiting != null) {
            val grantedTool = toolWaiting.request.toolName
            when (reply.decision) {
                "allow_once" -> AgentToolPermissionStore.grantTemporary(grantedTool, AgentToolPermissionStore.SCOPE_ONCE)
                "allow_session" -> AgentToolPermissionStore.grantTemporary(grantedTool, AgentToolPermissionStore.SCOPE_SESSION)
                "allow_always" -> AgentToolPermissionStore.grantTemporary(grantedTool, AgentToolPermissionStore.SCOPE_ALWAYS)
                else -> Unit
            }
            pendingTool = null
        }
        val tool = (waiting?.request?.toolName ?: toolWaiting?.request?.toolName).orEmpty()
        val toolSuffix = if (tool.isNotBlank()) "（工具：$tool）" else ""
        return when (reply.decision) {
            "allow_once" -> "我已在授权卡片上确认允许（一次性）。请继续之前被拦截的操作$toolSuffix。"
            "allow_session" -> "我已在授权卡片上选择本次会话允许。请继续之前被拦截的操作$toolSuffix。"
            "allow_always" -> "我已在授权卡片上选择始终允许。请继续之前被拦截的操作$toolSuffix。"
            else -> "我已在授权卡片上拒绝了该操作，请不要执行它。"
        }
    }

    // ---------- 规则存储 ----------

    fun storeFile(context: Context): File = File(context.filesDir, "auth_policy.json")

    fun rules(storeFile: File): List<Rule> = runCatching {
        if (!storeFile.exists()) return emptyList()
        val root = JSONObject(storeFile.readText())
        val arr = root.optJSONArray("rules") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val action = runCatching { ActionClass.valueOf(obj.optString("action")) }.getOrNull()
                ?: return@mapNotNull null
            Rule(action = action, packageName = obj.optString("package").take(120), allow = obj.optBoolean("allow", true))
        }
    }.getOrElse { emptyList() }

    fun findRule(storeFile: File, action: ActionClass, packageName: String): Rule? {
        val list = rules(storeFile)
        return list.firstOrNull { it.action == action && it.packageName.equals(packageName, ignoreCase = true) }
            ?: list.firstOrNull { it.action == action && it.packageName.isEmpty() }
    }

    fun upsertRule(storeFile: File, rule: Rule) {
        val rest = rules(storeFile).filterNot {
            it.action == rule.action && it.packageName.equals(rule.packageName, ignoreCase = true)
        }
        writeRules(storeFile, (listOf(rule) + rest).take(MAX_RULES))
    }

    /** 吊销规则（物理接触原则同样适用于吊销）。返回是否存在被吊销的规则。 */
    fun revoke(storeFile: File, action: ActionClass, packageName: String): Boolean {
        val list = rules(storeFile)
        val rest = list.filterNot {
            it.action == action && it.packageName.equals(packageName, ignoreCase = true)
        }
        if (rest.size == list.size) return false
        writeRules(storeFile, rest)
        return true
    }

    // ---------- 内部 ----------

    private fun packageNameFor(args: JSONObject, hint: String): String {
        args.optString("package_name").trim().takeIf { it.isNotBlank() }?.let { return it }
        args.optString("app_name").trim().takeIf { it.isNotBlank() }?.let { return it }
        return hint.trim()
    }

    private fun detailFor(toolName: String, args: JSONObject): String = buildString {
        append(toolName)
        val text = args.optString("text").trim()
        if (text.isNotBlank()) append("：").append(text.take(60))
        val command = args.optString("command").trim()
        if (command.isNotBlank()) append("：").append(command.take(60))
    }

    private fun writeRules(storeFile: File, rules: List<Rule>) {
        val root = JSONObject().put(
            "rules",
            JSONArray().apply {
                rules.forEach {
                    put(
                        JSONObject()
                            .put("action", it.action.name)
                            .put("package", it.packageName)
                            .put("allow", it.allow),
                    )
                }
            },
        )
        runCatching {
            storeFile.parentFile?.mkdirs()
            val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(storeFile)) {
                storeFile.writeText(root.toString())
                tmp.delete()
            }
        }
    }
}
