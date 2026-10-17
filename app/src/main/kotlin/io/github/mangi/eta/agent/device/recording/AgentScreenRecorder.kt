package io.github.mangi.eta.agent.device.recording

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import io.github.mangi.eta.core.AndroidAgentLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 屏幕录制核心：MediaProjection + MediaRecorder + VirtualDisplay，写入应用私有外部目录。
 *
 * 平台约束（targetSdk 34+，本项目 targetSdk=36）：
 * - 每个 MediaProjection 实例只能成功执行一次 [MediaProjection.createVirtualDisplay]；
 *   录制结束后必须调用 [MediaProjection.stop]，之后该实例不可复用，同一授权 token 也不能
 *   再次交给 getMediaProjection。因此**每一段录制都需要重新走一次系统授权**。
 * - getMediaProjection 必须在类型为 mediaProjection 的前台服务已进入前台之后调用，
 *   故实际的 start 由 [RecordingForegroundService] 在 startForeground 之后触发。
 *
 * 单实例：再次 [start] 返回 `ALREADY_RECORDING`。所有异常都收敛为错误码。
 */
internal object AgentScreenRecorder {

    /** 录制参数。[width]/[height] 为 0 时取设备真实屏幕尺寸。 */
    data class Options(
        val width: Int = 0,
        val height: Int = 0,
        val bitRate: Int = 8_000_000,
        val mic: Boolean = false,
        val maxSeconds: Int = 300,
        val name: String? = null,
    ) {
        fun toJson(): String = JSONObject()
            .put("width", width)
            .put("height", height)
            .put("bit_rate", bitRate)
            .put("mic", mic)
            .put("max_seconds", maxSeconds)
            .put("name", name ?: JSONObject.NULL)
            .toString()

        companion object {
            fun from(args: JSONObject): Options = Options(
                width = args.optInt("width", 0),
                height = args.optInt("height", 0),
                bitRate = args.optInt("bit_rate", DEFAULT_BIT_RATE),
                mic = args.optBoolean("mic", false),
                maxSeconds = args.optInt("max_seconds", DEFAULT_MAX_SECONDS),
                name = args.optString("name").takeIf { it.isNotBlank() },
            )

            private const val DEFAULT_BIT_RATE = 8_000_000
            private const val DEFAULT_MAX_SECONDS = 300

            fun fromJson(json: String?): Options =
                if (json.isNullOrBlank()) Options() else runCatching { from(JSONObject(json)) }.getOrDefault(Options())
        }
    }

    private data class Session(
        val file: File,
        val recorder: MediaRecorder,
        val virtualDisplay: VirtualDisplay,
        val startedAtElapsed: Long,
        val startedAtWall: Long,
        val width: Int,
        val height: Int,
    )

    private val lock = Any()

    @Volatile
    private var session: Session? = null

    @Volatile
    private var recording = false

    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var projectionValid = false

    /** 已消费过的 projection 不能再执行 createVirtualDisplay；仅未消费的才可能直接复用。 */
    @Volatile
    private var projectionConsumed = false

    private var projectionCallback: MediaProjection.Callback? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var pendingOptions: Options? = null

