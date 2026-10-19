package io.github.mangi.eta.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PrefsDefaultsTest {
    @Test
    fun defaultsMatchRecommendedInitialSettings() {
        assertEquals(
            mapOf(
                Prefs.Keys.POWER_KEY_TAKEOVER to false,
                Prefs.Keys.ASSISTANT_AUTO_CONFIG to false,
                Prefs.Keys.HOTWORD_SELF_HEAL to false,
                Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH to true,
                Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
                Prefs.Keys.LOCKSCREEN_VOICE_COMMAND to false,
                Prefs.Keys.SCREEN_ON_VOICE_COMMAND to false,
                Prefs.Keys.AGENT_CUSTOM_MODEL to true,
                Prefs.Keys.AGENT_REQUIRE_PREFIX to false,
                Prefs.Keys.AGENT_TERMINAL_TOOLS to true,
                Prefs.Keys.AGENT_BROWSER_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
                Prefs.Keys.AGENT_SHADOW_TOOLS to false,
                Prefs.Keys.AGENT_THINKING_ENABLED to true,
                Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED to true,
                Prefs.Keys.ETA_HOTWORD_ENABLED to false,
                Prefs.Keys.ETA_VOICE_CONTINUOUS to false,
                Prefs.Keys.ETA_TTS_ENABLED to false,
                Prefs.Keys.ETA_REMINDER_KAIGONG to false,
                Prefs.Keys.ETA_REMINDER_WINDOW to false,
                Prefs.Keys.MCP_SERVER_ENABLED to false,
                Prefs.Keys.MCP_SERVER_ALLOW_SENSITIVE to false,
                Prefs.Keys.MCP_CONFIRM_TRADES to true,
                Prefs.Keys.AGENT_TOOL_CLI_MODE to false,
                Prefs.Keys.AGENT_SKILL_SHARED_DIR to true,
                Prefs.Keys.AGENT_MCP_TOOLS_EAGER to false,
                Prefs.Keys.EXTERNAL_CHAT_ENABLED to false,
            ),
            Prefs.Keys.BOOLEAN_DEFAULTS,
        )
        assertFalse(
            Prefs.Keys.BOOLEAN_DEFAULTS.containsKey(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET),
        )
    }

    @Test
    fun localAgentKeysMatchRuntimeOwnedSettings() {
        assertEquals(
            setOf(
                Prefs.Keys.AGENT_TERMINAL_TOOLS,
                Prefs.Keys.AGENT_BROWSER_TOOLS,
                Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                Prefs.Keys.AGENT_SHADOW_TOOLS,
                Prefs.Keys.AGENT_THINKING_ENABLED,
                Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED,
                Prefs.Keys.ETA_HOTWORD_ENABLED,
                Prefs.Keys.ETA_VOICE_CONTINUOUS,
                Prefs.Keys.ETA_TTS_ENABLED,
                Prefs.Keys.ETA_REMINDER_KAIGONG,
                Prefs.Keys.ETA_REMINDER_WINDOW,
                Prefs.Keys.MCP_SERVER_ENABLED,
                Prefs.Keys.MCP_SERVER_ALLOW_SENSITIVE,
                Prefs.Keys.MCP_CONFIRM_TRADES,
                Prefs.Keys.EXTERNAL_CHAT_ENABLED,
                Prefs.Keys.AGENT_TOOL_CLI_MODE,
                Prefs.Keys.AGENT_SKILL_SHARED_DIR,
                Prefs.Keys.AGENT_MCP_TOOLS_EAGER,
            ),
            Prefs.Keys.LOCAL_AGENT_KEYS,
        )
    }
}
