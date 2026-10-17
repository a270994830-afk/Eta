package io.github.mangi.eta.agent.tool

import android.app.Activity
import android.content.Context
import io.github.mangi.eta.agent.device.recording.AgentScreenRecorder
import io.github.mangi.eta.agent.device.recording.RecordingForegroundService
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONObject

/**
 * 屏幕录制工具族（对齐 Operit 的录屏能力）。
 *
 * 平台约束：targetSdk 34+ 下每个 MediaProjection 实例只能创建一次 VirtualDisplay，同一授权
 * token 也不能复用，因此**每段录制都需要用户重新授权**：start 在无「已授权未消费」的
 * projection 时返回 `NEEDS_CONSENT`，主代理据此启动 [RecordingConsentActivity] 走系统弹窗。
 */
internal class AgentRecordingToolSet(
    private val context: Context,
    @Suppress("unused") private val logger: AgentLogger,
) {
    fun execute(name: String, args: JSONObject): String? = when (name) {
        "screen_record_start" -> start(args)
        "screen_record_stop" -> stop(args)
        "screen_record_status" -> AgentScreenRecorder.status(args.optString("handle").takeIf { it.isNotBlank() })
        "screen_record_list" -> AgentScreenRecorder.list(context)
        else -> null
    }

    private fun start(args: JSONObject): String {
        if (AgentScreenRecorder.isRecording()) {
            return error("ALREADY_RECORDING", "已有录制在进行中，请先调用 screen_record_stop")
        }
        val options = AgentScreenRecorder.Options.from(args)
        if (options.mic && !AgentScreenRecorder.hasAudioPermission(context)) {
            return error("AUDIO_PERMISSION_DENIED", "启用麦克风录制需要 RECORD_AUDIO 权限")
        }
        // 唯一可直接开录的情形：存在尚未被消费的 projection（此前授权后未真正开录）。
        if (AgentScreenRecorder.hasUnusedProjection()) {
            return AgentScreenRecorder.start(context, Activity.RESULT_OK, null, options)
        }
        AgentScreenRecorder.setPendingOptions(options)
        return JSONObject()
            .put("ok", false)
            .put("code", "NEEDS_CONSENT")
            .put("activity", CONSENT_COMPONENT)
            .put(
                "message",
                "需要用户授权录屏：以 root/am start 或用户点按启动 $CONSENT_COMPONENT，" +
                    "系统弹窗确认后将自动开始录制；也可用 --es $OPTIONS_EXTRA <json> 传参",
            )
            .toString()
    }

    private fun stop(args: JSONObject): String {
        val handle = args.optString("handle").takeIf { it.isNotBlank() }
        val result = AgentScreenRecorder.stop(handle)
        if (runCatching { JSONObject(result).optBoolean("ok") }.getOrDefault(false)) {
            RecordingForegroundService.stop(context)
        }
        return result
    }

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    companion object {
        /** 供执行层与目录层复用的工具名集合。 */
        val TOOL_NAMES: Set<String> = setOf(
            "screen_record_start",
            "screen_record_stop",
            "screen_record_status",
            "screen_record_list",
        )

        private const val CONSENT_COMPONENT =
            "io.github.mangi.eta/.agent.device.recording.RecordingConsentActivity"
        private const val OPTIONS_EXTRA =
            "io.github.mangi.eta.agent.device.recording.extra.OPTIONS_JSON"
    }
}
