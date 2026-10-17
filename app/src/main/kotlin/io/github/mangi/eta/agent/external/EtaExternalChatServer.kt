package io.github.mangi.eta.agent.external

import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * Eta 对外聊天通道 —— 不引入任何 HTTP 库，用 [ServerSocket] 手写最小 HTTP/1.1。
 *
 * 让外部程序（脚本 / 其他应用 / A2A 客户端）通过本机 HTTP 给 Eta 发消息并取回回复，
 * 同时内置一个极简网页聊天界面（GET /）。
 *
 * 端点：
 * - GET  /health                    健康检查（无需鉴权）
 * - GET  /                          内置聊天网页（无需鉴权）
 * - GET  /.well-known/agent.json    A2A Agent Card（无需鉴权）
 * - POST /chat                      跑一轮 agent，返回文本（需 Bearer 鉴权）
 * - POST /a2a                       JSON-RPC 2.0：message/send、tasks/get（需 Bearer 鉴权）
 *
 * 安全约束：仅监听 [HOST]（127.0.0.1），/chat 与 /a2a 必须携带正确 Bearer Token。
 */
internal class EtaExternalChatServer(
    private val tokenProvider: () -> String,
    private val chatRunner: ChatRunner,
    private val serverVersion: String = "unknown",
    private val maxRequestBytes: Int = 1_048_576,
    private val requestTimeoutMs: Int = 300_000,
) : AutoCloseable {

    /** 跑一轮 agent；实现方必须同步返回最终文本（可由后台线程阻塞等待）。 */
    fun interface ChatRunner {
        fun run(prompt: String, sessionId: String): ChatOutcome
    }

    data class ChatOutcome(
        val ok: Boolean,
        val text: String = "",
        val sessionId: String = "",
        val errorCode: String = "",
        val errorMessage: String = "",
    )

    private val running = AtomicBoolean(false)
    private val serverRef = AtomicReference<ServerSocket?>(null)
    private var executor: ExecutorService? = null

    @Volatile
    private var boundPort: Int = 0

    /** 绑定并开始接受连接；返回是否进入监听态。 */
    fun start(port: Int): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return try {
            val server = ServerSocket(port, 50, InetAddress.getByName(HOST))
            serverRef.set(server)
            boundPort = server.localPort
            val pool = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "EtaExternalChat-Http").apply { isDaemon = true }
            }
            executor = pool
            AndroidAgentLogger.info("External chat server listening on $HOST:$boundPort")
            pool.submit { acceptLoop(server, pool) }
            true
        } catch (e: IOException) {
            running.set(false)
            serverRef.set(null)
            AndroidAgentLogger.warn("External chat server bind failed: ${e.message}")
            false
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverRef.getAndSet(null)?.close() }
        executor?.shutdownNow()
        executor = null
        AndroidAgentLogger.info("External chat server stopped")
    }

    override fun close() = stop()

    fun isRunning(): Boolean = running.get()

    fun port(): Int = boundPort

    private fun acceptLoop(server: ServerSocket, pool: ExecutorService) {
        while (running.get() && !server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (running.get()) AndroidAgentLogger.warn("External chat accept failed: ${e.message}")
                return
            }
            pool.submit { handleConnection(client) }
        }
    }

    private fun handleConnection(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = requestTimeoutMs
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ", limit = 3)
                if (parts.size < 2) {
                    writeJson(socket, 400, failureJson("BAD_REQUEST", "Malformed request line"))
                    return
                }
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                val headers = readHeaders(reader)
                val contentLength = headers["content-length"]?.trim()?.toIntOrNull() ?: 0
                if (contentLength < 0 || contentLength > maxRequestBytes) {
                    writeJson(
                        socket,
                        413,
                        failureJson("PAYLOAD_TOO_LARGE", "请求体超过 $maxRequestBytes 字节上限"),
                    )
                    return
                }
                val body = if (contentLength > 0) readBody(reader, contentLength) else ""
                route(socket, method, path, headers, body)
            } catch (e: Exception) {
                AndroidAgentLogger.warn("External chat connection error: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { writeJson(socket, 500, failureJson("INTERNAL_ERROR", e.message ?: "内部错误")) }
            }
        }
    }

    private fun route(
        socket: Socket,
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String,
    ) {
        when (path) {
            "/health" ->
                if (method == "GET") writeJson(socket, 200, healthJson())
                else writeJson(socket, 405, failureJson("METHOD_NOT_ALLOWED", "仅支持 GET"))

            "/" ->
                if (method == "GET") writeHtml(socket, 200, CHAT_PAGE_HTML)
                else writeJson(socket, 405, failureJson("METHOD_NOT_ALLOWED", "仅支持 GET"))

            "/.well-known/agent.json" ->
                if (method == "GET") writeJson(socket, 200, agentCardJson())
                else writeJson(socket, 405, failureJson("METHOD_NOT_ALLOWED", "仅支持 GET"))

            "/chat" -> {
                if (method != "POST") {
                    writeJson(socket, 405, failureJson("METHOD_NOT_ALLOWED", "仅支持 POST"))
                    return
                }
                if (!authorize(headers)) {
                    writeJson(socket, 401, failureJson("UNAUTHORIZED", "缺少或错误的 Bearer Token"))
                    return
                }
                handleChat(socket, body)
            }

            "/a2a" -> {
                if (method != "POST") {
                    writeJson(socket, 405, failureJson("METHOD_NOT_ALLOWED", "仅支持 POST"))
                    return
                }
                if (!authorize(headers)) {
                    writeJson(socket, 401, failureJson("UNAUTHORIZED", "缺少或错误的 Bearer Token"))
                    return
                }
                handleA2a(socket, body)
            }

            else -> writeJson(socket, 404, failureJson("NOT_FOUND", "未找到路径 $path"))
        }
    }

    private fun handleChat(socket: Socket, body: String) {
        val params = try {
            JSONObject(body)
        } catch (e: Exception) {
            writeJson(socket, 400, failureJson("INVALID_REQUEST", "请求体必须是 JSON 对象"))
            return
        }
        val prompt = params.optString("prompt").trim()
        if (prompt.isEmpty()) {
            writeJson(socket, 400, failureJson("INVALID_REQUEST", "prompt 不能为空"))
            return
        }
        val sessionId = params.optString("sessionId").trim()
        val outcome = runChat(prompt, sessionId)
        if (outcome.ok) {
            val response = JSONObject().put("ok", true).put("text", outcome.text)
            if (outcome.sessionId.isNotBlank()) response.put("sessionId", outcome.sessionId)
            writeJson(socket, 200, response.toString())
        } else {
            val response = JSONObject()
                .put("ok", false)
                .put("code", outcome.errorCode.ifBlank { "RUN_FAILED" })
                .put("message", outcome.errorMessage.ifBlank { "运行失败" })
            writeJson(socket, statusFor(outcome.errorCode), response.toString())
        }
    }

    private fun handleA2a(socket: Socket, body: String) {
        val request = try {
            JSONObject(body)
        } catch (e: Exception) {
            writeJson(socket, 200, a2aError(null, -32700, "Parse error"))
            return
        }
        val id = request.opt("id")
        val method = request.optString("method", "")
        if (method.isBlank()) {
            writeJson(socket, 200, a2aError(id, -32600, "Invalid Request"))
            return
        }
        when (method) {
            "message/send" -> handleA2aMessageSend(socket, id, request.optJSONObject("params"))
            "tasks/get" -> writeJson(socket, 200, a2aError(id, -32001, "Task not found"))
            "message/stream", "tasks/resubscribe" ->
                writeJson(socket, 200, a2aError(id, -32601, "Method not supported: streaming disabled"))
            else -> writeJson(socket, 200, a2aError(id, -32601, "Method not found: $method"))
        }
    }

    private fun handleA2aMessageSend(socket: Socket, id: Any?, params: JSONObject?) {
        val message = params?.optJSONObject("message")
        if (message == null) {
            writeJson(socket, 200, a2aError(id, -32602, "缺少 message"))
            return
        }
        val text = extractText(message)
        if (text.isBlank()) {
            writeJson(socket, 200, a2aError(id, -32602, "message 不含文本 part"))
            return
        }
        val contextId = message.optString("contextId").ifBlank { UUID.randomUUID().toString() }
        val outcome = runChat(text, contextId)
        if (!outcome.ok) {
            writeJson(socket, 200, a2aError(id, -32603, outcome.errorMessage.ifBlank { "运行失败" }))
            return
        }
        val result = JSONObject()
            .put("kind", "message")
            .put("role", "agent")
            .put(
                "parts",
                JSONArray().put(JSONObject().put("kind", "text").put("text", outcome.text)),
            )
            .put("messageId", UUID.randomUUID().toString())
            .put("contextId", contextId)
        val response = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("result", result)
        writeJson(socket, 200, response.toString())
    }

    private fun runChat(prompt: String, sessionId: String): ChatOutcome = try {
        chatRunner.run(prompt, sessionId)
    } catch (t: Throwable) {
        AndroidAgentLogger.warn("External chat runner failed: ${t.javaClass.simpleName}: ${t.message}")
        ChatOutcome(ok = false, errorCode = "RUN_FAILED", errorMessage = t.message ?: t.javaClass.simpleName)
    }

    private fun extractText(message: JSONObject): String {
        val parts = message.optJSONArray("parts") ?: return ""
        val builder = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optString("kind", "text") != "text") continue
            val value = part.optString("text")
            if (value.isEmpty()) continue
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(value)
        }
        return builder.toString().trim()
    }

    private fun authorize(headers: Map<String, String>): Boolean {
        val expected = tokenProvider().trim()
        if (expected.isEmpty()) return false
        val header = headers["authorization"] ?: return false
        val provided = header.removePrefix("Bearer ").removePrefix("bearer ").trim()
        return provided == expected
    }

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx < 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        return headers
    }

    private fun readBody(reader: BufferedReader, length: Int): String {
        val buffer = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(buffer, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(buffer, 0, read)
    }

    private fun healthJson(): String = JSONObject()
        .put("ok", true)
        .put("version", serverVersion)
        .toString()

    private fun failureJson(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("message", message)
        .toString()

    private fun agentCardJson(): String = JSONObject()
        .put("name", "Eta")
        .put("description", "Eta Android AI assistant external chat channel (127.0.0.1 only)")
        .put("url", "http://$HOST:$boundPort")
        .put("version", serverVersion)
        .put("capabilities", JSONObject().put("streaming", false))
        .put("defaultInputModes", JSONArray().put("text/plain"))
        .put("defaultOutputModes", JSONArray().put("text/plain"))
        .put("skills", JSONArray())
        .toString()

    private fun a2aError(id: Any?, code: Int, message: String): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))
        .toString()

    private fun statusFor(code: String): Int = when (code) {
        "INVALID_REQUEST" -> 400
        "UNAUTHORIZED" -> 401
        "BUSY" -> 409
        else -> 500
    }

    private fun writeJson(socket: Socket, status: Int, json: String) =
        writeResponse(socket, status, "application/json; charset=utf-8", json)

    private fun writeHtml(socket: Socket, status: Int, html: String) =
        writeResponse(socket, status, "text/html; charset=utf-8", html)

    private fun writeResponse(socket: Socket, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.UTF_8))
            write(bytes)
            flush()
        }
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        else -> "Status $status"
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8094

        /** 内置极简聊天网页：纯静态字符串，token 由用户在页面上单独填写并存入 localStorage。 */
        private val CHAT_PAGE_HTML = """<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Eta Chat</title>
<style>
:root { color-scheme: light dark; }
* { box-sizing: border-box; }
body { margin: 0; font-family: -apple-system, "Segoe UI", Roboto, sans-serif; background: #f4f5f7; color: #111; display: flex; flex-direction: column; height: 100vh; }
header { padding: 12px 16px; background: #fff; border-bottom: 1px solid #e2e4e8; display: flex; gap: 8px; align-items: center; }
header h1 { font-size: 16px; margin: 0; flex: 1; }
input, textarea, button { font: inherit; }
#token { flex: 0 0 180px; padding: 6px 8px; border: 1px solid #ccd; border-radius: 6px; }
#log { flex: 1; overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 10px; }
.msg { max-width: 80%; padding: 10px 12px; border-radius: 12px; white-space: pre-wrap; word-break: break-word; line-height: 1.4; }
.me { align-self: flex-end; background: #2f6fed; color: #fff; }
.bot { align-self: flex-start; background: #fff; border: 1px solid #e2e4e8; }
.err { align-self: flex-start; background: #fdecec; border: 1px solid #f5c2c2; color: #a12b2b; }
footer { padding: 10px 16px; background: #fff; border-top: 1px solid #e2e4e8; display: flex; gap: 8px; }
#prompt { flex: 1; resize: none; height: 44px; padding: 10px; border: 1px solid #ccd; border-radius: 8px; }
#send { padding: 0 18px; border: 0; border-radius: 8px; background: #2f6fed; color: #fff; cursor: pointer; }
#send:disabled { opacity: .5; cursor: default; }
@media (prefers-color-scheme: dark) { body { background:#16181d; color:#eee } header, footer { background:#1e2128; border-color:#2b2f38 } .bot { background:#1e2128; border-color:#2b2f38 } #token, #prompt { background:#16181d; color:#eee; border-color:#3a3f4a } }
</style>
</head>
<body>
<header>
  <h1>Eta Chat</h1>
  <input id="token" type="password" placeholder="Bearer Token" autocomplete="off">
</header>
<div id="log"></div>
<footer>
  <textarea id="prompt" placeholder="输入消息，Ctrl+Enter 发送"></textarea>
  <button id="send">发送</button>
</footer>
<script>
var logEl = document.getElementById('log');
var tokenEl = document.getElementById('token');
var promptEl = document.getElementById('prompt');
var sendEl = document.getElementById('send');
tokenEl.value = localStorage.getItem('eta_token') || '';
tokenEl.addEventListener('change', function () { localStorage.setItem('eta_token', tokenEl.value); });
function add(text, cls) {
  var div = document.createElement('div');
  div.className = 'msg ' + cls;
  div.textContent = text;
  logEl.appendChild(div);
  logEl.scrollTop = logEl.scrollHeight;
  return div;
}
function send() {
  var prompt = promptEl.value.trim();
  if (!prompt) return;
  add(prompt, 'me');
  promptEl.value = '';
  sendEl.disabled = true;
  var pending = add('思考中…', 'bot');
  fetch('/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + tokenEl.value },
    body: JSON.stringify({ prompt: prompt })
  }).then(function (res) {
    return res.json().catch(function () {
      return { ok: false, code: 'HTTP_' + res.status, message: '响应不是 JSON' };
    });
  }).then(function (data) {
    if (data && data.ok) {
      pending.textContent = data.text || '(空回复)';
      pending.className = 'msg bot';
    } else {
      pending.textContent = '错误 ' + ((data && data.code) || '') + ': ' + ((data && data.message) || '未知错误');
      pending.className = 'msg err';
    }
  }).catch(function (err) {
    pending.textContent = '请求失败: ' + err;
    pending.className = 'msg err';
  }).finally(function () {
    sendEl.disabled = false;
    promptEl.focus();
  });
}
sendEl.addEventListener('click', send);
promptEl.addEventListener('keydown', function (e) {
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); send(); }
});
</script>
</body>
</html>"""
    }
}
