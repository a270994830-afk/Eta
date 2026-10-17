package io.github.mangi.eta.agent.device.recording

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.MainActivity
import org.json.JSONObject

/**
 * 屏幕录制的前台服务宿主（type = mediaProjection，启用麦克风时叠加 microphone）。
 *
 * 存在的唯一目的是满足 targetSdk 34+ 的平台要求：
 * - 必须在用户授权之后启动 mediaProjection 前台服务，且在其进入前台之后才能调用
 *   `MediaProjectionManager.getMediaProjection()`。
 * 因此 [AgentScreenRecorder.start] 由本服务的 onStartCommand 在 [ensureForeground] 之后触发。
 */
internal class RecordingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                runCatching { AgentScreenRecorder.release() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_RECORDING -> {
                val options = AgentScreenRecorder.Options.fromJson(intent.getStringExtra(EXTRA_OPTIONS))
                if (!ensureForeground(options.mic)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                val outcome = AgentScreenRecorder.start(applicationContext, resultCode, data, options)
                if (!isOk(outcome)) {
                    AndroidAgentLogger.warn("Screen recording start rejected: $outcome")
                    runCatching { AgentScreenRecorder.release() }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                // 无 action（进程重建）或未知 action：仅保持前台，避免空广播型 service 被回收。
                if (!ensureForeground(mic = false)) stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun ensureForeground(mic: Boolean): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "屏幕录制", NotificationManager.IMPORTANCE_LOW),
        )
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        return try {
            startForeground(NOTIFICATION_ID, notification(mic), type)
            true
        } catch (t: RuntimeException) {
            AndroidAgentLogger.warn("Screen recording foreground failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun notification(mic: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingForegroundService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Eta 屏幕录制")
            .setContentText(if (mic) "正在录制屏幕与麦克风" else "正在录制屏幕")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
    }

    private fun isOk(json: String): Boolean =
        runCatching { JSONObject(json).optBoolean("ok") }.getOrDefault(false)

    companion object {
        private const val CHANNEL = "eta_screen_recording"
        private const val NOTIFICATION_ID = 0x5245_4301 // "REC"
        private const val ACTION_START_RECORDING = "io.github.mangi.eta.action.SCREEN_RECORD_START"
        private const val ACTION_STOP_ALL = "io.github.mangi.eta.action.SCREEN_RECORD_STOP_ALL"
        private const val EXTRA_RESULT_CODE = "io.github.mangi.eta.extra.RECORD_RESULT_CODE"
        private const val EXTRA_RESULT_DATA = "io.github.mangi.eta.extra.RECORD_RESULT_DATA"
        private const val EXTRA_OPTIONS = "io.github.mangi.eta.extra.RECORD_OPTIONS"

        /** 由授权 Activity 在 onActivityResult 中调用：把授权结果交给服务，在前台态启动录制。 */
        fun startWithConsent(context: Context, resultCode: Int, data: Intent, options: AgentScreenRecorder.Options) {
            val intent = Intent(context, RecordingForegroundService::class.java)
                .setAction(ACTION_START_RECORDING)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_OPTIONS, options.toJson())
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                AndroidAgentLogger.warn("Screen recording service start failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        /** 停止前台服务（录制已由调用方停好）。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RecordingForegroundService::class.java)) }
        }
    }
}
