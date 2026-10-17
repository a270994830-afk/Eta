package io.github.mangi.eta.agent.device

import java.io.File

/**
 * 定位可用的 su 可执行文件。
 *
 * 背景：部分设备/ROM（例如 Magisk 30700 + 某些 HyperOS 版本）不会在应用进程的 PATH 里
 * 放 `su`，只在 `/debug_ramdisk/su` 等处提供符号链接。只按 PATH 查找会误判为「无 Root」，
 * 使影子屏、文件工具、root shell 等全部不可用。
 */
internal object RootSuResolver {
    private val FALLBACK_CANDIDATES = listOf(
        "/debug_ramdisk/su",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/su/bin/su",
        "/magisk/.core/bin/su",
        "/data/adb/magisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
    )

    @Volatile
    private var cached: String? = null

    @Volatile
    private var resolved = false

    /** 返回可执行的 su 绝对路径；找不到返回 null。结果缓存。 */
    fun resolve(): String? {
        if (resolved) return cached
        synchronized(this) {
            if (resolved) return cached
            cached = resolveUncached()
            resolved = true
            return cached
        }
    }

    /** 供 ProcessBuilder 使用：解析不到时退回裸 "su"（保留原 PATH 查找行为）。 */
    fun command(): String = resolve() ?: "su"

    /** 单测/调试用：清空缓存。 */
    fun invalidate() {
        synchronized(this) {
            cached = null
            resolved = false
        }
    }

    private fun resolveUncached(): String? {
        val fromPath = System.getenv("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { File(it, "su") }
            .firstOrNull { it.isFile && it.canExecute() }
        if (fromPath != null) return fromPath.absolutePath
        return FALLBACK_CANDIDATES
            .firstOrNull { candidate -> File(candidate).let { it.isFile && it.canExecute() } }
    }
}
