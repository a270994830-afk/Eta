package io.github.mangi.eta.agent.pet

import android.content.Context
import io.github.mangi.eta.config.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 桌宠情绪：决定宠物的动作与配色。 */
internal enum class AgentPetMood(val id: String) {
    IDLE("idle"),
    SLEEP("sleep"),
    THINKING("thinking"),
    WORKING("working"),
    SUCCESS("success"),
    ERROR("error"),
    ;

    companion object {
        fun fromId(value: String?): AgentPetMood =
            entries.firstOrNull { it.id.equals(value, ignoreCase = true) } ?: IDLE
    }
}

/**
 * 桌宠的开关、情绪与位置：状态留在进程内的 StateFlow 里，配置落本地 Agent 偏好。
 *
 * 与悬浮窗服务 [EtaPetService] 解耦，工具层与设置页只通过这里读写，不直接碰窗口。
 */
internal object AgentPet {
    private const val KEY_ENABLED = "agent_pet_enabled"
    private const val KEY_MOOD = "agent_pet_mood"
    private const val KEY_X = "agent_pet_x"
    private const val KEY_Y = "agent_pet_y"

    private val mutableMood = MutableStateFlow(AgentPetMood.IDLE)
    val mood: StateFlow<AgentPetMood> = mutableMood.asStateFlow()

    private val mutableSpeech = MutableStateFlow<String?>(null)
    val speech: StateFlow<String?> = mutableSpeech.asStateFlow()

    private fun prefs() = Prefs.localAgentPreferences()

    fun isEnabled(): Boolean =
        runCatching { prefs()?.getBoolean(KEY_ENABLED, false) ?: false }.getOrDefault(false)

    fun restoreMood(): AgentPetMood =
        runCatching { AgentPetMood.fromId(prefs()?.getString(KEY_MOOD, null)) }.getOrDefault(AgentPetMood.IDLE)

    /** 保存位置；x 可能为负（贴右边缘时用负数表示距右边界）。 */
    fun position(): Pair<Int, Int> = runCatching {
        val p = prefs()
        (p?.getInt(KEY_X, Int.MIN_VALUE) ?: Int.MIN_VALUE) to (p?.getInt(KEY_Y, Int.MIN_VALUE) ?: Int.MIN_VALUE)
    }.getOrDefault(Int.MIN_VALUE to Int.MIN_VALUE)

    fun savePosition(x: Int, y: Int) {
        runCatching { prefs()?.edit()?.putInt(KEY_X, x)?.putInt(KEY_Y, y)?.apply() }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        runCatching { prefs()?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply() }
        if (enabled) EtaPetService.show(context) else EtaPetService.hide(context)
    }

    fun setMood(mood: AgentPetMood) {
        mutableMood.value = mood
        if (mood != AgentPetMood.SUCCESS && mood != AgentPetMood.ERROR) mutableSpeech.value = null
        runCatching { prefs()?.edit()?.putString(KEY_MOOD, mood.id)?.apply() }
    }

    /** 让宠物说一句话（成功/失败时保留，其它情绪下一拍自动消失）。 */
    fun say(text: String?) {
        mutableSpeech.value = text?.takeIf { it.isNotBlank() }?.take(120)
    }

    /** 进程重启后把上次情绪恢复成空闲，避免卡在"工作中"。 */
    fun resetMoodFromPrefs() {
        val restored = restoreMood()
        mutableMood.value = if (restored == AgentPetMood.WORKING || restored == AgentPetMood.THINKING) {
            AgentPetMood.IDLE
        } else {
            restored
        }
    }
}
