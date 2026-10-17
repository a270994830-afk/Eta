package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shizuku 工具的模型可见声明（对齐 Operit 的 DEBUGGER 权限层级）。
 *
 * Shizuku 是免 Root 的 ADB/Root 特权通道：可用后无需 su 即可执行 adb shell 级别的命令
 * （uid 2000），若 Shizuku 由 root 启动则等价 root（uid 0）。首次使用需用户授权。
 */
internal object AgentShizukuToolCatalog {
    val TOOL_NAMES: Set<String> = setOf(
        "shizuku_status",
        "shizuku_request_permission",
        "shizuku_shell",
        "shizuku_list_apps",
    )

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "shizuku_status",
                    description = "查询 Shizuku（免 Root 的 ADB/Root 特权通道）状态：是否可用、是否已授权、" +
                        "服务版本、后端（adb=uid2000 / root=uid0）、当前 uid 与 SELinux 上下文。" +
                        "调用 shizuku_shell 前可先用它判断是否需要先申请权限。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                        .put("required", JSONArray()),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "shizuku_request_permission",
                    description = "申请 Shizuku 权限，会拉起授权界面。返回发起状态；" +
                        "授权结果是异步回调，需要 App 层注册 Shizuku.addRequestPermissionResultListener 才能收到，" +
                        "否则只能用 shizuku_status 轮询。已授权时直接返回 granted=true。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "request_code",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "授权请求码，默认 4001，用于回调匹配。"),
                            ),
                        )
                        .put("required", JSONArray()),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "shizuku_shell",
                    description = "以 Shizuku 特权身份（adb shell uid 2000，或 root uid 0）执行一条 shell 命令，" +
                        "等价于 adb shell。返回 stdout/stderr/exit_code；stdout、stderr 各最多 256KB，超出即截断。" +
                        "需要已获得 Shizuku 权限，否则返回 SHIZUKU_PERMISSION_REQUIRED。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "command",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "要执行的命令，会以 `sh -c` 执行，可使用管道与重定向。"),
                                )
                                .put(
                                    "timeout_seconds",
                                    JSONObject()
                                        .put("type", "number")
                                        .put("minimum", 1)
                                        .put("maximum", 300)
                                        .put("description", "超时秒数，默认 30，最大 300；超时会销毁子进程并置 timed_out=true。"),
                                ),
                        )
                        .put("required", JSONArray().put("command")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "shizuku_list_apps",
                    description = "通过 Shizuku 特权通道执行 `pm list packages`，返回已安装应用包名列表（可按关键字过滤），" +
                        "用于验证特权通道是否打通。其可见范围比普通应用更全。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "filter",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "可选，包名包含该关键字才返回（不区分大小写）。"),
                                )
                                .put(
                                    "timeout_seconds",
                                    JSONObject()
                                        .put("type", "number")
                                        .put("minimum", 1)
                                        .put("maximum", 300)
                                        .put("description", "超时秒数，默认 30。"),
                                ),
                        )
                        .put("required", JSONArray()),
                ),
            )
    }
}
