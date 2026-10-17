package io.github.mangi.eta.agent.tool

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import io.github.mangi.eta.agent.device.AgentNotificationHistoryService
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 音乐/媒体会话控制（对齐 Operit 的 music_* 工具族）：
 * 优先用 MediaController 直接控制当前活动的媒体会话（暂停/恢复/停止/跳转/查询），
 * 拿不到会话时退回媒体按键。
 */
internal class AgentMediaToolSet(
    private val context: Context,
    @Suppress("unused") private val logger: AgentLogger,
) {
    fun execute(name: String, args: JSONObject): String? = when (name) {
        "music_status" -> status()
        "music_pause" -> transport("pause")
        "music_resume" -> transport("resume")
        "music_stop" -> transport("stop")
        "music_seek" -> seek(args)
        else -> null
    }

    private fun sessions(): List<MediaController> = runCatching {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val listener = ComponentName(context, AgentNotificationHistoryService::class.java)
        manager.getActiveSessions(listener).orEmpty()
    }.getOrDefault(emptyList())

    private fun activeController(): MediaController? {
        val all = sessions()
        return all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull { it.playbackState != null }
            ?: all.firstOrNull()
    }

    private fun status(): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val controllers = sessions()
        val list = JSONArray()
        var playing = false
        for (controller in controllers) {
            val state = controller.playbackState
            val metadata = controller.metadata
            val item = JSONObject()
                .put("package", controller.packageName)
                .put("state", stateName(state?.state))
                .put("position_ms", state?.position ?: 0L)
                .put("speed", (state?.playbackSpeed ?: 0f).toDouble())
                .put("title", metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
                .put("artist", metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
                .put("album", metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
                .put("duration_ms", metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
            list.put(item)
            if (state?.state == PlaybackState.STATE_PLAYING) playing = true
        }
        return JSONObject()
            .put("ok", true)
            .put("music_active", audio.isMusicActive || playing)
            .put("volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("volume_max", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            .put("session_count", list.length())
            .put("sessions", list)
            .toString()
    }

    private fun transport(action: String): String {
        val controller = activeController()
        if (controller != null) {
            val controls = controller.transportControls
            when (action) {
                "pause" -> controls.pause()
                "resume" -> controls.play()
                "stop" -> controls.stop()
            }
            return JSONObject()
                .put("ok", true)
                .put("action", action)
                .put("via", "media_session")
                .put("package", controller.packageName)
                .toString()
        }
        val keyCode = when (action) {
            "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            "resume" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
            else -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
        }
        return JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("via", "media_key")
            .toString()
    }

    private fun seek(args: JSONObject): String {
        val positionMs = args.optLong("position_ms", -1L)
        if (positionMs < 0L) {
            return JSONObject().put("ok", false).put("code", "INVALID_ARGUMENT")
                .put("message", "缺少 position_ms（毫秒，>=0）").toString()
        }
        val controller = activeController()
            ?: return JSONObject().put("ok", false).put("code", "NO_ACTIVE_SESSION")
                .put("message", "当前没有可控制的媒体会话").toString()
        controller.transportControls.seekTo(positionMs)
        return JSONObject()
            .put("ok", true)
            .put("action", "seek")
            .put("position_ms", positionMs)
            .put("package", controller.packageName)
            .toString()
    }

    private fun stateName(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_FAST_FORWARDING -> "fast_forwarding"
        PlaybackState.STATE_REWINDING -> "rewinding"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "skipping_next"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "skipping_previous"
        PlaybackState.STATE_ERROR -> "error"
        PlaybackState.STATE_NONE -> "none"
        else -> "unknown"
    }

    companion object {
        val TOOL_NAMES: Set<String> = setOf(
            "music_status",
            "music_pause",
            "music_resume",
            "music_stop",
            "music_seek",
        )
    }
}
