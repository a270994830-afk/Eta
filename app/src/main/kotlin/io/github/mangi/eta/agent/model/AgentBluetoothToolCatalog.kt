package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentBluetoothToolCatalog {
    val TOOL_NAMES: Set<String> = setOf(
        "bt_status",
        "bt_paired_devices",
        "bt_scan",
        "bt_spp_connect",
        "bt_spp_send",
        "bt_spp_close",
        "bt_ble_scan",
        "bt_ble_connect",
        "bt_ble_write",
        "bt_ble_read",
    )

    private fun noArgs() = JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray())

    private fun stringProp(description: String) = JSONObject().put("type", "string").put("description", description)

    private fun intProp(description: String) = JSONObject().put("type", "integer").put("description", description)

    private fun boolProp(description: String) = JSONObject().put("type", "boolean").put("description", description)

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "bt_status",
                    description = "查询蓝牙适配器状态：是否存在、是否开启、本机名称、本机地址（出于隐私只返回前三段）与当前状态。",
                    parameters = noArgs(),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "bt_paired_devices",
                    description = "列出已配对的蓝牙设备：名称、地址（前三段）、设备类型（classic/le/dual）与配对状态（bonded/bonding/none）。",
                    parameters = noArgs(),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "bt_scan",
                    description = "经典蓝牙发现：开始搜索附近的经典蓝牙设备，等待指定秒数后返回扫描到的设备（名称/地址/类型/RSSI），结束时自动取消扫描。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "seconds",
                                intProp("扫描持续秒数，1 到 20，默认 8。"),
                            ),
                        )
                        .put("required", JSONArray()),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "bt_spp_connect",
                    description = "用标准 SPP（串口）UUID 00001101-0000-1000-8000-00805F9B34FB 通过 RFCOMM 连接指定蓝牙设备，超时 10 秒。成功后返回 handle，供 bt_spp_send / bt_spp_close 使用。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "address",
                                stringProp("目标设备的蓝牙 MAC 地址。"),
                            ),
                        )
                        .put("required", JSONArray().put("address")),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "bt_spp_send",
                    description = "向已建立的 SPP 连接写入数据，返回写入字节数。data 默认按 UTF-8 文本发送；传 base64=true 时 data 按 base64 解码成二进制字节。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("handle", stringProp("bt_spp_connect 返回的连接句柄。"))
                                .put("data", stringProp("要发送的数据。"))
                                .put("base64", boolProp("data 是否为 base64 编码的二进制，默认 false。")),
                        )
                        .put("required", JSONArray().put("handle").put("data")),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "bt_spp_close",
                    description = "关闭由 bt_spp_connect 建立的 SPP 连接并释放句柄。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("handle", stringProp("要关闭的连接句柄。")),
                        )
                        .put("required", JSONArray().put("handle")),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "bt_ble_scan",
                    description = "低功耗蓝牙（BLE）扫描：扫描指定秒数后返回发现的 BLE 设备（名称/地址/RSSI），结束后自动停止扫描。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "seconds",
                                intProp("扫描持续秒数，1 到 15，默认 6。"),
                            ),
                        )
                        .put("required", JSONArray()),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "bt_ble_connect",
                    description = "连接指定 BLE 设备并发现服务，返回 handle 与已发现的服务 UUID 列表，供 bt_ble_write / bt_ble_read 使用。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "address",
                                stringProp("目标 BLE 设备的蓝牙 MAC 地址。"),
                            ),
                        )
                        .put("required", JSONArray().put("address")),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "bt_ble_write",
                    description = "向已连接的 BLE 设备写入特征值，返回写入字节数。需提供 handle；缺省时选取第一个可写特征，也可用 service_uuid / characteristic_uuid 指定。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("handle", stringProp("bt_ble_connect 返回的连接句柄。"))
                                .put("data", stringProp("要写入的数据。"))
                                .put("base64", boolProp("data 是否为 base64 编码的二进制，默认 false。"))
                                .put("service_uuid", stringProp("可选，服务 UUID。"))
                                .put("characteristic_uuid", stringProp("可选，特征 UUID。")),
                        )
                        .put("required", JSONArray().put("handle").put("data")),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "bt_ble_read",
                    description = "读取已连接 BLE 设备的特征值，返回长度、base64 与十六进制内容。需提供 handle；缺省时选取第一个可读特征，也可用 service_uuid / characteristic_uuid 指定。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("handle", stringProp("bt_ble_connect 返回的连接句柄。"))
                                .put("service_uuid", stringProp("可选，服务 UUID。"))
                                .put("characteristic_uuid", stringProp("可选，特征 UUID。")),
                        )
                        .put("required", JSONArray().put("handle")),
                )
            )
    }
}
