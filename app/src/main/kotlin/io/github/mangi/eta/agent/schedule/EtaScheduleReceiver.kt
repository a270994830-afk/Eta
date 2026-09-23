package io.github.mangi.eta.agent.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 定时任务触发入口：闹钟到点自动运行；通知「立即运行」由用户点按后运行；设备重启后重排全部闹钟。
 */
class EtaScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> EtaScheduleScheduler.sync(appContext)
            ACTION_TRIGGER -> handleTrigger(appContext, intent)
            ACTION_RUN_NOW -> handleRunNow(appContext, intent)
        }
    }

    private fun handleTrigger(context: Context, intent: Intent) {
        val task = findTask(context, intent) ?: return
        if (!task.enabled) return
        EtaScheduleScheduler.scheduleNext(context, task)
        EtaScheduleService.start(context, task, userInitiated = false)
    }

    private fun handleRunNow(context: Context, intent: Intent) {
        val task = findTask(context, intent) ?: return
        EtaScheduleService.start(context, task, userInitiated = true)
    }

    private fun findTask(context: Context, intent: Intent): EtaScheduleStore.Task? {
        val id = intent.getStringExtra(EXTRA_TASK_ID) ?: return null
        return EtaScheduleStore.load(context).firstOrNull { it.id == id }
    }

    companion object {
        const val ACTION_TRIGGER = "io.github.mangi.eta.agent.schedule.ACTION_TRIGGER"
        const val ACTION_RUN_NOW = "io.github.mangi.eta.agent.schedule.ACTION_RUN_NOW"
        const val EXTRA_TASK_ID = "io.github.mangi.eta.agent.schedule.EXTRA_TASK_ID"
    }
}
