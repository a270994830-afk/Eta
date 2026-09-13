package io.github.mangi.eta.agent.tool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 语义流程的存储与录制会话。
 * 流程保存的是语义步骤（按文本找元素、输入内容等），回放时对照当前屏幕自适应执行，
 * 而不是盲放坐标；某一步失败即中止，由 agent 降级为普通 GUI 循环。
 */
internal object AgentFlowStore {

    data class FlowStep(val action: String, val args: JSONObject) {
        fun toJson(): JSONObject = JSONObject().put("action", action).put("args", args)

        companion object {
            fun fromJson(obj: JSONObject): FlowStep =
                FlowStep(obj.getString("action"), obj.optJSONObject("args") ?: JSONObject())
        }
    }

    data class AgentFlow(
        val name: String,
        val description: String,
        val steps: List<FlowStep>,
        val createdAt: Long,
        val useCount: Int,
    )

    private const val MAX_FLOWS = 50
    private const val MAX_STEPS = 100
    private const val MAX_NAME = 60

    @Volatile
    private var recordingName: String? = null

    @Volatile
    private var recordingDescription: String = ""

    private val recordedSteps = ArrayDeque<FlowStep>()

    val isRecording: Boolean get() = recordingName != null

    fun currentRecordingName(): String? = recordingName

    @Synchronized
    fun startRecording(name: String, description: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        recordingName = trimmed.take(MAX_NAME)
        recordingDescription = description.trim().take(300)
        recordedSteps.clear()
        return true
    }

    @Synchronized
    fun recordStep(step: FlowStep) {
        if (recordingName == null) return
        if (recordedSteps.size >= MAX_STEPS) return
        recordedSteps.addLast(step)
    }

    @Synchronized
    fun recordedStepCount(): Int = recordedSteps.size

    /** 结束录制；save=true 时把已录步骤写入存储并返回流程，否则丢弃。 */
    @Synchronized
    fun stopRecording(storeFile: File, save: Boolean): AgentFlow? {
        val name = recordingName ?: return null
        val steps = recordedSteps.toList()
        recordingName = null
        recordingDescription = ""
        recordedSteps.clear()
        if (!save || steps.isEmpty()) return null
        val flow = AgentFlow(
            name = name,
            description = recordingDescription,
            steps = steps,
            createdAt = System.currentTimeMillis(),
            useCount = 0,
        )
        saveFlow(storeFile, flow)
        return flow
    }

    fun storeFile(context: android.content.Context): File =
        File(context.filesDir, "agent_flows.json")

    @Synchronized
    fun listFlows(storeFile: File): List<AgentFlow> = readStore(storeFile).flows

    @Synchronized
    fun findFlow(storeFile: File, name: String): AgentFlow? =
        readStore(storeFile).flows.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    @Synchronized
    fun deleteFlow(storeFile: File, name: String): Boolean {
        val store = readStore(storeFile)
        val remaining = store.flows.filterNot { it.name.equals(name.trim(), ignoreCase = true) }
        if (remaining.size == store.flows.size) return false
        writeStore(storeFile, remaining)
        return true
    }

    /** 回放成功后累加使用次数，便于之后按使用频率排序展示。 */
    @Synchronized
    fun bumpUseCount(storeFile: File, name: String) {
        val store = readStore(storeFile)
        val updated = store.flows.map {
            if (it.name == name) it.copy(useCount = it.useCount + 1) else it
        }
        writeStore(storeFile, updated)
    }

    /** 从 replaceFrom（0 起）开始用新步骤替换流程尾部，用于回放失败后的复盘修正。 */
    @Synchronized
    fun updateFlow(storeFile: File, name: String, replaceFrom: Int, newSteps: List<FlowStep>): AgentFlow? {
        val store = readStore(storeFile)
        val index = store.flows.indexOfFirst { it.name.equals(name.trim(), ignoreCase = true) }
        if (index < 0) return null
        val flow = store.flows[index]
        if (newSteps.isEmpty()) return null
        val from = replaceFrom.coerceIn(0, flow.steps.size)
        val updatedSteps = flow.steps.take(from) + newSteps.take(MAX_STEPS)
        val updated = flow.copy(steps = updatedSteps)
        writeStore(storeFile, store.flows.toMutableList().also { it[index] = updated })
        return updated
    }

    @Synchronized
    private fun saveFlow(storeFile: File, flow: AgentFlow) {
        val existing = readStore(storeFile).flows.filterNot { it.name == flow.name }
        val updated = (listOf(flow) + existing).take(MAX_FLOWS)
        writeStore(storeFile, updated)
    }

    private data class Store(val flows: List<AgentFlow>)

    private fun readStore(storeFile: File): Store = runCatching {
        if (!storeFile.exists()) return Store(emptyList())
        val root = JSONObject(storeFile.readText())
        val flows = root.optJSONArray("flows") ?: JSONArray()
        Store((0 until flows.length()).mapNotNull { i ->
            val obj = flows.optJSONObject(i) ?: return@mapNotNull null
            val stepsJson = obj.optJSONArray("steps") ?: JSONArray()
            AgentFlow(
                name = obj.optString("name"),
                description = obj.optString("description"),
                steps = (0 until stepsJson.length()).mapNotNull { j ->
                    stepsJson.optJSONObject(j)?.let { FlowStep.fromJson(it) }
                },
                createdAt = obj.optLong("created_at"),
                useCount = obj.optInt("use_count"),
            )
        }.filter { it.name.isNotBlank() && it.steps.isNotEmpty() })
    }.getOrElse { Store(emptyList()) }

    private fun writeStore(storeFile: File, flows: List<AgentFlow>) {
        val root = JSONObject().put(
            "flows",
            JSONArray().apply {
                flows.forEach { flow ->
                    put(
                        JSONObject()
                            .put("name", flow.name)
                            .put("description", flow.description)
                            .put("created_at", flow.createdAt)
                            .put("use_count", flow.useCount)
                            .put("steps", JSONArray().apply { flow.steps.forEach { put(it.toJson()) } })
                    )
                }
            },
        )
        runCatching {
            storeFile.parentFile?.mkdirs()
            val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(storeFile)) {
                storeFile.writeText(root.toString())
                tmp.delete()
            }
        }
    }
}
