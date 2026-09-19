package io.github.mangi.eta.agent.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import java.util.Calendar

/**
 * 配送提醒种类：各带独立开关、闹钟与通知。触发时间按设备本地时区。
 *
 * KAIGONG：每晚 19:55 提醒该拉次日单；WINDOW：每天 7:55 提醒 8 点的核销时间窗切换。
 */
internal enum class ReminderKind(
    val action: String,
    val requestCode: Int,
    val hour: Int,
    val minute: Int,
    val titleRes: Int,
    val textRes: Int,
) {
    KAIGONG(
        "io.github.mangi.eta.agent.reminder.ACTION_KAIGONG",
        0x5251,
        19,
        55,
        R.string.reminder_kaigong_notify_title,
        R.string.reminder_kaigong_notify_text,
    ),
    WINDOW(
        "io.github.mangi.eta.agent.reminder.ACTION_WINDOW",
        0x5252,
        7,
        55,
        R.string.reminder_window_notify_title,
        R.string.reminder_window_notify_text,
    );

    val enabledKey: String
        get() = when (this) {
            KAIGONG -> Prefs.Keys.ETA_REMINDER_KAIGONG
            WINDOW -> Prefs.Keys.ETA_REMINDER_WINDOW
        }
}

/**
 * 闹钟排布：打开开关、App 启动、设备重启、单次提醒触发后都会重排「下一次」，
 * 因此不用维护重复闹钟，也不会随系统延迟累积漂移（每次从当下重算）。
 */
internal object EtaReminderScheduler {

    fun sync(context: Context) {
        val appContext = context.applicationContext
        ReminderKind.entries.forEach { kind ->
            if (Prefs.isEnabled(kind.enabledKey)) {
                scheduleNext(appContext, kind)
            } else {
                cancel(appContext, kind)
            }
        }
    }

    fun scheduleNext(context: Context, kind: ReminderKind) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextTriggerAt(kind),
                pendingIntent(context, kind),
            )
        }
    }

    fun cancel(context: Context, kind: ReminderKind) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarmManager.cancel(pendingIntent(context, kind)) }
    }

    private fun nextTriggerAt(kind: ReminderKind): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, kind.hour)
        calendar.set(Calendar.MINUTE, kind.minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    private fun pendingIntent(context: Context, kind: ReminderKind): PendingIntent {
        val intent = Intent(context, EtaReminderReceiver::class.java).setAction(kind.action)
        return PendingIntent.getBroadcast(
            context,
            kind.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
