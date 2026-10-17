package io.github.mangi.eta.ui.screens.ext

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ScreenshotMonitor
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.device.PrivilegedShell
import io.github.mangi.eta.agent.device.recording.AgentScreenRecorder
import io.github.mangi.eta.agent.device.recording.RecordingConsentActivity
import io.github.mangi.eta.agent.device.shizuku.ShizukuShell
import io.github.mangi.eta.agent.external.EtaExternalChatService
import io.github.mangi.eta.agent.model.AgentFunctionModelResolver
import io.github.mangi.eta.agent.model.AgentUsageTracker
import io.github.mangi.eta.agent.pet.AgentPet
import io.github.mangi.eta.agent.tool.AgentRecordingToolSet
import io.github.mangi.eta.agent.tool.AgentToolPackManager
import io.github.mangi.eta.agent.tool.AgentToolPermissionStore
import io.github.mangi.eta.agent.tool.AgentUpdateToolSet
import io.github.mangi.eta.agent.workflow.AgentWorkflowStore
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaDropdownPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.ListEmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Agent 扩展设置：用量统计、工具权限、限流、多 Key 池、功能模型槽位与相关开关。 */
private const val KEY_API_KEY_POOL = "agent_api_key_pool"
private const val KEY_API_KEY_POOL_INDEX = "agent_api_key_pool_index"

private val POLICY_OPTIONS = listOf(
    AgentToolPermissionStore.POLICY_ALLOW,
    AgentToolPermissionStore.POLICY_ASK,
    AgentToolPermissionStore.POLICY_FORBID,
)

private val FUNCTION_SLOTS = listOf(
    AgentFunctionModelResolver.PURPOSE_COMPACTION to "上下文压缩",
    AgentFunctionModelResolver.PURPOSE_REPLY_REWRITE to "回复改写",
    AgentFunctionModelResolver.PURPOSE_EMBEDDING to "记忆向量检索",
)

