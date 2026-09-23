package io.github.mangi.eta.agent.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.MainActivity
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 定时任务执行服务：前台保活运行一次 Agent 任务，完成后以通知汇报结果。
 *
 * 后台启动前台服务可能被系统拒绝（无精确闹钟、未豁免电池优化等），
 * 此时降级为一条「待运行」通知，由用户点按「立即运行」再入本服务。
 */
class EtaScheduleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runJob: Job? = null
    private var activeClient: AgentRuntimeClient? = null
    private var activeRunId: String? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.schedule_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            activeRunId?.let { runId -> activeClient?.cancelRun(runId) }
            return START_NOT_STICKY
        }
        val taskId = intent?.getStringExtra(EtaScheduleReceiver.EXTRA_TASK_ID)
        val task = taskId?.let { id -> EtaScheduleStore.load(this).firstOrNull { it.id == id } }
        if (task == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (runJob != null) {
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, progressNotification(task), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        runJob = scope.launch { execute(task) }
        return START_NOT_STICKY
    }

    private fun execute(task: EtaScheduleStore.Task) {
        AndroidAgentLogger.info("Scheduled task started: id=${task.id.take(8)}")
        var result: AgentRuntimeWire.RunResult? = null
        var error: String? = null
        try {
            val client = AgentRuntimeClient(this, AndroidAgentLogger)
            activeClient = client
            val runId = UUID.randomUUID().toString()
            activeRunId = runId
            val active = client.queryActiveRun()
            val busy = (active as? AgentRuntimeClient.ActiveRunQuery.Known)?.runId?.isNotBlank() == true
            if (busy) {
                error = getString(R.string.schedule_skipped_busy)
            } else {
                result = client.run(
                    request = AgentRuntimeWire.RunRequest(
                        runId = runId,
                        prompt = task.prompt,
                        config = AgentModelClient.loadConfig(),
                        images = emptyList(),
                    ),
                    onEvent = {},
                )
                client.ackResult(runId)
            }
        } catch (failure: Throwable) {
            AndroidAgentLogger.warn("Scheduled task failed: type=${failure.safeLogType()}")
            error = failure.message
        } finally {
            activeClient = null
            activeRunId = null
            notifyResult(task, result, error)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun progressNotification(task: EtaScheduleStore.Task): Notification {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, EtaScheduleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.schedule_running_title))
            .setContentText(getString(R.string.schedule_running_text, timeLabel(task)))
            .setContentIntent(openMain())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.schedule_stop), stop).build())
            .build()
    }

    private fun notifyResult(task: EtaScheduleStore.Task, result: AgentRuntimeWire.RunResult?, error: String?) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val ok = result?.ok == true
        val title = getString(if (ok) R.string.schedule_done_title else R.string.schedule_failed_title)
        val body = when {
            ok -> result?.content?.trim().orEmpty().ifBlank { getString(R.string.schedule_no_output) }
            error != null -> error
            else -> result?.error?.takeIf { it.isNotBlank() } ?: getString(R.string.schedule_failed_fallback)
        }.take(MAX_RESULT_CHARS)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$title · ${timeLabel(task)}")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openMain())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun openMain(): PendingIntent = PendingIntent.getActivity(
        this,
        2,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun timeLabel(task: EtaScheduleStore.Task): String = "%02d:%02d".format(task.hour, task.minute)

    companion object {
        private const val CHANNEL = "eta_schedule"
        private const val NOTIFICATION_ID = 1108
        private const val MAX_RESULT_CHARS = 1_200
        const val ACTION_STOP = "io.github.mangi.eta.agent.schedule.ACTION_STOP"

        internal fun start(context: Context, task: EtaScheduleStore.Task, userInitiated: Boolean) {
            val intent = Intent(context, EtaScheduleService::class.java)
                .putExtra(EtaScheduleReceiver.EXTRA_TASK_ID, task.id)
            try {
                context.startForegroundService(intent)
            } catch (failure: RuntimeException) {
                AndroidAgentLogger.warn("Scheduled task start rejected: type=${failure.safeLogType()}")
                notifyPending(context, task, startFailed = userInitiated)
            }
        }

        private fun notifyPending(context: Context, task: EtaScheduleStore.Task, startFailed: Boolean) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.schedule_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val runNow = PendingIntent.getBroadcast(
                context,
                11,
                Intent(context, EtaScheduleReceiver::class.java)
                    .setAction(EtaScheduleReceiver.ACTION_RUN_NOW)
                    .setData(Uri.parse("eta-schedule://task/${task.id}"))
                    .putExtra(EtaScheduleReceiver.EXTRA_TASK_ID, task.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val open = PendingIntent.getActivity(
                context,
                12,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val label = "%02d:%02d".format(task.hour, task.minute)
            val text = if (startFailed) {
                context.getString(R.string.schedule_start_failed, label)
            } else {
                context.getString(R.string.schedule_pending_text, label)
            }
            val builder = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.schedule_pending_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_REMINDER)
            if (!startFailed) {
                builder.addAction(
                    Notification.Action.Builder(null, context.getString(R.string.schedule_run_now), runNow).build(),
                )
            }
            runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
        }
    }
}
