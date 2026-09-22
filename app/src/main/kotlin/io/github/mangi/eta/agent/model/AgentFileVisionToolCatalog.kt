package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 文件路径到模型视觉输入的通用能力，不依赖任何个人数据 Provider。 */
internal object AgentFileVisionToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "read_image",
                description = "读取用户指定路径或系统相册 URI 中的图片，并作为视觉输入提供给模型。单张用 path；需要同时查看或对比多张时，在一次调用里用 paths 传全部路径（最多 3 张，按顺序标注为图1、图2、图3）。同一轮最多调用一次；要读取更多图片时，等待当前结果返回并观察后，再在下一轮读取下一张。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "path",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 1_024)
                                    .put("description", "单张图片的绝对路径、file URI 或系统相册 content URI；本机路径由 Root 读取"),
                            )
                            .put(
                                "paths",
                                JSONObject()
                                    .put("type", "array")
                                    .put(
                                        "items",
                                        JSONObject()
                                            .put("type", "string")
                                            .put("maxLength", 1_024),
                                    )
                                    .put("minItems", 1)
                                    .put("maxItems", 3)
                                    .put("uniqueItems", true)
                                    .put("description", "多张图片的路径列表，按顺序标注为图1、图2、图3；与 path 至少提供一个"),
                            ),
                    ),
            ),
        )
    }
}
