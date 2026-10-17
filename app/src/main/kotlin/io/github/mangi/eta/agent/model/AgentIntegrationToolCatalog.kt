package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 集成/系统工具族的 JSON Schema 声明；执行逻辑见 agent/tool/AgentIntegrationToolSet.kt。 */
internal object AgentIntegrationToolCatalog {

    val TOOL_NAMES: Set<String> = setOf(
        "http_request",
        "install_app",
        "uninstall_app",
        "list_installed_apps",
        "execute_intent",
        "send_broadcast",
        "toast",
        "send_notification",
        "calculate",
        "update_check",
    )

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "http_request",
                    description = "发起一次 HTTP 请求，返回状态码、响应头摘要与截断后的正文。只允许 http/https 地址（file:// 等 scheme 会被拒绝）。适合调用外部 API、拉取接口数据。默认 GET，超时默认 30 秒，正文默认最多返回 65536 字节。GET 不接受请求体。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "method",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("GET")
                                                .put("POST")
                                                .put("PUT")
                                                .put("PATCH")
                                                .put("DELETE")
                                        )
                                        .put("description", "HTTP 方法，默认 GET。")
                                )
                                .put(
                                    "url",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "完整请求地址，必须以 http:// 或 https:// 开头。")
                                )
                                .put(
                                    "headers",
                                    JSONObject()
                                        .put("type", "object")
                                        .put("description", "请求头键值对，例如 {\"Authorization\":\"Bearer xxx\",\"Content-Type\":\"application/json\"}。Host、Content-Length 等由系统管理的头会被忽略。")
                                )
                                .put(
                                    "body",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "请求体原文，用于 POST/PUT/PATCH/DELETE。未指定 Content-Type 时默认按 application/json 发送。")
                                )
                                .put(
                                    "timeout_seconds",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "超时秒数，1 到 120，默认 30。")
                                )
                                .put(
                                    "max_bytes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "正文最多返回的字节数，1024 到 262144，默认 65536；超出部分截断并标记 truncated。")
                                )
                        )
                        .put("required", JSONArray().put("url"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "install_app",
                    description = "安装 APK。优先用 Root 静默安装（pm install -r）；apk_path 与 url 二选一，url 会先下载到应用缓存再安装。无 Root 时只有声明了 FileProvider 才能调起系统安装器；设备无 Root 且没有 FileProvider 时返回明确错误，不会静默失败。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "apk_path",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "设备上已有的 APK 路径，例如 /sdcard/Download/app.apk。与 url 二选一。")
                                )
                                .put(
                                    "url",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "要下载并安装的 APK 地址（http/https）。与 apk_path 二选一。")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "uninstall_app",
                    description = "卸载应用（Root 的 pm uninstall --user 0）。只卸载指定包，不能卸载 Eta 自身；无 Root 时返回说明。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "要卸载的应用包名，例如 com.tencent.mm。")
                                )
                        )
                        .put("required", JSONArray().put("package_name"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "list_installed_apps",
                    description = "列出设备上已安装的应用，返回名称、包名与是否系统应用。可用 filter 按名称或包名子串筛选，用于找包名、确认应用是否存在。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "filter",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "按应用名称或包名的子串筛选，不区分大小写。留空返回全部。")
                                )
                                .put(
                                    "include_system",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "是否包含系统应用，默认 false 只列第三方应用。")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多返回条数，1 到 200，默认 50。")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "execute_intent",
                    description = "构造并发送一个 Android Intent（startActivity），可指定 action、uri、package、component、extras 与 flags。已自动附加 FLAG_ACTIVITY_NEW_TASK。会直接执行，包括 ACTION_CALL 等敏感 action，返回值里会带 warning 提示。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Intent action，例如 android.intent.action.VIEW、android.intent.action.CALL。")
                                )
                                .put(
                                    "uri",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "可选数据 URI，例如 https://example.com、tel:10086、content://...。")
                                )
                                .put(
                                    "package",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "可选目标包名；限定只由该应用处理。")
                                )
                                .put(
                                    "component",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "可选显式组件 pkg/Class 或 pkg/.Class。")
                                )
                                .put(
                                    "extras",
                                    JSONObject()
                                        .put("type", "object")
                                        .put("description", "附加参数键值对；字符串、数字、布尔按原生类型写入，对象与数组转成 JSON 字符串写入。")
                                )
                                .put(
                                    "flags",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("items", JSONObject().put("type", "string"))
                                        .put(
                                            "description",
                                            "附加 flag 名称数组，支持 FLAG_ACTIVITY_NEW_TASK、FLAG_ACTIVITY_CLEAR_TOP、FLAG_ACTIVITY_SINGLE_TOP、FLAG_ACTIVITY_CLEAR_TASK、FLAG_ACTIVITY_NO_HISTORY、FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS、FLAG_ACTIVITY_NEW_DOCUMENT、FLAG_GRANT_READ_URI_PERMISSION、FLAG_GRANT_WRITE_URI_PERMISSION。无法识别的名称会被忽略并列在 ignored_flags 中。"
                                        )
                                )
                        )
                        .put("required", JSONArray().put("action"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "send_broadcast",
                    description = "发送一个广播（sendBroadcast），可指定 action、限定包名、显式组件与 extras。系统只回执「已发出」，不返回接收者数量。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "广播 action，例如 com.example.ACTION_REFRESH。")
                                )
                                .put(
                                    "package",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "可选限定包名，只发给该应用。")
                                )
                                .put(
                                    "explicit_component",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "可选显式接收器组件 pkg/Class 或 pkg/.Class。")
                                )
                                .put(
                                    "extras",
                                    JSONObject()
                                        .put("type", "object")
                                        .put("description", "广播附加参数键值对，类型映射同 execute_intent。")
                                )
                        )
                        .put("required", JSONArray().put("action"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "toast",
                    description = "在主线程弹出一个 Toast 提示，用于给用户轻量反馈。不会阻塞等待，返回即表示已投递。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "提示内容。")
                                )
                                .put(
                                    "duration",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("short").put("long"))
                                        .put("description", "显示时长：short 约 2 秒（默认），long 约 3.5 秒。")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "send_notification",
                    description = "发送一条系统通知（需要通知权限）。不指定 channel_id 时使用 Eta 自建通道 eta_agent_notes；不指定 id 时用随机 id，因此可发多条。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "title",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "通知标题。")
                                )
                                .put(
                                    "text",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "通知正文。")
                                )
                                .put(
                                    "channel_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "通知通道 id，默认 eta_agent_notes。")
                                )
                                .put(
                                    "id",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "通知 id（相同 id 会覆盖上一条），默认随机。")
                                )
                        )
                        .put("required", JSONArray().put("title").put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "calculate",
                    description = "计算数学表达式，支持 + - * / % ^ 与括号、小数，例如 (12+3)*4/5、2^10、7%3。使用内置求值器，不执行任何脚本；非法表达式返回错误。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "expression",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "要计算的表达式，例如 (12+3)*4/5 或 2^10。")
                                )
                        )
                        .put("required", JSONArray().put("expression"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "update_check",
                    description = "查询 Eta 的 GitHub Releases 是否有新版本，返回最新版本号、是否有新版、发布说明与下载链接。current_version 留空时用设备当前安装的版本比较。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "current_version",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "要比较的当前版本号，例如 3.0.6；留空则读取本机安装的版本。")
                                )
                        )
                )
            )
    }
}
