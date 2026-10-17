package io.github.mangi.eta.ui.screens.mcp

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mcp.EtaMcpServerService
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitch
import java.security.SecureRandom
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 本地 MCP Server 设置区块 —— 在设置页让用户启停 Eta 自身的 MCP 服务。
 *
 * 状态机：
 * - [isEnabled] 反映 Prefs.MCP_SERVER_ENABLED，用户切换后写入。
 * - [serviceRunning] 是从外面周期性探测 EtaMcpServerService 是否真的在监听端口。
 *   这两者可能不一致（例如 Service 进程被杀但 Prefs 仍开着），界面要清晰反馈。
 */
@Composable
internal fun McpLocalServerSection(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MiuixTheme.colorScheme

    var isEnabled by remember { mutableStateOf(Prefs.isEnabled(Prefs.Keys.MCP_SERVER_ENABLED)) }
    var allowSensitive by remember { mutableStateOf(Prefs.isEnabled(Prefs.Keys.MCP_SERVER_ALLOW_SENSITIVE)) }
    var port by remember {
        mutableIntStateOf(Prefs.getLocalAgentInt(Prefs.Keys.MCP_SERVER_PORT, 18181).coerceIn(1024, 65535))
    }
    var token by remember { mutableStateOf(Prefs.getString(Prefs.Keys.MCP_SERVER_TOKEN)) }
    var serviceRunning by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var transientMessage by remember { mutableStateOf<String?>(null) }

    // 启动后每 3 秒探测一次 Service 状态；停服后清空
    LaunchedEffect(isEnabled) {
        while (isEnabled) {
            serviceRunning = isEtaMcpServerRunning(context)
            delay(3000)
        }
        serviceRunning = false
    }

    val statusText = when {
        working && serviceRunning -> stringResource(R.string.mcp_server_status_stopping)
        working && !serviceRunning -> stringResource(R.string.mcp_server_status_starting)
        serviceRunning -> stringResource(R.string.mcp_server_status_running, port)
        else -> stringResource(R.string.mcp_server_status_idle)
    }

    fun start() {
        if (token.isBlank()) {
            token = generateToken()
        }
        if (port !in 1024..65535) {
            transientMessage = context.getString(R.string.mcp_server_port_invalid)
            return
        }
        Prefs.putLocal(Prefs.Keys.MCP_SERVER_ENABLED, true)
        Prefs.putLocal(Prefs.Keys.MCP_SERVER_PORT, port)
        Prefs.putLocal(Prefs.Keys.MCP_SERVER_ALLOW_SENSITIVE, allowSensitive)
        Prefs.putLocal(Prefs.Keys.MCP_SERVER_TOKEN, token)
        isEnabled = true
        working = true
        EtaMcpServerService.start(context)
        scope.launch {
            delay(400)
            working = false
            serviceRunning = isEtaMcpServerRunning(context)
            transientMessage = context.getString(R.string.mcp_server_started)
        }
    }

    fun stop() {
        Prefs.putLocal(Prefs.Keys.MCP_SERVER_ENABLED, false)
        isEnabled = false
        working = true
        EtaMcpServerService.stop(context)
        scope.launch {
            delay(400)
            working = false
            serviceRunning = false
            transientMessage = context.getString(R.string.mcp_server_stopped)
        }
    }

    androidx.compose.foundation.layout.Column(modifier = modifier.fillMaxWidth()) {
        EtaPreferenceGroupTitle(stringResource(R.string.mcp_server_section_title))
        EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
            EtaPreferenceGroupItem(
                isFirst = true,
                isLast = false,
            ) {
                EtaPreference(
                    title = stringResource(R.string.mcp_server_enable_label),
                    summary = stringResource(R.string.mcp_server_enable_summary),
                    enabled = !working,
                ) {
                    EtaSwitch(
                        checked = isEnabled,
                        onCheckedChange = { checked ->
                            isEnabled = checked
                            Prefs.putLocal(Prefs.Keys.MCP_SERVER_ENABLED, checked)
                            if (!checked) stop() else start()
                        },
                        enabled = !working,
                    )
                }
            }
            EtaPreferenceGroupItem(isFirst = false, isLast = false) {
                EtaPreference(
                    title = stringResource(R.string.mcp_server_port_label),
                    summary = stringResource(R.string.mcp_server_port_summary),
                    enabled = !working,
                ) {
                    PortTextField(
                        value = port,
                        onValueChange = { newValue ->
                            port = newValue
                            Prefs.putLocal(Prefs.Keys.MCP_SERVER_PORT, newValue)
                        },
                    )
                }
            }
            EtaPreferenceGroupItem(isFirst = false, isLast = false) {
                EtaPreference(
                    title = stringResource(R.string.mcp_server_token_label),
                    summary = stringResource(R.string.mcp_server_token_summary),
                    enabled = !working,
                ) {
                    TextField(
                        value = token,
                        onValueChange = { newValue ->
                            token = newValue
                            Prefs.putLocal(Prefs.Keys.MCP_SERVER_TOKEN, newValue)
                        },
                        label = stringResource(R.string.mcp_server_token_placeholder),
                        singleLine = true,
                        enabled = !working,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                    )
                }
            }
            EtaPreferenceGroupItem(isFirst = false, isLast = false) {
                EtaPreference(
                    title = stringResource(R.string.mcp_server_allow_sensitive_label),
                    summary = stringResource(R.string.mcp_server_allow_sensitive_summary),
                    enabled = !working,
                ) {
                    EtaSwitch(
                        checked = allowSensitive,
                        onCheckedChange = { checked ->
                            allowSensitive = checked
                            Prefs.putLocal(Prefs.Keys.MCP_SERVER_ALLOW_SENSITIVE, checked)
                        },
                        enabled = !working,
                    )
                }
            }
            EtaPreferenceGroupItem(isFirst = false, isLast = true) {
                EtaPreference(
                    title = stringResource(R.string.mcp_server_status_label),
                    summary = statusText,
                ) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Button(
                            onClick = ::start,
                            enabled = !working && !serviceRunning,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primary,
                                contentColor = colors.onPrimary,
                            ),
                        ) {
                            Text(stringResource(R.string.mcp_server_start_button))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = ::stop,
                            enabled = !working && serviceRunning,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.error,
                                contentColor = colors.onError,
                            ),
                        ) {
                            Text(stringResource(R.string.mcp_server_stop_button))
                        }
                    }
                }
            }
        }
        if (transientMessage != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = transientMessage!!,
                style = MiuixTheme.textStyles.body2,
                color = colors.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            // 3 秒后自动消失
            LaunchedEffect(transientMessage) {
                delay(3000)
                transientMessage = null
            }
        }
    }
}

@Composable
private fun PortTextField(
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    TextField(
        value = text,
        onValueChange = {
            text = it.filter { ch -> ch.isDigit() }.take(5)
            val parsed = text.toIntOrNull()
            if (parsed != null && parsed in 0..65535) onValueChange(parsed)
        },
        label = "0-65535",
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
    )
}

private fun generateToken(): String {
    // 32 字节随机 → 64 个十六进制字符，符合常见安全阈值
    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun isEtaMcpServerRunning(context: Context): Boolean {
    // 通过查询 ActivityManager 判断 Service 进程是否存在
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val myPid = android.os.Process.myPid()
    return runCatching {
        am.getRunningServices(Int.MAX_VALUE).any { service ->
            service.service.className == EtaMcpServerService::class.java.name &&
                service.pid != myPid && service.pid > 0
        }
    }.getOrDefault(false)
}
