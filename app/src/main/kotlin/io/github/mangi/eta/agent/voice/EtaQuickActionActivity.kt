package io.github.mangi.eta.agent.voice

import android.app.Activity
import android.os.Bundle

/**
 * 「开工 / 收工」快捷方式的跳板：不显示界面，把指令交给语音面板后立即结束。
 *
 * launcher 通过显式 Intent 启动本 Activity（因此 exported=true），为防止任意第三方
 * 注入 prompt，只接受两个固定指令，其余一律忽略。
 */
class EtaQuickActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val query = intent?.getStringExtra(EXTRA_QUERY)
        if (query == QUERY_KAIGONG || query == QUERY_SHOUGONG) {
            runCatching {
                EtaAssistantOverlayService.show(
                    context = applicationContext,
                    voiceMode = true,
                    initialQuery = query,
                )
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_QUERY = "io.github.mangi.eta.agent.voice.extra.QUICK_ACTION_QUERY"
        const val QUERY_KAIGONG = "开工"
        const val QUERY_SHOUGONG = "收工"
    }
}
