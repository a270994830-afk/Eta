package io.github.mangi.eta.agent.voice

import android.app.Service
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.runtime.Composable
import androidx.core.app.NotificationCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.MainActivity
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentRunMessageProjector
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * Eta 数字助理的用户界面窗口。
 *
 * 系统助理会话只负责承接电源键入口；这里固定使用全屏 TYPE_APPLICATION_OVERLAY，
 * 让输入法、动画和厂商助手式浮窗拥有同一个窗口生命周期。
 */
internal class EtaAssistantOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancellationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EtaAssistantRuntimeCancel")
    }
    private val runtimeClient = AgentRuntimeClient(this, AndroidAgentLogger)
    private val runMessageProjector = AgentRunMessageProjector()
    private val conversationKey = "eta_assistant_${UUID.randomUUID()}"
    private var conversationHistory = emptyList<AgentModelClient.ConversationMessage>()

    private var windowManager: WindowManager? = null
    private var windowView: ComposeView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var detachingWindowView: View? = null
    private val windowDetachCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var backInvokedDispatcher: OnBackInvokedDispatcher? = null
    private var backInvokedCallback: OnBackInvokedCallback? = null
    private var runJob: Job? = null
    private var entryCaptureJob: Job? = null
    private var activeRunId: String? = null
    private var entryGeneration = 0L
    private var presentedEntryGeneration = -1L
    private var screenContextAttachment: EtaScreenContextAttachment? = null
    private var hiddenForForegroundOperation = false
    private var handoffInProgress = false
    private var handoffExitRequested by mutableStateOf(false)
    private var inputText by mutableStateOf("")
    private var inputFocusRequestKey by mutableIntStateOf(-1)
    private var uiState by mutableStateOf(EtaVoiceUiState())
    private var voiceSessionMode = false
    private var voiceListening = false
    private var voiceTurnSerial = 0L
    private var pendingInitialQuery: String? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var lastResultContent: String = ""

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        activeService = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                voiceSessionMode = intent?.getBooleanExtra(EXTRA_VOICE_MODE, false) ?: false
                pendingInitialQuery = intent?.getStringExtra(EXTRA_INITIAL_QUERY)
                showEntry()
            }
            ACTION_HANDOFF_READY -> finishHandoff()
            else -> {
                voiceSessionMode = intent?.getBooleanExtra(EXTRA_VOICE_MODE, false) ?: false
                showEntry()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        entryGeneration++
        entryCaptureJob?.cancel()
        entryCaptureJob = null
        screenContextAttachment = null
        cancelCurrentRun()
        removeWindow()
        scope.cancel()
        cancellationExecutor.shutdown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    private fun showEntry() {
        if (!Settings.canDrawOverlays(this)) {
            AndroidAgentLogger.warnThrottled("eta_assistant_overlay_permission_missing") {
                "Eta assistant overlay permission is missing"
            }
            stopSelf()
            return
        }
        cancelCurrentRun()
        entryCaptureJob?.cancel()
        removeWindow()
        val generation = ++entryGeneration
        presentedEntryGeneration = -1L
        screenContextAttachment = null
        inputText = ""
        uiState = EtaVoiceUiState(
            screenContext = EtaScreenContextUiState(
                phase = EtaScreenContextPhase.CAPTURING,
            ),
        )
        hiddenForForegroundOperation = false
        handoffInProgress = false
        handoffExitRequested = false
        val accessibility = AgentAccessibilityService.current()
        if (accessibility == null) {
            uiState = uiState.copy(
                screenContext = EtaScreenContextUiState(
                    phase = EtaScreenContextPhase.UNAVAILABLE,
                ),
            )
            presentEntry(generation)
            return
        }
        entryCaptureJob = scope.launch {
            val result = accessibility.captureScreenshotExcludingOverlays(
                onWindowsSubmitted = {
                    scope.launch(Dispatchers.Main.immediate) {
                        presentEntry(generation)
                    }
                },
            )
            val bitmap = result.bitmap
            val attachment = if (bitmap == null || result.criticalWindowMissing) {
                null
            } else {
                try {
                    runCatching {
                        val image = AgentImageCodec.fromScreenContextBitmap(
                            bitmap,
                            source = "screen_context",
                        )
                        val preview = AgentImageCodec.previewFromReference(
                            this@EtaAssistantOverlayService,
                            image,
                        ) ?: return@runCatching null
                        EtaScreenContextAttachment(
                            image = image,
                            previewDataUrl = preview.reference,
                        )
                    }.onFailure { throwable ->
                        AndroidAgentLogger.warn(
                            "Eta assistant entry screenshot encode failed: " +
                                "type=${throwable.javaClass.simpleName}"
                        )
                    }.getOrNull()
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (generation != entryGeneration) return@withContext
                entryCaptureJob = null
                screenContextAttachment = attachment
                uiState = uiState.copy(
                    screenContext = if (attachment == null) {
                        EtaScreenContextUiState(phase = EtaScreenContextPhase.UNAVAILABLE)
                    } else {
                        EtaScreenContextUiState(
                            phase = EtaScreenContextPhase.AVAILABLE,
                            previewDataUrl = attachment.previewDataUrl,
                        )
                    },
                )
                presentEntry(generation)
            }
        }
    }

    private fun presentEntry(generation: Long) {
        if (generation != entryGeneration || presentedEntryGeneration == generation) return
        presentedEntryGeneration = generation
        showWindow()
        if (windowView == null) {
            stopSelf()
            return
        }
        if (voiceSessionMode) {
            updateSoftInput(visible = false)
            val initial = pendingInitialQuery?.takeIf { it.isNotBlank() }
            pendingInitialQuery = null
            if (initial != null) {
                submitPrompt(initial)
            } else {
                startVoiceListening()
            }
        } else {
            val initial = pendingInitialQuery?.takeIf { it.isNotBlank() }
            pendingInitialQuery = null
            if (initial != null) {
                submitPrompt(initial)
            } else {
                showKeyboard()
            }
        }
    }

    private fun showWindow() {
        if (windowView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = createComposeView {
            val appearance by AppearanceSettingsRepository.settingsFlow()
                .collectAsState(initial = AppearanceSettings())
            AgentAppTheme(
                appearance = appearance,
                applyInterfaceScale = false,
            ) {
                // ColorOS 在 Overlay 窗口切换期间可能短暂使用软件画布；RuntimeShader
                // 无法在该画布绘制，因此浮窗统一使用 Miuix 的圆角回退路径。
                CompositionLocalProvider(LocalSquircleEnabled provides false) {
                    EtaVoicePanel(
                        state = uiState,
                        input = inputText,
                        inputFocusRequestKey = inputFocusRequestKey,
                        isListening = voiceListening,
                        onVoiceToggle = ::toggleVoiceListening,
                        onInputChange = { inputText = it },
                        onScreenContextSelect = ::selectScreenContext,
                        onScreenContextRemove = ::removeScreenContext,
                        onSubmit = ::submitInput,
                        onStop = ::stopCurrentRun,
                        onClose = ::dismissAndStop,
                        canOpenConversation = activeRunId == null &&
                            uiState.messages.any { message ->
                                message is AgentMessageUi && message.content.isNotBlank()
                            },
                        exitRequested = handoffExitRequested,
                        onOpenConversation = ::openConversation,
                    )
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            dimAmount = 0f
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            title = "EtaAssistantOverlay"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wm.isCrossWindowBlurEnabled) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = 24
            }
        }
        runCatching { wm.addView(view, params) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("eta_assistant_overlay_add_failed") {
                "Eta assistant overlay addView failed: type=${throwable.javaClass.simpleName}"
            }
            return
        }
        windowManager = wm
        windowView = view
        windowParams = params
        registerSystemBackCallback(view)
        view.requestFocus()
    }

    private fun createComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusableInTouchMode = true
            setViewTreeLifecycleOwner(this@EtaAssistantOverlayService)
            setViewTreeSavedStateRegistryOwner(this@EtaAssistantOverlayService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent(content)
        }

    private fun registerSystemBackCallback(view: View) {
        unregisterSystemBackCallback()
        val dispatcher = view.findOnBackInvokedDispatcher()
        if (dispatcher == null) {
            AndroidAgentLogger.warn("Eta assistant overlay back dispatcher unavailable")
            return
        }
        val callback = OnBackInvokedCallback(::dismissAndStop)
        dispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        )
        backInvokedDispatcher = dispatcher
        backInvokedCallback = callback
    }

    private fun unregisterSystemBackCallback() {
        val dispatcher = backInvokedDispatcher
        val callback = backInvokedCallback
        backInvokedDispatcher = null
        backInvokedCallback = null
        if (dispatcher != null && callback != null) {
            dispatcher.unregisterOnBackInvokedCallback(callback)
        }
    }

    private fun showKeyboard(status: EtaVoiceStatus = EtaVoiceStatus.InputRequest) {
        uiState = uiState.copy(
            phase = EtaVoicePhase.READY,
            status = status,
        )
        updateSoftInput(visible = true)
        inputFocusRequestKey++
    }

    private fun submitInput() {
        val prompt = inputText.trim()
        if (prompt.isBlank() || activeRunId != null) return
        submitPrompt(prompt)
    }

    private fun submitPrompt(prompt: String) {
        val normalized = prompt.trim()
        if (normalized.isBlank() || activeRunId != null) return
        val attachment = screenContextAttachment.takeIf { uiState.screenContext.selected }
        val runImages = attachment?.let { listOf(it.image) }.orEmpty()
        val previewImages = attachment?.let { listOf(it.previewDataUrl) }.orEmpty()
        screenContextAttachment = null
        inputText = ""
        activeRunId = UUID.randomUUID().toString()
        val runId = activeRunId ?: return
        uiState = uiState.copy(
            phase = EtaVoicePhase.PROCESSING,
            status = EtaVoiceStatus.Reasoning,
            screenContext = EtaScreenContextStateReducer.consume(),
            messages = uiState.messages + UserMessageUi(
                id = "user-$runId",
                content = normalized,
                images = previewImages,
            ),
        )
        updateSoftInput(visible = false)
        runJob = scope.launch {
            val config = AgentModelClient.loadConfig()
            val payload = AgentExternalArchivePayload(
                userText = normalized,
                conversationKey = conversationKey,
                title = normalized.take(40),
            )
            val result = runtimeClient.run(
                request = AgentRuntimeWire.RunRequest(
                    runId = runId,
                    prompt = normalized,
                    config = config,
                    images = runImages,
                    history = conversationHistory,
                    handoff = AgentRuntimeWire.EntryHandoff(
                        id = "$conversationKey:$runId",
                        source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                        payload = payload.toJson(),
                        dismissEntrySurfaceOnForegroundOperation = true,
                    ),
                ),
                onEvent = { event -> handleRuntimeEvent(runId, event) },
            )
            val shouldStopAfterResult = withContext(Dispatchers.Main.immediate) {
                if (activeRunId != runId) return@withContext false
                activeRunId = null
                runJob = null
                if (result.contextSnapshot != null) {
                    conversationHistory = result.contextSnapshot.messages
                } else if (result.ok) {
                    conversationHistory = conversationHistory +
                        AgentModelClient.buildUserHistoryMessage(normalized, runImages) + result.transcript
                }
                if (result.ok) {
                    lastResultContent = result.content
                    uiState = uiState.copy(
                        phase = EtaVoicePhase.READY,
                        status = EtaVoiceStatus.Completed,
                        messages = finishRunMessages(runId, result),
                    )
                } else {
                    uiState = uiState.copy(
                        phase = EtaVoicePhase.ERROR,
                        status = EtaVoiceStatus.Failed(result.error),
                        messages = finishRunMessages(runId, result),
                    )
                }
                if (!hiddenForForegroundOperation) {
                    updateSoftInput(visible = false)
                }
                hiddenForForegroundOperation
            }
            runtimeClient.ackResult(runId)
            val resumeVoice = withContext(Dispatchers.Main.immediate) {
                if (activeRunId != null) return@withContext false
                voiceSessionMode && !voiceListening
            }
            if (shouldStopAfterResult && !resumeVoice) {
                withContext(Dispatchers.Main.immediate) {
                    if (activeRunId == null) {
                        removeWindow()
                        stopSelf()
                    }
                }
            } else if (resumeVoice) {
                scheduleNextVoiceTurn()
            } else {
                // 单轮模式：结果完成，前台浮窗收起，结果改为通知后台展示。
                withContext(Dispatchers.Main.immediate) {
                    showResultNotificationIfAny()
                    removeWindow()
                    stopSelf()
                }
            }
        }
    }

    private fun handleRuntimeEvent(runId: String, event: AgentEvent) {
        scope.launch(Dispatchers.Main.immediate) {
            if (activeRunId != runId) return@launch
            if (AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event)) {
                hideForForegroundOperation()
            }
            uiState = projectRuntimeEvent(runId, event, uiState)
        }
    }

    private fun projectRuntimeEvent(
        runId: String,
        event: AgentEvent,
        state: EtaVoiceUiState,
    ): EtaVoiceUiState {
        var messages = state.messages
        var status = state.status
        var phase = state.phase
        when (event) {
            is AgentEvent.AssistantBlockStart -> {
                messages = runMessageProjector.startAssistantBlock(runId, event, messages)
            }

            is AgentEvent.AssistantBlockDelta -> {
                messages = when (event.kind) {
                    AgentEvent.AssistantBlockKind.TEXT ->
                        runMessageProjector.appendTextDelta(
                            runId,
                            event.round,
                            event.index,
                            event.delta,
                            messages,
                        )

                    AgentEvent.AssistantBlockKind.THINKING ->
                        runMessageProjector.appendReasoningDelta(
                            runId,
                            event.round,
                            event.index,
                            event.delta,
                            messages,
                        )

                    AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                }
            }

            is AgentEvent.AssistantBlockEnd -> {
                messages = when (event.kind) {
                    AgentEvent.AssistantBlockKind.TEXT ->
                        runMessageProjector.finalizeTextBlock(
                            runId,
                            event.round,
                            event.index,
                            event.replacementContent,
                            messages,
                        )

                    AgentEvent.AssistantBlockKind.THINKING ->
                        runMessageProjector.finalizeThinkingBlock(
                            runId,
                            event.round,
                            event.index,
                            event.replacementContent,
                            messages,
                        )

                    AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                }
            }

            is AgentEvent.UsageReceived -> {
                val assistantPrefix = "assistant-$runId-${event.round}"
                val usage = TokenUsageUi(
                    contextTokens = event.usage.contextTokens,
                    inputTokens = event.usage.inputTokens,
                    outputTokens = event.usage.outputTokens,
                    reasoningTokens = event.usage.reasoningTokens,
                    cachedTokens = event.usage.cachedTokens,
                )
                val targetIndex = messages.indexOfLast { message ->
                    message is AgentMessageUi &&
                        (message.id == assistantPrefix || message.id.startsWith("$assistantPrefix-"))
                }
                messages = messages.mapIndexed { index, message ->
                    if (index == targetIndex && message is AgentMessageUi) {
                        message.copy(usage = usage)
                    } else {
                        message
                    }
                }
            }

            is AgentEvent.UserSupplementReceived -> {
                val id = "user-$runId-supplement-${event.index}"
                if (messages.none { it.id == id }) {
                    messages = messages + UserMessageUi(id = id, content = event.text)
                }
            }

            is AgentEvent.ToolStarted -> {
                status = EtaVoiceStatus.RunningTool(event.name)
                messages = runMessageProjector.startTool(
                    runId,
                    event,
                    runMessageProjector.finalizeTextRound(
                        runId,
                        event.round,
                        runMessageProjector.finalizeThinkingRound(runId, event.round, messages),
                    ),
                )
            }

            is AgentEvent.ToolFinished -> {
                messages = runMessageProjector.finishTool(runId, event, messages)
            }

            is AgentEvent.HostedToolStarted -> {
                status = EtaVoiceStatus.RunningTool(event.name)
                messages = runMessageProjector.startHostedTool(
                    runId,
                    event,
                    runMessageProjector.finalizeTextRound(
                        runId,
                        event.round,
                        runMessageProjector.finalizeThinkingRound(runId, event.round, messages),
                    ),
                )
            }

            is AgentEvent.HostedToolFinished -> {
                messages = runMessageProjector.finishHostedTool(runId, event, messages)
            }

            is AgentEvent.RunFailed -> {
                phase = EtaVoicePhase.ERROR
                status = EtaVoiceStatus.Failed(event.reason)
                messages = runMessageProjector.failRunningTools(
                    event.reason,
                    runMessageProjector.finalizeText(
                        runId,
                        runMessageProjector.finalizeThinking(runId, messages),
                    ),
                )
            }

            is AgentEvent.AssistantReceived -> {
                if (event.reasoningContent.isNotBlank()) {
                    messages = runMessageProjector.ensureCompletedThinking(
                        runId = runId,
                        round = event.round,
                        content = event.reasoningContent,
                        messages = messages,
                    )
                }
            }

            is AgentEvent.RunFinished -> {
                messages = runMessageProjector.finalizeText(
                    runId,
                    runMessageProjector.finalizeThinking(runId, messages),
                )
            }

            is AgentEvent.ContextCompaction -> {
                val noticeId = "assistant-$runId-compaction-${event.operationId}"
                messages = messages.filterNot { it.id == noticeId } + SystemNoticeMessageUi(
                    id = noticeId,
                    code = SystemNoticeCode.ContextCompaction,
                    detail = event.displayMessage,
                    contextTokens = event.tokensAfter,
                    running = event.phase == AgentEvent.ContextCompaction.PHASE_STARTED,
                )
                status = EtaVoiceStatus.Reasoning
            }
            is AgentEvent.ModelRetryScheduled -> {
                messages = runMessageProjector.scheduleModelRetry(runId, event, messages)
                status = EtaVoiceStatus.Reasoning
            }
            is AgentEvent.ProviderRequestStarted -> status = EtaVoiceStatus.Reasoning
            is AgentEvent.RunStarted,
            is AgentEvent.ProviderResponseStarted,
            is AgentEvent.ToolImagesAttached,
            is AgentEvent.RoundStarted,
            -> Unit
        }
        return state.copy(messages = messages, phase = phase, status = status)
    }

    private fun finishRunMessages(
        runId: String,
        result: AgentRuntimeWire.RunResult,
    ): List<AgentChatMessageUi> {
        var messages = runMessageProjector.finalizeText(
            runId,
            runMessageProjector.finalizeThinking(runId, uiState.messages),
        )
        if (!result.ok) {
            messages = runMessageProjector.failRunningTools(
                result.error ?: SYNTHETIC_RUNTIME_FAILED,
                messages,
            )
        }
        val notice = when {
            result.ok && result.content.isBlank() -> SystemNoticeCode.EmptyResult
            !result.ok && result.error == LEGACY_STOPPED_ERROR -> SystemNoticeCode.Stopped
            !result.ok -> SystemNoticeCode.RuntimeFailed
            else -> null
        }
        val lastAssistantIndex = AgentRunMessageProjector.resultTargetIndex(runId, messages)
        messages = if (lastAssistantIndex >= 0) {
            val targetRound = (messages[lastAssistantIndex] as AgentMessageUi).id
                .assistantRound(runId)
            val sameRoundBlocks = targetRound?.let { round ->
                messages.count { message ->
                    message is AgentMessageUi && message.id.assistantRound(runId) == round
                }
            } ?: 0
            messages.mapIndexed { index, message ->
                if (index == lastAssistantIndex && message is AgentMessageUi) {
                    if (notice == null) {
                        message.copy(
                            content = if (sameRoundBlocks <= 1) {
                                result.content
                            } else {
                                message.content.ifBlank { result.content }
                            },
                            isStreaming = false,
                            renderMarkdown = true,
                        )
                    } else {
                        SystemNoticeMessageUi(
                            id = message.id,
                            code = notice,
                            detail = result.error.takeIf { notice == SystemNoticeCode.RuntimeFailed },
                        )
                    }
                } else {
                    message
                }
            }
        } else {
            if (notice == null) {
                messages + AgentMessageUi(
                    id = AgentRunMessageProjector.resultFallbackId(runId, messages),
                    content = result.content,
                    isStreaming = false,
                    renderMarkdown = true,
                )
            } else {
                messages + SystemNoticeMessageUi(
                    id = AgentRunMessageProjector.resultFallbackId(runId, messages),
                    code = notice,
                    detail = result.error.takeIf { notice == SystemNoticeCode.RuntimeFailed },
                )
            }
        }
        runMessageProjector.clearRun(runId)
        return messages
    }

    private fun String.assistantRound(runId: String): Int? {
        val prefix = "assistant-$runId-"
        return removePrefix(prefix)
            .takeIf { it != this }
            ?.substringBefore('-')
            ?.toIntOrNull()
    }

    private fun stopCurrentRun() {
        val runId = activeRunId
        if (runId != null) {
            activeRunId = null
            requestRuntimeCancellation(runId)
            runJob?.cancel()
            runJob = null
            uiState = uiState.copy(
                phase = EtaVoicePhase.READY,
                status = EtaVoiceStatus.Stopped,
                messages = runMessageProjector.failRunningTools(
                    SYNTHETIC_STOPPED,
                    runMessageProjector.finalizeText(
                        runId,
                        runMessageProjector.finalizeThinking(runId, uiState.messages),
                    ),
                ),
            )
            runMessageProjector.clearRun(runId)
            updateSoftInput(visible = true)
            inputFocusRequestKey++
        } else {
            dismissAndStop()
        }
    }

    private fun cancelCurrentRun() {
        val runId = activeRunId ?: return
        activeRunId = null
        requestRuntimeCancellation(runId)
        runJob?.cancel()
        runJob = null
    }

    private fun requestRuntimeCancellation(runId: String) {
        runCatching {
            cancellationExecutor.execute { runtimeClient.cancelRun(runId) }
        }
    }

    private fun startVoiceListening() {
        if (voiceListening || activeRunId != null) return
        val recognizer = SystemSpeechRecognizer.create(this)
        if (recognizer == null) {
            uiState = uiState.copy(
                phase = EtaVoicePhase.ERROR,
                status = EtaVoiceStatus.Failed("语音识别服务不可用"),
            )
            return
        }
        voiceListening = true
        voiceTurnSerial++
        val serial = voiceTurnSerial
        speechRecognizer = recognizer
        uiState = uiState.copy(
            phase = EtaVoicePhase.READY,
            status = EtaVoiceStatus.Listening,
        )
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                scope.launch(Dispatchers.Main.immediate) {
                    if (serial != voiceTurnSerial) return@launch
                    handleVoiceError(error)
                }
            }
            override fun onResults(results: Bundle?) {
                scope.launch(Dispatchers.Main.immediate) {
                    if (serial != voiceTurnSerial) return@launch
                    handleVoiceResults(results)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer.startListening(intent)
    }

    private fun stopVoiceListening() {
        voiceListening = false
        val recognizer = speechRecognizer
        speechRecognizer = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        if (!voiceSessionMode) {
            uiState = uiState.copy(status = EtaVoiceStatus.InputRequest)
        }
    }

    private fun handleVoiceResults(results: Bundle?) {
        voiceListening = false
        val texts = results?.getStringArrayList(RecognizerIntent.EXTRA_RESULTS)
        val text = texts?.firstOrNull()?.trim().orEmpty()
        if (text.isBlank()) {
            speechRecognizer?.destroy()
            speechRecognizer = null
            if (voiceSessionMode) exitVoiceSession()
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = null
        inputText = text
        submitPrompt(text)
    }

    private fun handleVoiceError(error: Int) {
        voiceListening = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (voiceSessionMode) {
                    exitVoiceSession()
                } else {
                    uiState = uiState.copy(status = EtaVoiceStatus.InputRequest)
                }
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                if (voiceSessionMode) scheduleNextVoiceTurn()
            }
            else -> {
                if (voiceSessionMode) {
                    exitVoiceSession()
                } else {
                    uiState = uiState.copy(
                        phase = EtaVoicePhase.ERROR,
                        status = EtaVoiceStatus.Failed("语音识别错误 #$error"),
                    )
                }
            }
        }
    }

    private fun toggleVoiceListening() {
        if (voiceListening) {
            stopVoiceListening()
        } else {
            startVoiceListening()
        }
    }

    private fun scheduleNextVoiceTurn() {
        scope.launch {
            delay(VOICE_TURN_GAP_MS)
            withContext(Dispatchers.Main.immediate) {
                if (voiceSessionMode && activeRunId == null && !voiceListening && windowView != null) {
                    startVoiceListening()
                }
            }
        }
    }

    private fun exitVoiceSession() {
        voiceSessionMode = false
        voiceListening = false
        val recognizer = speechRecognizer
        speechRecognizer = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        showResultNotificationIfAny()
        removeWindow()
        stopSelf()
        resumeHotwordListening()
    }

    /** 若存在最后一轮结果，发通知后台展示并清空；无结果则不动作。 */
    private fun showResultNotificationIfAny() {
        val content = lastResultContent.trim()
        if (content.isBlank()) return
        lastResultContent = ""
        runCatching { showResultNotification(content) }
    }

    private fun showResultNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                RESULT_CHANNEL_ID,
                getString(R.string.assistant_result_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = content
            .replace(Regex("\n{2,}"), "\n")
            .trim()
            .take(500)
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.assistant_result_notification_title))
            .setContentText(summary.take(120).ifBlank { content.take(120) })
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun updateSoftInput(visible: Boolean) {
        val wm = windowManager ?: return
        val view = windowView ?: return
        val params = windowParams ?: return
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
            if (visible) {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            } else {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            }
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun selectScreenContext() {
        uiState = uiState.copy(
            screenContext = EtaScreenContextStateReducer.select(
                state = uiState.screenContext,
                enabled = activeRunId == null,
                hasAttachment = screenContextAttachment != null,
            ),
        )
    }

    private fun removeScreenContext() {
        uiState = uiState.copy(
            screenContext = EtaScreenContextStateReducer.remove(
                state = uiState.screenContext,
                enabled = activeRunId == null,
            ),
        )
    }

    private fun hideForForegroundOperation(onComplete: ((Boolean) -> Unit)? = null) {
        hiddenForForegroundOperation = true
        EtaVoiceInteractionSession.requestHideForForegroundOperation(this)
        removeWindow(onComplete)
    }

    private fun removeWindow(onComplete: ((Boolean) -> Unit)? = null) {
        unregisterSystemBackCallback()
        detachingWindowView?.let { detachingView ->
            onComplete?.let(windowDetachCallbacks::add)
            if (!detachingView.isAttachedToWindow) {
                finishWindowDetach(success = true)
            }
            return
        }

        val view = windowView
        val wm = windowManager
        if (view == null || wm == null || !view.isAttachedToWindow) {
            windowView = null
            windowParams = null
            windowManager = null
            onComplete?.invoke(true)
            return
        }

        onComplete?.let(windowDetachCallbacks::add)
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                finishWindowDetach(success = true)
            }
        }
        detachingWindowView = view
        view.addOnAttachStateChangeListener(attachListener)

        val removed = runCatching {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
            wm.removeView(view)
            true
        }.getOrElse { throwable ->
            view.removeOnAttachStateChangeListener(attachListener)
            AndroidAgentLogger.warnThrottled("eta_assistant_overlay_remove_failed") {
                "Eta assistant overlay removeView failed: type=${throwable.javaClass.simpleName}"
            }
            false
        }
        if (!removed) {
            finishWindowDetach(success = false)
            return
        }

        windowView = null
        windowParams = null
        windowManager = null
        if (!view.isAttachedToWindow) {
            view.removeOnAttachStateChangeListener(attachListener)
            finishWindowDetach(success = true)
        }
    }

    private fun finishWindowDetach(success: Boolean) {
        detachingWindowView = null
        val callbacks = windowDetachCallbacks.toList()
        windowDetachCallbacks.clear()
        callbacks.forEach { callback -> callback(success) }
    }

    private fun dismissAndStop() {
        entryGeneration++
        entryCaptureJob?.cancel()
        entryCaptureJob = null
        screenContextAttachment = null
        voiceSessionMode = false
        voiceListening = false
        pendingInitialQuery = null
        val recognizer = speechRecognizer
        speechRecognizer = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        cancelCurrentRun()
        removeWindow()
        stopSelf()
        resumeHotwordListening()
    }

    /** 语音会话结束后恢复语音唤醒监听（麦克风交还唤醒服务）。 */
    private fun resumeHotwordListening() {
        if (!Prefs.isEnabled(Prefs.Keys.ETA_HOTWORD_ENABLED)) return
        runCatching { EtaHotwordService.resumeHotword(this) }
    }

    private fun openConversation() {
        if (handoffInProgress || activeRunId != null || uiState.messages.isEmpty()) return
        handoffInProgress = true
        AndroidAgentLogger.info("Eta assistant handoff requested")
        updateSoftInput(visible = false)
        val intent = Intent(this, MainActivity::class.java)
            .setAction(ACTION_OPEN_CONVERSATION)
            .putExtra(EXTRA_CONVERSATION_KEY, conversationKey)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        val creatorOptions = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                pendingIntentCreatorBackgroundActivityStartMode =
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            HANDOFF_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorOptions.toBundle(),
        )
        val senderOptions = ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode =
                if (Build.VERSION.SDK_INT >= 36) {
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                } else {
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
        }
        runCatching { pendingIntent.send(senderOptions.toBundle()) }
            .onFailure {
                handoffInProgress = false
                AndroidAgentLogger.warn("Eta assistant handoff activity launch failed")
                return
            }
        scope.launch(Dispatchers.Main.immediate) {
            delay(HANDOFF_TIMEOUT_MS)
            if (handoffInProgress) {
                AndroidAgentLogger.warn("Eta assistant handoff timed out waiting for chat")
                handoffInProgress = false
            }
        }
    }

    private fun finishHandoff() {
        if (!handoffInProgress) return
        if (handoffExitRequested) return
        AndroidAgentLogger.info("Eta assistant handoff chat ready")
        handoffExitRequested = true
        scope.launch(Dispatchers.Main.immediate) {
            delay(HANDOFF_EXIT_DURATION_MS)
            handoffInProgress = false
            removeWindow()
            stopSelf()
        }
    }

    internal companion object {
        const val ACTION_SHOW = "io.github.mangi.eta.agent.voice.SHOW"
        const val ACTION_OPEN_CONVERSATION = "io.github.mangi.eta.agent.voice.OPEN_CONVERSATION"
        const val EXTRA_VOICE_MODE = "io.github.mangi.eta.agent.voice.extra.VOICE_MODE"
        const val EXTRA_INITIAL_QUERY = "io.github.mangi.eta.agent.voice.extra.SPOKEN_QUERY"
        const val EXTRA_CONVERSATION_KEY = "io.github.mangi.eta.agent.voice.extra.CONVERSATION_KEY"
        private const val ACTION_HANDOFF_READY = "io.github.mangi.eta.agent.voice.HANDOFF_READY"
        private const val HANDOFF_TIMEOUT_MS = 5_000L
        private const val HANDOFF_EXIT_DURATION_MS = 220L
        private const val HANDOFF_REQUEST_CODE = 0x455441
        private const val VOICE_TURN_GAP_MS = 700L
        private const val RESULT_CHANNEL_ID = "eta_assistant_result"
        private const val RESULT_NOTIFICATION_ID = 0xE7A2
        private const val LEGACY_STOPPED_ERROR = "已停止"
        private const val SYNTHETIC_STOPPED = "eta_status:stopped"
        private const val SYNTHETIC_RUNTIME_FAILED = "eta_status:runtime_failed"
        private const val FOREGROUND_DISMISS_TIMEOUT_MS = 2_000L
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var activeService: EtaAssistantOverlayService? = null

        /**
         * Eta 自己拥有入口浮层，直接关闭并等待具体 View detach；不能按包名猜测，
         * 因为入口、Runtime 与结果浮层都属于同一个包。
         */
        fun dismissForForegroundOperation(context: Context): Boolean {
            val service = activeService
            if (service == null) {
                EtaVoiceInteractionSession.requestHideForForegroundOperation(context)
                return true
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.hideForForegroundOperation()
                return service.windowView == null && service.detachingWindowView == null
            }

            val completed = CountDownLatch(1)
            val dismissed = AtomicBoolean(false)
            mainHandler.post {
                val current = activeService
                if (current == null) {
                    EtaVoiceInteractionSession.requestHideForForegroundOperation(context)
                    dismissed.set(true)
                    completed.countDown()
                } else if (current !== service) {
                    completed.countDown()
                } else {
                    current.hideForForegroundOperation { success ->
                        dismissed.set(success)
                        completed.countDown()
                    }
                }
            }
            return try {
                completed.await(FOREGROUND_DISMISS_TIMEOUT_MS, TimeUnit.MILLISECONDS) &&
                    dismissed.get()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        fun show(context: Context, voiceMode: Boolean = false, initialQuery: String? = null) {
            val intent = Intent(context.applicationContext, EtaAssistantOverlayService::class.java)
                .setAction(ACTION_SHOW)
                .putExtra(EXTRA_VOICE_MODE, voiceMode)
            if (!initialQuery.isNullOrBlank()) {
                intent.putExtra(EXTRA_INITIAL_QUERY, initialQuery)
            }
            context.applicationContext.startService(intent)
        }

        fun dismiss(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, EtaAssistantOverlayService::class.java),
            )
        }

        fun notifyHandoffReady(context: Context) {
            context.applicationContext.startService(
                Intent(context.applicationContext, EtaAssistantOverlayService::class.java)
                    .setAction(ACTION_HANDOFF_READY),
            )
        }
    }
}

private data class EtaScreenContextAttachment(
    val image: AgentModelClient.ModelImage,
    val previewDataUrl: String,
)
