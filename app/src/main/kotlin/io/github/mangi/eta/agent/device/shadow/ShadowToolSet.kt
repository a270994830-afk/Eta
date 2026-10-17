package io.github.mangi.eta.agent.device.shadow

import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONObject

/**
 * 影子屏工具集门面。
 *
 * 把 [ShadowDisplayManager]、[ShadowCarrierDeployer]、[ShadowDisplayController] 组合成
 * 一组面向智能体的工具调用，并在每次调用前保证载体已部署、影子屏已就绪。
 */
internal class ShadowToolSet(
    private val logger: AgentLogger,
    private val rootCommandExecutor: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean,
    private val context: android.content.Context,
) {
    private val displayManager = ShadowDisplayManager(logger, rootCommandExecutor, rootAvailable)
    private val displayController = ShadowDisplayController(logger, rootCommandExecutor, rootAvailable)

    /** 处理一次影子屏工具调用；非影子屏工具返回 null 交由其他执行器处理。 */
    fun execute(toolName: String, args: JSONObject): AgentModelClient.ToolResult? {
        if (toolName !in SHADOW_TOOL_NAMES) return null
        return when (toolName) {
            "shadow_open" -> shadowOpen(args)
            "shadow_close" -> shadowClose()
            "shadow_status" -> shadowStatus()
            "shadow_launch" -> withSession { session ->
                val packageName = args.optString("package_name")
                if (packageName.isBlank()) {
                    return@withSession text(error(code = "INVALID_ARGUMENT", message = "缺少 package_name"))
                }
                mapOutcome(displayController.launchApp(session, packageName)) {
                    success("display_id" to session.displayId, "package_name" to packageName)
                }
            }
            "shadow_tap" -> withSession { session ->
                val x = args.optInt("x", Int.MIN_VALUE)
                val y = args.optInt("y", Int.MIN_VALUE)
                if (x == Int.MIN_VALUE || y == Int.MIN_VALUE) {
                    return@withSession text(error(code = "INVALID_ARGUMENT", message = "缺少 x 或 y 坐标"))
                }
                mapOutcome(displayController.tap(session, x, y)) {
                    success("display_id" to session.displayId, "x" to x, "y" to y)
                }
            }
            "shadow_swipe" -> withSession { session ->
                val x1 = args.optInt("x1", Int.MIN_VALUE)
                val y1 = args.optInt("y1", Int.MIN_VALUE)
                val x2 = args.optInt("x2", Int.MIN_VALUE)
                val y2 = args.optInt("y2", Int.MIN_VALUE)
                if (x1 == Int.MIN_VALUE || y1 == Int.MIN_VALUE || x2 == Int.MIN_VALUE || y2 == Int.MIN_VALUE) {
                    return@withSession text(error(code = "INVALID_ARGUMENT", message = "缺少滑动起止坐标"))
                }
                val duration = args.optInt("duration_ms", DEFAULT_SWIPE_MS)
                mapOutcome(displayController.swipe(session, x1, y1, x2, y2, duration)) {
                    success("display_id" to session.displayId, "duration_ms" to duration)
                }
            }
            "shadow_input_text" -> withSession { session ->
                val value = args.optString("text")
                mapOutcome(displayController.inputText(session, value)) {
                    success("display_id" to session.displayId, "length" to value.length)
                }
            }
            "shadow_press_key" -> withSession { session ->
                val key = args.optString("key")
                mapOutcome(displayController.pressKey(session, key)) {
                    success("display_id" to session.displayId, "key" to key.uppercase())
                }
            }
            "shadow_screenshot" -> shadowScreenshot()
            "shadow_ui_tree" -> shadowUiTree()
            else -> null
        }
    }

    /** 向前台服务暴露当前会话，用于心跳续租。 */
    fun currentSession(): ShadowDisplayManager.SessionState.Active? = displayManager.currentSession()

    /** 刷新心跳租约。 */
    fun heartbeat(): Boolean = displayManager.heartbeat()

    /** 释放影子屏。 */
    fun release(): Boolean = displayManager.release()

    private fun shadowOpen(args: JSONObject): AgentModelClient.ToolResult {
        val deploy = ShadowCarrierDeployer.ensureDeployed(context, logger, rootCommandExecutor, rootAvailable)
        if (deploy is ShadowCarrierDeployer.Result.Failed) {
            return text(error(deploy.code, deploy.message))
        }

        val width = args.optInt("width", 0).takeIf { it > 0 }
        val height = args.optInt("height", 0).takeIf { it > 0 }
        val defaults = defaultGeometry()
        val state = displayManager.ensureStarted(
            width = width ?: defaults.first,
            height = height ?: defaults.second,
            dpi = defaults.third,
        )
        return when (state) {
            is ShadowDisplayManager.SessionState.Failed -> text(error(state.code, state.message))
            is ShadowDisplayManager.SessionState.Active -> {
                val launchPackage = args.optString("package_name").takeIf(String::isNotBlank)
                val launchNote = launchPackage?.let { packageName ->
                    when (val outcome = displayController.launchApp(state, packageName)) {
                        is ShadowDisplayController.ShadowOutcome.Success -> "launched=$packageName"
                        is ShadowDisplayController.ShadowOutcome.Failure ->
                            "launch_failed=${outcome.code}"
                    }
                }
                text(
                    success(
                        "display_id" to state.displayId,
                        "width" to state.width,
                        "height" to state.height,
                        "dpi" to state.dpi,
                        "surface_flinger_id" to state.surfaceFlingerId,
                        "launch" to launchNote,
                    ),
                )
            }
            ShadowDisplayManager.SessionState.Idle -> text(
                error("SHADOW_DISPLAY_UNAVAILABLE", "影子屏未进入可用状态"),
            )
        }
    }

    private fun shadowClose(): AgentModelClient.ToolResult {
        val released = displayManager.release()
        return text(
            if (released) success("released" to true)
            else error("SHADOW_RELEASE_INCOMPLETE", "影子屏释放未完成，可能存在残留进程"),
        )
    }

    private fun shadowStatus(): AgentModelClient.ToolResult {
        val session = displayManager.currentSession()
        return text(
            if (session == null) {
                success("active" to false)
            } else {
                success(
                    "active" to true,
                    "display_id" to session.displayId,
                    "width" to session.width,
                    "height" to session.height,
                    "dpi" to session.dpi,
                )
            },
        )
    }

    private fun shadowScreenshot(): AgentModelClient.ToolResult = withSession { session ->
        when (val capture = displayController.capture(session)) {
            is ShadowDisplayController.ShadowCapture.Failed -> text(error(capture.code, capture.message))
            is ShadowDisplayController.ShadowCapture.Png -> {
                val image = runCatching {
                    AgentImageCodec.fromScreenBytes(capture.bytes, source = "eta_shadow")
                }.getOrNull()
                if (image == null) {
                    text(error("SHADOW_CAPTURE_DECODE_FAILED", "截图数据无法解析为图片"))
                } else {
                    AgentModelClient.ToolResult(
                        content = success(
                            "display_id" to session.displayId,
                            "width" to image.width,
                            "height" to image.height,
                        ),
                        images = listOf(image),
                    )
                }
            }
        }
    }

    private fun shadowUiTree(): AgentModelClient.ToolResult = withSession { session ->
        mapOutcome(displayController.dumpUiTree(session)) { output ->
            success("display_id" to session.displayId, "nodes" to output)
        }
    }

    /** 保证影子屏处于可用状态后执行动作。 */
    private inline fun withSession(
        block: (ShadowDisplayManager.SessionState.Active) -> AgentModelClient.ToolResult,
    ): AgentModelClient.ToolResult {
        val session = displayManager.currentSession()
            ?: return text(error("SHADOW_DISPLAY_UNAVAILABLE", "影子屏尚未开启，请先调用 shadow_open"))
        return block(session)
    }

    private fun mapOutcome(
        outcome: ShadowDisplayController.ShadowOutcome,
        onSuccess: (String) -> String,
    ): AgentModelClient.ToolResult = when (outcome) {
        is ShadowDisplayController.ShadowOutcome.Success -> text(onSuccess(outcome.output))
        is ShadowDisplayController.ShadowOutcome.Failure -> text(error(outcome.code, outcome.message))
    }

    /** 读取设备物理屏幕尺寸作为影子屏默认分辨率。 */
    private fun defaultGeometry(): Triple<Int, Int, Int> {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.takeIf { it > 0 } ?: ShadowDisplayManager.DEFAULT_WIDTH
        val height = metrics.heightPixels.takeIf { it > 0 } ?: ShadowDisplayManager.DEFAULT_HEIGHT
        val dpi = metrics.densityDpi.takeIf { it > 0 } ?: ShadowDisplayManager.DEFAULT_DPI
        return Triple(width, height, dpi)
    }

    private fun text(content: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(content = content)

    private fun success(vararg pairs: Pair<String, Any?>): String =
        JSONObject().put("ok", true).also { json ->
            pairs.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        }.toString()

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("error", code).put("message", message).toString()

    internal companion object {
        private const val DEFAULT_SWIPE_MS = 500

        val SHADOW_TOOL_NAMES: Set<String> = setOf(
            "shadow_open",
            "shadow_close",
            "shadow_status",
            "shadow_launch",
            "shadow_tap",
            "shadow_swipe",
            "shadow_input_text",
            "shadow_press_key",
            "shadow_screenshot",
            "shadow_ui_tree",
        )
    }
}
