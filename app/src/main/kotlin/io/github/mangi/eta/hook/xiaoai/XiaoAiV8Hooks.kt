package io.github.mangi.eta.hook.xiaoai

import android.content.Context
import android.content.Intent
import io.github.mangi.eta.agent.runtime.AgentAppContext
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import java.util.concurrent.atomic.AtomicLong

/**
 * 超级小爱 8.2.x（versionCode 508002003）的接管适配。
 *
 * 与旧版（507013032）不同，新版小爱把语音链路下沉到未混淆的 AI SDK（com.xiaomi.ai.*）：
 * 实测其 NLP 请求不再经过 SDK 出站事件（`EngineInterceptor.interceptEvent` 只见
 * SpeechRecognizer.*、General.* 等），因此接管改由「本地 ASR 结果认领 + 结果侧阻断」完成——
 * 在用户说完话时拿到文本交给 Eta 面板，并在认领窗口内丢弃小爱原生的回复指令，
 * 避免双方重复响应。系统唤醒与麦克风链路保持原生，不额外耗电。
 */
internal object XiaoAiV8Hooks {

    const val SUPPORTED_VERSION_CODE = 508002003L

    private const val INTERCEPTOR_CLASS = "com.xiaomi.ai.android.interceptor.EngineInterceptor"
    private const val INSTRUCTION_CLASS = "com.xiaomi.ai.api.common.Instruction"
    private const val OPERATION_MANAGER_CLASS =
        "com.xiaomi.voiceassistant.instruction.base.OperationManager"
    private const val ASR_RESULT_CLASS = "r51.b"

    private const val ETA_PACKAGE = "io.github.mangi.eta"
    private const val ACTION_SPOKEN_QUERY =
        "io.github.mangi.eta.agent.voice.action.SPOKEN_QUERY"
    private const val EXTRA_SPOKEN_QUERY = "io.github.mangi.eta.agent.voice.extra.SPOKEN_QUERY"

    /** 认领生效窗口：期间丢弃小爱原生下发的指令。 */
    private const val CLAIM_WINDOW_MS = 20_000L
    private val claimUntil = AtomicLong(0L)

    fun isSupportedVersion(versionCode: Long): Boolean = versionCode == SUPPORTED_VERSION_CODE

    fun install(
        module: io.github.libxposed.api.XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "XiaoAiV8")
        return hooks.install {
            hookAsrClaim(this, rootLogger, classLoader)
            hookInstructionDrop(this, rootLogger, classLoader)
        }
    }

    /** 在本地 ASR 结果处认领：命中后把文本转交 Eta 面板，并跳过小爱原生处理。 */
    private fun hookAsrClaim(
        hooks: HookRegistrar,
        logger: ModuleLogger,
        classLoader: ClassLoader,
    ) {
        val managerClass = HookSupport.findClassOrNull(classLoader, OPERATION_MANAGER_CLASS)
        val asrResultClass = HookSupport.findClassOrNull(classLoader, ASR_RESULT_CLASS)
        if (managerClass == null || asrResultClass == null) {
            hooks.missing(
                id = "xiaoai.v8.asr",
                description = "OperationManager.onAsrResult",
                detail = "未找到 $OPERATION_MANAGER_CLASS.onAsrResult($ASR_RESULT_CLASS)",
            )
            return
        }
        val method = HookSupport.findMethod(managerClass, "onAsrResult", asrResultClass)
        if (method == null) {
            hooks.missing(
                id = "xiaoai.v8.asr",
                description = "OperationManager.onAsrResult",
                detail = "未找到 onAsrResult($ASR_RESULT_CLASS)",
            )
            return
        }
        hooks.intercept(
            id = "xiaoai.v8.asr",
            executable = method,
            description = "XiaoAi 8.x OperationManager.onAsrResult",
        ) { chain ->
            val query = chain.args.getOrNull(0)?.let { invokeString(it, "getQuery") }.orEmpty()
            val decision = if (query.isBlank()) {
                null
            } else {
                XiaoAiTakeoverPolicy.decide(
                    query = query,
                    hasImage = false,
                    customModelEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_CUSTOM_MODEL),
                    requirePrefix = Prefs.isEnabled(Prefs.Keys.AGENT_REQUIRE_PREFIX),
                )
            }
            if (decision != null) {
                val context = AgentAppContext.resolve()
                if (context != null && launchEtaAssist(logger, context, decision.prompt)) {
                    claimUntil.set(android.os.SystemClock.uptimeMillis() + CLAIM_WINDOW_MS)
                    logger.info("已接管超级小爱 8.x 请求")
                    // 不调用 chain.proceed()：跳过小爱原生处理。
                    return@intercept null
                }
            }
            chain.proceed()
        }
    }

    /**
     * 结果侧阻断：认领窗口内丢弃小爱原生下发的指令（卡片/TTS 等），
     * 避免小爱在 Eta 处理的同时自行回答。
     */
    private fun hookInstructionDrop(
        hooks: HookRegistrar,
        logger: ModuleLogger,
        classLoader: ClassLoader,
    ) {
        val interceptorClass = HookSupport.findClassOrNull(classLoader, INTERCEPTOR_CLASS)
        val instructionClass = HookSupport.findClassOrNull(classLoader, INSTRUCTION_CLASS)
        if (interceptorClass == null || instructionClass == null) {
            hooks.missing(
                id = "xiaoai.v8.drop",
                description = "EngineInterceptor.interceptInstruction",
                detail = "未找到指令拦截器",
            )
            return
        }
        val method = HookSupport.findMethod(interceptorClass, "interceptInstruction", instructionClass)
        if (method == null) {
            hooks.missing(
                id = "xiaoai.v8.drop",
                description = "EngineInterceptor.interceptInstruction(Instruction)",
                detail = "未找到 interceptInstruction(Instruction)",
            )
            return
        }
        hooks.intercept(
            id = "xiaoai.v8.drop",
            executable = method,
            description = "XiaoAi 8.x EngineInterceptor.interceptInstruction",
        ) { chain ->
            if (android.os.SystemClock.uptimeMillis() < claimUntil.get()) {
                logger.warnThrottled("xiaoai_v8_drop") {
                    "已接管：丢弃小爱原生回复指令"
                }
                return@intercept null
            }
            chain.proceed()
        }
    }

    /**
     * 把用户文本交给 Eta 语音面板处理。
     *
     * 钩子在小爱进程内，直接 startActivity 会被 Android 的后台启动限制静默拦截，
     * 因此改为定向广播，由 Eta 自己的进程收到后再启动浮层。
     */
    private fun launchEtaAssist(logger: ModuleLogger, context: Context, prompt: String): Boolean {
        return try {
            val intent = Intent(ACTION_SPOKEN_QUERY).apply {
                setPackage(ETA_PACKAGE)
                putExtra(EXTRA_SPOKEN_QUERY, prompt)
            }
            context.sendBroadcast(intent)
            true
        } catch (exception: Exception) {
            logger.warnThrottled("xiaoai_v8_assist_launch_failed") {
                "转交 Eta 语音面板失败，保持原生行为: type=${exception.javaClass.simpleName}"
            }
            false
        }
    }

    private fun invokeString(target: Any, method: String): String =
        runCatching {
            val value = target.javaClass.getMethod(method).invoke(target)
            value?.toString().orEmpty()
        }.getOrDefault("")
}
