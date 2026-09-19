package io.github.mangi.eta.agent.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.MainActivity

/**
 * 配送提醒接收器：闹钟触发时发一条高优先级通知，并重排下一天；设备重启后重排所有闹钟。
 *
 * 通知点击只打开 Eta 主界面（不带任何自动动作），是否开工由用户自己说。
 */
class EtaReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            EtaReminderScheduler.sync(appContext)
            return
        }
        val kind = ReminderKind.entries.firstOrNull { it.action == intent.action } ?: return
        if (!Prefs.isEnabled(kind.enabledKey)) return
        notify(appContext, kind)
        EtaReminderScheduler.scheduleNext(appContext, kind)
    }

    private fun notify(context: Context, kind: ReminderKind) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            kind.requestCode,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(kind.textRes)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(kind.titleRes))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { manager.notify(kind.requestCode, notification) }
    }

    private companion object {
        const val CHANNEL_ID = "eta_delivery_reminder"
    }
}
