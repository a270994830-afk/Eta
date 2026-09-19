package io.github.mangi.eta.agent.voice

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 「开工 / 收工」桌面快捷方式：长按 Eta 图标可用，点击后打开语音面板并直接提交对应指令。
 * 每次 App 启动重新发布（id 固定、幂等），可修复被系统或桌面清掉的快捷方式。
 */
internal object EtaQuickActionShortcuts {

    fun publish(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val appContext = context.applicationContext
        runCatching {
            manager.setDynamicShortcuts(
                listOf(
                    shortcut(
                        appContext,
                        id = "zsl_kaigong",
                        shortRes = R.string.shortcut_kaigong_label,
                        longRes = R.string.shortcut_kaigong_long,
                        query = EtaQuickActionActivity.QUERY_KAIGONG,
                    ),
                    shortcut(
                        appContext,
                        id = "zsl_shougong",
                        shortRes = R.string.shortcut_shougong_label,
                        longRes = R.string.shortcut_shougong_long,
                        query = EtaQuickActionActivity.QUERY_SHOUGONG,
                    ),
                )
            )
        }.onFailure {
            AndroidAgentLogger.warn("Eta quick shortcuts publish failed: ${it.javaClass.simpleName}")
        }
    }

    private fun shortcut(
        context: Context,
        id: String,
        shortRes: Int,
        longRes: Int,
        query: String,
    ): ShortcutInfo =
        ShortcutInfo.Builder(context, id)
            .setShortLabel(context.getString(shortRes))
            .setLongLabel(context.getString(longRes))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_launcher_foreground))
            .setIntent(
                Intent(context, EtaQuickActionActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(EtaQuickActionActivity.EXTRA_QUERY, query)
            )
            .build()
}
