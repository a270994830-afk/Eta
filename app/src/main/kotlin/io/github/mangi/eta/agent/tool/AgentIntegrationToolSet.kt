package io.github.mangi.eta.agent.tool

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.model.AgentIntegrationToolCatalog
import io.github.mangi.eta.core.AgentLogger
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * 集成/系统工具族：HTTP 请求、应用安装卸载、Intent/广播、Toast、通知、表达式计算与版本检查。
 *
 * 约定：execute 只认 [TOOL_NAMES]；未知名字返回 null。任何异常都在内部转换为错误 JSON，
 * 不向调用方抛出，避免单个工具失败中断整轮 Agent 执行。
 */
internal class AgentIntegrationToolSet(
    private val context: Context,
    private val logger: AgentLogger,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
    private val httpClient: OkHttpClient,
) {

    fun execute(name: String, args: JSONObject): String? {
        if (name !in TOOL_NAMES) return null
        return try {
            when (name) {
                "http_request" -> httpRequest(args)
                "install_app" -> installApp(args)
                "uninstall_app" -> uninstallApp(args)
                "list_installed_apps" -> listInstalledApps(args)
                "execute_intent" -> executeIntent(args)
                "send_broadcast" -> sendBroadcast(args)
                "toast" -> toast(args)
                "send_notification" -> sendNotification(args)
                "calculate" -> calculate(args)
                "update_check" -> updateCheck(args)
                else -> null
            }
        } catch (e: Exception) {
            logger.warn("Agent integration tool action=$name outcome=failed error=${e.message}")
            error("TOOL_FAILED", "工具执行失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    // ---------------------------------------------------------------- HTTP

    private fun httpRequest(args: JSONObject): String {
        val rawUrl = args.optString("url").trim()
        if (rawUrl.isEmpty()) return error("INVALID_ARGUMENT", "缺少 url")
        val scheme = runCatching { URI(rawUrl).scheme?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        if (scheme != "http" && scheme != "https") {
            return error(
                "UNSUPPORTED_SCHEME",
                "只允许 http/https 请求，收到的 scheme=" + scheme.ifEmpty { "(无)" },
            )
        }
        val method = args.optString("method", "GET").trim().uppercase(Locale.ROOT).ifEmpty { "GET" }
        if (method !in SUPPORTED_HTTP_METHODS) return error("INVALID_ARGUMENT", "不支持的 HTTP 方法：" + method)
        val timeoutSeconds = args.optInt("timeout_seconds", DEFAULT_HTTP_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_HTTP_TIMEOUT_SECONDS)
        val maxBytes = args.optInt("max_bytes", DEFAULT_HTTP_MAX_BYTES)
            .coerceIn(MIN_HTTP_MAX_BYTES, MAX_HTTP_MAX_BYTES)
        val body = args.optString("body")
        val headerObject = args.optJSONObject("headers")

        val contentType = headerObject?.optString("Content-Type")?.takeIf(String::isNotBlank)
            ?: headerObject?.optString("content-type")?.takeIf(String::isNotBlank)
            ?: DEFAULT_CONTENT_TYPE
        val ignoredHeaders = JSONArray()
        val builder = Request.Builder().url(rawUrl)
        headerObject?.keys()?.forEach { key ->
            if (key.equals("Content-Type", ignoreCase = true)) return@forEach
            if (MANAGED_HEADERS.any { it.equals(key, ignoreCase = true) }) {
                ignoredHeaders.put(key)
                return@forEach
            }
            val value = headerObject.optString(key)
            runCatching { builder.header(key, value) }.onFailure { ignoredHeaders.put(key) }
        }

        val bodyDropped = method == "GET" && body.isNotEmpty()
        val request = when (method) {
            "GET" -> builder.get().build()
            "DELETE" -> builder.delete(buildBodyOrNull(body, contentType)).build()
            else -> builder.method(method, buildBodyOrNull(body, contentType) ?: EMPTY_BODY).build()
        }
        val client = httpClient.newBuilder()
            .callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            return error("TIMEOUT", "请求超时（" + timeoutSeconds + " 秒）")
        } catch (e: IOException) {
            return error("NETWORK_ERROR", "网络请求失败：" + (e.message ?: e.javaClass.simpleName))
        }
        response.use { result ->
            val text = readBounded(result.body.source(), maxBytes)
            val headers = JSONObject()
            val names = result.headers.names()
            var index = 0
            for (headerName in names) {
                if (index >= MAX_RESPONSE_HEADER_ENTRIES) break
                headers.put(headerName, result.headers.values(headerName).joinToString(", "))
                index++
            }
            return ok("http_request")
                .put("method", method)
                .put("status", result.code)
                .put("successful", result.isSuccessful)
                .put("final_url", result.request.url.toString())
                .put("headers", headers)
                .put("header_count", names.size)
                .put("body", text.text)
                .put("body_bytes", text.bytes)
                .put("truncated", text.truncated)
                .apply {
                    if (bodyDropped) put("note", "GET 请求不支持请求体，body 已忽略")
                    if (ignoredHeaders.length() > 0) put("ignored_headers", ignoredHeaders)
                }
                .toString()
        }
    }

    private fun buildBodyOrNull(body: String, contentType: String): okhttp3.RequestBody? =
        if (body.isEmpty()) null else body.toRequestBody(contentType.toMediaType())

    private fun readBounded(source: BufferedSource, maxBytes: Int): BoundedText {
        val buffer = Buffer()
        var total = 0L
        var truncated = false
        while (total < maxBytes) {
            val remaining = minOf(maxBytes - total, READ_CHUNK_BYTES.toLong())
            val read = source.read(buffer, remaining)
            if (read == -1L) break
            total += read
        }
        if (total >= maxBytes) {
            truncated = runCatching { !source.exhausted() }.getOrDefault(true)
        }
        return BoundedText(text = buffer.readUtf8(), truncated = truncated, bytes = total)
    }

    // ------------------------------------------------------- 应用安装/卸载

    private fun installApp(args: JSONObject): String {
        val apkPath = args.optString("apk_path").trim()
        val url = args.optString("url").trim()
        if (apkPath.isEmpty() && url.isEmpty()) return error("INVALID_ARGUMENT", "需要 apk_path 或 url 之一")
        if (apkPath.isNotEmpty() && url.isNotEmpty()) {
            return error("INVALID_ARGUMENT", "apk_path 与 url 只能提供其中一个")
        }
        var localPath = apkPath
        if (localPath.isEmpty()) {
            val scheme = runCatching { URI(url).scheme?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
            if (scheme != "http" && scheme != "https") {
                return error("UNSUPPORTED_SCHEME", "只允许通过 http/https 下载 APK")
            }
            localPath = try {
                downloadApk(url).absolutePath
            } catch (e: IOException) {
                return error("DOWNLOAD_FAILED", "APK 下载失败：" + (e.message ?: e.javaClass.simpleName))
            }
        }
        val apkFile = File(localPath)
        if (!apkFile.isFile) return error("APK_NOT_FOUND", "找不到 APK 文件：" + localPath)
        val packageName = runCatching {
            context.packageManager
                .getPackageArchiveInfo(localPath, PackageManager.PackageInfoFlags.of(0L))
                ?.packageName
                ?.takeIf(String::isNotBlank)
        }.getOrNull()

        if (!rootAvailable()) {
            val authority = fileProviderAuthority()
            if (authority == null) {
                return error(
                    "INSTALL_REQUIRES_ROOT",
                    "本版本未在 AndroidManifest 声明 FileProvider，无法把它交给系统安装器；" +
                        "请授予 Eta Root 权限后重试，或让用户手动安装该 APK。",
                )
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(FileProvider.getUriForFile(context, authority, apkFile), APK_MIME_TYPE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return runCatching { context.startActivity(intent) }.fold(
                onSuccess = {
                    ok("install_app")
                        .put("mode", "user_confirm")
                        .put("installed", false)
                        .put("download_url", url)
                        .apply { packageName?.let { put("package_name", it) } }
                        .toString()
                },
                onFailure = { error("INSTALL_UI_FAILED", "无法调起系统安装器：" + it.message) },
            )
        }

        val result = root.execute(
            "pm install -r " + shellQuote(localPath),
            timeoutMillis = INSTALL_TIMEOUT_MS,
            maxOutputBytes = 64 * 1024,
        )
        val output = (result.stdout + "\n" + result.stderr).trim()
        if (!result.ok || !output.contains("Success")) {
            val reason = output.lineSequence().firstOrNull { it.contains("Failure") }?.trim()
                ?: output.take(300)
            return error(
                installErrorCode(output),
                "Root 安装失败：" + reason.ifEmpty { "exit=" + result.exitCode },
            )
        }
        logger.info("Agent integration tool action=install_app outcome=installed")
        return ok("install_app")
            .put("mode", "root")
            .put("installed", true)
            .put("path", localPath)
            .apply { packageName?.let { put("package_name", it) } }
            .toString()
    }

    private fun downloadApk(url: String): File {
        val uri = URI(url)
        val fileName = uri.path?.substringAfterLast('/')?.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: "download.apk"
        val directory = File(context.cacheDir, INSTALL_CACHE_DIR).apply { mkdirs() }
        val target = File(directory, fileName)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val client = httpClient.newBuilder()
            .callTimeout(DOWNLOAD_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP " + response.code)
            val declaredLength = response.body.contentLength()
            if (declaredLength > MAX_APK_BYTES) throw IOException("APK 超过大小上限")
            response.body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(READ_CHUNK_BYTES)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_APK_BYTES) throw IOException("APK 超过大小上限")
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        return target
    }

    private fun installErrorCode(output: String): String = when {
        "INSTALL_FAILED_ALREADY_EXISTS" in output -> "INSTALL_FAILED_ALREADY_EXISTS"
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in output -> "INSTALL_FAILED_UPDATE_INCOMPATIBLE"
        "INSTALL_FAILED_VERSION_DOWNGRADE" in output -> "INSTALL_FAILED_VERSION_DOWNGRADE"
        "INSTALL_PARSE_FAILED" in output -> "INSTALL_PARSE_FAILED"
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" in output -> "INSTALL_FAILED_INSUFFICIENT_STORAGE"
        "INSTALL_FAILED_USER_RESTRICTED" in output -> "INSTALL_FAILED_USER_RESTRICTED"
        else -> "INSTALL_FAILED"
    }

    @Suppress("DEPRECATION")
    private fun fileProviderAuthority(): String? = listOf(
        context.packageName + ".fileprovider",
        context.packageName + ".provider",
    ).firstOrNull { authority ->
        runCatching { context.packageManager.resolveContentProvider(authority, 0) != null }
            .getOrDefault(false)
    }

    private fun uninstallApp(args: JSONObject): String {
        val packageName = args.optString("package_name").trim()
        if (packageName.isEmpty()) return error("INVALID_ARGUMENT", "缺少 package_name")
        if (!PACKAGE_NAME.matches(packageName)) return error("INVALID_PACKAGE", "包名格式无效：" + packageName)
        if (packageName == context.packageName) return error("INVALID_ARGUMENT", "不能卸载 Eta 自身")
        if (!rootAvailable()) {
            return error("ROOT_REQUIRED", "卸载应用需要 Root 权限（pm uninstall --user 0）；请授予 Eta Root 后重试。")
        }
        val result = root.execute(
            "pm uninstall --user 0 " + shellQuote(packageName),
            timeoutMillis = INSTALL_TIMEOUT_MS,
            maxOutputBytes = 64 * 1024,
        )
        val output = (result.stdout + "\n" + result.stderr).trim()
        if (!result.ok || !output.contains("Success")) {
            val reason = output.lineSequence().firstOrNull { it.contains("Failure") }?.trim()
                ?: output.take(300)
            return error(
                "UNINSTALL_FAILED",
                "卸载失败：" + reason.ifEmpty { "exit=" + result.exitCode },
            )
        }
        logger.info("Agent integration tool action=uninstall_app outcome=uninstalled")
        return ok("uninstall_app").put("package_name", packageName).put("uninstalled", true).toString()
    }

    private fun listInstalledApps(args: JSONObject): String {
        val filter = args.optString("filter").trim()
        val includeSystem = args.optBoolean("include_system", false)
        val limit = args.optInt("limit", DEFAULT_APP_LIMIT).coerceIn(1, MAX_APP_LIMIT)
        val packageManager = context.packageManager
        val applications = runCatching {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        }.getOrDefault(emptyList())
        val matched = applications.asSequence()
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = runCatching { info.loadLabel(packageManager).toString() }
                        .getOrDefault(info.packageName),
                    system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .filter { includeSystem || !it.system }
            .filter {
                filter.isEmpty() ||
                    it.packageName.contains(filter, ignoreCase = true) ||
                    it.label.contains(filter, ignoreCase = true)
            }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
            .toList()
        val items = JSONArray()
        matched.take(limit).forEach { app ->
            items.put(
                JSONObject()
                    .put("label", app.label)
                    .put("package_name", app.packageName)
                    .put("system", app.system),
            )
        }
        return ok("list_installed_apps")
            .put("items", items)
            .put("count", items.length())
            .put("matched", matched.size)
            .put("truncated", matched.size > items.length())
            .apply { if (filter.isNotEmpty()) put("filter", filter) }
            .toString()
    }

    // --------------------------------------------- Intent / Broadcast

    private fun executeIntent(args: JSONObject): String {
        val action = args.optString("action").trim()
        if (action.isEmpty()) return error("INVALID_ARGUMENT", "缺少 action")
        val uri = args.optString("uri").trim()
        val uriValue = uri.takeIf(String::isNotEmpty)?.let(Uri::parse)
        val intent = if (uriValue != null) Intent(action, uriValue) else Intent(action)
        args.optString("package").trim().takeIf(String::isNotEmpty)?.let(intent::setPackage)
        val component = args.optString("component").trim()
        if (component.isNotEmpty()) {
            val parts = parseComponent(component) ?: return error("INVALID_COMPONENT", "组件格式应为 pkg/Class")
            intent.setClassName(parts.first, parts.second)
        }
        applyExtras(intent, args.optJSONObject("extras"))
        val ignoredFlags = JSONArray()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = args.optJSONArray("flags")
        val appliedFlags = JSONArray()
        if (flags != null) {
            for (index in 0 until flags.length()) {
                val name = flags.optString(index).trim()
                val flag = INTENT_FLAGS[name.uppercase(Locale.ROOT)]
                if (flag == null) ignoredFlags.put(name) else {
                    intent.addFlags(flag)
                    appliedFlags.put(name)
                }
            }
        }
        return runCatching { context.startActivity(intent) }.fold(
            onSuccess = {
                logger.info("Agent integration tool action=execute_intent outcome=dispatched")
                ok("execute_intent")
                    .put("action", action)
                    .put("flags", appliedFlags)
                    .apply {
                        if (uri.isNotEmpty()) put("uri", uri)
                        if (component.isNotEmpty()) put("component", component)
                        if (ignoredFlags.length() > 0) put("ignored_flags", ignoredFlags)
                        if (action in SENSITIVE_INTENT_ACTIONS) {
                            put("warning", "这是敏感 action（" + action + "），已按请求直接执行，请确认设备上的实际结果。")
                        }
                    }
                    .toString()
            },
            onFailure = { error("START_ACTIVITY_FAILED", "无法启动该 Intent：" + (it.message ?: "无匹配应用")) },
        )
    }

    private fun sendBroadcast(args: JSONObject): String {
        val action = args.optString("action").trim()
        if (action.isEmpty()) return error("INVALID_ARGUMENT", "缺少 action")
        val intent = Intent(action)
        args.optString("package").trim().takeIf(String::isNotEmpty)?.let(intent::setPackage)
        val component = args.optString("explicit_component").trim()
        if (component.isNotEmpty()) {
            val parts = parseComponent(component)
                ?: return error("INVALID_COMPONENT", "组件格式应为 pkg/Class")
            intent.setClassName(parts.first, parts.second)
        }
        applyExtras(intent, args.optJSONObject("extras"))
        context.sendBroadcast(intent)
        logger.info("Agent integration tool action=send_broadcast outcome=sent")
        return ok("send_broadcast")
            .put("action", action)
            .put("note", "广播已发出；Android 不返回接收者数量")
            .toString()
    }

    private fun parseComponent(component: String): Pair<String, String>? {
        val slash = component.indexOf('/')
        if (slash <= 0 || slash == component.length - 1) return null
        val packageName = component.substring(0, slash)
        val rawClass = component.substring(slash + 1)
        val className = if (rawClass.startsWith(".")) packageName + rawClass else rawClass
        return packageName to className
    }

    private fun applyExtras(intent: Intent, extras: JSONObject?) {
        extras ?: return
        extras.keys().forEach { key ->
            val value = extras.opt(key) ?: return@forEach
            if (value === JSONObject.NULL) return@forEach
            when (value) {
                is String -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Double -> intent.putExtra(key, value)
                else -> intent.putExtra(key, value.toString())
            }
        }
    }

    // -------------------------------------------------- Toast / 通知

    private fun toast(args: JSONObject): String {
        val text = args.optString("text")
        if (text.isBlank()) return error("INVALID_ARGUMENT", "缺少 text")
        val duration = if (args.optString("duration").trim().lowercase(Locale.ROOT) == "long") {
            Toast.LENGTH_LONG
        } else {
            Toast.LENGTH_SHORT
        }
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(appContext, text, duration).show() }
        }
        return ok("toast").put("text", text).put("duration", if (duration == Toast.LENGTH_LONG) "long" else "short")
            .toString()
    }

    private fun sendNotification(args: JSONObject): String {
        val title = args.optString("title")
        val text = args.optString("text")
        if (title.isBlank() && text.isBlank()) return error("INVALID_ARGUMENT", "缺少 title 与 text")
        val channelId = args.optString("channel_id").trim().ifEmpty { DEFAULT_NOTIFICATION_CHANNEL }
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return error("NOTIFICATION_UNAVAILABLE", "系统通知服务不可用")
        if (!notificationsAllowed(manager)) {
            return error(
                "NOTIFICATION_PERMISSION_REQUIRED",
                "没有通知权限或通知被关闭，请让用户在系统设置中允许 Eta 发送通知后重试。",
            )
        }
        return runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            val id = args.optInt("id", 0).takeIf { it != 0 }
                ?: (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            manager.notify(id, notification)
            logger.info("Agent integration tool action=send_notification outcome=posted")
            ok("send_notification")
                .put("id", id)
                .put("channel_id", channelId)
                .toString()
        }.getOrElse { error("NOTIFICATION_FAILED", "发送通知失败：" + (it.message ?: it.javaClass.simpleName)) }
    }

    private fun notificationsAllowed(manager: NotificationManager): Boolean {
        val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return granted && manager.areNotificationsEnabled()
    }

    // ------------------------------------------------------ 表达式求值

    private fun calculate(args: JSONObject): String {
        val expression = args.optString("expression").trim()
        if (expression.isEmpty()) return error("INVALID_ARGUMENT", "缺少 expression")
        if (expression.length > MAX_EXPRESSION_LENGTH) {
            return error("EXPRESSION_TOO_LONG", "表达式过长（上限 " + MAX_EXPRESSION_LENGTH + " 字符）")
        }
        val value = try {
            ArithmeticParser(expression).parse()
        } catch (e: ExpressionException) {
            return error(e.code, e.message ?: "表达式无效")
        }
        return ok("calculate")
            .put("expression", expression)
            .put("result", value)
            .put("result_text", formatNumber(value))
            .toString()
    }

    private fun formatNumber(value: Double): String {
        if (value == 0.0) return "0"
        val magnitude = kotlin.math.abs(value)
        if (magnitude >= SCIENTIFIC_UPPER_BOUND || magnitude < SCIENTIFIC_LOWER_BOUND) {
            return value.toString()
        }
        return runCatching {
            val rounded = BigDecimal.valueOf(value)
                .setScale(DISPLAY_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros()
            if (rounded.scale() <= 0) rounded.toBigIntegerExact().toString() else rounded.toPlainString()
        }.getOrDefault(value.toString())
    }

    /** 手写四则/括号/幂/取模求值器：不使用任何脚本引擎，也不做 eval。 */
    private class ArithmeticParser(private val source: String) {
        private var index = 0
        private var depth = 0

        fun parse(): Double {
            val value = parseExpression()
            skipSpaces()
            if (index < source.length) {
                throw ExpressionException("INVALID_EXPRESSION", "表达式在位置 " + index + " 处包含无法识别的字符")
            }
            if (!value.isFinite()) throw ExpressionException("INVALID_RESULT", "计算结果不是有限数值")
            return value
        }

        private fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipSpaces()
                value = when (peek()) {
                    '+' -> {
                        index++
                        value + parseTerm()
                    }
                    '-' -> {
                        index++
                        value - parseTerm()
                    }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parseUnary()
            while (true) {
                skipSpaces()
                value = when (peek()) {
                    '*' -> {
                        index++
                        value * parseUnary()
                    }
                    '/' -> {
                        index++
                        value / requireNonZero(parseUnary(), "除数")
                    }
                    '%' -> {
                        index++
                        value % requireNonZero(parseUnary(), "取模的除数")
                    }
                    else -> return value
                }
            }
        }

        private fun parseUnary(): Double {
            skipSpaces()
            return when (peek()) {
                '-' -> {
                    index++
                    -parseUnary()
                }
                '+' -> {
                    index++
                    parseUnary()
                }
                else -> parsePower()
            }
        }

        private fun parsePower(): Double {
            val base = parsePrimary()
            skipSpaces()
            if (peek() != '^') return base
            index++
            val exponent = parseUnary()
            if (kotlin.math.abs(exponent) > MAX_EXPONENT) {
                throw ExpressionException("EXPONENT_TOO_LARGE", "幂指数过大")
            }
            if (base == 0.0 && exponent < 0) {
                throw ExpressionException("DIVISION_BY_ZERO", "0 的负次幂无意义")
            }
            val value = Math.pow(base, exponent)
            if (!value.isFinite()) throw ExpressionException("INVALID_RESULT", "幂运算结果超出范围")
            return value
        }

        private fun parsePrimary(): Double {
            skipSpaces()
            val current = peek() ?: throw ExpressionException("INVALID_EXPRESSION", "表达式意外结束")
            if (current == '(') {
                index++
                depth++
                if (depth > MAX_NESTING_DEPTH) throw ExpressionException("EXPRESSION_TOO_DEEP", "括号嵌套过深")
                val value = parseExpression()
                skipSpaces()
                if (peek() != ')') throw ExpressionException("INVALID_EXPRESSION", "括号不匹配")
                index++
                depth--
                return value
            }
            if (current.isDigit() || current == '.') return parseNumber()
            throw ExpressionException("INVALID_EXPRESSION", "表达式在位置 " + index + " 处无法解析")
        }

        private fun parseNumber(): Double {
            val start = index
            var dots = 0
            while (index < source.length && (source[index].isDigit() || source[index] == '.')) {
                if (source[index] == '.') dots++
                index++
            }
            val literal = source.substring(start, index)
            if (dots > 1) throw ExpressionException("INVALID_NUMBER", "数字格式无效：" + literal)
            return literal.toDoubleOrNull() ?: throw ExpressionException("INVALID_NUMBER", "数字格式无效：" + literal)
        }

        private fun requireNonZero(value: Double, name: String): Double {
            if (value == 0.0) throw ExpressionException("DIVISION_BY_ZERO", name + "不能为 0")
            return value
        }

        private fun peek(): Char? = source.getOrNull(index)

        private fun skipSpaces() {
            while (index < source.length && source[index].isWhitespace()) index++
        }
    }

    private class ExpressionException(val code: String, message: String) : IllegalArgumentException(message)

    // ---------------------------------------------------------- 版本检查

    private fun updateCheck(args: JSONObject): String {
        val currentVersion = args.optString("current_version").trim().ifEmpty { appVersionName() }
        if (currentVersion.isEmpty()) {
            return error("VERSION_UNKNOWN", "无法读取当前版本，请用 current_version 指定要比较的版本号。")
        }
        val request = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", GITHUB_ACCEPT)
            .header("User-Agent", USER_AGENT)
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .get()
            .build()
        val client = httpClient.newBuilder()
            .callTimeout(UPDATE_CHECK_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            return error("TIMEOUT", "查询 GitHub 超时")
        } catch (e: IOException) {
            return error("NETWORK_ERROR", "查询 GitHub 失败：" + (e.message ?: e.javaClass.simpleName))
        }
        response.use { result ->
            when {
                result.code == 404 -> return error("NO_RELEASES", "该仓库还没有发布任何 Release，无法比较版本")
                result.code == 403 || result.code == 429 ->
                    return error("RATE_LIMITED", "GitHub API 限流，请稍后重试")
                !result.isSuccessful -> return error("HTTP_ERROR", "GitHub 返回 HTTP " + result.code)
            }
            val payload = readBounded(result.body.source(), MAX_RELEASE_BYTES).text
            val json = runCatching { JSONObject(payload) }.getOrNull()
                ?: return error("INVALID_RESPONSE", "无法解析 GitHub 返回内容")
            val latestVersion = json.optString("tag_name").trim().ifEmpty { json.optString("name").trim() }
                .trimStart('v', 'V')
            if (latestVersion.isEmpty()) return error("INVALID_RESPONSE", "GitHub 返回的 Release 缺少版本号")
            val apkAsset = runCatching {
                val assets = json.optJSONArray("assets") ?: JSONArray()
                (0 until assets.length())
                    .mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            }.getOrNull()
            val downloadUrl = apkAsset?.optString("browser_download_url")?.takeIf(String::isNotBlank)
                ?: json.optString("html_url")
            val hasUpdate = compareVersions(latestVersion, currentVersion) > 0
            return ok("update_check")
                .put("current_version", currentVersion)
                .put("latest_version", latestVersion)
                .put("has_update", hasUpdate)
                .put("release_name", json.optString("name"))
                .put("release_url", json.optString("html_url"))
                .put("download_url", downloadUrl)
                .put("published_at", json.optString("published_at"))
                .put("prerelease", json.optBoolean("prerelease", false))
                .put("notes", json.optString("body").take(MAX_RELEASE_NOTES_CHARS))
                .toString()
        }
    }

    private fun appVersionName(): String = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
            .versionName
            ?.takeIf(String::isNotBlank)
            .orEmpty()
    }.getOrDefault("")

    private fun compareVersions(left: String, right: String): Int {
        val a = versionSegments(left)
        val b = versionSegments(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val difference = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }

    private fun versionSegments(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '+', '_')
            .mapNotNull { part -> part.takeWhile(Char::isDigit).takeIf(String::isNotEmpty)?.toIntOrNull() }

    // ------------------------------------------------------------ 公共

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun ok(tool: String): JSONObject = JSONObject().put("ok", true).put("tool", tool)

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private data class BoundedText(val text: String, val truncated: Boolean, val bytes: Long)

    private data class InstalledApp(val packageName: String, val label: String, val system: Boolean)

    companion object {
        /** 与 AgentIntegrationToolCatalog.TOOL_NAMES 保持同源。 */
        val TOOL_NAMES: Set<String> = AgentIntegrationToolCatalog.TOOL_NAMES

        private val SUPPORTED_HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
        private val MANAGED_HEADERS = listOf(
            "Host",
            "Content-Length",
            "Connection",
            "Transfer-Encoding",
            "Accept-Encoding",
        )
        private val SENSITIVE_INTENT_ACTIONS = setOf(
            "android.intent.action.CALL",
            "android.intent.action.DELETE",
            "android.intent.action.FACTORY_RESET",
            "android.intent.action.MASTER_CLEAR",
            "android.intent.action.INSTALL_PACKAGE",
            "android.intent.action.UNINSTALL_PACKAGE",
        )
        private val INTENT_FLAGS = mapOf(
            "FLAG_ACTIVITY_NEW_TASK" to Intent.FLAG_ACTIVITY_NEW_TASK,
            "FLAG_ACTIVITY_CLEAR_TOP" to Intent.FLAG_ACTIVITY_CLEAR_TOP,
            "FLAG_ACTIVITY_SINGLE_TOP" to Intent.FLAG_ACTIVITY_SINGLE_TOP,
            "FLAG_ACTIVITY_CLEAR_TASK" to Intent.FLAG_ACTIVITY_CLEAR_TASK,
            "FLAG_ACTIVITY_NO_HISTORY" to Intent.FLAG_ACTIVITY_NO_HISTORY,
            "FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS" to Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            "FLAG_ACTIVITY_NEW_DOCUMENT" to Intent.FLAG_ACTIVITY_NEW_DOCUMENT,
            "FLAG_GRANT_READ_URI_PERMISSION" to Intent.FLAG_GRANT_READ_URI_PERMISSION,
            "FLAG_GRANT_WRITE_URI_PERMISSION" to Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

        private const val USER_AGENT = "Eta-Agent-Integration"
        private const val GITHUB_ACCEPT = "application/vnd.github+json"
        private const val GITHUB_API_VERSION = "2022-11-28"
        private const val REPOSITORY_OWNER = "Mangi-11"
        private const val REPOSITORY_NAME = "Eta"
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/" + REPOSITORY_OWNER + "/" + REPOSITORY_NAME + "/releases/latest"

        private const val DEFAULT_HTTP_TIMEOUT_SECONDS = 30
        private const val MAX_HTTP_TIMEOUT_SECONDS = 120
        private const val DEFAULT_HTTP_MAX_BYTES = 65536
        private const val MIN_HTTP_MAX_BYTES = 1024
        private const val MAX_HTTP_MAX_BYTES = 262144
        private const val MAX_RESPONSE_HEADER_ENTRIES = 40
        private const val DEFAULT_CONTENT_TYPE = "application/json; charset=utf-8"
        private const val READ_CHUNK_BYTES = 8 * 1024

        private const val INSTALL_TIMEOUT_MS = 30_000L
        private const val DOWNLOAD_TIMEOUT_SECONDS = 180
        private const val MAX_APK_BYTES = 512L * 1024 * 1024
        private const val INSTALL_CACHE_DIR = "agent-install"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        private const val DEFAULT_APP_LIMIT = 50
        private const val MAX_APP_LIMIT = 200

        private const val DEFAULT_NOTIFICATION_CHANNEL = "eta_agent_notes"
        private const val NOTIFICATION_CHANNEL_NAME = "Eta 助手提示"

        private const val MAX_EXPRESSION_LENGTH = 512
        private const val MAX_NESTING_DEPTH = 64
        private const val MAX_EXPONENT = 1024.0
        private const val DISPLAY_SCALE = 12
        private const val SCIENTIFIC_UPPER_BOUND = 1e15
        private const val SCIENTIFIC_LOWER_BOUND = 1e-9

        private const val UPDATE_CHECK_TIMEOUT_SECONDS = 20
        private const val MAX_RELEASE_BYTES = 256 * 1024
        private const val MAX_RELEASE_NOTES_CHARS = 2000

        private val EMPTY_BODY = "".toRequestBody(DEFAULT_CONTENT_TYPE.toMediaType())
    }
}
