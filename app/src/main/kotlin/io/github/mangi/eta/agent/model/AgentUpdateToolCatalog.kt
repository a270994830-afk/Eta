package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** OTA 更新工具族（下载/校验/安装）的 JSON Schema 声明；执行逻辑见 agent/tool/AgentUpdateToolSet.kt。 */
internal object AgentUpdateToolCatalog {

    val TOOL_NAMES: Set<String> = setOf(
        "update_download",
        "update_verify",
        "update_install",
    )

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "update_download",
                    description = "下载 Eta 的新版本 APK 到本地。url 可留空，留空时自动查询 GitHub 最新 Release 里第一个 .apk 资产的下载地址；destination 也可留空，默认保存到 /sdcard/Download/智能体/eta-update-<版本号>.apk。下载大小上限 300MB，超时 10 分钟。完成后返回本地路径、字节数与文件 md5。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "url",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "APK 下载地址，必须 http/https。留空则查询 api.github.com 上 Mangi-11/Eta 的最新 Release 并取其中 .apk 资产。")
                                )
                                .put(
                                    "destination",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "本地保存路径（含文件名，需以 .apk 结尾）。留空默认 /sdcard/Download/智能体/eta-update-<版本号>.apk。")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "update_verify",
                    description = "校验一个本地文件是否是合法 APK：检查 ZIP 魔数、是否含 AndroidManifest.xml，并读出包名、versionName、versionCode，以及是否比本机已安装的同名应用更新。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "path",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "要校验的 APK 文件路径。")
                                )
                        )
                        .put("required", JSONArray().put("path"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "update_install",
                    description = "安装一个本地 APK。优先尝试用 su 静默安装（pm install -r -d）；没有 root 或静默安装失败时，改为拉起系统安装器让用户在界面上确认。无 root 时若系统拦截 file:// 会返回 NEEDS_USER_INSTALL 并附带文件路径，需用户手动安装。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "path",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "要安装的 APK 文件路径。")
                                )
                        )
                        .put("required", JSONArray().put("path"))
                )
            )
    }
}
