package io.github.mangi.eta.hook.xiaoai

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/** 小爱侧结果朗读：旧版与 8.x 适配各自提供实现，由 [XiaoAiStreamRenderer] 在完成/取消时调用。 */
internal interface XiaoAiSpeech {
    fun speak(text: String)

    fun stop()
}

/** 旧版超级小爱（507013032）的 TTS 调用实现。 */
internal class LegacyXiaoAiSpeech(
    private val classLoader: ClassLoader,
    private val logger: ModuleLogger,
) : XiaoAiSpeech {
    override fun speak(text: String) {
        try {
            val player = ttsPlayer() ?: return
            HookSupport.findMethod(player.javaClass, "speakTts", String::class.java)
                ?.invoke(player, text)
        } catch (exception: Exception) {
            logger.warnThrottled("xiaoai_tts_failed") {
                "超级小爱 Eta 结果朗读失败: type=${exception.safeLogType()}"
            }
        }
    }

    override fun stop() {
        try {
            val player = ttsPlayer() ?: return
            HookSupport.findMethod(player.javaClass, "stopPlay")?.invoke(player)
        } catch (exception: Exception) {
            logger.warnThrottled("xiaoai_tts_stop_failed") {
                "超级小爱 Eta 朗读停止失败: type=${exception.safeLogType()}"
            }
        }
    }

    private fun ttsPlayer(): Any? {
        val playerClass = Class.forName(XiaoAiHooks.TTS_PLAYER_CLASS, false, classLoader)
        val singletonField = playerClass.declaredFields.firstOrNull { field ->
            Modifier.isStatic(field.modifiers) && field.type == playerClass
        } ?: return null
        singletonField.isAccessible = true
        return singletonField.get(null)
    }
}

/**
 * 超级小爱 8.2.3（508002003）的 TTS 实现。
 *
 * 主通道：向小爱指令队列注入 Speak 指令（与云端朗读指令同一条路，最接近原生）；
 * 次通道 n31.d1（源码名 TtsEngine）：自建 TTS 引擎实例直接合成朗读，不依赖卡片/对话状态；
 * 引擎不保活、空闲断连后事件会被静默吞掉：闲置过久先释放重建，提交无回调也会重建重发；
 * 兜底通道 wf1.v1（源码名 ToastStreamPlayer）：流式卡上的播放器。
 */
