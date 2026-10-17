package io.github.mangi.eta.agent.device

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用 UI 文档：把每次观察到的界面元素沉淀成「这个应用有哪些可操作元素」的档案。
 *
 * 对齐 AutoDroid-V2 的 app document 思路：让模型**一次**写出多步 flow，再由 flow_run 回放，
 * 而不是每一步都问一次模型（论文实测：延迟 −93%、token −97.8%）。
 *
 * 只存可操作/可读元素的轻量描述（id/class/文字/desc/是否可点可输），不存坐标（坐标随滚动变化）。
 * 写入走后台线程 + AtomicFile，不阻塞观察路径。
 */
internal object AppUiDocument {
    private const val DIRECTORY = "app_docs"
    private const val MAX_ELEMENTS = 400
    private const val MAX_TEXT_CHARS = 80

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-app-doc").apply { isDaemon = true }
    }
    private val lock = Any()

    private fun fileFor(context: Context, packageName: String): File =
        File(File(context.filesDir, DIRECTORY), sanitize(packageName) + ".json")

    private fun sanitize(packageName: String): String =
        packageName.map { ch -> if (ch.isLetterOrDigit() || ch == '.' || ch == '_') ch else '_' }.joinToString("")

    /** 异步合并一次观察结果。packageName 为空或节点为空时不做任何事。 */
    fun record(context: Context?, packageName: String, nodes: List<RootShellDeviceController.UiNode>) {
        if (context == null || packageName.isBlank() || nodes.isEmpty()) return
        val snapshot = nodes.take(MAX_ELEMENTS).map { node ->
            UiElement(
                key = elementKey(node),
                className = node.className,
                text = node.text.take(MAX_TEXT_CHARS),
                desc = node.desc.take(MAX_TEXT_CHARS),
                clickable = node.clickable,
                editable = node.editable,
                scrollable = node.scrollable,
            )
        }
        val appContext = context.applicationContext
        runCatching { executor.execute { merge(appContext, packageName, snapshot) } }
    }

    /** 读取某个应用的 UI 文档（同步，供工具调用）。 */
    fun snapshot(context: Context?, packageName: String, limit: Int): JSONObject {
        val bounded = limit.coerceIn(5, 200)
        if (context == null || packageName.isBlank()) {
            return JSONObject().put("ok", false).put("code", "INVALID_ARGUMENT").put("message", "缺少包名或上下文")
        }
        val doc = synchronized(lock) { read(context, packageName) }
        val elements = doc.optJSONArray("elements") ?: JSONArray()
        val actionable = JSONArray()
        val readable = JSONArray()
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            val isActionable = element.optBoolean("clickable") || element.optBoolean("editable") ||
                element.optBoolean("scrollable")
            if (isActionable) {
                if (actionable.length() < bounded) actionable.put(element)
            } else if (readable.length() < bounded / 2) {
                readable.put(element)
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "app_ui_doc")
            .put("package", packageName)
            .put("element_count", elements.length())
            .put("updated_at", doc.optLong("updated_at"))
            .put("actionable", actionable)
            .put("readable", readable)
            .put(
                "note",
                "这是该应用沉淀下来的元素档案。用它一次写出多步 flow（tap_text/input_text/tap_element…），" +
                    "再用 flow_run 回放——不要每一步都重新观察屏幕。",
            )
    }

    private data class UiElement(
        val key: String,
        val className: String,
        val text: String,
        val desc: String,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
    )

    private fun elementKey(node: RootShellDeviceController.UiNode): String = when {
        node.viewId.isNotBlank() -> node.viewId
        node.text.isNotBlank() -> "${node.className}|${node.text.take(MAX_TEXT_CHARS)}"
        node.desc.isNotBlank() -> "${node.className}|desc:${node.desc.take(MAX_TEXT_CHARS)}"
        else -> node.className
    }

    private fun merge(context: Context, packageName: String, incoming: List<UiElement>) {
        runCatching {
            synchronized(lock) {
                val doc = read(context, packageName)
                val now = System.currentTimeMillis()
                val existing = LinkedHashMap<String, JSONObject>()
                val old = doc.optJSONArray("elements") ?: JSONArray()
                for (index in 0 until old.length()) {
                    val item = old.optJSONObject(index) ?: continue
                    existing[item.optString("key")] = item
                }
                for (element in incoming) {
                    val previous = existing[element.key]
                    val seen = (previous?.optLong("seen") ?: 0L) + 1L
                    existing[element.key] = JSONObject()
                        .put("key", element.key)
                        .put("class", element.className)
                        .put("text", element.text.ifBlank { previous?.optString("text").orEmpty() })
                        .put("desc", element.desc.ifBlank { previous?.optString("desc").orEmpty() })
                        .put("clickable", element.clickable || previous?.optBoolean("clickable") == true)
                        .put("editable", element.editable || previous?.optBoolean("editable") == true)
                        .put("scrollable", element.scrollable || previous?.optBoolean("scrollable") == true)
                        .put("seen", seen)
                        .put("last_seen", now)
                }
                // 超出上限时淘汰最久未出现的元素
                val trimmed = existing.values
                    .sortedByDescending { it.optLong("last_seen") }
                    .take(MAX_ELEMENTS)
                val array = JSONArray().also { out -> trimmed.forEach { out.put(it) } }
                val payload = JSONObject()
                    .put("package", packageName)
                    .put("updated_at", now)
                    .put("elements", array)
                write(context, packageName, payload)
            }
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("app_ui_doc_write_failed") {
                "AppUiDocument write failed: ${throwable.javaClass.simpleName}"
            }
        }
    }

    private fun read(context: Context, packageName: String): JSONObject {
        val file = fileFor(context, packageName)
        if (!file.exists()) return JSONObject().put("package", packageName).put("elements", JSONArray())
        return runCatching {
            AtomicFile(file).openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        }.getOrElse {
            JSONObject().put("package", packageName).put("elements", JSONArray())
        }
    }

    private fun write(context: Context, packageName: String, payload: JSONObject) {
        val file = fileFor(context, packageName)
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(payload.toString().toByteArray(Charsets.UTF_8))
            out.flush()
            atomic.finishWrite(out)
        } catch (throwable: Throwable) {
            atomic.failWrite(out)
            throw throwable
        }
    }
}
