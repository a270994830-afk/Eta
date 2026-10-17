package io.github.mangi.eta.agent.device.recording

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 屏幕录制授权桥：透明、不入最近任务。
 *
 * 流程：onCreate 拉起系统录屏授权弹窗 → onActivityResult 把 resultCode/data 交给
 * [AgentScreenRecorder.beginFromConsent]（内部转交 [RecordingForegroundService]，
 * 由其在前台态启动录制）→ finish。
 *
 * 注意：manifest **不能**给本 Activity 加 `android:noHistory="true"`。官方文档明确
 * 「onActivityResult 在 noHistory=true 时永不被调用」，会导致授权结果丢失、录制无法启动。
 * 通过 `excludeFromRecents="true"` + 自己 finish() 达到同样「不留痕迹」的效果。
 */
class RecordingConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val options = readOptions()
        AgentScreenRecorder.setPendingOptions(options)

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val captureIntent = try {
            manager?.createScreenCaptureIntent()
        } catch (t: Throwable) {
            AndroidAgentLogger.warn("createScreenCaptureIntent failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (captureIntent == null) {
            AgentScreenRecorder.clearPendingOptions()
            finish()
            return
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(captureIntent, REQUEST_CONSENT)
        } catch (t: Throwable) {
            AndroidAgentLogger.warn("Screen capture consent launch failed: ${t.javaClass.simpleName}: ${t.message}")
            AgentScreenRecorder.clearPendingOptions()
            finish()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONSENT) return
        if (resultCode == RESULT_OK && data != null) {
            AgentScreenRecorder.beginFromConsent(this, resultCode, data)
        } else {
            AgentScreenRecorder.clearPendingOptions()
            AndroidAgentLogger.info("Screen recording consent denied")
        }
        finish()
    }

    private fun readOptions(): AgentScreenRecorder.Options {
        val json = intent?.getStringExtra(EXTRA_OPTIONS)
        if (!json.isNullOrBlank()) {
            return AgentScreenRecorder.Options.fromJson(json)
        }
        return AgentScreenRecorder.peekPendingOptions() ?: AgentScreenRecorder.Options()
    }

    companion object {
        /** 可选：把 [AgentScreenRecorder.Options.toJson] 的 JSON 通过 `am start --es` 传入。 */
        const val EXTRA_OPTIONS = "io.github.mangi.eta.agent.device.recording.extra.OPTIONS_JSON"
        private const val REQUEST_CONSENT = 0x5245
    }
}