internal class XiaoAiV8Speech(
    private val classLoader: ClassLoader,
    private val logger: ModuleLogger,
) : XiaoAiSpeech {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var pendingSpeakTask: Runnable? = null

    @Volatile
    private var retryTask: Runnable? = null

    override fun speak(text: String) {
        XiaoAiV8Cards.clearLoadingCard(classLoader)
        releaseIfIdle("朗读前")
        injectSpeakInstruction(text)
        val connected = connectTtsEngine()
        val submit = Runnable { beginSubmit(text) }
        pendingSpeakTask = submit
        mainHandler.postDelayed(submit, if (connected) SPEAK_CONNECT_DELAY_MS else 0L)
    }

    /** 认领阶段预热：引擎闲置过久先释放（提交时重建），否则提前发起连接。 */
    fun prepare() {
        releaseIfIdle("准备阶段")
        logger.info("V8 朗读：预热引擎连接")
        connectTtsEngine()
    }

    override fun stop() {
        pendingSpeakTask?.let { task ->
            mainHandler.removeCallbacks(task)
            pendingSpeakTask = null
        }
        retryTask?.let { task ->
            mainHandler.removeCallbacks(task)
            retryTask = null
        }
        stopViaTtsEngine()
        stopViaToastPlayer()
    }

    private fun beginSubmit(text: String) {
        pendingSpeakTask = null
        lastSpeakAt = SystemClock.elapsedRealtime()
        if (!submitViaTtsEngine(text, attempt = 1)) {
            speakViaToastPlayer(text)
        }
    }

    /** 主通道：注入一条 Speak 指令，让小爱用它自己的原生流程朗读（与云端指令同一条路）。 */
    private fun injectSpeakInstruction(text: String): Boolean = try {
        val speakClass = Class.forName(SPEAK_PAYLOAD_CLASS, false, classLoader)
        val payload = speakClass.getConstructor(String::class.java).newInstance(text)
        speakClass.getMethod("setSampleRate", java.lang.Integer.TYPE).invoke(payload, SPEAK_SAMPLE_RATE)
        val codecClass = Class.forName(TTS_CODEC_CLASS, false, classLoader)
        val mp3 = codecClass.enumConstants?.firstOrNull { constant -> (constant as Enum<*>).name == "MP3" }
        if (mp3 != null) {
            speakClass.getMethod("setCodec", codecClass).invoke(payload, mp3)
        }
        val payloadClass = Class.forName(INSTRUCTION_PAYLOAD_CLASS, false, classLoader)
        val instruction = Class.forName(API_UTILS_CLASS, false, classLoader)
            .getMethod("buildInstruction", payloadClass)
            .invoke(null, payload)
            ?: return false
        val dialogId = XiaoAiV8Cards.currentDialogId(classLoader)
        if (dialogId.isNotBlank()) {
            instruction.javaClass.getMethod("setDialogId", String::class.java).invoke(instruction, dialogId)
        }
        val managerClass = Class.forName(OPERATION_MANAGER_CLASS, false, classLoader)
        val manager = managerClass.getMethod("getInstance").invoke(null) ?: return false
        val instructionClass = Class.forName(INSTRUCTION_CLASS, false, classLoader)
        val batch = java.lang.reflect.Array.newInstance(instructionClass, 1)
        java.lang.reflect.Array.set(batch, 0, instruction)
        managerClass.getMethod("addInstructionsToOperationQueue", batch.javaClass).invoke(manager, batch)
        lastInjectedText = text
        logger.info("V8 朗读：已注入 Speak 指令（${text.length} 字）")
        true
    } catch (exception: Exception) {
        logger.warnThrottled("xiaoai_v8_tts_inject_failed") {
            "V8 朗读：Speak 指令注入失败: type=${exception.safeLogType()}"
        }
        false
    }

    /**
     * 主动请求引擎连接：引擎不保活、空闲断连，未连接时朗读会被静默吞掉。
     * 目标必须落在真引擎对象（d1 的静态字段 c）一侧，先走「引擎→q1→n31.n」实体链，再试引擎直连。
     */
    private fun connectTtsEngine(): Boolean {
        val engine = engineInstance()
        if (engine == null) {
            logger.info("V8 朗读：引擎对象不可用，跳过连接")
            return false
        }
        logger.info("V8 朗读：引擎实现类=${engine.javaClass.name}")
        return try {
            val engineTask = findFieldOfType(engine, Class.forName(ENGINE_TASK_CLASS, false, classLoader))
            val bridge = engineTask?.let { findFieldOfType(it, Class.forName(ENGINE_BRIDGE_CLASS, false, classLoader)) }
            val bridgeConnect = bridge?.let { findEngineMethod(it, "connect") }
            if (bridgeConnect != null) {
                bridgeConnect.invoke(bridge)
                logger.info("V8 朗读：已请求 TTS 引擎连接（引擎任务桥）")
                return true
            }
            val direct = findEngineMethod(engine, "connect")
            if (direct != null) {
                direct.invoke(engine)
                logger.info("V8 朗读：已请求 TTS 引擎连接（引擎直连）")
                return true
            }
            logger.info("V8 朗读：未找到引擎 connect 方法")
            false
        } catch (exception: Exception) {
            logger.warnThrottled("xiaoai_v8_tts_connect_failed") {
                "V8 朗读：TTS 引擎连接请求失败: type=${exception.safeLogType()}"
            }
            false
        }
    }

    /** d1 的静态字段 c 即真引擎实例（n31.a1）。 */
    private fun engineInstance(): Any? = runCatching {
        val ttsClass = Class.forName(TTS_ENGINE_CLASS, false, classLoader)
        ttsClass.getField("c").get(null)
    }.getOrNull()

    /** 沿类层次与接口查找引擎侧的公开方法。 */
    private fun findEngineMethod(engine: Any, name: String, vararg parameterTypes: Class<*>): java.lang.reflect.Method? {
        val visited = HashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        queue.add(engine.javaClass)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            val found = current.declaredMethods.firstOrNull { method ->
                method.name == name &&
                    method.parameterCount == parameterTypes.size &&
                    method.parameterTypes.contentEquals(parameterTypes)
            }
            if (found != null) {
                found.isAccessible = true
                return found
            }
            current.superclass?.let(queue::add)
            current.interfaces.forEach(queue::add)
        }
        return null
    }

    private fun findFieldOfType(target: Any, type: Class<*>): Any? {
        var current: Class<*>? = target.javaClass
        while (current != null) {
            val found = current.declaredFields.firstOrNull { field -> field.type == type }
            if (found != null) {
                found.isAccessible = true
                return runCatching { found.get(target) }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    /**
     * 提交朗读。首次提交后若引擎 [RETRY_DELAY_MS] 内无任何回调，判定创建后异步初始化未完成，
     * 清队列并重试一次。
     */
    private fun submitViaTtsEngine(text: String, attempt: Int): Boolean {
        val gotCallback = AtomicBoolean(false)
        val submitted = try {
            val engine = ttsEngine() ?: return false
            val existing = readField(engine, "c")
            logger.info("V8 朗读：引擎状态=${if (existing == null) "将新建" else "已存在"}（第 $attempt 次）")
            if (existing != null) {
                probeEngineDetail(engine)?.let { detail -> logger.info("V8 朗读：引擎细节 $detail") }
            }
            val method = engine.javaClass.methods.firstOrNull { candidate ->
                candidate.name == "speakTts" &&
                    candidate.parameterCount == 2 &&
                    candidate.parameterTypes[0] == String::class.java
            } ?: return false
            method.invoke(engine, text, buildTtsListener(gotCallback))
            logger.info("V8 朗读：TtsEngine 已提交（${text.length} 字，第 $attempt 次）")
            true
        } catch (exception: Exception) {
            logger.warnThrottled("xiaoai_v8_tts_engine_failed") {
                "V8 朗读：TtsEngine 提交失败: type=${exception.safeLogType()}"
            }
            false
        }
        if (!submitted) return false
        if (attempt == 1) {
            val task = Runnable {
                retryTask = null
                if (!gotCallback.get()) {
                    logger.info("V8 朗读：引擎无回调，释放重建后重发")
                    stopViaTtsEngine()
                    releaseTtsEngine()
                    submitViaTtsEngine(text, attempt = 2)
                }
            }
            retryTask = task
            mainHandler.postDelayed(task, RETRY_DELAY_MS)
        }
        return true
    }

    /** 备选通道：真引擎的 speak(q51.f, false)（主音频轨，更接近原生朗读）。 */
    private fun speakViaEngineSpeakApi(text: String): Boolean = try {
        val engine = engineInstance() ?: return false
        val baseClass = Class.forName(SPEAK_REQUEST_BASE_CLASS, false, classLoader)
        val request = Class.forName(SPEAK_REQUEST_CLASS, false, classLoader)
            .getConstructor()
            .newInstance()
        baseClass.getMethod("setTextToSpeak", String::class.java).invoke(request, text)
        val speakMethod = findEngineMethod(engine, "speak", baseClass, java.lang.Boolean.TYPE)
        if (speakMethod == null) {
            logger.info("V8 朗读：未找到引擎 speak 方法")
            return false
        }
        speakMethod.invoke(engine, request, false)
        logger.info("V8 朗读：speak 接口已调用（${text.length} 字）")
        true
    } catch (exception: Exception) {
        logger.warnThrottled("xiaoai_v8_tts_speak_api_failed") {
            "V8 朗读：speak 接口失败: type=${exception.safeLogType()}"
        }
        false
    }

    /** 代理引擎的 TTS 监听器：标记回调到达并记录（合成/播放/错误），便于定位无声原因。 */
    private fun buildTtsListener(gotCallback: AtomicBoolean): Any? = runCatching {
        val listenerClass = Class.forName(TTS_LISTENER_CLASS, false, classLoader)
        java.lang.reflect.Proxy.newProxyInstance(classLoader, arrayOf(listenerClass)) { _, method, args ->
            gotCallback.set(true)
            val detail = args?.joinToString(separator = ", ") { argument ->
                argument?.toString() ?: "null"
            }.orEmpty().take(300)
            logger.info("V8 朗读回调：${method.name}($detail)")
            defaultValueForType(method.returnType)
        }
    }.getOrNull()

    private fun defaultValueForType(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        else -> null
    }

    /**
     * 引擎闲置过久时释放旧实例。引擎不保活、空闲断连后事件会被静默吞掉（无回调、无声），
     * 且对已断连的引擎调 connect 不会重建连接；释放后由下次 speakTts 懒建全新引擎。
     */
    private fun releaseIfIdle(tag: String) {
        val last = lastSpeakAt
        if (last == 0L) return
        val idle = SystemClock.elapsedRealtime() - last
        if (idle < IDLE_REBUILD_MS) return
        val engine = ttsEngine() ?: return
        if (readField(engine, "c") == null) return
        logger.info(
            "V8 朗读：引擎已空闲 ${idle / 1000}s（$tag），释放待重建；释放前细节：${probeEngineDetail(engine) ?: "n/a"}"
        )
        releaseTtsEngine()
    }

    /** 调用 d1.c()：释放引擎实例并置空，下次 speakTts 懒建新实例（原生 onError 亦走此恢复路径）。 */
    private fun releaseTtsEngine() {
        try {
            val ttsClass = Class.forName(TTS_ENGINE_CLASS, false, classLoader)
            val singleton = ttsClass.getField("a").get(null) ?: return
            HookSupport.findMethod(ttsClass, "c")?.invoke(singleton)
            logger.info("V8 朗读：旧引擎已释放")
        } catch (exception: Exception) {
            logger.warnThrottled("xiaoai_v8_tts_release_failed") {
                "V8 朗读：释放引擎失败: type=${exception.safeLogType()}"
            }
        }
    }

    /** 引擎链诊断：d1 静态字段 c（真引擎）→q1→桥→代理→实现，含 SDK 引擎/启动状态/连接状态。 */
    private fun probeEngineDetail(engine: Any): String? = runCatching {
        val instance = readField(engine, "c") ?: return@runCatching "engine=null"
        val task = readField(instance, "f") ?: return@runCatching "q1=null"
        val bridge = readField(task, "b") ?: return@runCatching "bridge=null"
        val proxy = readField(bridge, "a") ?: return@runCatching "proxy=null"
        val impl = readField(proxy, "n") ?: return@runCatching "impl=null"
        "sdkEngine=${readField(impl, "v") != null}, startStatus=${readField(impl, "J")}, sdkConnected=${readField(impl, "K")}"
    }.getOrNull()

    private fun readField(target: Any, name: String): Any? =
        runCatching { target.javaClass.getField(name).get(target) }.getOrNull()

    private fun stopViaTtsEngine() {
        try {
            val engine = ttsEngine() ?: return
            HookSupport.findMethod(engine.javaClass, "stop")?.invoke(engine)
        } catch (exception: Exception) {
            logger.warnThrottled("xiaoai_v8_tts_engine_stop_failed") {
                "小爱 TTS 引擎停止失败: type=${exception.safeLogType()}"
            }
        }
    }

    private fun ttsEngine(): Any? = runCatching {
        val engineClass = Class.forName(TTS_ENGINE_CLASS, false, classLoader)
        val singletonField = engineClass.declaredFields.firstOrNull { field ->
            Modifier.isStatic(field.modifiers) && field.type == engineClass
        } ?: return@runCatching null
        singletonField.isAccessible = true
        singletonField.get(null)
    }.getOrNull()

    private fun speakViaToastPlayer(text: String) {
        try {
            val player = toastPlayer() ?: return
            HookSupport.findMethod(player.javaClass, "speakTts", String::class.java)
                ?.invoke(player, text)
        } catch (exception: Exception) {
            logger.warnThrottled("xiaoai_v8_tts_failed") {
                "超级小爱 8.x Eta 结果朗读失败: type=${exception.safeLogType()}"
            }
        }
    }

    private fun stopViaToastPlayer() {
        try {
            val player = toastPlayer() ?: return
            HookSupport.findMethod(player.javaClass, "stopPlay")?.invoke(player)
        } catch (exception: Exception) {
            logger.warnThrottled("xiaoai_v8_tts_stop_failed") {
                "超级小爱 8.x Eta 朗读停止失败: type=${exception.safeLogType()}"
            }
        }
    }

    private fun toastPlayer(): Any? {
        val playerClass = Class.forName(PLAYER_CLASS, false, classLoader)
        val singletonField = playerClass.declaredFields.firstOrNull { field ->
            Modifier.isStatic(field.modifiers) && field.type == playerClass
        } ?: return null
        singletonField.isAccessible = true
        return singletonField.get(null)
    }

    companion object {
        /** 源码名 TtsEngine：独立 TTS 引擎（TTS_ENGINE 通道），直接朗读任意文本。 */
        const val TTS_ENGINE_CLASS = "n31.d1"

        /** TTS 监听器接口（speakTts 的第二个参数）。 */
        const val TTS_LISTENER_CLASS = "e21.a"

        /** 源码名 ToastStreamPlayer：流式回答卡的播放器（兜底）。 */
        const val PLAYER_CLASS = "wf1.v1"

        /** 首次提交后等待引擎回调的时长；超时判定异步初始化未完成并重试。 */
        const val RETRY_DELAY_MS = 2_500L

        /** 引擎闲置超过该时长（毫秒）即视为连接已断，释放后重建。 */
        const val IDLE_REBUILD_MS = 45_000L

        /** 最近一次提交朗读的时间（elapsedRealtime），用于判断引擎闲置时长。 */
        @Volatile
        private var lastSpeakAt = 0L

        /** speak 请求对象（q51.e 继承 q51.f），事件之外的直说接口。 */
        const val SPEAK_REQUEST_CLASS = "q51.e"
        const val SPEAK_REQUEST_BASE_CLASS = "q51.f"

        /** 发出连接请求后等待引擎就绪的时长，再提交朗读。 */
        const val SPEAK_CONNECT_DELAY_MS = 1_500L

        /** 引擎任务与底层桥的类名（位于引擎实现对象的字段上）。 */
        const val ENGINE_TASK_CLASS = "com.xiaomi.voiceassist.engine.context.q1"
        const val ENGINE_BRIDGE_CLASS = "n31.n"

        /** Speak 指令注入（原生朗读通道）相关类。 */
        const val SPEAK_PAYLOAD_CLASS = "com.xiaomi.ai.api.SpeechSynthesizer\$Speak"
        const val INSTRUCTION_PAYLOAD_CLASS = "com.xiaomi.ai.api.common.InstructionPayload"
        const val API_UTILS_CLASS = "com.xiaomi.ai.api.common.APIUtils"
        const val INSTRUCTION_CLASS = "com.xiaomi.ai.api.common.Instruction"
        const val OPERATION_MANAGER_CLASS = "com.xiaomi.voiceassistant.instruction.base.OperationManager"
        const val TTS_CODEC_CLASS = "com.xiaomi.ai.api.Common\$TTSCodec"

        /** 对齐原生朗读指令的音频参数（样本：codec=MP3, sample_rate=24000）。 */
        const val SPEAK_SAMPLE_RATE = 24_000

        /** 最近一次注入的朗读文本；拦截器用它识别「注入回声」，避免被当作小爱原生回复丢弃。 */
        @Volatile
        var lastInjectedText: String? = null
    }
}
