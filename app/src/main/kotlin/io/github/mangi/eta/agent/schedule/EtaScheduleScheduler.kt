package io.github.mangi.eta.agent.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Calendar

/** 为每个启用的定时任务排布下一次闹钟；触发、重启、增删改与 App 启动时都会重排。 */
internal object EtaScheduleScheduler {

    fun sync(context: Context) {
        val appContext = context.applicationContext
        EtaScheduleStore.load(appContext).forEach { task ->
            if (task.enabled) scheduleNext(appContext, task) else cancel(appContext, task)
        }
    }

    fun scheduleNext(context: Context, task: EtaScheduleStore.Task) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            val triggerAt = nextTriggerAt(task)
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context, task))
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context, task))
            }
        }
    }

    fun cancel(context: Context, task: EtaScheduleStore.Task) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarmManager.cancel(pendingIntent(context, task)) }
    }

    private fun nextTriggerAt(task: EtaScheduleStore.Task): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, task.hour)
        calendar.set(Calendar.MINUTE, task.minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    private fun pendingIntent(context: Context, task: EtaScheduleStore.Task): PendingIntent {
        val intent = Intent(context, EtaScheduleReceiver::class.java)
            .setAction(EtaScheduleReceiver.ACTION_TRIGGER)
            .setData(Uri.parse("eta-schedule://task/${task.id}"))
            .putExtra(EtaScheduleReceiver.EXTRA_TASK_ID, task.id)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
