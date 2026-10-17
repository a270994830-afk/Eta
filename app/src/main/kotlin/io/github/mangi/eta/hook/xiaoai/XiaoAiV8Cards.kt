package io.github.mangi.eta.hook.xiaoai

import io.github.mangi.eta.core.HookSupport

/**
 * 超级小爱 8.2.3（508002003）的界面接缝。
 *
 * 承接旧版 FlowTemplateToastCard 链路的三个等价物（均经 8.2.3 字节码核对）：
 * - 结果卡：`com.xiaomi.voiceassistant.instruction.card.stream.b`（SMAP 显示其源码为 FlowTemplateToastCard，继承 card.a）
 * - 卡片入口：`com.xiaomi.voiceassistant.m0.getFloatManagerBridge()` 返回的浮窗桥（t5，addCard(card.a)）
 * - 对话 id：`m0.getOperationBridge().getDialogId()`（instruction.base.y）
 */
internal object XiaoAiV8Cards {
    /** FlowTemplateToastCard 在 8.2.3 的运行时名，构造器 (String) 即卡文本。 */
    const val CARD_CLASS = "com.xiaomi.voiceassistant.instruction.card.stream.b"

    private const val BUSINESS_APPLICATION_CLASS = "com.xiaomi.voiceassistant.m0"

    /** @return 直连渲染不可用的原因；全部接缝可用时返回 null。 */
    fun probeUnavailableReason(classLoader: ClassLoader): String? {
        val cardClass = HookSupport.findClassOrNull(classLoader, CARD_CLASS)
            ?: return "card class missing"
        val hasTextConstructor = cardClass.constructors.any { constructor ->
            constructor.parameterTypes.contentEquals(arrayOf(String::class.java))
        }
        if (!hasTextConstructor) return "card text constructor missing"
        val hasUpdate = HookSupport.findDeclaredMethods(cardClass, makeAccessible = true) { method ->
            method.name == "updateCardText"
        }.isNotEmpty()
        if (!hasUpdate) return "card updateCardText missing"
        val bridge = floatManagerBridge(classLoader) ?: return "float manager bridge unavailable"
        val visited = HashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        queue.add(bridge.javaClass)
        var addCardAvailable = false
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            if (current.declaredMethods.any { it.name == "addCard" && it.parameterCount == 1 }) {
                addCardAvailable = true
                break
            }
            current.superclass?.let(queue::add)
            current.interfaces.forEach(queue::add)
        }
        if (!addCardAvailable) return "bridge addCard missing"
        return null
    }

    fun floatManagerBridge(classLoader: ClassLoader): Any? = runCatching {
        Class.forName(BUSINESS_APPLICATION_CLASS, false, classLoader)
            .getMethod("getFloatManagerBridge")
            .invoke(null)
    }.getOrNull()

    fun operationBridge(classLoader: ClassLoader): Any? = runCatching {
        Class.forName(BUSINESS_APPLICATION_CLASS, false, classLoader)
            .getMethod("getOperationBridge")
            .invoke(null)
    }.getOrNull()

    /** 当前按键会话的 dialogId；取不到时返回空串（卡片仍可显示，只是不关联会话）。 */
    fun currentDialogId(classLoader: ClassLoader): String {
        val bridge = operationBridge(classLoader) ?: return ""
        return runCatching {
            bridge.javaClass.getMethod("getDialogId").invoke(bridge)?.toString().orEmpty()
        }.getOrDefault("")
    }

    /** 移除小爱的「思考中」加载卡（接管后其对话状态机不再走完，需主动清理）。 */
    fun clearLoadingCard(classLoader: ClassLoader) {
        runCatching {
            val helperClass = Class.forName(ACTION_UI_HELPER_CLASS, false, classLoader)
            val instance = helperClass.getField("a").get(null)
            helperClass.getMethod("removeLoadingCard").invoke(instance)
        }
    }

    private const val ACTION_UI_HELPER_CLASS = "eo1.q0"
}
