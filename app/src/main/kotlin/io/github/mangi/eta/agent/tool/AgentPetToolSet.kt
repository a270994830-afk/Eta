package io.github.mangi.eta.agent.tool

import android.content.Context
import android.provider.Settings
import io.github.mangi.eta.agent.pet.AgentPet
import io.github.mangi.eta.agent.pet.AgentPetMood
import io.github.mangi.eta.agent.pet.EtaPetService
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONObject

/**
 * 桌面宠物工具族：让模型（或用户通过 MCP）显示/隐藏桌宠、切换情绪、让它说一句话。
 *
 * 桌宠是用户可见的悬浮窗，因此 `pet_show` 需要"显示在其他应用上层"权限，
 * 缺权限时返回可读的失败原因而不是静默无效。
 */
internal class AgentPetToolSet(
    private val context: Context,
    @Suppress("unused") private val logger: AgentLogger,
) {
    fun execute(name: String, args: JSONObject): String? = when (name) {
        "pet_status" -> status()
        "pet_show" -> show()
        "pet_hide" -> hide()
        "pet_state" -> setState(args)
        "pet_say" -> say(args)
        else -> null
    }

    private fun status(): String = JSONObject()
        .put("ok", true)
        .put("enabled", AgentPet.isEnabled())
        .put("mood", AgentPet.mood.value.id)
        .put("speech", AgentPet.speech.value ?: JSONObject.NULL)
        .put("overlay_permission", canDrawOverlay())
        .toString()

    private fun show(): String {
        if (!canDrawOverlay()) {
            return JSONObject()
                .put("ok", false)
                .put("code", "OVERLAY_PERMISSION_REQUIRED")
                .put("message", "需要「显示在其他应用上层」权限才能显示桌宠：设置 → 应用 → Eta → 其他权限 → 显示在其他应用上层")
                .toString()
        }
        AgentPet.setEnabled(context, true)
        EtaPetService.show(context)
        return JSONObject().put("ok", true).put("enabled", true).put("mood", AgentPet.mood.value.id).toString()
    }

    private fun hide(): String {
        AgentPet.setEnabled(context, false)
        EtaPetService.hide(context)
        return JSONObject().put("ok", true).put("enabled", false).toString()
    }

    private fun setState(args: JSONObject): String {
        val raw = args.optString("mood").trim()
        val mood = AgentPetMood.entries.firstOrNull { it.id.equals(raw, ignoreCase = true) }
            ?: return JSONObject()
                .put("ok", false)
                .put("code", "UNKNOWN_MOOD")
                .put("message", "未知心情：$raw（可用：${AgentPetMood.entries.joinToString("/") { it.id }}）")
                .toString()
        AgentPet.setMood(mood)
        if (AgentPet.isEnabled()) EtaPetService.show(context)
        return JSONObject().put("ok", true).put("mood", mood.id).toString()
    }

    private fun say(args: JSONObject): String {
        val text = args.optString("text")
        AgentPet.say(text)
        if (text.isNotBlank() && AgentPet.isEnabled()) EtaPetService.show(context)
        return JSONObject().put("ok", true).put("speech", AgentPet.speech.value ?: JSONObject.NULL).toString()
    }

    private fun canDrawOverlay(): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    companion object {
        val TOOL_NAMES: Set<String> = setOf("pet_status", "pet_show", "pet_hide", "pet_state", "pet_say")
    }
}
