package io.github.mangi.eta.ui.screens.devtools

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.components.EtaOverlayDropdownPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 开发工具箱：Logcat / SQLite / 性能存储 三个只读诊断面板。
 *
 * 全部数据都在 [Dispatchers.IO] 上采集，任何一路失败只在对应面板内提示，不影响其它面板。
 */
@Composable
fun DevToolsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // ── Logcat ─────────────────────────────────────────────────────────────
    var logcatLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var logcatLoading by remember { mutableStateOf(false) }
    var logcatError by remember { mutableStateOf<String?>(null) }
    var logcatKeyword by remember { mutableStateOf("") }
    var logcatLevelIndex by rememberSaveable { mutableIntStateOf(0) }

    // ── SQL ────────────────────────────────────────────────────────────────
    var tables by remember { mutableStateOf<List<String>>(emptyList()) }
    var tablesLoading by remember { mutableStateOf(false) }
    var tablesError by remember { mutableStateOf<String?>(null) }
    var tablesInitialized by remember { mutableStateOf(false) }
    var selectedTableIndex by remember { mutableIntStateOf(-1) }
    var tableColumns by remember { mutableStateOf<List<String>>(emptyList()) }
    var tableRows by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var tableLoading by remember { mutableStateOf(false) }
    var tableError by remember { mutableStateOf<String?>(null) }
    var queryText by remember { mutableStateOf("") }
    var queryColumns by remember { mutableStateOf<List<String>>(emptyList()) }
    var queryRows by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var queryRan by remember { mutableStateOf(false) }
    var queryLoading by remember { mutableStateOf(false) }
    var queryError by remember { mutableStateOf<String?>(null) }

    // ── 性能 / 存储 ────────────────────────────────────────────────────────
    var perf by remember { mutableStateOf<PerfSnapshot?>(null) }
    var perfLoading by remember { mutableStateOf(false) }
    var perfError by remember { mutableStateOf<String?>(null) }

    fun loadTableRows(table: String?) {
        if (table == null) {
            tableColumns = emptyList()
            tableRows = emptyList()
            tableError = null
            return
        }
        scope.launch {
            tableLoading = true
            tableError = null
            val quoted = table.replace("\"", "\"\"")
            runQuery(context, "SELECT * FROM \"$quoted\" LIMIT 100", 100)
                .onSuccess { result ->
                    tableColumns = result.columns
                    tableRows = result.rows
                }
                .onFailure { error ->
                    tableColumns = emptyList()
                    tableRows = emptyList()
                    tableError = error.message ?: "读取表数据失败"
                }
            tableLoading = false
        }
    }

    fun refreshTables() {
        scope.launch {
            tablesLoading = true
            tablesError = null
            queryTableNames(context)
                .onSuccess { names ->
                    tables = names
                    if (names.isEmpty()) {
                        selectedTableIndex = -1
                        loadTableRows(null)
                    } else {
                        selectedTableIndex = 0
                        loadTableRows(names[0])
                    }
                }
                .onFailure { error ->
                    tables = emptyList()
                    selectedTableIndex = -1
                    tablesError = error.message ?: "读取数据表失败"
                }
            tablesLoading = false
        }
    }

    fun refreshLogcat() {
        scope.launch {
            logcatLoading = true
            logcatError = null
            readLogcatSnapshot()
                .onSuccess { logcatLines = it }
                .onFailure { error ->
                    logcatLines = emptyList()
                    logcatError = error.message ?: "读取日志失败"
                }
            logcatLoading = false
        }
    }

    fun refreshPerf() {
        scope.launch {
            perfLoading = true
            perfError = null
            loadPerfSnapshot(context)
                .onSuccess { perf = it }
                .onFailure { error ->
                    perf = null
                    perfError = error.message ?: "读取性能信息失败"
                }
            perfLoading = false
        }
    }

    fun runQueryBox() {
        val sql = queryText.trim().trimEnd(';').trim()
        queryRan = false
        if (sql.isEmpty()) {
            queryColumns = emptyList()
            queryRows = emptyList()
            queryError = "请输入 SQL"
            return
        }
        if (!isReadOnlyQuery(sql)) {
            queryColumns = emptyList()
            queryRows = emptyList()
            queryError = "只允许 SELECT 或 PRAGMA 开头的只读查询"
            return
        }
        scope.launch {
            queryLoading = true
            queryError = null
            runQuery(context, sql, 200)
                .onSuccess { result ->
                    queryColumns = result.columns
                    queryRows = result.rows
                    queryRan = true
                }
                .onFailure { error ->
                    queryColumns = emptyList()
                    queryRows = emptyList()
                    queryError = error.message ?: "查询失败"
                }
            queryLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshLogcat()
        refreshPerf()
    }
    LaunchedEffect(selectedTab) {
        if (selectedTab == 1 && !tablesInitialized) {
            tablesInitialized = true
            refreshTables()
        }
    }

    val filteredLogs = remember(logcatLines, logcatKeyword, logcatLevelIndex) {
        val level = LOG_LEVEL_OPTIONS[logcatLevelIndex].second
        logcatLines.filter { line ->
            (logcatKeyword.isBlank() || line.contains(logcatKeyword, ignoreCase = true)) &&
                (level == null || logcatLevel(line) == level)
        }
    }

    MiuixScaffoldPage(
        title = "开发工具箱",
        onBack = onBack,
        modifier = modifier,
    ) {
        item(key = "devtools_tabs") {
            TabRow(
                tabs = listOf("Logcat", "数据库", "性能"),
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        when (selectedTab) {
            0 -> {
                item(key = "logcat_keyword") {
                    TextField(
                        value = logcatKeyword,
                        onValueChange = { logcatKeyword = it },
                        label = "关键字过滤",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
                    )
                }
                item(key = "logcat_controls") {
                    EtaPreferenceGroup(
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    ) {
                        EtaOverlayDropdownPreference(
                            title = "日志级别",
                            items = LOG_LEVEL_OPTIONS.map { it.first },
                            selectedIndex = logcatLevelIndex,
                            startAction = {
                                EtaPreferenceIcon(Icons.Rounded.Tune, tint = EtaPreferenceColors.Blue)
                            },
                            onSelectedIndexChange = { logcatLevelIndex = it },
                        )
                        EtaPreferenceDivider()
                        EtaPreference(
                            title = "刷新日志",
                            startAction = {
                                EtaPreferenceIcon(Icons.Rounded.Refresh, tint = EtaPreferenceColors.Green)
                            },
                            endActions = {
                                EtaTextButton(
                                    text = if (logcatLoading) "读取中…" else "刷新",
                                    enabled = !logcatLoading,
                                    onClick = { refreshLogcat() },
                                )
                            },
                        )
                        EtaPreferenceDivider()
                        EtaPreference(
                            title = "复制全部（${filteredLogs.size} 行）",
                            startAction = {
                                EtaPreferenceIcon(Icons.Rounded.ContentCopy, tint = EtaPreferenceColors.Orange)
                            },
                            endActions = {
                                EtaTextButton(
                                    text = "复制",
                                    enabled = filteredLogs.isNotEmpty(),
                                    onClick = {
                                        clipboard.setText(AnnotatedString(filteredLogs.joinToString("\n")))
                                    },
                                )
                            },
                        )
                    }
                }
                when {
                    logcatLoading -> item(key = "logcat_loading") { DevHint("正在读取日志…") }
                    logcatError != null -> item(key = "logcat_error") { DevHint(logcatError ?: "", isError = true) }
                    filteredLogs.isEmpty() -> item(key = "logcat_empty") { DevHint("没有匹配的日志") }
                    else -> items(filteredLogs) { line -> LogLine(line) }
                }
            }

            1 -> {
                item(key = "sql_tables") {
                    EtaPreferenceGroup(
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    ) {
                        EtaOverlayDropdownPreference(
                            title = "数据表",
                            items = tables,
                            selectedIndex = selectedTableIndex.coerceIn(0, (tables.size - 1).coerceAtLeast(0)),
                            enabled = tables.isNotEmpty(),
                            startAction = {
                                EtaPreferenceIcon(Icons.Rounded.Storage, tint = EtaPreferenceColors.Blue)
                            },
                            onSelectedIndexChange = { index ->
                                selectedTableIndex = index
                                loadTableRows(tables.getOrNull(index))
                            },
                        )
                        EtaPreferenceDivider()
                        EtaPreference(
                            title = "刷新数据表",
                            startAction = {
                                EtaPreferenceIcon(Icons.Rounded.Refresh, tint = EtaPreferenceColors.Green)
                            },
                            endActions = {
                                EtaTextButton(
                                    text = if (tablesLoading) "读取中…" else "刷新",
                                    enabled = !tablesLoading,
                                    onClick = { refreshTables() },
                                )
                            },
                        )
                    }
                }
                when {
                    tablesError != null -> item(key = "sql_tables_error") { DevHint(tablesError ?: "", isError = true) }
                    !tablesLoading && tables.isEmpty() -> item(key = "sql_tables_empty") { DevHint("没有可用的数据表") }
                }
                when {
                    tableLoading -> item(key = "sql_table_loading") { DevHint("正在读取表数据…") }
                    tableError != null -> item(key = "sql_table_error") { DevHint(tableError ?: "", isError = true) }
                    tableRows.isNotEmpty() -> {
                        item(key = "sql_table_header") {
                            DevHint(
                                "${tables.getOrNull(selectedTableIndex).orEmpty()}（前 ${tableRows.size} 行）· 列：" +
                                    tableColumns.joinToString(", "),
                            )
                        }
                        items(tableRows) { row -> DataLine(row.joinToString(" | ")) }
                    }
                }
                item(key = "sql_query_title") {
                    EtaPreferenceGroupTitle("SQL 查询（只读）")
                }
                item(key = "sql_query") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
                    ) {
                        TextField(
                            value = queryText,
                            onValueChange = { queryText = it },
                            label = "SELECT 或 PRAGMA 语句",
                            useLabelAsPlaceholder = true,
                            singleLine = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        EtaTextButton(
                            text = if (queryLoading) "执行中…" else "执行",
                            enabled = !queryLoading,
                            onClick = { runQueryBox() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }
                }
                when {
                    queryLoading -> item(key = "sql_query_loading") { DevHint("正在执行查询…") }
                    queryError != null -> item(key = "sql_query_error") { DevHint(queryError ?: "", isError = true) }
                    queryRan && queryRows.isEmpty() ->
                        item(key = "sql_query_empty") { DevHint("查询完成，无结果") }
                    queryRan -> {
                        item(key = "sql_query_header") {
                            DevHint("结果 ${queryRows.size} 行 · 列：${queryColumns.joinToString(", ")}")
                        }
                        items(queryRows) { row -> DataLine(row.joinToString(" | ")) }
                    }
                }
            }

            else -> {
                item(key = "perf_controls") {
                    EtaPreferenceGroup(
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    ) {
                        EtaPreference(
                            title = "刷新概览",
                            startAction = {
                                EtaPreferenceIcon(Icons.Rounded.Refresh, tint = EtaPreferenceColors.Green)
                            },
                            endActions = {
                                EtaTextButton(
                                    text = if (perfLoading) "读取中…" else "刷新",
                                    enabled = !perfLoading,
                                    onClick = { refreshPerf() },
                                )
                            },
                        )
                    }
                }
                val snapshot = perf
                when {
                    perfError != null -> item(key = "perf_error") { DevHint(perfError ?: "", isError = true) }
                    snapshot == null -> item(key = "perf_loading") { DevHint("正在读取性能信息…") }
                    else -> item(key = "perf_group") {
                        EtaPreferenceGroup(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            EtaPreference(
                                title = "内存",
                                summary = "可用 ${formatBytes(snapshot.availableMemory)} / 总计 " +
                                    formatBytes(snapshot.totalMemory),
                                startAction = {
                                    EtaPreferenceIcon(Icons.Rounded.Memory, tint = EtaPreferenceColors.Blue)
                                },
                            )
                            EtaPreferenceDivider()
                            EtaPreference(
                                title = "设备存储",
                                summary = "可用 ${formatBytes(snapshot.availableStorage)} / 总计 " +
                                    formatBytes(snapshot.totalStorage),
                                startAction = {
                                    EtaPreferenceIcon(Icons.Rounded.Storage, tint = EtaPreferenceColors.Green)
                                },
                            )
                            EtaPreferenceDivider()
                            EtaPreference(
                                title = "Eta 数据占用",
                                summary = formatBytes(snapshot.appDataBytes),
                                startAction = {
                                    EtaPreferenceIcon(Icons.Rounded.Folder, tint = EtaPreferenceColors.Orange)
                                },
                            )
                            EtaPreferenceDivider()
                            EtaPreference(
                                title = "进程运行时长",
                                summary = snapshot.uptimeSeconds?.let(::formatDuration) ?: "不可用",
                                startAction = {
                                    EtaPreferenceIcon(Icons.Rounded.AccessTime, tint = EtaPreferenceColors.Blue)
                                },
                            )
                            EtaPreferenceDivider()
                            EtaPreference(
                                title = "线程数",
                                summary = snapshot.threadCount?.toString() ?: "不可用",
                                startAction = {
                                    EtaPreferenceIcon(Icons.Rounded.Widgets, tint = EtaPreferenceColors.Green)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 行渲染 ────────────────────────────────────────────────────────────────

@Composable
private fun DevHint(text: String, isError: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MiuixTheme.textStyles.body2,
        color = if (isError) {
            MiuixTheme.colorScheme.error
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
    )
}

@Composable
private fun LogLine(line: String) {
    val level = logcatLevel(line)
    val color = when (level) {
        "E", "F" -> MiuixTheme.colorScheme.error
        "W" -> Color(0xFFB26A00)
        else -> MiuixTheme.colorScheme.onBackground
    }
    SelectionContainer {
        Text(
            text = line,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 1.dp),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            ),
            color = color,
        )
    }
}

@Composable
private fun DataLine(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp),
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        ),
        color = MiuixTheme.colorScheme.onBackground,
    )
}

// ── 数据采集 ──────────────────────────────────────────────────────────────

private data class QueryResult(val columns: List<String>, val rows: List<List<String>>)

private data class PerfSnapshot(
    val totalMemory: Long,
    val availableMemory: Long,
    val totalStorage: Long,
    val availableStorage: Long,
    val appDataBytes: Long,
    val uptimeSeconds: Long?,
    val threadCount: Int?,
)

private val LOG_LEVEL_OPTIONS = listOf(
    "全部" to null,
    "Verbose (V)" to "V",
    "Debug (D)" to "D",
    "Info (I)" to "I",
    "Warn (W)" to "W",
    "Error (E)" to "E",
    "Fatal (F)" to "F",
)

/** logcat threadtime 前缀：`MM-DD HH:MM:SS.mmm PID TID L Tag:`，取优先级字母。 */
private val LOGCAT_LEVEL_REGEX = Regex("""^\S+\s+\S+\s+\d+\s+\d+\s+([VDIWEF])""")

private fun logcatLevel(line: String): String? =
    LOGCAT_LEVEL_REGEX.find(line)?.groupValues?.getOrNull(1)

private suspend fun readLogcatSnapshot(): Result<List<String>> = withContext(Dispatchers.IO) {
    runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "500")
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        lines
    }
}

private suspend fun queryTableNames(context: Context): Result<List<String>> = withContext(Dispatchers.IO) {
    runCatching {
        val database = EtaDatabase.get(context).openHelper.readableDatabase
        database.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }
}

private suspend fun runQuery(context: Context, sql: String, limit: Int): Result<QueryResult> =
    withContext(Dispatchers.IO) {
        runCatching {
            val database = EtaDatabase.get(context).openHelper.readableDatabase
            database.query(sql).use { cursor ->
                val columns = cursor.columnNames.toList()
                val rows = ArrayList<List<String>>()
                while (cursor.moveToNext() && rows.size < limit) {
                    rows += columns.indices.map { index -> cursor.getString(index) ?: "NULL" }
                }
                QueryResult(columns, rows)
            }
        }
    }

private fun isReadOnlyQuery(sql: String): Boolean {
    val upper = sql.trimStart().uppercase()
    val allowed = upper.startsWith("SELECT") || upper.startsWith("PRAGMA")
    return allowed && !sql.contains(';')
}

private suspend fun loadPerfSnapshot(context: Context): Result<PerfSnapshot> = withContext(Dispatchers.IO) {
    runCatching {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val statFs = StatFs(Environment.getDataDirectory().absolutePath)
        val dataDir = context.filesDir.parentFile
        val appDataBytes = directorySize(context.filesDir) +
            (dataDir?.let { directorySize(File(it, "databases")) } ?: 0L) +
            directorySize(context.cacheDir)
        PerfSnapshot(
            totalMemory = memoryInfo.totalMem,
            availableMemory = memoryInfo.availMem,
            totalStorage = statFs.blockCountLong * statFs.blockSizeLong,
            availableStorage = statFs.availableBlocksLong * statFs.blockSizeLong,
            appDataBytes = appDataBytes,
            uptimeSeconds = readProcessUptimeSeconds(),
            threadCount = readThreadCount(),
        )
    }
}

/** 递归求和，限制深度与访问文件数，避免大目录拖垮读取。 */
private fun directorySize(root: File, maxDepth: Int = 6, maxFiles: Int = 8000): Long {
    if (!root.exists()) return 0L
    var total = 0L
    var visited = 0
    val stack = ArrayDeque<Pair<File, Int>>()
    stack.addLast(root to 0)
    while (stack.isNotEmpty() && visited < maxFiles) {
        val (dir, depth) = stack.removeLast()
        val children = dir.listFiles() ?: continue
        for (child in children) {
            if (visited >= maxFiles) break
            visited++
            if (child.isFile) {
                total += child.length()
            } else if (child.isDirectory && depth < maxDepth) {
                stack.addLast(child to depth + 1)
            }
        }
    }
    return total
}

/** 进程运行时长 = 系统 uptime - /proc/self/stat 的 starttime（按 100Hz 换算）。 */
private fun readProcessUptimeSeconds(): Long? = runCatching {
    val uptime = File("/proc/uptime").readText().trim().substringBefore(' ').toDouble()
    val stat = File("/proc/self/stat").readText()
    val fields = stat.substringAfterLast(") ").split(' ')
    // 去掉 pid(comm) 后从 state 字段起算，starttime 为第 22 个字段，即下标 19。
    val startSeconds = fields[19].toLong() / 100.0
    (uptime - startSeconds).toLong().coerceAtLeast(0)
}.getOrNull()

private fun readThreadCount(): Int? = runCatching {
    File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("Threads:") }
        ?.substringAfter(':')
        ?.trim()
        ?.toInt()
}.getOrNull()

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return "%.1f %s".format(value, units[index])
}

private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分${seconds}秒"
        else -> "${seconds}秒"
    }
}