@Composable
fun AgentExtSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var usage by remember { mutableStateOf<JSONObject?>(null) }
    var toolDefault by remember { mutableStateOf(AgentToolPermissionStore.POLICY_ALLOW) }
    var toolEntries by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var newToolName by remember { mutableStateOf("") }
    var newToolPolicyIndex by remember { mutableStateOf(0) }
    var rateLimit by remember { mutableStateOf("0") }
    var dailyLimit by remember { mutableStateOf("0") }
    var fallbackProvider by remember { mutableStateOf("") }
    var fallbackModel by remember { mutableStateOf("") }
    var cliMode by remember { mutableStateOf(false) }
    var browserTools by remember { mutableStateOf(true) }
    var petEnabled by remember { mutableStateOf(false) }
    var mcpServerEnabled by remember { mutableStateOf(false) }
    var mcpAllowSensitive by remember { mutableStateOf(false) }
    var poolText by remember { mutableStateOf("{}") }
    var poolError by remember { mutableStateOf<String?>(null) }
    var slotIndex by remember { mutableStateOf(0) }
    var providerIndex by remember { mutableStateOf(0) }
    var modelIndex by remember { mutableStateOf(0) }
    var functionMapText by remember { mutableStateOf("{}") }

    // 特权通道
    var privilegedAvailable by remember { mutableStateOf(false) }
    var rootAvailable by remember { mutableStateOf(false) }
    var shizukuStatus by remember { mutableStateOf<JSONObject?>(null) }
    var activeChannel by remember { mutableStateOf(PrivilegedShell.CHANNEL_NONE) }
    // 屏幕录制
    val recordingToolSet = remember(context) { AgentRecordingToolSet(context, AndroidAgentLogger) }
    var recording by remember { mutableStateOf(false) }
    var recordingDurationMs by remember { mutableStateOf(0L) }
    var recordingFiles by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var recordingBusy by remember { mutableStateOf(false) }
    var recordingMessage by remember { mutableStateOf<String?>(null) }
    // 更新
    var appVersion by remember { mutableStateOf("") }
    var updateBusy by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    // 外部聊天通道
    var chatEnabled by remember { mutableStateOf(false) }
    var chatPort by remember { mutableStateOf(EtaExternalChatService.DEFAULT_PORT.toString()) }
    var chatToken by remember { mutableStateOf("") }
    var chatMessage by remember { mutableStateOf<String?>(null) }
    // 工作流 / 插件包（只读）
    var workflows by remember { mutableStateOf<List<AgentWorkflowStore.Workflow>>(emptyList()) }
    var toolPacks by remember { mutableStateOf<List<JSONObject>>(emptyList()) }

    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())

    fun reload() {
        usage = runCatching { AgentUsageTracker.snapshot(7) }.getOrNull()
        runCatching {
            val snapshot = AgentToolPermissionStore.snapshot()
            val storedDefault = snapshot.optString("default", AgentToolPermissionStore.POLICY_ALLOW)
            toolDefault = storedDefault.takeIf { it in POLICY_OPTIONS } ?: AgentToolPermissionStore.POLICY_ALLOW
            val tools = snapshot.optJSONObject("tools")
            toolEntries = tools?.keys()?.asSequence()
                ?.map { name -> name to tools.optString(name, AgentToolPermissionStore.POLICY_ALLOW) }
                ?.sortedBy { it.first }
                ?.toList()
                .orEmpty()
        }
        rateLimit = readLocalText(Prefs.Keys.AGENT_RATE_LIMIT_PER_MINUTE, "0")
        dailyLimit = readLocalText(Prefs.Keys.AGENT_DAILY_TOKEN_LIMIT, "0")
        fallbackProvider = readLocalText(Prefs.Keys.AGENT_FALLBACK_PROVIDER_ID)
        fallbackModel = readLocalText(Prefs.Keys.AGENT_FALLBACK_MODEL_ID)
        cliMode = readLocalBoolean(Prefs.Keys.AGENT_TOOL_CLI_MODE, false)
        browserTools = readLocalBoolean(Prefs.Keys.AGENT_BROWSER_TOOLS, true)
        mcpServerEnabled = readLocalBoolean(Prefs.Keys.MCP_SERVER_ENABLED, false)
        mcpAllowSensitive = readLocalBoolean(Prefs.Keys.MCP_SERVER_ALLOW_SENSITIVE, false)
        poolText = readLocalText(KEY_API_KEY_POOL, "{}")
        poolError = null
        functionMapText = readLocalText(Prefs.Keys.AGENT_FUNCTION_MODEL_MAP, "{}")
        chatEnabled = readLocalBoolean(Prefs.Keys.EXTERNAL_CHAT_ENABLED, false)
        petEnabled = AgentPet.isEnabled()
        chatPort = readLocalText(Prefs.Keys.EXTERNAL_CHAT_PORT, EtaExternalChatService.DEFAULT_PORT.toString())
        chatToken = readLocalText(Prefs.Keys.EXTERNAL_CHAT_TOKEN)
        appVersion = runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
                .versionName
        }.getOrNull() ?: "未知"
    }

    suspend fun refreshPrivileged() {
        val snapshot = withContext(Dispatchers.IO) {
            PrivilegedSnapshot(
                available = runCatching { PrivilegedShell.available() }.getOrDefault(false),
                rootAvailable = runCatching { PrivilegedShell.rootAvailable() }.getOrDefault(false),
                activeChannel = runCatching { PrivilegedShell.activeChannel() }
                    .getOrDefault(PrivilegedShell.CHANNEL_NONE),
                shizukuStatus = runCatching { ShizukuShell.status() }.getOrNull(),
            )
        }
        privilegedAvailable = snapshot.available
        rootAvailable = snapshot.rootAvailable
        activeChannel = snapshot.activeChannel
        shizukuStatus = snapshot.shizukuStatus
    }

    suspend fun refreshRecording() {
        val result = withContext(Dispatchers.IO) {
            val status = runCatching { JSONObject(AgentScreenRecorder.status(null)) }.getOrNull()
            val list = runCatching { JSONObject(AgentScreenRecorder.list(context)) }.getOrNull()
            val files = list?.optJSONArray("files")?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            }.orEmpty()
            status to files
        }
        recording = result.first?.optBoolean("recording") ?: false
        recordingDurationMs = result.first?.optLong("duration_ms") ?: 0L
        recordingFiles = result.second
    }

    suspend fun refreshWorkflows() {
        workflows = withContext(Dispatchers.IO) {
            runCatching { AgentWorkflowStore.list(context) }.getOrDefault(emptyList())
        }
    }

    suspend fun refreshToolPacks() {
        toolPacks = withContext(Dispatchers.IO) {
            runCatching {
                AgentToolPackManager.init(context)
                val array = AgentToolPackManager.listPacks()
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            }.getOrDefault(emptyList())
        }
    }

    fun applyExternalChatService(enabled: Boolean) {
        runCatching {
            if (enabled) EtaExternalChatService.start(context) else EtaExternalChatService.stop(context)
        }.onFailure { chatMessage = "服务操作失败：" + (it.message ?: it.javaClass.simpleName) }
    }

    fun restartExternalChat() {
        scope.launch {
            runCatching { EtaExternalChatService.stop(context) }
            delay(300)
            runCatching { EtaExternalChatService.start(context) }
                .onFailure { chatMessage = "重启失败：" + (it.message ?: it.javaClass.simpleName) }
        }
    }

    LaunchedEffect(Unit) {
        reload()
        refreshPrivileged()
        refreshWorkflows()
        refreshToolPacks()
        while (true) {
            refreshRecording()
            delay(2000)
        }
    }

    val policyItems = remember { POLICY_OPTIONS.map { DropdownItem(text = policyLabel(it)) } }
    val slotItems = remember { FUNCTION_SLOTS.map { DropdownItem(text = it.second) } }
    val providerItems = remember(providers) {
        listOf(DropdownItem(text = "未指定")) + providers.map { DropdownItem(text = it.name) }
    }
    val selectedProvider = providers.getOrNull(providerIndex - 1)
    val modelItems = remember(providers, providerIndex) {
        listOf(DropdownItem(text = "未指定")) +
            (providers.getOrNull(providerIndex - 1)?.models ?: emptyList()).map { DropdownItem(text = it.displayName) }
    }

    MiuixScaffoldPage(
        title = "扩展设置",
        onBack = onBack,
    ) {
        // ── 特权通道 ────────────────────────────────────────────────
        item(key = "ext_privileged_title") { EtaPreferenceGroupTitle("特权通道") }
        item(key = "ext_privileged") {
            EtaPreferenceGroup {
                EtaPreference(
                    title = "特权可用性",
                    summary = if (privilegedAvailable) "可用" else "不可用",
                    startAction = { EtaPreferenceIcon(Icons.Rounded.Shield, tint = EtaPreferenceColors.Green) },
                )
                EtaPreferenceDivider()
                EtaPreference(title = "当前生效通道", summary = channelLabel(activeChannel))
                EtaPreferenceDivider()
                EtaPreference(title = "Root（su）", summary = if (rootAvailable) "可用" else "不可用")
                EtaPreferenceDivider()
                EtaPreference(title = "Shizuku", summary = shizukuSummary(shizukuStatus))
                EtaPreferenceDivider(hasLeading = false)
                EtaActionRow(
                    text = "申请 Shizuku 权限",
                    enabled = shizukuStatus?.optBoolean("available") == true &&
                        shizukuStatus?.optBoolean("permission") != true,
                    onClick = {
                        ShizukuShell.requestPermission()
                        scope.launch {
                            delay(600)
                            refreshPrivileged()
                        }
                    },
                )
                EtaActionRow(
                    text = "刷新通道状态",
                    onClick = { scope.launch { refreshPrivileged() } },
                )
            }
        }

        // ── 屏幕录制 ────────────────────────────────────────────────
        item(key = "ext_recording_title") { EtaPreferenceGroupTitle("屏幕录制") }
        item(key = "ext_recording") {
            EtaPreferenceGroup {
                EtaPreference(
                    title = "状态",
                    summary = if (recording) "录制中 · " + formatDuration(recordingDurationMs) else "未在录制",
                    startAction = { EtaPreferenceIcon(Icons.Rounded.ScreenshotMonitor, tint = EtaPreferenceColors.Orange) },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaActionRow(
                    text = when {
                        recordingBusy -> "处理中…"
                        recording -> "停止录制"
                        else -> "开始录制"
                    },
                    enabled = !recordingBusy,
                    onClick = {
                        scope.launch {
                            recordingBusy = true
                            recordingMessage = null
                            val resultJson = withContext(Dispatchers.IO) {
                                runCatching {
                                    recordingToolSet.execute(
                                        if (recording) "screen_record_stop" else "screen_record_start",
                                        JSONObject(),
                                    )
                                }.getOrNull()
                            }
                            recordingBusy = false
                            val json = resultJson?.let { runCatching { JSONObject(it) }.getOrNull() }
                            when {
                                json == null -> recordingMessage = "操作失败：无返回"
                                json.optBoolean("ok") -> {
                                    recordingMessage = if (recording) "已停止" else "已开始录制"
                                    refreshRecording()
                                }
                                json.optString("code") == "NEEDS_CONSENT" -> {
                                    recordingMessage = "请在系统弹窗中确认录屏授权"
                                    runCatching {
                                        context.startActivity(
                                            Intent(context, RecordingConsentActivity::class.java)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }.onFailure {
                                        recordingMessage = "无法拉起授权页：" + (it.message ?: it.javaClass.simpleName)
                                    }
                                }
                                else -> recordingMessage = json.optString("message")
                                    .ifBlank { json.optString("code") }
                            }
                        }
                    },
                )
                recordingMessage?.let { message ->
                    EtaPreference(title = "结果", summary = message)
                }
            }
        }
        item(key = "ext_recording_files") {
            EtaPreferenceGroupTitle("已录文件" + if (recordingFiles.isEmpty()) "" else "（${recordingFiles.size}）")
            EtaPreferenceGroup {
                if (recordingFiles.isEmpty()) {
                    ListEmptyState(title = "暂无录制文件")
                } else {
                    recordingFiles.forEachIndexed { index, file ->
                        if (index > 0) EtaPreferenceDivider()
                        EtaPreference(
                            title = file.optString("name"),
                            summary = formatBytes(file.optLong("size_bytes")),
                        )
                    }
                }
            }
        }

        // ── 更新 ────────────────────────────────────────────────
        item(key = "ext_update_title") { EtaPreferenceGroupTitle("更新") }
        item(key = "ext_update") {
            EtaPreferenceGroup {
                EtaPreference(
                    title = "当前版本",
                    summary = appVersion.ifBlank { "读取中…" },
                    startAction = { EtaPreferenceIcon(Icons.Rounded.CloudDownload, tint = EtaPreferenceColors.Blue) },
                )
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = "检查更新（GitHub Releases）",
                    summary = RELEASES_URL,
                    onClick = { runCatching { uriHandler.openUri(RELEASES_URL) } },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaActionRow(
                    text = if (updateBusy) "下载中，请勿离开本页…" else "下载最新版 APK",
                    enabled = !updateBusy,
                    onClick = {
                        scope.launch {
                            updateBusy = true
                            updateMessage = null
                            val resultJson = withContext(Dispatchers.IO) {
                                runCatching {
                                    AgentUpdateToolSet(context, AndroidAgentLogger)
                                        .execute("update_download", JSONObject())
                                }.getOrNull()
                            }
                            updateBusy = false
                            val json = resultJson?.let { runCatching { JSONObject(it) }.getOrNull() }
                            updateMessage = when {
                                json == null -> "下载失败：无返回"
                                json.optBoolean("ok") -> {
                                    val path = json.optString("path")
                                    val bytes = json.optLong("bytes")
                                    "已下载到 $path（${formatBytes(bytes)}）"
                                }
                                else -> json.optString("message").ifBlank { json.optString("code") }
                            }
                        }
                    },
                )
                updateMessage?.let { message ->
                    EtaPreference(title = "结果", summary = message)
                }
            }
        }

        // ── 外部聊天通道 ──────────────────────────────────────────
        item(key = "ext_external_title") { EtaPreferenceGroupTitle("外部聊天通道") }
        item(key = "ext_external") {
            EtaPreferenceGroup {
                EtaSwitchPreference(
                    title = "启用外部聊天通道",
                    summary = "仅监听 127.0.0.1，供外部客户端经 HTTP/A2A 调用",
                    checked = chatEnabled,
                    onCheckedChange = { enabled ->
                        chatEnabled = enabled
                        Prefs.putLocal(Prefs.Keys.EXTERNAL_CHAT_ENABLED, enabled)
                        applyExternalChatService(enabled)
                        chatMessage = if (enabled) "已开启，服务启动中" else "已关闭"
                    },
                    startAction = { EtaPreferenceIcon(Icons.Rounded.SupportAgent, tint = EtaPreferenceColors.Blue) },
                )
                EtaPreferenceDivider()
                EtaPreference {
                    TextField(
                        value = chatPort,
                        onValueChange = { input ->
                            chatPort = input.filter { it.isDigit() }.take(5)
                            Prefs.putLocal(Prefs.Keys.EXTERNAL_CHAT_PORT, chatPort)
                        },
                        label = "端口（1024-65535，默认 8094）",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                EtaPreferenceDivider()
                EtaPreference {
                    TextField(
                        value = chatToken,
                        onValueChange = { input ->
                            chatToken = input
                            Prefs.putLocal(Prefs.Keys.EXTERNAL_CHAT_TOKEN, input)
                        },
                        label = "访问 Token（留空则拒绝启动）",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                EtaPreferenceDivider(hasLeading = false)
                EtaActionRow(
                    text = "保存并重启服务",
                    onClick = {
                        if (chatEnabled) {
                            restartExternalChat()
                            chatMessage = "已重启服务"
                        } else {
                            chatMessage = "通道未启用，先打开上方开关"
                        }
                    },
                )
                chatMessage?.let { message ->
                    EtaPreference(title = "结果", summary = message)
                }
            }
        }

        // ── 工作流（只读） ────────────────────────────────────────
        item(key = "ext_workflow_title") { EtaPreferenceGroupTitle("工作流（只读）") }
        item(key = "ext_workflow") {
            EtaPreferenceGroup {
                if (workflows.isEmpty()) {
                    ListEmptyState(title = "暂无工作流", summary = "工作流由模型通过工具创建")
                } else {
                    workflows.forEachIndexed { index, workflow ->
                        if (index > 0) EtaPreferenceDivider()
                        EtaPreference(
                            title = workflow.name,
                            summary = buildString {
                                append("${workflow.steps.size} 步")
                                append(if (workflow.enabled) " · 启用" else " · 停用")
                                if (workflow.lastStatus.isNotBlank()) {
                                    append(" · 最近 " + workflowStatusLabel(workflow.lastStatus))
                                }
                            },
                            startAction = { EtaPreferenceIcon(Icons.Rounded.PlaylistPlay, tint = EtaPreferenceColors.Green) },
                        )
                    }
                }
            }
        }

        // ── 插件包（只读） ────────────────────────────────────────
        item(key = "ext_pack_title") { EtaPreferenceGroupTitle("插件包（只读）") }
        item(key = "ext_pack") {
            EtaPreferenceGroup {
                if (toolPacks.isEmpty()) {
                    ListEmptyState(title = "暂无插件包", summary = "插件包由模型通过工具安装")
                } else {
                    toolPacks.forEachIndexed { index, pack ->
                        if (index > 0) EtaPreferenceDivider()
                        EtaPreference(
                            title = pack.optString("name"),
                            summary = packSummary(pack),
                            startAction = { EtaPreferenceIcon(Icons.Rounded.Extension, tint = EtaPreferenceColors.Orange) },
                        )
                    }
                }
            }
        }

        // ── 用量统计 ────────────────────────────────────────────────
        item(key = "ext_usage_title") { EtaPreferenceGroupTitle("用量统计") }
        item(key = "ext_usage") {
            EtaPreferenceGroup {
                val snapshot = usage
                val recent = snapshot?.optJSONObject("recent_totals")
                val month = snapshot?.optJSONObject("month")
                val usedToday = snapshot?.optInt("used_today", 0) ?: 0
                val dailyLimitValue = snapshot?.optInt("daily_limit", 0) ?: 0
                EtaPreference(
                    title = "今日",
                    summary = if (snapshot == null) {
                        "读取失败"
                    } else {
                        "已用 $usedToday token" +
                            if (dailyLimitValue > 0) " / 上限 $dailyLimitValue" else "（未设上限）"
                    },
                    startAction = { EtaPreferenceIcon(Icons.Rounded.Dashboard, tint = EtaPreferenceColors.Blue) },
                )
                EtaPreferenceDivider()
                EtaPreference(title = "近 7 天", summary = formatUsage(recent))
                EtaPreferenceDivider()
                EtaPreference(title = "本月", summary = formatUsage(month))
            }
        }

        // ── 工具权限 ────────────────────────────────────────────────
        item(key = "ext_toolperm_title") { EtaPreferenceGroupTitle("工具权限") }
        item(key = "ext_toolperm") {
            EtaPreferenceGroup {
                EtaDropdownPreference(
                    title = "默认策略",
                    items = policyItems,
                    selectedIndex = POLICY_OPTIONS.indexOf(toolDefault).coerceAtLeast(0),
                    startAction = { EtaPreferenceIcon(Icons.Rounded.GppMaybe, tint = EtaPreferenceColors.Blue) },
                    onSelectedIndexChange = { index ->
                        runCatching { AgentToolPermissionStore.setPolicy(null, POLICY_OPTIONS[index]) }
                        reload()
                    },
                )
                toolEntries.forEach { (tool, policy) ->
                    EtaPreferenceDivider()
                    EtaDropdownPreference(
                        title = tool,
                        items = policyItems,
                        selectedIndex = POLICY_OPTIONS.indexOf(policy).coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            runCatching { AgentToolPermissionStore.setPolicy(tool, POLICY_OPTIONS[index]) }
                            reload()
                        },
                    )
                }
            }
        }

        item(key = "ext_toolperm_add_title") { EtaPreferenceGroupTitle("新增 / 修改工具策略") }
        item(key = "ext_toolperm_add") {
            EtaPreferenceGroup {
                EtaPreference(
                    startAction = { EtaPreferenceIcon(Icons.Rounded.Terminal, tint = EtaPreferenceColors.Green) },
                ) {
                    TextField(
                        value = newToolName,
                        onValueChange = { newToolName = it },
                        label = "工具名，如 run_command",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                EtaPreferenceDivider(hasLeading = false)
                EtaDropdownPreference(
                    title = "策略",
                    items = policyItems,
                    selectedIndex = newToolPolicyIndex,
                    onSelectedIndexChange = { newToolPolicyIndex = it },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaActionRow(
                    text = "保存工具策略",
                    enabled = newToolName.isNotBlank(),
                    onClick = {
                        runCatching {
                            AgentToolPermissionStore.setPolicy(
                                newToolName.trim(),
                                POLICY_OPTIONS[newToolPolicyIndex],
                            )
                        }
                        newToolName = ""
                        reload()
                    },
                )
            }
        }

        // ── 限流与配额 ──────────────────────────────────────────────
        item(key = "ext_limit_title") { EtaPreferenceGroupTitle("限流与配额") }
        item(key = "ext_limit") {
            EtaPreferenceGroup {
                EtaPreference(
                    startAction = { EtaPreferenceIcon(Icons.Rounded.Schedule, tint = EtaPreferenceColors.Orange) },
                ) {
                    TextField(
                        value = rateLimit,
                        onValueChange = { input -> rateLimit = input.filter { it.isDigit() } },
                        label = "每分钟请求上限（0=不限）",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                EtaPreferenceDivider()
                EtaPreference(
                    startAction = { EtaPreferenceIcon(Icons.Rounded.Schedule, tint = EtaPreferenceColors.Orange) },
                ) {
                    TextField(
                        value = dailyLimit,
                        onValueChange = { input -> dailyLimit = input.filter { it.isDigit() } },
                        label = "每日 Token 限额（0=不限）",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                EtaPreferenceDivider()
                EtaActionRow(
                    text = "保存限流与配额",
                    onClick = {
                        Prefs.putLocal(Prefs.Keys.AGENT_RATE_LIMIT_PER_MINUTE, rateLimit.ifBlank { "0" })
                        Prefs.putLocal(Prefs.Keys.AGENT_DAILY_TOKEN_LIMIT, dailyLimit.ifBlank { "0" })
                        reload()
                    },
                )
            }
        }

        // ── 多 Key 池 ───────────────────────────────────────────────
        item(key = "ext_pool_title") { EtaPreferenceGroupTitle("多 Key 池（按 Provider 轮询）") }
        item(key = "ext_pool") {
            EtaCard(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "JSON：{\"providerId\":[\"key1\",\"key2\"]}；轮询游标存于 $KEY_API_KEY_POOL_INDEX（只读）",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = poolText,
                        onValueChange = { input ->
                            poolText = input
                            poolError = validateJsonObject(input)
                        },
                        label = KEY_API_KEY_POOL,
                        useLabelAsPlaceholder = true,
                        minLines = 5,
                        maxLines = 14,
                        textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    poolError?.let { message ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = message,
                            color = MiuixTheme.colorScheme.error,
                            style = MiuixTheme.textStyles.footnote1,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    EtaTextButton(
                        text = "保存 Key 池",
                        enabled = validateJsonObject(poolText) == null,
                        onClick = {
                            Prefs.putLocal(KEY_API_KEY_POOL, poolText.trim().ifBlank { "{}" })
                            poolError = null
                            reload()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // ── 功能模型槽位 ────────────────────────────────────────────
        item(key = "ext_function_title") { EtaPreferenceGroupTitle("功能模型槽位") }
        item(key = "ext_function") {
            EtaPreferenceGroup {
                EtaDropdownPreference(
                    title = "功能槽位",
                    items = slotItems,
                    selectedIndex = slotIndex,
                    startAction = { EtaPreferenceIcon(Icons.Rounded.Psychology, tint = EtaPreferenceColors.Blue) },
                    onSelectedIndexChange = { slotIndex = it },
                )
                EtaPreferenceDivider()
                EtaDropdownPreference(
                    title = "Provider",
                    items = providerItems,
                    selectedIndex = providerIndex,
                    onSelectedIndexChange = { index ->
                        providerIndex = index
                        modelIndex = 0
                    },
                )
                EtaPreferenceDivider()
                EtaDropdownPreference(
                    title = "Model",
                    items = modelItems,
                    selectedIndex = modelIndex,
                    enabled = providerIndex > 0,
                    onSelectedIndexChange = { modelIndex = it },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaPreference(title = "当前分配", summary = functionMapText)
                EtaPreferenceDivider()
                EtaActionRow(
                    text = "保存到槽位",
                    enabled = selectedProvider != null && modelIndex > 0,
                    onClick = {
                        val model = selectedProvider?.models?.getOrNull(modelIndex - 1)
                        if (selectedProvider != null && model != null) {
                            runCatching {
                                val map = JSONObject(readLocalText(Prefs.Keys.AGENT_FUNCTION_MODEL_MAP, "{}"))
                                map.put(FUNCTION_SLOTS[slotIndex].first, "${selectedProvider.id}/${model.id}")
                                Prefs.putLocal(Prefs.Keys.AGENT_FUNCTION_MODEL_MAP, map.toString())
                            }
                            reload()
                        }
                    },
                )
            }
        }

        // ── 开关 ────────────────────────────────────────────────────
        // ── 桌面宠物（虚拟形象） ──────────────────────────────
        item(key = "ext_pet_title") { EtaPreferenceGroupTitle("桌面宠物") }
        item(key = "ext_pet") {
            EtaPreferenceGroup {
                EtaSwitchPreference(
                    title = "显示桌面宠物",
                    summary = "屏幕上显示一只会随任务状态变化的小家伙（需要「显示在其他应用上层」权限）",
                    checked = petEnabled,
                    onCheckedChange = { enabled ->
                        petEnabled = enabled
                        AgentPet.setEnabled(context, enabled)
                    },
                    startAction = { EtaPreferenceIcon(Icons.Rounded.Pets, tint = EtaPreferenceColors.Blue) },
                )
            }
        }

        item(key = "ext_switch_title") { EtaPreferenceGroupTitle("开关") }
        item(key = "ext_switch") {
            EtaPreferenceGroup {
                EtaSwitchPreference(
                    title = "工具精简模式（CLI）",
                    summary = "只向模型下发核心工具，其余按需检索",
                    checked = cliMode,
                    onCheckedChange = { enabled ->
                        cliMode = enabled
                        Prefs.putLocal(Prefs.Keys.AGENT_TOOL_CLI_MODE, enabled)
                        reload()
                    },
                    startAction = { EtaPreferenceIcon(Icons.Rounded.Terminal, tint = EtaPreferenceColors.Green) },
                )
                EtaPreferenceDivider()
                EtaSwitchPreference(
                    title = "网页浏览工具",
                    checked = browserTools,
                    onCheckedChange = { enabled ->
                        browserTools = enabled
                        Prefs.putLocal(Prefs.Keys.AGENT_BROWSER_TOOLS, enabled)
                        reload()
                    },
                    startAction = { EtaPreferenceIcon(Icons.Rounded.Language, tint = EtaPreferenceColors.Blue) },
                )
                EtaPreferenceDivider()
                EtaSwitchPreference(
                    title = "MCP 服务器（对外开放工具集）",
                    checked = mcpServerEnabled,
                    onCheckedChange = { enabled ->
                        mcpServerEnabled = enabled
                        Prefs.putLocal(Prefs.Keys.MCP_SERVER_ENABLED, enabled)
                        reload()
                    },
                    startAction = { EtaPreferenceIcon(Icons.Rounded.AccountTree, tint = EtaPreferenceColors.Blue) },
                )
                EtaPreferenceDivider()
                EtaSwitchPreference(
                    title = "MCP 允许敏感工具",
                    checked = mcpAllowSensitive,
                    onCheckedChange = { enabled ->
                        mcpAllowSensitive = enabled
                        Prefs.putLocal(Prefs.Keys.MCP_SERVER_ALLOW_SENSITIVE, enabled)
                        reload()
                    },
                    startAction = { EtaPreferenceIcon(Icons.Rounded.GppMaybe, tint = EtaPreferenceColors.Orange) },
                )
            }
        }

        // ── 主备模型 ────────────────────────────────────────────────
        item(key = "ext_fallback_title") { EtaPreferenceGroupTitle("主备模型（故障自动切换）") }
        item(key = "ext_fallback") {
            EtaPreferenceGroup {
                EtaPreference(
                    startAction = { EtaPreferenceIcon(Icons.Rounded.Sync, tint = EtaPreferenceColors.Blue) },
                ) {
                    TextField(
                        value = fallbackProvider,
                        onValueChange = { fallbackProvider = it },
                        label = "备用 Provider ID",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                EtaPreferenceDivider()
                EtaPreference(
                    startAction = { EtaPreferenceIcon(Icons.Rounded.Key, tint = EtaPreferenceColors.Blue) },
                ) {
                    TextField(
                        value = fallbackModel,
                        onValueChange = { fallbackModel = it },
                        label = "备用 Model ID",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                EtaPreferenceDivider()
                EtaActionRow(
                    text = "保存主备模型",
                    onClick = {
                        Prefs.putLocal(Prefs.Keys.AGENT_FALLBACK_PROVIDER_ID, fallbackProvider.trim())
                        Prefs.putLocal(Prefs.Keys.AGENT_FALLBACK_MODEL_ID, fallbackModel.trim())
                        reload()
                    },
                )
            }
        }
    }
}

@Composable
private fun EtaActionRow(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        EtaTextButton(
            text = text,
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun policyLabel(policy: String): String = when (policy) {
    AgentToolPermissionStore.POLICY_ALLOW -> "允许"
    AgentToolPermissionStore.POLICY_ASK -> "询问"
    AgentToolPermissionStore.POLICY_FORBID -> "禁止"
    else -> policy
}

private fun formatUsage(source: JSONObject?): String {
    if (source == null) return "无数据"
    val tokens = source.optInt("total_tokens", 0)
    val requests = source.optInt("requests", 0)
    return "$tokens token · $requests 次请求"
}

private fun validateJsonObject(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    return runCatching {
        JSONObject(trimmed)
        null as String?
    }.getOrElse { error -> "JSON 格式错误：" + (error.message ?: "无法解析") }
}

private fun readLocalText(key: String, fallback: String = ""): String =
    runCatching { Prefs.localAgentPreferences()?.all?.get(key)?.toString() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: fallback

private fun readLocalBoolean(key: String, default: Boolean): Boolean = runCatching {
    when (val value = Prefs.localAgentPreferences()?.all?.get(key)) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        else -> Prefs.isEnabled(key)
    }
}.getOrDefault(default)

private const val RELEASES_URL = "https://github.com/Mangi-11/Eta/releases"

private data class PrivilegedSnapshot(
    val available: Boolean,
    val rootAvailable: Boolean,
    val activeChannel: String,
    val shizukuStatus: JSONObject?,
)

private fun channelLabel(channel: String): String = when (channel) {
    PrivilegedShell.CHANNEL_ROOT -> "Root（su）"
    PrivilegedShell.CHANNEL_SHIZUKU -> "Shizuku（ADB 级）"
    else -> "无（需要 root 或 Shizuku）"
}

private fun shizukuSummary(status: JSONObject?): String {
    if (status == null) return "状态读取失败"
    if (!status.optBoolean("available")) return "未安装或服务未运行"
    if (!status.optBoolean("permission")) return "服务在运行，尚未授权本应用"
    val version = status.optInt("version", -1)
    val backend = status.optString("backend")
    return "已授权 · v$version" + if (backend.isNotBlank()) " · $backend" else ""
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun workflowStatusLabel(status: String): String = when (status) {
    AgentWorkflowStore.STATUS_SUCCESS -> "成功"
    AgentWorkflowStore.STATUS_FAILED -> "失败"
    else -> status
}

private fun packSummary(pack: JSONObject): String {
    if (!pack.optBoolean("valid", true)) {
        return "无效：" + pack.optString("error").ifBlank { "解析失败" }
    }
    val tools = "${pack.optInt("tool_count", 0)} 个工具"
    val version = pack.optString("version").takeIf { it.isNotBlank() }
    return if (version != null) "$tools · v$version" else tools
}
