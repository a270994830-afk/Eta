package io.github.mangi.eta.agent.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.Locale

/** 语音播报：把最终回复朗读出来。懒加载引擎；首次初始化期间的请求会暂存，就绪后补播。 */
internal object EtaSpeechAnnouncer {
    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun speak(context: Context, text: String) {
        val cleaned = cleanForSpeech(text)
        if (cleaned.isBlank()) return
        if (!ensure(context)) {
            pending = cleaned
            return
        }
        engine?.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun stop() {
        pending = null
        engine?.stop()
    }

    /** @return 引擎是否已就绪；未就绪时发起初始化（结果通过 [pending] 补播）。 */
    private fun ensure(context: Context): Boolean {
        if (ready) return true
        if (engine != null) return false
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val tts = engine
                if (tts != null) {
                    runCatching {
                        tts.language = if (tts.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE) {
                            Locale.SIMPLIFIED_CHINESE
                        } else {
                            Locale.getDefault()
                        }
                    }
                    ready = true
                    pending?.let { queued ->
                        pending = null
                        tts.speak(queued, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
                    }
                }
            } else {
                AndroidAgentLogger.warn("TTS init failed: status=$status")
                engine = null
            }
        }
        return false
    }

    private fun cleanForSpeech(source: String): String {
        var text = source
        text = text.replace(Regex("```[\\s\\S]*?```"), "（代码略）")
        text = text.replace(Regex("`([^`]*)`"), "$1")
        text = text.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        text = text.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        text = text.replace(Regex("(?m)^#{1,6}\\s*"), "")
        text = text.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        text = text.replace(Regex("(?m)^\\s*\\d+[.、]\\s+"), "")
        text = text.replace(Regex("[*_~>|]"), "")
        text = text.replace(Regex("\\n{2,}"), "。")
        text = text.replace("\n", "，")
        text = text.trim()
        return if (text.length > MAX_SPEECH_CHARS) text.take(MAX_SPEECH_CHARS) + "…" else text
    }

    private const val UTTERANCE_ID = "eta-reply"
    private const val MAX_SPEECH_CHARS = 600
}
