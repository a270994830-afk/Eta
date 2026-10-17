package io.github.mangi.eta.agent.widget

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mcp.EtaMcpServerService
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.MainActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 桌面小组件：一眼看 Eta 状态，一键发起交互。
 *
 * 展示的都是进程内即可读到的轻量状态（MCP 前台通知、agent_usage.json 今日用量、
 * 最近一次助理结果通知），不做 DB / 网络等重活；任何读取失败都退化为占位文案，
 * 不会把异常带到 App 主流程。
 */
class EtaStatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            runCatching { appWidgetManager.updateAppWidget(id, buildViews(context)) }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_WIDGET_VOICE -> {
                // 复用 Eta 自己的语音入口：由 App 进程收到广播后再拉起浮层，
                // 与 EtaSpokenQueryReceiver 同一路径。若被后台启动限制拦截，
                // 退化为打开 App 并标记 widget_action=voice。
                val directStarted = runCatching {
                    EtaAssistantOverlayService.show(context.applicationContext, voiceMode = true)
                }.isSuccess
                if (!directStarted) {
                    runCatching {
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .setAction(ACTION_WIDGET_NEW_CHAT)
                                .putExtra(EXTRA_WIDGET_ACTION, EXTRA_VALUE_VOICE)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                ),
                        )
                    }
                }
                return
            }
            ACTION_WIDGET_REFRESH -> {
                runCatching { refreshAll(context.applicationContext) }
                return
            }
        }
        super.onReceive(context, intent)
    }

    private fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, EtaStatusWidgetProvider::class.java))
        if (ids.isNotEmpty()) onUpdate(context, manager, ids)
    }

    private fun buildViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.eta_status_widget)
        views.setTextViewText(R.id.widget_status, statusLine(context))
        views.setTextViewText(R.id.widget_detail, detailLine(context))
        views.setTextViewText(R.id.widget_btn_new, LABEL_NEW_CHAT)
        views.setTextViewText(R.id.widget_btn_voice, LABEL_VOICE)
        views.setOnClickPendingIntent(R.id.widget_btn_new, newChatPendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_btn_voice, voicePendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_status, refreshPendingIntent(context))
        return views
    }

    private fun statusLine(context: Context): String {
        val enabled = runCatching { Prefs.isEnabled(Prefs.Keys.MCP_SERVER_ENABLED) }
            .getOrDefault(false)
        val running = runCatching { hasNotification(context, EtaMcpServerService.NOTIFICATION_ID) }
            .getOrDefault(false)
        val tokens = runCatching { todayTokens(context) }.getOrDefault(0L)
        val headline = when {
            running -> "Eta 已就绪"
            enabled -> "Eta 启动中"
            else -> "Eta 待机"
        }
        val mcp = when {
            running -> "运行中"
            enabled -> "已开启"
            else -> "关闭"
        }
        return "$headline · MCP $mcp · 今日 ${formatTokens(tokens)} tokens"
    }

    private fun detailLine(context: Context): String {
        val summary = runCatching { lastResultSummary(context) }.getOrNull()
        return if (summary.isNullOrBlank()) {
            "最近：暂无任务记录"
        } else {
            "最近：" + truncate(summary, SUMMARY_MAX_CHARS)
        }
    }

    /** MCP / 结果通知都由本 App 发出，读自己的活动通知不需要额外权限。 */
    private fun hasNotification(context: Context, id: Int): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.activeNotifications.any { it.id == id }
    }

    private fun lastResultSummary(context: Context): String? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        val posted = manager.activeNotifications
            .firstOrNull { it.id == ASSISTANT_RESULT_NOTIFICATION_ID } ?: return null
        val extras = posted.notification.extras
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: return null
        return text.toString().replace(WHITESPACE, " ").trim().takeIf { it.isNotEmpty() }
    }

    /** 今日 token 用量：读 filesDir/agent_usage.json 的 days[today].total_tokens。 */
    private fun todayTokens(context: Context): Long {
        val file = File(context.filesDir, USAGE_FILE_NAME)
        if (!file.isFile) return 0L
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return 0L
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val day = root.optJSONObject("days")?.optJSONObject(today) ?: return 0L
        return day.optLong("total_tokens", 0L).coerceAtLeast(0L)
    }

    private fun formatTokens(value: Long): String = when {
        value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000L -> String.format(Locale.US, "%.1fk", value / 1_000.0)
        else -> value.toString()
    }

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"

    private fun newChatPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_WIDGET_NEW_CHAT)
            .putExtra(EXTRA_WIDGET_ACTION, EXTRA_VALUE_NEW_CHAT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, REQUEST_NEW_CHAT, intent, PENDING_FLAGS)
    }

    private fun voicePendingIntent(context: Context): PendingIntent {
        // 目标写成显式组件（本 Provider），PendingIntent 以本 App 身份执行，
        // exported=false 也无需在 receiver 的 intent-filter 里登记该 action。
        val intent = Intent(context, EtaStatusWidgetProvider::class.java).setAction(ACTION_WIDGET_VOICE)
        return PendingIntent.getBroadcast(context, REQUEST_VOICE, intent, PENDING_FLAGS)
    }

    private fun refreshPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, EtaStatusWidgetProvider::class.java).setAction(ACTION_WIDGET_REFRESH)
        return PendingIntent.getBroadcast(context, REQUEST_REFRESH, intent, PENDING_FLAGS)
    }

    companion object {
        const val ACTION_WIDGET_VOICE = "io.github.mangi.eta.agent.widget.ACTION_VOICE"
        const val ACTION_WIDGET_REFRESH = "io.github.mangi.eta.agent.widget.ACTION_REFRESH"
        const val ACTION_WIDGET_NEW_CHAT = "io.github.mangi.eta.agent.widget.ACTION_NEW_CHAT"
        const val EXTRA_WIDGET_ACTION = "widget_action"
        const val EXTRA_VALUE_NEW_CHAT = "new_chat"
        const val EXTRA_VALUE_VOICE = "voice"

        private const val LABEL_NEW_CHAT = "新对话"
        private const val LABEL_VOICE = "语音"
        private const val SUMMARY_MAX_CHARS = 60
        private const val USAGE_FILE_NAME = "agent_usage.json"

        /** 与 EtaAssistantOverlayService 的 RESULT_NOTIFICATION_ID 保持一致（该常量是私有的）。 */
        private const val ASSISTANT_RESULT_NOTIFICATION_ID = 0xE7A2

        private const val REQUEST_NEW_CHAT = 0xE7A001
        private const val REQUEST_VOICE = 0xE7A002
        private const val REQUEST_REFRESH = 0xE7A003

        private const val PENDING_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private val WHITESPACE = Regex("\\s+")
    }
}
