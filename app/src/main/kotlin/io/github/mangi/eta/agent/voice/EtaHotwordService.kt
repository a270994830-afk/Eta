package io.github.mangi.eta.agent.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 语音唤醒前台服务：用 Porcupine 轻量引擎常驻监听唤醒词，命中后拉起 Eta 语音会话。
 *
 * 仅在设置开关开启时启动；唤醒时暂停监听把麦克风让给语音会话，会话结束后由
 * [resumeHotword] 恢复监听。任何异常都降级为停止自身，不干扰 Eta 的其它功能。
 */
internal class EtaHotwordService : Service() {

    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val managerRef = AtomicReference<PorcupineManager?>()
    private var toneGenerator: ToneGenerator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopHotword()
                stopSelf()
            }
            ACTION_PAUSE -> pauseListening()
            ACTION_RESUME -> {
                if (!Prefs.isEnabled(Prefs.Keys.ETA_HOTWORD_ENABLED)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundCompat()
                ensureHotwordRunning()
            }
            else -> {
                if (!Prefs.isEnabled(Prefs.Keys.ETA_HOTWORD_ENABLED)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    AndroidAgentLogger.warnThrottled("eta_hotword_permission_missing") {
                        "Hotword service started without RECORD_AUDIO permission"
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundCompat()
                ensureHotwordRunning()
            }
        }
        return START_STICKY
    }

    private fun ensureHotwordRunning() {
        if (running.get()) return
        if (managerRef.get() != null) {
            resumeListening()
            return
        }
        if (!started.compareAndSet(false, true)) return
        val accessKey = readAccessKey()
        if (accessKey.isNullOrBlank()) {
            AndroidAgentLogger.warnThrottled("eta_hotword_access_key_missing") {
                "Porcupine access key is not bundled; hotword disabled"
            }
            started.set(false)
            stopSelf()
            return
        }
        try {
            val builder = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setSensitivity(HOTWORD_SENSITIVITY)
            effectiveAsset(MODEL_ASSET_ZH)?.let(builder::setModelPath)
            effectiveAsset(MODEL_ASSET)?.let(builder::setModelPath)
            val keyword = effectiveAsset(KEYWORD_ASSET_ZH)
            if (keyword != null) {
                builder.setKeywordPath(keyword)
            } else {
                builder.setKeyword(Porcupine.BuiltInKeyword.JARVIS)
            }
            val manager = builder.build(applicationContext) { keywordIndex ->
                if (keywordIndex >= 0) {
                    triggerWake()
                }
            }
            managerRef.set(manager)
            manager.start()
            running.set(true)
            updateNotification(currentKeyword())
        } catch (error: Exception) {
            AndroidAgentLogger.warnThrottled("eta_hotword_start_failed") {
                "Porcupine init failed: ${error.javaClass.simpleName}"
            }
            started.set(false)
            stopSelf()
        }
    }

    private fun pauseListening() {
        if (!running.compareAndSet(true, false)) return
        runCatching { managerRef.get()?.stop() }
    }

    private fun resumeListening() {
        if (running.get()) return
        if (!Prefs.isEnabled(Prefs.Keys.ETA_HOTWORD_ENABLED)) return
        val manager = managerRef.get() ?: return
        runCatching {
            manager.start()
            running.set(true)
            updateNotification(currentKeyword())
        }
    }

    private fun triggerWake() {
        if (!Prefs.isEnabled(Prefs.Keys.ETA_HOTWORD_ENABLED)) {
            stopHotword()
            stopSelf()
            return
        }
        pauseListening()
        playWakeTone()
        runCatching {
            EtaAssistantOverlayService.show(context = this, voiceMode = true)
        }.onFailure {
            AndroidAgentLogger.warnThrottled("eta_hotword_wake_failed") {
                it.message ?: it.javaClass.simpleName
            }
        }
    }

    private fun playWakeTone() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            toneGenerator = tg
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 200)
            Handler(Looper.getMainLooper()).postDelayed({ tg.release() }, 350)
        }
    }

    private fun stopHotword() {
        pauseListening()
        managerRef.getAndSet(null)?.let { manager -> runCatching { manager.delete() } }
        toneGenerator?.release()
        toneGenerator = null
    }

    private fun readAccessKey(): String? =
        runCatching {
            assets.open(ACCESS_KEY_ASSET).bufferedReader().use { it.readText().trim() }
        }.getOrNull()?.takeIf { it.isNotBlank() && it != ACCESS_KEY_PLACEHOLDER }

    /** 返回 assets 中真实存在的资源名，供 Porcupine 直接加载；不存在则返回 null。 */
    private fun effectiveAsset(name: String): String? =
        runCatching {
            assets.open(name).close()
            name
        }.getOrNull()

    private fun currentKeyword(): String =
        Prefs.getString(Prefs.Keys.ETA_HOTWORD_KEYWORD).ifBlank { DEFAULT_KEYWORD }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.hotword_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(currentKeyword()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private fun updateNotification(keyword: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(keyword))
    }

    private fun buildNotification(keyword: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.hotword_notification_title))
            .setContentText(getString(R.string.hotword_notification_text, keyword))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    override fun onDestroy() {
        stopHotword()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "eta_hotword"
        private const val NOTIFICATION_ID = 0xE7A1
        private const val HOTWORD_SENSITIVITY = 0.6f
        private const val DEFAULT_KEYWORD = "小爱同学"
        private const val ACCESS_KEY_ASSET = "porcupine_access_key.txt"
        private const val ACCESS_KEY_PLACEHOLDER = "__ETA_PORCUPINE_ACCESS_KEY__"
        private const val KEYWORD_ASSET_ZH = "wakeword.ppn"
        private const val MODEL_ASSET_ZH = "porcupine_params_zh.pv"
        private const val MODEL_ASSET = "porcupine_params.pv"

        const val ACTION_START = "io.github.mangi.eta.agent.voice.HOTWORD_START"
        const val ACTION_STOP = "io.github.mangi.eta.agent.voice.HOTWORD_STOP"
        const val ACTION_PAUSE = "io.github.mangi.eta.agent.voice.HOTWORD_PAUSE"
        const val ACTION_RESUME = "io.github.mangi.eta.agent.voice.HOTWORD_RESUME"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, EtaHotwordService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EtaHotwordService::class.java))
        }

        fun pauseHotword(context: Context) {
            context.startService(
                Intent(context, EtaHotwordService::class.java).setAction(ACTION_PAUSE),
            )
        }

        fun resumeHotword(context: Context) {
            context.startService(
                Intent(context, EtaHotwordService::class.java).setAction(ACTION_RESUME),
            )
        }
    }
}
