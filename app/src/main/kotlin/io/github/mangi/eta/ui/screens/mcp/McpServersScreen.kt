package io.github.mangi.eta.ui.screens.mcp

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mcp.McpServerManager
import io.github.mangi.eta.agent.mcp.validateMcpEndpoint
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.model.McpTransport
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun McpServersScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableStateOf<McpServerPreset?>(null) }
    var presetKey by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmTrades by remember { mutableStateOf(Prefs.isEnabled(Prefs.Keys.MCP_CONFIRM_TRADES)) }
    var disableConfirmGate by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    MiuixScaffoldPage(
        title = stringResource(R.string.route_mcp_servers),
        onBack = onBack,
        actions = {
            IconButton(onClick = { showAdd = true }) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.mcp_add_server),
                )
            }
        },
    ) {
        item(key = "trade_gate") {
            EtaPreferenceGroupTitle(stringResource(R.string.mcp_security_section))
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                EtaSwitchPreference(
                    title = stringResource(R.string.mcp_confirm_trades),
                    summary = stringResource(R.string.mcp_confirm_trades_summary),
                    checked = confirmTrades,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            confirmTrades = true
                            Prefs.putLocal(Prefs.Keys.MCP_CONFIRM_TRADES, true)
                        } else {
                            disableConfirmGate = true
                        }
                    },
                )
            }
        }
        item(key = "local_server") {
            McpLocalServerSection()
        }
        item(key = "servers") {
            EtaPreferenceGroupTitle(stringResource(R.string.mcp_configured_servers, servers.size))
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                if (servers.isEmpty()) {
                    ListEmptyState(
                        title = stringResource(R.string.mcp_empty_title),
                        summary = stringResource(R.string.mcp_empty_summary),
                        action = {
                            EtaTextButton(
                                text = stringResource(R.string.mcp_add_server),
                                onClick = { showAdd = true },
                            )
                        },
                    )
                } else {
                    servers.forEach { server ->
                        EtaArrowPreference(
                            title = server.name,
                            summary = stringResource(
                                R.string.mcp_server_row_summary,
                                server.activeTools.size,
                                server.tools.size,
                            ),
                            onClick = { onNavigate(AppRoute.McpServerDetail(server.id)) },
                        )
                    }
                }
            }
        }
    }

    if (disableConfirmGate) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.mcp_confirm_trades_disable_title),
            summary = stringResource(R.string.mcp_confirm_trades_disable_summary),
            onDismissRequest = { disableConfirmGate = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.mcp_confirm_trades_disable_confirm),
                destructive = true,
                onCancel = { disableConfirmGate = false },
                onConfirm = {
                    confirmTrades = false
                    Prefs.putLocal(Prefs.Keys.MCP_CONFIRM_TRADES, false)
                    disableConfirmGate = false
                },
            )
        }
    }

    EtaWindowDialog(
        show = showAdd,
        title = stringResource(R.string.mcp_add_server),
        onDismissRequest = { if (!working) showAdd = false },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.mcp_preset_title),
                style = MiuixTheme.textStyles.body2,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                McpServerPresets.LIST.forEach { preset ->
                    EtaTextButton(
                        text = stringResource(preset.nameRes),
                        onClick = {
                            selectedPreset = preset
                            name = resources.getString(preset.nameRes)
                            presetKey = ""
                            error = null
                        },
                    )
                }
                EtaTextButton(
                    text = stringResource(R.string.mcp_preset_custom),
                    onClick = {
                        selectedPreset = null
                        error = null
                    },
                )
            }
            val preset = selectedPreset
            if (preset != null) {
                Text(
                    text = stringResource(preset.descriptionRes),
                    style = MiuixTheme.textStyles.body2,
                )
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.mcp_server_name),
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = presetKey,
                    onValueChange = { presetKey = it },
                    label = stringResource(R.string.mcp_preset_key_label),
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                EtaTextButton(
                    text = stringResource(R.string.mcp_preset_get_key),
                    onClick = { runCatching { uriHandler.openUri(preset.docsUrl) } },
                )
            } else {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.mcp_server_name),
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = url,
                    onValueChange = { url = it },
                    label = stringResource(R.string.mcp_server_url),
                    singleLine = true,
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (url.trim().startsWith(McpTransport.STDIO_PREFIX)) {
                    Text(
                        text = "stdio 本地服务器：stdio://<命令>，可用 ?cwd=<目录> 指定工作目录",
                        style = MiuixTheme.textStyles.body2,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    McpServerPresets.STDIO_EXAMPLES.forEach { example ->
                        EtaTextButton(
                            text = example.label,
                            onClick = {
                                url = example.command
                                if (name.isBlank()) name = example.label
                                error = null
                            },
                        )
                    }
                }
                TextField(
                    value = token,
                    onValueChange = { token = it },
                    label = stringResource(R.string.mcp_bearer_optional),
                    singleLine = true,
                    enabled = !working,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                )
            }
            MiuixDialogActions(
                confirmText = if (working) {
                    stringResource(R.string.mcp_connecting)
                } else {
                    stringResource(R.string.mcp_add_server)
                },
                confirmEnabled = !working,
                onCancel = { showAdd = false },
                onConfirm = {
                    val normalizedName = name.trim()
                    val activePreset = selectedPreset
                    val effectiveUrl = if (activePreset != null) {
                        McpServerPresets.buildUrl(activePreset, presetKey.trim())
                    } else {
                        url.trim()
                    }
                    error = when {
                        normalizedName.isBlank() -> resources.getString(R.string.mcp_name_required)
                        activePreset != null && presetKey.isBlank() ->
                            resources.getString(R.string.mcp_preset_key_required)
                        effectiveUrl.trim().startsWith(McpTransport.STDIO_PREFIX) -> null
                        else -> runCatching { validateMcpEndpoint(effectiveUrl) }
                            .exceptionOrNull()?.message
                    }
                    if (error == null) scope.launch {
                        working = true
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val draft = McpServerSetting(
                                    id = "",
                                    name = normalizedName,
                                    url = effectiveUrl,
                                    protocolMode = McpProtocolMode.AUTO,
                                    authorizationType = if (activePreset == null && token.isNotBlank()) {
                                        McpAuthorizationType.BEARER
                                    } else {
                                        McpAuthorizationType.NONE
                                    },
                                )
                                val effectiveToken = if (activePreset == null) token else ""
                                val discovered = McpServerManager.discover(draft, effectiveToken)
                                McpServerRepository.add(discovered, effectiveToken)
                            }
                        }
                        working = false
                        result.onSuccess {
                            showAdd = false
                            name = ""
                            url = ""
                            token = ""
                            presetKey = ""
                            selectedPreset = null
                            error = null
                            Toast.makeText(
                                context,
                                resources.getString(R.string.mcp_server_added, it.tools.size),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }.onFailure {
                            error = it.message ?: resources.getString(R.string.mcp_connection_failed)
                        }
                    }
                },
            )
        }
    }
}

