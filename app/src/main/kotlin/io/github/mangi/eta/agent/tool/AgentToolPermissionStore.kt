package io.github.mangi.eta.agent.tool

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具级权限（对齐 Operit 的 ALLOW/ASK/FORBID）。
 *
 * 配置文件 filesDir/tool_permissions.json：
 * `{"default":"allow","tools":{"run_command":"ask","app_state_control":"forbid"}}`
 *
 * 语义（与 Eta 的同步工具调用模型一致，不做 UI 弹窗）：
 * - `allow`：直接执行（默认）
 * - `forbid`：拒绝执行，返回 TOOL_FORBIDDEN，模型应换方案或请用户改策略
 * - `ask`：不直接执行，返回 TOOL_NEEDS_APPROVAL 与授权卡片（card 字段）；用户授权
 *   （卡片点击经 [AgentAuthPolicy.AUTH_REPLY_PREFIX] 回复，或直接调 tool_permission_approve）后放行：
 *   once 仅本轮一次、session 本会话内一直放行、always 持久写入 allow
 */
internal object AgentToolPermissionStore {
    private const val FILE_NAME = "tool_permissions.json"
    const val POLICY_ALLOW = "allow"
    const val POLICY_ASK = "ask"
    const val POLICY_FORBID = "forbid"

    const val SCOPE_ONCE = "once"
    const val SCOPE_SESSION = "session"
    const val SCOPE_ALWAYS = "always"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var storageDir: File? = null

    /** 临时放行（全内存，不落盘）：once 消费一次即失效；session 进程存活期内一直放行。 */
    private val onceGrants: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val sessionGrants: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun init(context: Context) {
        appContext = context.applicationContext
        storageDir = context.applicationContext.filesDir
    }

    /** 单测注入存储目录（不依赖 Android Context）。 */
    internal fun attachStorageDir(dir: File?) {
        storageDir = dir
        onceGrants.clear()
        sessionGrants.clear()
    }

    private fun file(): File? = storageDir?.let { File(it, FILE_NAME) }

    private fun load(): JSONObject = runCatching {
        val f = file() ?: return JSONObject()
        if (!f.exists()) JSONObject() else JSONObject(f.readText())
    }.getOrDefault(JSONObject())

    @Synchronized
    private fun save(root: JSONObject) {
        runCatching {
            val f = file() ?: return
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(root.toString())
                tmp.delete()
            }
        }
    }

    fun defaultPolicy(): String {
        val value = load().optString("default", POLICY_ALLOW).trim().lowercase()
        return value.takeIf { it in setOf(POLICY_ALLOW, POLICY_ASK, POLICY_FORBID) } ?: POLICY_ALLOW
    }

    fun policyOf(toolName: String): String {
        val explicit = load().optJSONObject("tools")?.optString(toolName, "")?.trim()?.lowercase()
        if (explicit != null && explicit in setOf(POLICY_ALLOW, POLICY_ASK, POLICY_FORBID)) return explicit
        return defaultPolicy()
    }

    @Synchronized
    fun setPolicy(toolName: String?, policy: String): Boolean {
        val normalized = policy.trim().lowercase()
        if (normalized !in setOf(POLICY_ALLOW, POLICY_ASK, POLICY_FORBID)) return false
        val root = load()
        if (toolName.isNullOrBlank()) {
            root.put("default", normalized)
        } else {
            val tools = root.optJSONObject("tools") ?: JSONObject().also { root.put("tools", it) }
            tools.put(toolName, normalized)
        }
        save(root)
        return true
    }

    fun snapshot(): JSONObject = JSONObject(load().toString())
        .put("ok", true)
        .put("temporary", temporarySnapshot())

    /** 当前临时放行（全内存）：once 一次性、session 会话级。 */
    fun temporarySnapshot(): JSONObject = JSONObject()
        .put("once", JSONArray(onceGrants.toList()))
        .put("session", JSONArray(sessionGrants.toList()))

    /**
     * 登记一次授权：once 下次检查消费即失效；session 进程存活期内一直放行；
     * always 直接持久化为 allow（等价 tool_permission_set）。返回是否受理。
     */
    fun grantTemporary(toolName: String, scope: String): Boolean {
        val name = toolName.trim()
        if (name.isBlank()) return false
        return when (scope.trim().lowercase()) {
            SCOPE_ONCE -> {
                onceGrants.add(name)
                true
            }
            SCOPE_SESSION -> {
                sessionGrants.add(name)
                true
            }
            SCOPE_ALWAYS -> setPolicy(name, POLICY_ALLOW)
            else -> false
        }
    }

    private fun consumeTemporary(toolName: String): Boolean {
        if (sessionGrants.contains(toolName)) return true
        return onceGrants.remove(toolName)
    }

    /** 执行前检查：被禁止/待批准时返回错误 JSON，允许时返回 null。 */
    fun checkError(toolName: String): String? = when (policyOf(toolName)) {
        POLICY_FORBID -> JSONObject()
            .put("ok", false)
            .put("code", "TOOL_FORBIDDEN")
            .put("message", "工具 $toolName 已被用户设为禁止（forbid）。请换用其它方案；如需解禁，请让用户同意后调用 tool_permission_set。")
            .toString()
        POLICY_ASK -> if (consumeTemporary(toolName)) {
            null
        } else {
            val request = AgentAuthPolicy.requestToolApproval(toolName)
            JSONObject()
                .put("ok", false)
                .put("code", "TOOL_NEEDS_APPROVAL")
                .put("tool", toolName)
                .put("approval_id", request.id)
                .put("card", JSONObject(AgentAuthPolicy.buildCardPayload(request)))
                .put(
                    "message",
                    "工具 $toolName 需要用户确认（ask）。授权卡片随 card 字段返回（type=auth_request）；" +
                        "若前端无法渲染卡片，请用一句话向用户说明并征得同意后，调用 " +
                        "tool_permission_approve(tool_name=\"$toolName\", scope=\"once|session|always\") 再重试；用户拒绝则不要执行。",
                )
                .toString()
        }
        else -> null
    }
}
