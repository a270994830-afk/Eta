package io.github.mangi.eta.agent.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收厂商助手钩子转交的口述文本，并打开 Eta 语音面板。
 *
 * 钩子运行在厂商助手进程内，直接启动 Eta 界面会被 Android 的后台启动限制拦截，
 * 因此改为发送广播：由 Eta 自己的进程收到后再启动浮层，符合系统限制。
 */
class EtaSpokenQueryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SPOKEN_QUERY) return
        val query = intent.getStringExtra(EXTRA_QUERY)?.takeIf { it.isNotBlank() } ?: return
        EtaAssistantOverlayService.show(
            context = context.applicationContext,
            voiceMode = true,
            initialQuery = query,
        )
    }

    companion object {
        const val ACTION_SPOKEN_QUERY =
            "io.github.mangi.eta.agent.voice.action.SPOKEN_QUERY"
        const val EXTRA_QUERY = "io.github.mangi.eta.agent.voice.extra.SPOKEN_QUERY"
    }
}
