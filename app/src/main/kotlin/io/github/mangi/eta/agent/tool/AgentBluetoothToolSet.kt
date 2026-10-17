package io.github.mangi.eta.agent.tool

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Base64
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 蓝牙工具族：适配器状态、已配对设备、经典发现、SPP（RFCOMM）读写、BLE 扫描/连接/读写。
 *
 * 权限在运行时校验：缺少 BLUETOOTH_CONNECT / BLUETOOTH_SCAN 时系统调用会抛 SecurityException，
 * 这里统一捕获成 BLUETOOTH_PERMISSION_MISSING，不让 Agent 崩溃。
 * 连接/扫描等阻塞操作都放到后台线程并带超时，句柄保存在本对象里跨调用复用。
 */
@SuppressLint("MissingPermission")
internal class AgentBluetoothToolSet(
    private val context: Context,
    private val logger: AgentLogger,
) {
    private val sppSockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val bleGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val bleByGatt = ConcurrentHashMap<BluetoothGatt, BleConnection>()
    private val handleCounter = AtomicInteger(0)

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val connection = bleByGatt[gatt] ?: return
            connection.connectStatus = status
            connection.connectState = newState
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connection.connected.countDown()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connection.connected.countDown()
                    connection.services.countDown()
                    connection.readQueue.offer(ReadResult(BluetoothGatt.GATT_FAILURE, null))
                    connection.writeQueue.offer(BluetoothGatt.GATT_FAILURE)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            bleByGatt[gatt]?.services?.countDown()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            bleByGatt[gatt]?.readQueue?.offer(ReadResult(status, value))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            bleByGatt[gatt]?.writeQueue?.offer(status)
        }
    }

    fun execute(name: String, args: JSONObject): String? {
        if (name !in TOOL_NAMES) return null
        logger.debug { "bluetooth tool: $name" }
        return try {
            when (name) {
                "bt_status" -> status()
                "bt_paired_devices" -> pairedDevices()
                "bt_scan" -> {
                    val seconds = args.optInt("seconds", 8).coerceIn(1, 20)
                    runWithTimeout((seconds + 3) * 1000L, "bt_scan") { classicScan(seconds) }
                }
                "bt_spp_connect" -> runWithTimeout(13_000L, "bt_spp_connect") { sppConnect(args) }
                "bt_spp_send" -> {
                    val handle = args.optString("handle", "")
                    val socket = sppSockets[handle]
                    val bytes = payload(args)
                    when {
                        socket == null -> fail("SPP_HANDLE_NOT_FOUND", "未找到连接句柄：$handle")
                        bytes == null -> fail("INVALID_ARGUMENT", "缺少 data，或 base64=true 时 data 不是合法 base64")
                        else -> {
                            val sendBytes: ByteArray = bytes
                            runWithTimeout(11_000L, "bt_spp_send") {
                                val out = socket.outputStream
                                out.write(sendBytes)
                                out.flush()
                                ok("handle" to handle, "bytes" to sendBytes.size)
                            }
                        }
                    }
                }
                "bt_spp_close" -> {
                    val handle = args.optString("handle", "")
                    val socket = sppSockets.remove(handle)
                    if (socket == null) {
                        fail("SPP_HANDLE_NOT_FOUND", "未找到连接句柄：$handle")
                    } else {
                        runCatching { socket.close() }.fold(
                            onSuccess = { ok("handle" to handle) },
                            onFailure = { fail("SPP_CLOSE_FAILED", it.message ?: it.javaClass.simpleName) },
                        )
                    }
                }
                "bt_ble_scan" -> {
                    val seconds = args.optInt("seconds", 6).coerceIn(1, 15)
                    runWithTimeout((seconds + 3) * 1000L, "bt_ble_scan") { bleScan(seconds) }
                }
                "bt_ble_connect" -> runWithTimeout(20_000L, "bt_ble_connect") { bleConnect(args) }
                "bt_ble_write" -> runWithTimeout(12_000L, "bt_ble_write") { bleWrite(args) }
                "bt_ble_read" -> runWithTimeout(12_000L, "bt_ble_read") { bleRead(args) }
                else -> null
            }
        } catch (e: SecurityException) {
            logger.warn("bluetooth tool $name missing permission: ${e.message}")
            fail(
                "BLUETOOTH_PERMISSION_MISSING",
                "缺少蓝牙运行时权限（BLUETOOTH_CONNECT / BLUETOOTH_SCAN，BLE 扫描可能还需定位）：${e.message ?: "未授予"}",
            )
        } catch (e: Exception) {
            logger.error("bluetooth tool $name failed", e)
            fail("BLUETOOTH_ERROR", e.message ?: e.javaClass.simpleName)
        }
    }

    // --- 基础 ---

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun status(): String {
        val adapter = adapter() ?: return fail("BLUETOOTH_UNAVAILABLE", "本设备不支持蓝牙")
        return ok(
            "supported" to true,
            "enabled" to adapter.isEnabled,
            "name" to (adapter.name ?: ""),
            "address" to maskAddress(adapter.address),
            "state" to adapter.state,
            "scan_mode" to adapter.scanMode,
        )
    }

    private fun pairedDevices(): String {
        val adapter = adapter() ?: return fail("BLUETOOTH_UNAVAILABLE", "本设备不支持蓝牙")
        val devices = JSONArray()
        for (device in adapter.bondedDevices.orEmpty()) {
            devices.put(
                JSONObject()
                    .put("name", device.name ?: "")
                    .put("address", maskAddress(device.address))
                    .put("type", typeName(device.type))
                    .put("bond_state", bondName(device.bondState)),
            )
        }
        return ok("count" to devices.length(), "devices" to devices)
    }

    // --- 经典蓝牙发现 ---

    private fun classicScan(seconds: Int): String {
        val adapter = adapter() ?: return fail("BLUETOOTH_UNAVAILABLE", "本设备不支持蓝牙")
        if (!adapter.isEnabled) return fail("BLUETOOTH_DISABLED", "蓝牙未开启")
        val found = ConcurrentHashMap<String, JSONObject>()
        val finished = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                runCatching {
                    when (intent?.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val device = intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java,
                            ) ?: return
                            val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                            found[device.address] = JSONObject()
                                .put("name", device.name ?: "")
                                .put("address", maskAddress(device.address))
                                .put("type", typeName(device.type))
                                .put("rssi", rssi.toInt())
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> finished.countDown()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        try {
            if (!adapter.startDiscovery()) return fail("SCAN_START_FAILED", "startDiscovery 返回 false")
            finished.await(seconds.toLong(), TimeUnit.SECONDS)
        } finally {
            runCatching { adapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
        val devices = JSONArray()
        for (item in found.values) devices.put(item)
        return ok("seconds" to seconds, "count" to devices.length(), "devices" to devices)
    }

    // --- SPP（RFCOMM）---

    private fun sppConnect(args: JSONObject): String {
        val address = args.optString("address", "")
        if (address.isBlank()) return fail("INVALID_ARGUMENT", "缺少 address")
        val adapter = adapter() ?: return fail("BLUETOOTH_UNAVAILABLE", "本设备不支持蓝牙")
        if (!adapter.isEnabled) return fail("BLUETOOTH_DISABLED", "蓝牙未开启")
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: return fail("INVALID_ARGUMENT", "非法蓝牙地址：$address")

        val holder = AtomicReference<BluetoothSocket?>()
        val error = AtomicReference<String?>()
        val done = CountDownLatch(1)
        Thread {
            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                runCatching { adapter.cancelDiscovery() }
                socket.connect()
                holder.set(socket)
            } catch (t: Throwable) {
                runCatching { socket?.close() }
                error.set(t.message ?: t.javaClass.simpleName)
            } finally {
                done.countDown()
            }
        }.start()

        if (!done.await(11, TimeUnit.SECONDS)) {
            // 连接线程可能稍后才成功，超时返回后回收掉，避免句柄泄漏
            Thread {
                runCatching {
                    done.await(20, TimeUnit.SECONDS)
                    holder.get()?.close()
                }
            }.start()
            return fail("SPP_CONNECT_TIMEOUT", "连接超时（10s）：$address")
        }
        val socket = holder.get() ?: return fail("SPP_CONNECT_FAILED", error.get() ?: "连接失败")
        val handle = nextHandle("spp")
        sppSockets[handle] = socket
        return ok("handle" to handle, "address" to maskAddress(address), "uuid" to SPP_UUID.toString())
    }

    // --- BLE ---

    private fun bleScan(seconds: Int): String {
        val adapter = adapter() ?: return fail("BLUETOOTH_UNAVAILABLE", "本设备不支持蓝牙")
        if (!adapter.isEnabled) return fail("BLUETOOTH_DISABLED", "蓝牙未开启")
        val scanner = adapter.bluetoothLeScanner ?: return fail("BLE_UNAVAILABLE", "低功耗蓝牙扫描不可用")
        val found = ConcurrentHashMap<String, JSONObject>()
        val finished = CountDownLatch(1)
        val error = AtomicReference<String?>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                addBleResult(found, result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                for (result in results.orEmpty()) addBleResult(found, result)
            }

            override fun onScanFailed(errorCode: Int) {
                error.set("scan failed code=$errorCode")
                finished.countDown()
            }
        }
        try {
            scanner.startScan(callback)
            finished.await(seconds.toLong(), TimeUnit.SECONDS)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        error.get()?.let { if (found.isEmpty()) return fail("BLE_SCAN_FAILED", it) }
        val devices = JSONArray()
        for (item in found.values) devices.put(item)
        return ok("seconds" to seconds, "count" to devices.length(), "devices" to devices)
    }

    private fun addBleResult(found: MutableMap<String, JSONObject>, result: ScanResult?) {
        if (result == null) return
        runCatching {
            val device = result.device
            found[device.address] = JSONObject()
                .put("name", device.name ?: "")
                .put("address", maskAddress(device.address))
                .put("rssi", result.rssi)
        }
    }

    private fun bleConnect(args: JSONObject): String {
        val address = args.optString("address", "")
        if (address.isBlank()) return fail("INVALID_ARGUMENT", "缺少 address")
        val adapter = adapter() ?: return fail("BLUETOOTH_UNAVAILABLE", "本设备不支持蓝牙")
        if (!adapter.isEnabled) return fail("BLUETOOTH_DISABLED", "蓝牙未开启")
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: return fail("INVALID_ARGUMENT", "非法蓝牙地址：$address")

        val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            ?: return fail("BLE_CONNECT_FAILED", "connectGatt 返回 null")
        val connection = BleConnection(gatt)
        bleByGatt[gatt] = connection

        if (!connection.connected.await(10, TimeUnit.SECONDS)) {
            runCatching { gatt.close() }
            bleByGatt.remove(gatt)
            return fail("BLE_CONNECT_TIMEOUT", "连接超时：$address")
        }
        if (connection.connectState != BluetoothProfile.STATE_CONNECTED) {
            runCatching { gatt.close() }
            bleByGatt.remove(gatt)
            return fail("BLE_CONNECT_FAILED", "连接失败 status=${connection.connectStatus}")
        }
        runCatching { gatt.discoverServices() }
        val discovered = connection.services.await(6, TimeUnit.SECONDS)
        val handle = nextHandle("ble")
        bleGatts[handle] = gatt
        val services = JSONArray()
        for (service in gatt.services.orEmpty()) services.put(service.uuid.toString())
        return ok(
            "handle" to handle,
            "address" to maskAddress(address),
            "services_discovered" to discovered,
            "services" to services,
        )
    }

    private fun bleWrite(args: JSONObject): String {
        val handle = args.optString("handle", "")
        val gatt = bleGatts[handle] ?: return fail("BLE_HANDLE_NOT_FOUND", "未找到连接句柄：$handle")
        val connection = bleByGatt[gatt] ?: return fail("BLE_HANDLE_NOT_FOUND", "连接已失效：$handle")
        val bytes = payload(args) ?: return fail("INVALID_ARGUMENT", "缺少 data，或 base64=true 时 data 不是合法 base64")
        val characteristic = findCharacteristic(gatt, args, needWrite = true)
            ?: return fail("BLE_CHARACTERISTIC_NOT_FOUND", "未找到可写特征（检查 service_uuid / characteristic_uuid）")
        val status = gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (status != BluetoothStatusCodes.SUCCESS) {
            return fail("BLE_WRITE_FAILED", "writeCharacteristic 返回 $status")
        }
        val writeStatus = connection.writeQueue.poll(8, TimeUnit.SECONDS)
            ?: return fail("BLE_WRITE_TIMEOUT", "等待写入回调超时")
        if (writeStatus != BluetoothGatt.GATT_SUCCESS) {
            return fail("BLE_WRITE_FAILED", "写入状态 $writeStatus")
        }
        return ok("handle" to handle, "bytes" to bytes.size, "characteristic" to characteristic.uuid.toString())
    }

    private fun bleRead(args: JSONObject): String {
        val handle = args.optString("handle", "")
        val gatt = bleGatts[handle] ?: return fail("BLE_HANDLE_NOT_FOUND", "未找到连接句柄：$handle")
        val connection = bleByGatt[gatt] ?: return fail("BLE_HANDLE_NOT_FOUND", "连接已失效：$handle")
        val characteristic = findCharacteristic(gatt, args, needWrite = false)
            ?: return fail("BLE_CHARACTERISTIC_NOT_FOUND", "未找到可读特征（检查 service_uuid / characteristic_uuid）")
        if (!gatt.readCharacteristic(characteristic)) {
            return fail("BLE_READ_FAILED", "readCharacteristic 返回 false")
        }
        val result = connection.readQueue.poll(8, TimeUnit.SECONDS)
            ?: return fail("BLE_READ_TIMEOUT", "等待读取回调超时")
        if (result.status != BluetoothGatt.GATT_SUCCESS) {
            return fail("BLE_READ_FAILED", "读取状态 ${result.status}")
        }
        val value = result.value ?: ByteArray(0)
        return ok(
            "handle" to handle,
            "characteristic" to characteristic.uuid.toString(),
            "bytes" to value.size,
            "base64" to Base64.encodeToString(value, Base64.NO_WRAP),
            "hex" to toHex(value),
        )
    }

    private fun findCharacteristic(
        gatt: BluetoothGatt,
        args: JSONObject,
        needWrite: Boolean,
    ): BluetoothGattCharacteristic? {
        val serviceUuid = args.optString("service_uuid", "")
        val charUuid = args.optString("characteristic_uuid", "")
        val services = if (serviceUuid.isBlank()) {
            gatt.services.orEmpty()
        } else {
            listOfNotNull(safeUuid(serviceUuid)?.let { gatt.getService(it) })
        }
        for (service in services) {
            val characteristics = if (charUuid.isBlank()) {
                service.characteristics.orEmpty()
            } else {
                listOfNotNull(safeUuid(charUuid)?.let { service.getCharacteristic(it) })
            }
            for (characteristic in characteristics) {
                val props = characteristic.properties
                val writable = props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                    props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                val readable = props and BluetoothGattCharacteristic.PROPERTY_READ != 0
                if (needWrite && writable) return characteristic
                if (!needWrite && readable) return characteristic
            }
        }
        return null
    }

    // --- 工具 ---

    private fun payload(args: JSONObject): ByteArray? {
        if (!args.has("data")) return null
        val data = args.optString("data", "")
        return if (args.optBoolean("base64", false)) {
            runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
        } else {
            data.toByteArray(Charsets.UTF_8)
        }
    }

    private fun runWithTimeout(timeoutMs: Long, label: String, block: () -> String): String {
        val ref = AtomicReference<String>()
        val latch = CountDownLatch(1)
        Thread {
            try {
                ref.set(block())
            } catch (e: SecurityException) {
                ref.set(fail("BLUETOOTH_PERMISSION_MISSING", "缺少蓝牙运行时权限：${e.message ?: "未授予"}"))
            } catch (t: Throwable) {
                ref.set(fail("BLUETOOTH_ERROR", t.message ?: t.javaClass.simpleName))
            } finally {
                latch.countDown()
            }
        }.start()
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            ref.get() ?: fail("BLUETOOTH_ERROR", "$label 无返回结果")
        } else {
            fail("BLUETOOTH_TIMEOUT", "$label 超时")
        }
    }

    private fun ok(vararg pairs: Pair<String, Any?>): String {
        val json = JSONObject().put("ok", true)
        for ((key, value) in pairs) json.put(key, value ?: JSONObject.NULL)
        return json.toString()
    }

    private fun fail(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun nextHandle(prefix: String): String = "$prefix-${handleCounter.incrementAndGet()}"

    private fun maskAddress(address: String?): String {
        if (address.isNullOrBlank()) return ""
        val parts = address.split(":")
        return if (parts.size >= 3) parts.take(3).joinToString(":") + ":**:**:**" else address
    }

    private fun safeUuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

    private fun typeName(type: Int): String = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
        BluetoothDevice.DEVICE_TYPE_LE -> "le"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
        else -> "unknown"
    }

    private fun bondName(state: Int): String = when (state) {
        BluetoothDevice.BOND_BONDED -> "bonded"
        BluetoothDevice.BOND_BONDING -> "bonding"
        else -> "none"
    }

    private fun toHex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) builder.append(String.format("%02x", byte))
        return builder.toString()
    }

    private class BleConnection(val gatt: BluetoothGatt) {
        val connected = CountDownLatch(1)
        val services = CountDownLatch(1)
        val readQueue = ArrayBlockingQueue<ReadResult>(1)
        val writeQueue = ArrayBlockingQueue<Int>(1)

        @Volatile var connectStatus: Int = BluetoothGatt.GATT_FAILURE
        @Volatile var connectState: Int = BluetoothProfile.STATE_DISCONNECTED
    }

    private class ReadResult(val status: Int, val value: ByteArray?)

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

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
    }
}
