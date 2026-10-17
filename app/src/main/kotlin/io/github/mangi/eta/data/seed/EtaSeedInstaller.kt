package io.github.mangi.eta.data.seed

import android.content.Context
import android.os.Environment
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 首启种子安装器：把 assets/eta_seed.zip 中的技能、记忆、设置与脚本释放到各自持久化位置
 * （app 私有目录与共享存储），使新设备安装后即具备完整技能库与浙食链工具链。
 *
 * 所有条目按「目标不存在才写」处理，只补齐缺失、不覆盖用户后续改动；核心目录与共享存储
 * 各自记录版本标记，共享存储因权限尚未授权而写入失败时留待下次启动重试。
 */
internal object EtaSeedInstaller {
    private const val ASSET_NAME = "eta_seed.zip"
    private const val SEED_VERSION = "2026-10-12"
    private const val CORE_MARKER = ".eta_seed_core"
    private const val SDCARD_MARKER = ".eta_seed_sdcard"
    private const val FILES_PREFIX = "files/"
    private const val PREFS_PREFIX = "shared_prefs/"
    private const val DATABASES_PREFIX = "databases/"
    private const val SDCARD_PREFIX = "sdcard/"
    private const val SHARED_DIR = "Download/智能体"

    fun applyIfNeeded(context: Context) {
        val coreMarker = File(context.filesDir, CORE_MARKER)
        val sdcardMarker = File(context.filesDir, SDCARD_MARKER)
        val needCore = readVersion(coreMarker) != SEED_VERSION
        val needSdcard = readVersion(sdcardMarker) != SEED_VERSION
        if (!needCore && !needSdcard) return

        val prefsDir = File(context.dataDir, "shared_prefs")
        val databasesDir = File(context.dataDir, "databases")
        val sharedRoot = File(Environment.getExternalStorageDirectory(), SHARED_DIR)

        runCatching {
            context.assets.open(ASSET_NAME).use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!entry.isDirectory && name.isNotBlank()) {
                            val target = when {
                                name.startsWith(SDCARD_PREFIX) ->
                                    if (needSdcard) File(sharedRoot, name.removePrefix(SDCARD_PREFIX)) else null
                                !needCore -> null
                                name.startsWith(FILES_PREFIX) ->
                                    File(context.filesDir, name.removePrefix(FILES_PREFIX))
                                name.startsWith(PREFS_PREFIX) ->
                                    File(prefsDir, name.removePrefix(PREFS_PREFIX))
                                name.startsWith(DATABASES_PREFIX) ->
                                    File(databasesDir, name.removePrefix(DATABASES_PREFIX))
                                else -> null
                            }
                            if (target != null) writeIfAbsent(zip, target)
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        }.onFailure {
            AndroidAgentLogger.warn("Eta seed install failed: type=${it.javaClass.simpleName}")
        }

        if (needCore && readVersion(coreMarker) != SEED_VERSION) {
            runCatching { coreMarker.writeText(SEED_VERSION) }
        }
        if (needSdcard && sharedRoot.isDirectory && sharedRoot.canWrite()) {
            runCatching { sdcardMarker.writeText(SEED_VERSION) }
        }
    }

    /** 目标已存在则跳过，否则写入并返回目标文件。 */
    private fun writeIfAbsent(zip: ZipInputStream, target: File) {
        if (target.exists()) return
        runCatching {
            target.parentFile?.mkdirs()
            target.outputStream().use { zip.copyTo(it) }
        }
    }

    private fun readVersion(marker: File): String? =
        runCatching { marker.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
}
