package io.github.mangi.eta.agent.device.shadow

import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 影子屏操作执行器：在虚拟显示上启动应用、注入手势、输入文本、抓取画面。
 *
 * 所有操作都通过 `-d <displayId>` 定向到虚拟屏，不影响用户正在使用的物理屏幕。
 * 命令全部由本类构造，模型参数只作为受约束的值传入，不参与脚本拼接。
 */
internal class ShadowDisplayController(
    private val logger: AgentLogger,
    private val rootCommandExecutor: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean,
) {
    /** 在影子屏上启动一个应用；返回是否成功。 */
    fun launchApp(session: ShadowDisplayManager.SessionState.Active, packageName: String): ShadowOutcome {
        if (!isSanitizedPackage(packageName)) {
            return ShadowOutcome.failure("INVALID_ARGUMENT", "包名格式不合法：$packageName")
        }
        val command = buildString {
            append("am start --display ").append(session.displayId)
            append(" -f 0x10200000")
            append(" -a android.intent.action.MAIN")
            append(" -c android.intent.category.LAUNCHER")
            append(" -p ").append(packageName)
            append(" 2>&1")
        }
        return run(command, timeout = ACTION_TIMEOUT_MS, label = "launch_app")
    }

    /** 在影子屏上点击坐标。 */
    fun tap(session: ShadowDisplayManager.SessionState.Active, x: Int, y: Int): ShadowOutcome {
        if (x < 0 || y < 0 || x > session.width || y > session.height) {
            return ShadowOutcome.failure(
                "INVALID_ARGUMENT",
                "坐标超出影子屏范围：($x,$y) 不属于 ${session.width}x${session.height}",
            )
        }
        return run(
            command = "input -d ${session.displayId} tap $x $y 2>&1",
            timeout = ACTION_TIMEOUT_MS,
            label = "tap",
        )
    }

    /** 在影子屏上滑动。 */
    fun swipe(
        session: ShadowDisplayManager.SessionState.Active,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ): ShadowOutcome {
        val duration = durationMs.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)
        return run(
            command = "input -d ${session.displayId} swipe $x1 $y1 $x2 $y2 $duration 2>&1",
            timeout = ACTION_TIMEOUT_MS,
            label = "swipe",
        )
    }

    /** 向影子屏发送按键。 */
    fun pressKey(session: ShadowDisplayManager.SessionState.Active, key: String): ShadowOutcome {
        if (!isSanitizedKey(key)) {
            return ShadowOutcome.failure("INVALID_ARGUMENT", "不支持的按键：$key")
        }
        return run(
            command = "input -d ${session.displayId} keyevent $key 2>&1",
            timeout = ACTION_TIMEOUT_MS,
            label = "press_key",
        )
    }

    /** 向影子屏输入文本（经转义，避免 shell 注入）。 */
    fun inputText(session: ShadowDisplayManager.SessionState.Active, text: String): ShadowOutcome {
        if (text.isEmpty()) {
            return ShadowOutcome.failure("INVALID_ARGUMENT", "输入文本不能为空")
        }
        if (text.length > MAX_TEXT_LENGTH) {
            return ShadowOutcome.failure("INVALID_ARGUMENT", "输入文本过长，上限 $MAX_TEXT_LENGTH 字")
        }
        val escaped = text.replace("'", "'\\''").replace(" ", "%s")
        return run(
            command = "input -d ${session.displayId} text '$escaped' 2>&1",
            timeout = ACTION_TIMEOUT_MS,
            label = "input_text",
        )
    }

    /**
     * 抓取影子屏当前画面。
     *
     * screencap -d 需要的是 SurfaceFlinger 物理显示 ID，而非逻辑 displayId。
     * 抓取结果以原始 PNG 字节返回，由调用方决定如何编码给模型。
     */
    fun capture(session: ShadowDisplayManager.SessionState.Active): ShadowCapture =
        if (session.surfaceFlingerId.isBlank() || session.surfaceFlingerId == "0") {
            ShadowCapture.Failed("SHADOW_SURFACE_ID_UNKNOWN", "影子屏的 SurfaceFlinger ID 未就绪，请稍后重试")
        } else {
            val target = ShadowPaths.SCREENSHOT_FILE
            val command = buildString {
                append("screencap -p -d ").append(session.surfaceFlingerId)
                append(" > ").append(target).append(" 2>/dev/null; ")
                append("if [ -s ").append(target).append(" ]; then ")
                append("{ base64 -w0 ").append(target).append(" 2>/dev/null || base64 ").append(target).append("; } ")
                append("else echo '__SHADOW_CAPTURE_EMPTY__'; fi")
            }
            val result = rootCommandExecutor.execute(
                command = command,
                timeoutMillis = CAPTURE_TIMEOUT_MS,
                maxOutputBytes = MAX_CAPTURE_BYTES,
            )
            val payload = result.stdout.trim()
            when {
                !result.ok -> ShadowCapture.Failed(
                    result.errorCode.ifBlank { "SHADOW_CAPTURE_FAILED" },
                    "影子屏截图失败：${result.stderr.take(160)}",
                )
                payload.isEmpty() || payload == CAPTURE_EMPTY_MARKER -> ShadowCapture.Failed(
                    "SHADOW_CAPTURE_EMPTY",
                    "影子屏截图内容为空，影子屏可能尚未渲染完成",
                )
                result.truncated -> ShadowCapture.Failed(
                    "SHADOW_CAPTURE_TRUNCATED",
                    "影子屏截图超出单次读取上限，请缩小影子屏尺寸或稍后重试",
                )
                else -> runCatching {
                    android.util.Base64.decode(
                        payload.filterNot(Char::isWhitespace),
                        android.util.Base64.DEFAULT,
                    )
                }.fold(
                    onSuccess = { ShadowCapture.Png(it) },
                    onFailure = {
                        ShadowCapture.Failed("SHADOW_CAPTURE_DECODE_FAILED", "截图数据解码失败")
                    },
                )
            }
        }

    /**
     * 读取影子屏的 UI 层级。
     *
     * 主路：**无障碍服务按 displayId 取窗口树**——不占 UiAutomation（全设备独占，被别的自动化
     * 占住时 `uiautomator dump` 会直接崩），也不影响用户正在用的物理屏。
     * 兜底：uiautomator（需 UiAutomation 槽空闲；本机多数时候被占）。
     */
    fun dumpUiTree(session: ShadowDisplayManager.SessionState.Active): ShadowOutcome {
        val snapshot = AgentAccessibilityService.current()
            ?.captureNodeSnapshotForDisplay(session.displayId, UI_DUMP_NODE_LIMIT)
        if (snapshot != null && snapshot.nodes.isNotEmpty()) {
            val nodes = JSONArray().also { array ->
                snapshot.nodes.forEach { node -> array.put(node.toShadowJson()) }
            }
            val payload = JSONObject()
                .put("ok", true)
                .put("display_id", session.displayId)
                .put("source", "accessibility")
                .put("package", snapshot.packageName)
                .put("window_id", snapshot.windowId)
                .put("truncated", snapshot.truncated)
                .put("node_count", nodes.length())
                .put("nodes", nodes)
                .toString()
            return ShadowOutcome.success(payload)
        }
        val dumpPath = ShadowPaths.UI_DUMP_FILE
        val command = buildString {
            append("rm -f ").append(dumpPath).append("; ")
            append("uiautomator dump --compressed ").append(dumpPath).append(" >/dev/null 2>&1; ")
            append("if [ -f ").append(dumpPath).append(" ]; then cat ").append(dumpPath).append("; ")
            append("else echo 'UI_DUMP_UNAVAILABLE'; fi")
        }
        return run(command, timeout = UI_DUMP_TIMEOUT_MS, label = "ui_dump")
    }

    /** 与 observe_screen 的 ui_nodes 字段完全一致，便于模型同一套坐标/动作使用。 */
    private fun AgentAccessibilityService.UiNode.toShadowJson(): JSONObject =
        JSONObject()
            .put("index", index)
            .put("text", text)
            .put("desc", desc)
            .put("class", className)
            .put("package", packageName)
            .put("view_id", viewId)
            .put("bounds", bounds.toShortString())
            .put("center", JSONObject().put("x", bounds.centerX()).put("y", bounds.centerY()))
            .put("clickable", clickable)
            .put("long_clickable", longClickable)
            .put("scrollable", scrollable)
            .put("focused", focused)
            .put("editable", editable)
            .put("password", password)
            .put("enabled", enabled)

    private fun run(command: String, timeout: Long, label: String): ShadowOutcome {
        if (!rootAvailable()) {
            return ShadowOutcome.failure("ROOT_REQUIRED", "影子屏操作需要 Root 授权")
        }
        val result = rootCommandExecutor.execute(command, timeoutMillis = timeout)
        if (!result.ok) {
            logger.warn(
                "Agent shadow action=$label outcome=failed exit=${result.exitCode} " +
                    "timeout=${result.timedOut} stderr=${result.stderr.take(160)}",
            )
            val code = result.errorCode.ifBlank {
                if (result.timedOut) "SHADOW_TIMEOUT" else "SHADOW_ACTION_FAILED"
            }
            return ShadowOutcome.failure(code, result.stderr.ifBlank { result.stdout }.take(400))
        }
        logger.debug { "Agent shadow action=$label outcome=completed" }
        return ShadowOutcome.success(result.stdout.trim().take(MAX_MESSAGE_LENGTH))
    }

    private fun isSanitizedPackage(name: String): Boolean =
        name.length in 3..255 && PACKAGE_PATTERN.matches(name)

    private fun isSanitizedKey(key: String): Boolean = key.uppercase() in ALLOWED_KEYS

    internal sealed interface ShadowOutcome {
        data class Success(val output: String) : ShadowOutcome
        data class Failure(val code: String, val message: String) : ShadowOutcome

        val ok: Boolean get() = this is Success

        companion object {
            fun success(output: String): ShadowOutcome = Success(output)
            fun failure(code: String, message: String): ShadowOutcome = Failure(code, message)
        }
    }

    internal sealed interface ShadowCapture {
        /** 原始 PNG 字节。 */
        data class Png(val bytes: ByteArray) : ShadowCapture
        data class Failed(val code: String, val message: String) : ShadowCapture
    }

    internal companion object {
        private const val ACTION_TIMEOUT_MS = 10_000L
        private const val CAPTURE_TIMEOUT_MS = 20_000L
        private const val UI_DUMP_TIMEOUT_MS = 15_000L
        /** 影子屏 UI 树一次最多返回的节点数（与 observe_screen 上限一致）。 */
        private const val UI_DUMP_NODE_LIMIT = 60
        private const val MAX_CAPTURE_BYTES = 16 * 1024 * 1024
        private const val MAX_TEXT_LENGTH = 2_000
        private const val MAX_MESSAGE_LENGTH = 2_000
        private const val MIN_SWIPE_MS = 100
        private const val MAX_SWIPE_MS = 3_000
        private const val CAPTURE_EMPTY_MARKER = "__SHADOW_CAPTURE_EMPTY__"
        private val PACKAGE_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
        private val ALLOWED_KEYS: Set<String> = setOf(
            "HOME", "BACK", "APP_SWITCH", "ENTER", "DEL", "TAB", "ESCAPE", "MENU",
            "VOLUME_UP", "VOLUME_DOWN", "POWER", "WAKEUP", "SLEEP",
            "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER",
        )
    }
}

/** 影子屏操作的返回封装。 */
internal data class ShadowToolResult(
    val content: String,
    val sensitive: Boolean = false,
) {
    fun toJson(): String = content
}
