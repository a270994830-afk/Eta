package io.github.mangi.eta.hook.xiaoai

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 超级小爱 8.2.3 的直连渲染桥：按键会话认领后，由小爱进程直接驱动 Eta Runtime，
 * 把结果流式渲染到小爱的结果卡上并由小爱 TTS 朗读（复用 [XiaoAiStreamRenderer]）。
 *
 * 与旧版（507013032）的桥接等价，只是接管时机来自本地 ASR 回调而非 Nlp.Request。
 */
internal object XiaoAiV8Bridge {
    private val activeRun = AtomicReference<ActiveRun?>()

    private val executor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(4),
        { runnable ->
            Thread(runnable, "Eta-XiaoAiV8Bridge").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    /** @return true=已接管并进入直连渲染；false=接缝暂不可用，调用方应回退浮窗广播。 */
    fun claim(
        logger: ModuleLogger,
        context: Context,
        classLoader: ClassLoader,
        prompt: String,
    ): Boolean {
        val unavailable = XiaoAiV8Cards.probeUnavailableReason(classLoader)
        if (unavailable != null) {
            logger.warnThrottled("xiaoai_v8_render_unavailable") {
                "V8 直连渲染不可用，回退浮窗模式: $unavailable"
            }
            return false
        }
        val dialogId = XiaoAiV8Cards.currentDialogId(classLoader)
        val speech = XiaoAiV8Speech(classLoader, logger)
        speech.prepare()
        val run = ActiveRun(
            runId = UUID.randomUUID().toString(),
            dialogId = dialogId,
            prompt = prompt,
            renderer = XiaoAiStreamRenderer(
                context = context,
                logger = logger,
                classLoader = classLoader,
                dialogId = dialogId,
                floatManagerProvider = { XiaoAiV8Cards.floatManagerBridge(classLoader) },
                cardClassName = XiaoAiV8Cards.CARD_CLASS,
                speech = speech,
            ),
        )
        val previous = activeRun.getAndSet(run)
        previous?.cancel(logger)
        val submitted = runCatching {
            executor.submit { executeRun(logger, context, run) }
        }.isSuccess
        if (!submitted) {
            activeRun.compareAndSet(run, previous)
            logger.warnThrottled("xiaoai_v8_bridge_rejected") {
                "V8 渲染任务提交失败，回退浮窗模式"
            }
            return false
        }
        logger.info("V8 直连渲染已启动: dialog=${dialogId.take(8)}, promptChars=${prompt.length}")
        return true
    }

    /** 浮窗退出/关闭时取消进行中的渲染与朗读。 */
    fun cancelActive(logger: ModuleLogger) {
        activeRun.getAndSet(null)?.cancel(logger)
    }

    private fun executeRun(logger: ModuleLogger, context: Context, run: ActiveRun) {
        if (activeRun.get() !== run || run.cancelled.get()) return
        val client = AgentRuntimeClient(context, logger)
        run.client.set(client)
        var resultRunId: String? = null
        try {
            val result = client.run(
                request = AgentRuntimeWire.RunRequest(
                    runId = run.runId,
                    prompt = run.prompt,
                    config = AgentModelClient.loadConfig(),
                    images = emptyList(),
                    handoff = XiaoAiHandoff.create(
                        runId = run.runId,
                        dialogId = run.dialogId,
                        prompt = run.prompt,
                    ),
                ),
                onEvent = { event ->
                    if (activeRun.get() === run && !run.cancelled.get()) {
                        run.renderer.onEvent(event)
                    }
                },
            )
            resultRunId = result.runId.ifBlank { run.runId }
            if (activeRun.get() !== run || run.cancelled.get()) return
            if (result.ok) {
                run.renderer.complete(result.content)
            } else {
                logger.warnThrottled("xiaoai_v8_runtime_failed") {
                    "V8 Agent Runtime 执行失败: ${result.error}"
                }
                run.renderer.fail()
            }
        } catch (exception: Exception) {
            logger.warnThrottled("xiaoai_v8_bridge_failed") {
                "V8 Agent 执行异常: type=${exception.safeLogType()}"
            }
            if (activeRun.get() === run && !run.cancelled.get()) {
                run.renderer.fail()
            }
        } finally {
            resultRunId?.let { client.ackResult(it) }
            activeRun.compareAndSet(run, null)
        }
    }

    private data class ActiveRun(
        val runId: String,
        val dialogId: String,
        val prompt: String,
        val renderer: XiaoAiStreamRenderer,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val client: AtomicReference<AgentRuntimeClient?> = AtomicReference(),
    ) {
        fun cancel(logger: ModuleLogger) {
            if (!cancelled.compareAndSet(false, true)) return
            client.get()?.cancelRun(runId)
            renderer.cancel()
            logger.debug { "V8 渲染任务已取消" }
        }
    }
}