    private var autoStopTask: ScheduledFuture<*>? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "eta-screen-recorder").apply { isDaemon = true }
    }

    fun isRecording(): Boolean = recording

    /** 是否存在「已授权但尚未被消费」的 projection（唯一可直接开录的情形）。 */
    fun hasUnusedProjection(): Boolean = projectionValid && !projectionConsumed

    fun setPendingOptions(options: Options) {
        pendingOptions = options
    }

    fun peekPendingOptions(): Options? = pendingOptions

    fun consumePendingOptions(): Options = pendingOptions.also { pendingOptions = null } ?: Options()

    fun clearPendingOptions() {
        pendingOptions = null
    }

    /** 授权 Activity 拿到 resultCode/data 后的入口：交给前台服务在其 startForeground 之后启动。 */
    fun beginFromConsent(context: Context, resultCode: Int, data: Intent) {
        val options = consumePendingOptions()
        RecordingForegroundService.startWithConsent(context.applicationContext, resultCode, data, options)
    }

    /**
     * 启动录制。若已有未消费的 projection 则忽略 resultCode/data 直接复用，否则要求
     * resultCode=RESULT_OK 且 data 非空。调用方需保证 mediaProjection 前台服务已在运行。
     */
    fun start(context: Context, resultCode: Int, data: Intent?, options: Options): String {
        synchronized(lock) {
            if (recording) return error("ALREADY_RECORDING", "已有录制在进行中，请先停止")
            val app = context.applicationContext
            if (options.mic && !hasAudioPermission(app)) {
                return error("AUDIO_PERMISSION_DENIED", "启用麦克风录制需要 RECORD_AUDIO 权限")
            }
            if (options.mic) {
                val busy = micBusyReason(app)
                if (busy != null) {
                    return error(
                        "MIC_BUSY",
                        "麦克风当前被占用：$busy。请先结束语音识别或通话，或改用 mic=false 仅录屏。",
                    )
                }
            }
            val mediaProjection = obtainProjection(app, resultCode, data)
                ?: return error("NO_PROJECTION", "没有可用的屏幕捕获授权，需先完成系统录屏授权")

            val geometry = resolveGeometry(app)
            val width = normalizeDimension(options.width.takeIf { it > 0 } ?: geometry[0])
            val height = normalizeDimension(options.height.takeIf { it > 0 } ?: geometry[1])
            val dpi = geometry[2]
            val dir = recordingsDir(app).also { it.mkdirs() }
            val file = File(dir, buildFileName(options.name))

            val recorder = MediaRecorder(app)
            var virtualDisplay: VirtualDisplay? = null
            try {
                configure(recorder, options, width, height, file)
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    DISPLAY_NAME,
                    width,
                    height,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorder.surface,
                    null,
                    null,
                )
                if (virtualDisplay == null) {
                    throw IllegalStateException("createVirtualDisplay 返回 null")
                }
                recorder.start()
            } catch (t: Throwable) {
                runCatching { virtualDisplay?.release() }
                runCatching { recorder.reset() }
                runCatching { recorder.release() }
                runCatching { file.delete() }
                // 该 projection 已尝试过 createVirtualDisplay，按平台语义视为已消费，后续需重新授权。
                projectionConsumed = true
                AndroidAgentLogger.warn("Screen record start failed: ${t.javaClass.simpleName}: ${t.message}")
                return error("START_FAILED", "启动录制失败：" + (t.message ?: t.javaClass.simpleName))
            }

            val active = Session(
                file = file,
                recorder = recorder,
                virtualDisplay = requireNotNull(virtualDisplay),
                startedAtElapsed = SystemClock.elapsedRealtime(),
                startedAtWall = System.currentTimeMillis(),
                width = width,
                height = height,
            )
            session = active
            projectionConsumed = true
            recording = true
            appContext = app
            scheduleAutoStop(app, options.maxSeconds)
            AndroidAgentLogger.info("Screen recording started: ${file.absolutePath} ${width}x$height mic=${options.mic}")

            return JSONObject()
                .put("ok", true)
                .put("recording", true)
                .put("handle", file.absolutePath)
                .put("file", file.absolutePath)
                .put("name", file.name)
                .put("width", width)
                .put("height", height)
                .put("bit_rate", options.bitRate)
                .put("mic", options.mic)
                .put("max_seconds", options.maxSeconds)
                .put("started_at", active.startedAtWall)
                .apply {
                    if (options.mic) put("mic_warning", MIC_WARNING)
                }
                .toString()
        }
    }

    /** 停止并释放当前录制，返回文件路径与时长/大小。handle 为 start 返回的文件路径，可省略；重复调用幂等。 */
    fun stop(handle: String?): String {
        synchronized(lock) {
            val active = session ?: return JSONObject()
                .put("ok", true)
                .put("recording", false)
                .put("already_stopped", true)
                .put("released", true)
                .toString()
            if (!handle.isNullOrBlank() && handle != active.file.absolutePath) {
                return error("NOT_RECORDING", "handle 与当前录制不匹配")
            }
            cancelAutoStop()
            finalizeSession(active)
            session = null
            recording = false
            releaseProjectionLocked()
            val durationMs = SystemClock.elapsedRealtime() - active.startedAtElapsed
            val size = if (active.file.exists()) active.file.length() else 0L
            AndroidAgentLogger.info("Screen recording stopped: ${active.file.absolutePath} duration=${durationMs}ms size=${size}B")
            return JSONObject()
                .put("ok", true)
                .put("recording", false)
                .put("handle", active.file.absolutePath)
                .put("file", active.file.absolutePath)
                .put("duration_ms", durationMs)
                .put("size_bytes", size)
                .toString()
        }
    }

    /** 释放：停止录制（若有）并清理 projection。供前台服务的停止动作复用，幂等。 */
    fun release(): String {
        synchronized(lock) {
            val active = session
            if (active != null) {
                cancelAutoStop()
                finalizeSession(active)
                session = null
                recording = false
            }
            releaseProjectionLocked()
            return JSONObject().put("ok", true).put("released", true).put("was_recording", active != null).toString()
        }
    }

    fun status(handle: String?): String {
        synchronized(lock) {
            val active = session
            val durationMs = if (recording && active != null) {
                SystemClock.elapsedRealtime() - active.startedAtElapsed
            } else {
                0L
            }
            val path = active?.file?.absolutePath
            return JSONObject()
                .put("ok", true)
                .put("recording", recording)
                .put("projection_ready", hasUnusedProjection())
                .put("handle", path ?: JSONObject.NULL)
                .put("file", path ?: JSONObject.NULL)
                .put("duration_ms", durationMs)
                .put("width", active?.width ?: 0)
                .put("height", active?.height ?: 0)
                .toString()
        }
    }

    /** 列出已录文件（按修改时间倒序），含大小与修改时间。 */
    fun list(context: Context): String {
        val dir = recordingsDir(context.applicationContext)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4", ignoreCase = true) }.orEmpty()
        val sorted = files.sortedByDescending { it.lastModified() }
        val array = JSONArray()
        for (file in sorted) {
            array.put(
                JSONObject()
                    .put("name", file.name)
                    .put("path", file.absolutePath)
                    .put("size_bytes", file.length())
                    .put("modified_at", file.lastModified()),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("dir", dir.absolutePath)
            .put("count", array.length())
            .put("files", array)
            .toString()
    }

    fun recordingsDir(context: Context): File =
        context.getExternalFilesDir(RECORDINGS_DIR) ?: File(context.filesDir, RECORDINGS_DIR)

    fun hasAudioPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 麦克风被占用/不可用的原因，返回 null 表示可用。仅用于 mic=true 录制前的预检。 */
    private fun micBusyReason(context: Context): String? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val activeRecordings = runCatching { audioManager.activeRecordingConfigurations }.getOrNull()
        val activeCount = activeRecordings?.size ?: 0
        if (activeCount > 0) {
            return "检测到其他应用正在录音（$activeCount 路）"
        }
        val mode = runCatching { audioManager.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        if (isCallOrCommunicationMode(mode)) {
            return "设备处于通话/语音通信模式（mode=$mode）"
        }
        if (runCatching { audioManager.isMicrophoneMute }.getOrDefault(false)) {
            return "麦克风已被系统静音"
        }
        return null
    }

    private fun isCallOrCommunicationMode(mode: Int): Boolean = when (mode) {
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        AudioManager.MODE_CALL_SCREENING,
        -> true
        else -> false
    }

    private fun obtainProjection(context: Context, resultCode: Int, data: Intent?): MediaProjection? {
        projection?.let { if (projectionValid && !projectionConsumed) return it }
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return null
        val created = (try {
            manager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            AndroidAgentLogger.warn("getMediaProjection failed: ${t.javaClass.simpleName}: ${t.message}")
            return null
        }) ?: return null

        // 必须先注册 Callback 再 createVirtualDisplay（targetSdk 34+ 强制要求）。
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                onProjectionStopped(created)
            }
        }
        try {
            created.registerCallback(callback, Handler(Looper.getMainLooper()))
        } catch (t: Throwable) {
            runCatching { created.stop() }
            return null
        }
        // 丢弃旧实例时先注销回拨，避免其 onStop 误伤新会话。
        projection?.let { stale ->
            projectionCallback?.let { staleCallback -> runCatching { stale.unregisterCallback(staleCallback) } }
            runCatching { stale.stop() }
        }
        projectionCallback = null
        projection = created
        projectionCallback = callback
        projectionValid = true
        projectionConsumed = false
        return created
    }

    private fun onProjectionStopped(stopped: MediaProjection) {
        synchronized(lock) {
            // 仅当前 projection 的停止才代表会话终止，避免旧实例回调误停新录制。
            if (projection !== stopped) return
            projection = null
            projectionValid = false
            projectionConsumed = false
            projectionCallback = null
            val active = session
            if (active != null) {
                cancelAutoStop()
                finalizeSession(active)
                session = null
                recording = false
            }
        }
        appContext?.let { RecordingForegroundService.stop(it) }
    }

    private fun releaseProjectionLocked() {
        val closing = projection
        projection = null
        projectionValid = false
        projectionConsumed = false
        val callback = projectionCallback
        projectionCallback = null
        if (closing != null) {
            if (callback != null) runCatching { closing.unregisterCallback(callback) }
            runCatching { closing.stop() }
        }
    }

    private fun finalizeSession(active: Session) {
        runCatching { active.recorder.stop() }
            .onFailure { AndroidAgentLogger.warn("MediaRecorder.stop failed: ${it.message}") }
        runCatching { active.recorder.reset() }
        runCatching { active.recorder.release() }
        runCatching { active.virtualDisplay.release() }
    }

    private fun configure(recorder: MediaRecorder, options: Options, width: Int, height: Int, file: File) {
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        if (options.mic) recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (options.mic) recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setVideoSize(width, height)
        recorder.setVideoFrameRate(FRAME_RATE)
        recorder.setVideoEncodingBitRate(options.bitRate.coerceIn(MIN_BIT_RATE, MAX_BIT_RATE))
        if (options.mic) {
            recorder.setAudioEncodingBitRate(AUDIO_BIT_RATE)
            recorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE)
        }
        recorder.setOutputFile(file.absolutePath)
        recorder.prepare()
    }

    private fun scheduleAutoStop(context: Context, maxSeconds: Int) {
        cancelAutoStop()
        val seconds = maxSeconds.coerceIn(1, MAX_SECONDS)
        autoStopTask = scheduler.schedule(
            {
                runCatching { stop(null) }
                runCatching { RecordingForegroundService.stop(context) }
            },
            seconds.toLong(),
            TimeUnit.SECONDS,
        )
    }

    private fun cancelAutoStop() {
        autoStopTask?.cancel(false)
        autoStopTask = null
    }

    private fun resolveGeometry(context: Context): IntArray {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val bounds = windowManager?.currentWindowMetrics?.bounds
        val metrics = context.resources.displayMetrics
        val width = bounds?.width()?.takeIf { it > 0 } ?: metrics.widthPixels
        val height = bounds?.height()?.takeIf { it > 0 } ?: metrics.heightPixels
        val dpi = metrics.densityDpi.takeIf { it > 0 } ?: DEFAULT_DPI
        return intArrayOf(width, height, dpi)
    }

    /** H264 编码器要求偶数尺寸。 */
    private fun normalizeDimension(value: Int): Int = if (value <= 2) 2 else (value and -2)

    private fun buildFileName(name: String?): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safe = name?.trim()?.takeIf { it.isNotEmpty() }
            ?.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
            ?.take(48)
        return if (safe.isNullOrEmpty()) "rec_$stamp.mp4" else "${safe}_$stamp.mp4"
    }

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private const val RECORDINGS_DIR = "recordings"
    private const val DISPLAY_NAME = "EtaScreenRecord"
    private const val MIC_WARNING =
        "已启用麦克风录制：若同时使用语音助手/语音识别会互相抢麦，可能导致识别失败或录音无声；建议录制期间暂不唤起语音助手。"
    private const val MIN_BIT_RATE = 1_000_000
    private const val MAX_BIT_RATE = 50_000_000
    private const val AUDIO_BIT_RATE = 128_000
    private const val AUDIO_SAMPLE_RATE = 44_100
    private const val FRAME_RATE = 30
    private const val MAX_SECONDS = 3_600
    private const val DEFAULT_DPI = 440
}