@Composable
internal fun McpServerDetailScreen(
    serverId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    val server = servers.firstOrNull { it.id == serverId }
    var working by remember { mutableStateOf(false) }
    var pendingRiskyTool by remember { mutableStateOf<McpToolDefinition?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var showHeaders by remember { mutableStateOf(false) }
    var headerCount by remember { mutableStateOf(0) }
    var showEnableAll by remember { mutableStateOf(false) }

    LaunchedEffect(serverId, showHeaders) {
        headerCount = withContext(Dispatchers.IO) {
            runCatching { McpServerRepository.customHeaders(serverId).size }.getOrDefault(0)
        }
    }

    MiuixScaffoldPage(
        title = server?.name ?: stringResource(R.string.route_mcp_server_detail),
        onBack = onBack,
        actions = {
            IconButton(
                enabled = server != null && !working,
                onClick = {
                    scope.launch {
                        working = true
                        val result = withContext(Dispatchers.IO) {
                            runCatching { McpServerManager.refresh(serverId) }
                        }
                        working = false
                        Toast.makeText(
                            context,
                            result.fold(
                                onSuccess = { resources.getString(R.string.mcp_refreshed, it.tools.size) },
                                onFailure = { it.message ?: resources.getString(R.string.mcp_refresh_failed) },
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.mcp_refresh_tools),
                )
            }
        },
    ) {
        if (server == null) {
            item(key = "missing") {
                EtaPreference(title = stringResource(R.string.mcp_server_missing))
            }
            return@MiuixScaffoldPage
        }
        item(key = "server") {
            EtaPreferenceGroupTitle(stringResource(R.string.mcp_server_settings))
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                EtaSwitchPreference(
                    title = stringResource(R.string.mcp_enable_server),
                    summary = server.url,
                    checked = server.enabled,
                    onCheckedChange = { enabled ->
                        scope.launch(Dispatchers.IO) {
                            McpServerRepository.update(server.copy(enabled = enabled))
                        }
                    },
                )

                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(
                    title = stringResource(R.string.mcp_update_token),
                    summary = stringResource(
                        if (server.authorizationType == McpAuthorizationType.BEARER) {
                            R.string.mcp_token_configured
                        } else {
                            R.string.mcp_no_authentication
                        }
                    ),
                    onClick = { showToken = true },
                )

                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(
                    title = stringResource(R.string.mcp_custom_headers),
                    summary = if (headerCount > 0) {
                        stringResource(R.string.mcp_custom_headers_count, headerCount)
                    } else {
                        stringResource(R.string.mcp_custom_headers_none)
                    },
                    onClick = { showHeaders = true },
                )
            }
        }
        item(key = "tools") {
            EtaPreferenceGroupTitle(stringResource(R.string.mcp_tools_count, server.tools.size))
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                if (server.tools.isEmpty()) {
                    ListEmptyState(
                        title = stringResource(R.string.mcp_no_tools),
                        summary = stringResource(R.string.mcp_refresh_tools_hint),
                    )
                } else {
                    val allToolsEnabled = server.tools.all { it.name in server.enabledToolNames }
                    EtaArrowPreference(
                        title = stringResource(R.string.mcp_enable_all_tools),
                        summary = if (allToolsEnabled) {
                            stringResource(R.string.mcp_enable_all_tools_done)
                        } else {
                            stringResource(R.string.mcp_enable_all_tools_summary)
                        },
                        onClick = { if (!allToolsEnabled) showEnableAll = true },
                    )
                    EtaPreferenceDivider(hasLeading = false)
                    server.tools.forEach { tool ->
                        val checked = tool.name in server.enabledToolNames
                        EtaSwitchPreference(
                            title = tool.title.ifBlank { tool.name },
                            summary = toolSummary(tool),
                            checked = checked,
                            onCheckedChange = { enabled ->
                                if (enabled && tool.readOnlyHint != true) {
                                    pendingRiskyTool = tool
                                } else {
                                    scope.launch(Dispatchers.IO) {
                                        McpServerRepository.setToolEnabled(serverId, tool.name, enabled)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        item(key = "delete") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                EtaPreference(
                    title = stringResource(R.string.mcp_delete_server),
                    summary = stringResource(R.string.mcp_delete_server_summary),
                    onClick = { showDelete = true },
                )
            }
        }
    }

    if (showEnableAll) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.mcp_enable_all_tools_dialog_title),
            summary = stringResource(
                R.string.mcp_enable_all_tools_dialog_summary,
                server?.tools?.size ?: 0,
                server?.name.orEmpty(),
            ),
            onDismissRequest = { showEnableAll = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.mcp_enable_all_tools),
                onCancel = { showEnableAll = false },
                onConfirm = {
                    val names = server?.tools?.map { it.name }?.toSet().orEmpty()
                    scope.launch(Dispatchers.IO) {
                        runCatching { McpServerRepository.setToolsEnabled(serverId, names, true) }
                    }
                    showEnableAll = false
                },
            )
        }
    }

    pendingRiskyTool?.let { tool ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.mcp_enable_risky_tool),
            summary = stringResource(R.string.mcp_enable_risky_tool_summary, tool.name, server?.name.orEmpty()),
            onDismissRequest = { pendingRiskyTool = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.mcp_enable_tool),
                destructive = true,
                onCancel = { pendingRiskyTool = null },
                onConfirm = {
                    scope.launch(Dispatchers.IO) {
                        McpServerRepository.setToolEnabled(serverId, tool.name, true)
                    }
                    pendingRiskyTool = null
                },
            )
        }
    }

    EtaWindowDialog(
        show = showToken,
        title = stringResource(R.string.mcp_update_token),
        summary = stringResource(R.string.mcp_update_token_summary),
        onDismissRequest = { showToken = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = token,
                onValueChange = { token = it },
                label = stringResource(R.string.mcp_bearer_token),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_save),
                onCancel = { showToken = false },
                onConfirm = {
                    server?.let { current ->
                        scope.launch(Dispatchers.IO) {
                            McpServerRepository.update(
                                current.copy(
                                    authorizationType = if (token.isBlank()) {
                                        McpAuthorizationType.NONE
                                    } else {
                                        McpAuthorizationType.BEARER
                                    },
                                ),
                                bearerToken = token,
                            )
                        }
                        token = ""
                        showToken = false
                    }
                },
            )
        }
    }

    EtaWindowDialog(
        show = showHeaders,
        title = stringResource(R.string.mcp_custom_headers),
        summary = stringResource(R.string.mcp_custom_headers_summary),
        onDismissRequest = { showHeaders = false },
    ) {
        var headers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        var headerNameInput by remember { mutableStateOf("") }
        var headerValueInput by remember { mutableStateOf("") }
        LaunchedEffect(showHeaders) {
            if (showHeaders) {
                headers = withContext(Dispatchers.IO) {
                    runCatching { McpServerRepository.customHeaders(serverId) }.getOrDefault(emptyMap())
                }
                headerNameInput = ""
                headerValueInput = ""
            }
        }
        fun persistHeaders(next: Map<String, String>) {
            headers = next
            scope.launch(Dispatchers.IO) {
                runCatching { McpServerRepository.setCustomHeaders(serverId, next) }
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (headers.isEmpty()) {
                Text(
                    text = stringResource(R.string.mcp_custom_headers_empty),
                    style = MiuixTheme.textStyles.body2,
                )
            } else {
                headers.forEach { (headerName, headerValue) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = headerName,
                                style = MiuixTheme.textStyles.body1,
                            )
                            Text(
                                text = maskHeaderValue(headerValue),
                                style = MiuixTheme.textStyles.body2,
                            )
                        }
                        IconButton(onClick = { persistHeaders(headers - headerName) }) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.ui_delete_3755f5),
                            )
                        }
                    }
                }
            }
            TextField(
                value = headerNameInput,
                onValueChange = { headerNameInput = it },
                label = stringResource(R.string.mcp_custom_header_name),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = headerValueInput,
                onValueChange = { headerValueInput = it },
                label = stringResource(R.string.mcp_custom_header_value),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                confirmText = stringResource(R.string.mcp_custom_header_add),
                confirmEnabled = validHeaderName(headerNameInput) && headerValueInput.isNotBlank(),
                onCancel = { showHeaders = false },
                onConfirm = {
                    persistHeaders(headers + (headerNameInput.trim() to headerValueInput.trim()))
                    headerNameInput = ""
                    headerValueInput = ""
                },
            )
        }
    }

    EtaWindowDialog(
        show = showDelete,
        title = stringResource(R.string.mcp_delete_server),
        summary = stringResource(R.string.mcp_delete_confirm, server?.name.orEmpty()),
        onDismissRequest = { showDelete = false },
    ) {
        MiuixDialogActions(
            confirmText = stringResource(R.string.ui_delete_3755f5),
            destructive = true,
            onCancel = { showDelete = false },
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { McpServerRepository.delete(serverId) }
                    onBack()
                }
            },
        )
    }
}

@Composable
private fun toolSummary(tool: McpToolDefinition): String = when {
    tool.readOnlyHint == true -> tool.description.ifBlank { stringResource(R.string.mcp_read_only_tool) }
    else -> tool.description.ifBlank { stringResource(R.string.mcp_may_modify_data) }
}

private fun maskHeaderValue(value: String): String = if (value.length <= 8) {
    "••••"
} else {
    value.take(4) + "…" + value.takeLast(4)
}

private val HEADER_NAME_PATTERN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

private fun validHeaderName(name: String): Boolean =
    HEADER_NAME_PATTERN.matches(name.trim())
