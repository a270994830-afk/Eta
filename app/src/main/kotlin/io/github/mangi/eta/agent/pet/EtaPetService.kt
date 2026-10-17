package io.github.mangi.eta.agent.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 桌面宠物悬浮窗：一个前台服务 + 一个 WRAP_CONTENT 的 TYPE_APPLICATION_OVERLAY 窗口。
 *
 * 情绪/台词都由 [AgentPet] 的 StateFlow 驱动（工具、运行状态、设置页共用同一份状态），
 * 服务只负责窗口与交互：点击打开 Eta，拖动保存位置。
 */
internal class EtaPetService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var petView: ComposeView? = null
    private var petParams: WindowManager.LayoutParams? = null

    private val savePositionRunnable = Runnable {
        petParams?.let { AgentPet.savePosition(it.x, it.y) }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        AgentPet.resetMoodFromPrefs()
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                removePetWindow()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> showPetWindow()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(savePositionRunnable)
        removePetWindow()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private fun showPetWindow() {
        if (petView != null) return
        val wm = (getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return
        windowManager = wm
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@EtaPetService)
            setViewTreeSavedStateRegistryOwner(this@EtaPetService)
            setContent {
                val mood by AgentPet.mood.collectAsState()
                val speech by AgentPet.speech.collectAsState()
                AgentOverlayPet(
                    mood = mood,
                    speech = speech,
                    onTap = { openEta() },
                    onDrag = { dx, dy -> applyDrag(dx, dy) },
                    onDragEnd = { snapToEdge() },
                    onLongPress = { hideFromPet() },
                )
            }
        }
        val params = layoutParams()
        runCatching { wm.addView(view, params) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("pet_add_view_failed") {
                "Agent pet addView failed: ${throwable.javaClass.simpleName}"
            }
            petView = null
            petParams = null
            stopSelf()
            return
        }
        petView = view
        petParams = params
    }

    private fun removePetWindow() {
        petView?.let { view -> runCatching { windowManager?.removeView(view) } }
        petView = null
        petParams = null
    }

    private fun applyDrag(dx: Float, dy: Float) {
        val lp = petParams ?: return
        val wm = windowManager ?: return
        val view = petView ?: return
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        runCatching { wm.updateViewLayout(view, lp) }
        mainHandler.removeCallbacks(savePositionRunnable)
        mainHandler.postDelayed(savePositionRunnable, 400L)
    }

    /** 松手后吸附到最近的左右边缘，并立即保存位置（避免半途停在屏幕中间）。 */
    private fun snapToEdge() {
        val lp = petParams ?: return
        val wm = windowManager ?: return
        val view = petView ?: return
        mainHandler.removeCallbacks(savePositionRunnable)
        val metrics = resources.displayMetrics
        val width = view.width.takeIf { it > 0 } ?: (metrics.density * 84f).toInt()
        val edgeGap = (metrics.density * 8f).toInt()
        lp.x = if (lp.x + width / 2 >= metrics.widthPixels / 2) {
            (metrics.widthPixels - width - edgeGap).coerceAtLeast(0)
        } else {
            edgeGap
        }
        runCatching { wm.updateViewLayout(view, lp) }
        AgentPet.savePosition(lp.x, lp.y)
    }

    /** 长按桌宠 = 关掉它（等价于设置页把开关关掉，下次可在设置里再打开）。 */
    private fun hideFromPet() {
        AgentPet.setEnabled(this, false)
        removePetWindow()
        stopSelf()
    }

    private fun openEta() {
        val intent = Intent().apply {
            setClassName(packageName, MAIN_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(intent) }
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val (savedX, savedY) = AgentPet.position()
        val metrics = resources.displayMetrics
        val fallbackY = (metrics.heightPixels * 0.62f).toInt()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (savedX == Int.MIN_VALUE) (metrics.widthPixels - 260).coerceAtLeast(0) else savedX
            y = if (savedY == Int.MIN_VALUE) fallbackY else savedY
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "桌面宠物", NotificationManager.IMPORTANCE_MIN).apply {
                description = "显示桌宠悬浮窗时使用"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent().apply {
                setClassName(packageName, MAIN_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Eta 桌宠")
            .setContentText("桌宠在屏幕上陪你")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        const val ACTION_SHOW = "io.github.mangi.eta.pet.action.SHOW"
        const val ACTION_HIDE = "io.github.mangi.eta.pet.action.HIDE"
        private const val CHANNEL_ID = "eta_pet"
        private const val NOTIFICATION_ID = 0x7E74
        private const val MAIN_ACTIVITY = "io.github.mangi.eta.ui.MainActivity"

        fun show(context: Context) {
            val intent = Intent(context, EtaPetService::class.java).setAction(ACTION_SHOW)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** 隐藏：先给服务发 HIDE（清窗口），再兜底 stopService。 */
        fun hide(context: Context) {
            val intent = Intent(context, EtaPetService::class.java).setAction(ACTION_HIDE)
            runCatching { context.startService(intent) }
            runCatching { context.stopService(Intent(context, EtaPetService::class.java)) }
        }

        fun refresh(context: Context) {
            if (!AgentPet.isEnabled()) {
                hide(context)
                return
            }
            show(context)
        }
    }
}
