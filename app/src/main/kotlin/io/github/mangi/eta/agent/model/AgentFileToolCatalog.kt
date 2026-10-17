package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 文件工具族的 schema 声明；与 AgentFileToolSet 中的名字一一对应。 */
internal object AgentFileToolCatalog {
    val TOOL_NAMES: Set<String> = setOf(
        "file_exists",
        "file_move",
        "file_copy",
        "file_delete",
        "file_mkdir",
        "file_find",
        "file_grep",
        "file_edit",
        "file_zip",
        "file_unzip",
        "file_download",
    )

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "file_exists",
                    description = "检查路径是否存在，并返回类型（file/directory/other/missing）、字节大小与修改时间。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "path",
                                JSONObject().put("type", "string").put("description", "要检查的路径。"),
                            ),
                        )
                        .put("required", JSONArray().put("path"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "file_move",
                    description = "移动或重命名文件/目录。overwrite=true 时覆盖已存在的目标；跨设备时自动回退为复制后删除。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("source", JSONObject().put("type", "string").put("description", "源路径。"))
                                .put("destination", JSONObject().put("type", "string").put("description", "目标路径。"))
                                .put(
                                    "overwrite",
                                    JSONObject().put("type", "boolean")
                                        .put("description", "目标已存在时是否覆盖，默认 false。"),
                                ),
                        )
                        .put("required", JSONArray().put("source").put("destination"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "file_copy",
                    description = "复制文件或目录（目录递归复制）。overwrite=true 时覆盖已存在的目标。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("source", JSONObject().put("type", "string").put("description", "源路径。"))
                                .put("destination", JSONObject().put("type", "string").put("description", "目标路径。"))
                                .put(
                                    "overwrite",
                                    JSONObject().put("type", "boolean")
                                        .put("description", "目标已存在时是否覆盖，默认 false。"),
                                ),
                        )
                        .put("required", JSONArray().put("source").put("destination"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "file_delete",
                    description = "删除文件或目录。删除目录必须显式 recursive=true；拒绝对 /、/data、/sdcard 等危险根目录执行删除。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string").put("description", "要删除的路径。"))
                                .put(
                                    "recursive",
                                    JSONObject().put("type", "boolean")
                                        .put("description", "递归删除目录时必须为 true，默认 false。"),
                                ),
                        )
                        .put("required", JSONArray().put("path"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "file_mkdir",
                    description = "创建目录。parents=true 时递归创建多级父目录。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string").put("description", "要创建的目录路径。"))
                                .put(
                                    "parents",
                                    JSONObject().put("type", "boolean")
                                        .put("description", "是否递归创建父目录，默认 false。"),
                                ),
                        )
                        .put("required", JSONArray().put("path"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "file_find",
                    description = "在目录下按名称查找文件。name 支持 glob（* ? [ ]）或子串匹配；max_depth 限制递归深度，limit 限制返回数量。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string").put("description", "搜索起始目录。"))
                                .put(
                                    "name",
                                    JSONObject().put("type", "string")
                                        .put("description", "文件名匹配：glob 模式或子串。"),
                                )
                                .put(
                                    "max_depth",
                                    JSONObject().put("type", "integer")
                                        .put("description", "最大递归深度，默认 8，最大 64。"),
                                )
                                .put(
                                    "limit",
                                    JSONObject().put("type", "integer")
                                        .put("description", "最多返回的匹配数量，默认 200，最大 2000。"),
                                ),
                        )
                        .put("required", JSONArray().put("path").put("name"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "file_grep",
                    description = "在文本文件中按正则表达式搜索内容。glob 用于过滤文件名；ignore_case 忽略大小写；自动跳过二进制与超大文件。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string").put("description", "搜索的文件或目录。"))
                                .put("pattern", JSONObject().put("type", "string").put("description", "正则表达式。"))
                                .put(
                                    "glob",
                                    JSONObject().put("type", "string")
                                        .put("description", "文件名过滤，如 *.kt 或 *.log。"),
                                )
                                .put(
                                    "max_matches",
                                    JSONObject().put("type", "integer")
                                        .put("description", "最多返回的匹配数量，默认 100，最大 500。"),
                                )
                                .put(
                                    "ignore_case",
                                    JSONObject().put("type", "boolean")
                                        .put("description", "是否忽略大小写，默认 false。"),
                                ),
                        )
                        .put("required", JSONArray().put("path").put("pattern"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "file_edit",
                    description = "精确替换文件中的文本。默认只替换第一处，all=true 替换全部；写入前会备份为同名 .bak；找不到 old_string 时直接报错。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string").put("description", "要编辑的文件。"))
                                .put(
                                    "old_string",
                                    JSONObject().put("type", "string").put("description", "要被替换的原文。"),
                                )
                                .put(
                                    "new_string",
                                    JSONObject().put("type", "string").put("description", "替换后的新文本。"),
                                )
                                .put(
                                    "all",
                                    JSONObject().put("type", "boolean")
                                        .put("description", "是否替换全部匹配，默认 false（仅第一处）。"),
                                ),
                        )
                        .put("required", JSONArray().put("path").put("old_string").put("new_string"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "file_zip",
                    description = "把若干文件或目录打包成 zip 文件。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "sources",
                                    JSONObject().put("type", "array")
                                        .put("description", "要打包的文件或目录路径列表。")
                                        .put(
                                            "items",
                                            JSONObject().put("type", "string"),
                                        ),
                                )
                                .put(
                                    "destination",
                                    JSONObject().put("type", "string").put("description", "输出的 zip 路径。"),
                                ),
                        )
                        .put("required", JSONArray().put("sources").put("destination"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "file_unzip",
                    description = "解压 zip 到目标目录。带 zip-slip 防护，每个条目的解压路径必须落在目标目录内。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string").put("description", "zip 文件路径。"))
                                .put(
                                    "destination",
                                    JSONObject().put("type", "string").put("description", "解压目标目录。"),
                                ),
                        )
                        .put("required", JSONArray().put("path").put("destination"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "file_download",
                    description = "通过 HTTP/HTTPS 下载文件到本地。大小上限 32MiB，仅支持 http/https。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("url", JSONObject().put("type", "string").put("description", "下载地址。"))
                                .put(
                                    "destination",
                                    JSONObject().put("type", "string").put("description", "保存到的本地路径。"),
                                )
                                .put(
                                    "timeout_seconds",
                                    JSONObject().put("type", "integer")
                                        .put("description", "超时秒数，1 到 300，默认 60。"),
                                ),
                        )
                        .put("required", JSONArray().put("url").put("destination"))
                )
            )
    }
}
