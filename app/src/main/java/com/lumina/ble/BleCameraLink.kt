package com.lumina.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 扫描命中的相机（地址 + 广播名 + RSSI）。 */
data class BleCameraHit(
    val name: String,
    val address: String,
    val rssi: Int,
)

/** 已配对的 BLE 身份：重连时复用 device/nonce，相机据此认旧会话。 */
data class PairingIdentity(
    val address: String,
    val cameraName: String,
    val deviceId: Int,
    val nonce: Int,
    val serial: String,
)

/** 一次配对/唤醒的结果。 */
data class BlePairResult(
    val credentials: WifiCredentials?,
    val identity: PairingIdentity,
    val wifiWoken: Boolean,
    val warning: String? = null,
)

class BleLinkException(message: String) : Exception(message)

/**
 * 尼康 SnapBridge BLE 链路的 Android 实现：扫描 → GATT 连接 → 四步配对 →
 * 读 0x2004 凭据（解密）→ 写 0x2005 唤起相机 Wi-Fi。
 *
 * 只在工作线程调用（方法是阻塞的）。GATT 操作严格串行：一笔写完等回调再下一笔。
 */
class BleCameraLink(
    private val context: Context,
    private val onStatus: (String) -> Unit = {},
) {

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private val characteristics = HashMap<String, BluetoothGattCharacteristic>()

    private val connectedLatch = CountDownLatch(1)
    private val servicesLatch = CountDownLatch(1)
    private val opLatch = AtomicReference(CountDownLatch(0))
    private val opValue = AtomicReference<ByteArray?>(null)
    private val opStatus = AtomicReference(0)
    private val notifyLatch = AtomicReference(CountDownLatch(0))
    private val notifyData = AtomicReference<ByteArray?>(null)

    @Volatile private var lastError: String? = null

    fun hasPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 扫描尼康相机（LSS 服务 / 0x0399 厂商数据 / 名字特征）。阻塞 timeoutMs。 */
    @SuppressLint("MissingPermission")
    fun scan(timeoutMs: Long = 8_000): List<BleCameraHit> {
        val adapter = adapter ?: throw BleLinkException("此设备没有蓝牙")
        if (!adapter.isEnabled) throw BleLinkException("蓝牙未开启")
        val scanner = adapter.bluetoothLeScanner ?: throw BleLinkException("蓝牙扫描不可用")
        val found = LinkedHashMap<String, BleCameraHit>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: ""
                val serviceHit = record?.serviceUuids?.any { it.uuid.toString().contains("de00", true) } == true
                val mfgHit = record?.getManufacturerSpecificData(NikonBleUuids.COMPANY_ID) != null
                val nameHit = name.startsWith("Z ") || name.contains("Z_") || name.contains("Z 30") ||
                    name.contains("Z 50") || name.contains("Z fc")
                if (serviceHit || mfgHit || nameHit) {
                    found[result.device.address] = BleCameraHit(name, result.device.address, result.rssi)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                lastError = "扫描失败（$errorCode）"
            }
        }
        try {
            scanner.startScan(null, ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            onStatus("扫描中…发现 ${found.size} 台")
            Thread.sleep(timeoutMs)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        if (found.isEmpty() && lastError != null) throw BleLinkException(lastError!!)
        return found.values.sortedByDescending { it.rssi }
    }

    /**
     * 连接并完成配对/唤醒。每一步都写进 onStatus，便于 UI 分步显示与排障。
     * 失败抛 [BleLinkException]（消息可直接上屏）。
     */
    @SuppressLint("MissingPermission")
    fun pair(hit: BleCameraHit, saved: PairingIdentity? = null): BlePairResult {
        val adapter = adapter ?: throw BleLinkException("此设备没有蓝牙")
        if (!adapter.isEnabled) throw BleLinkException("蓝牙未开启")
        lastError = null
        onStatus("连接相机…")
        val device = adapter.getRemoteDevice(hit.address)
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw BleLinkException("无法建立 GATT 连接")
        gatt = g
        try {
            await(connectedLatch, 20_000, "连接超时：相机可能已休眠，按一下快门再试")
            onStatus("发现服务…")
            g.requestMtu(517)
            g.discoverServices()
            await(servicesLatch, 20_000, "服务发现超时")
            buildCharTable(g)
            val pairChar = characteristic(NikonBleUuids.PAIR)
                ?: throw BleLinkException("不是尼康相机：缺少配对特征")
            enableIndicate(NikonBleUuids.PAIR)
            runCatching { enableIndicate(NikonBleUuids.NOT1) }
            runCatching { enableIndicate(NikonBleUuids.NOT2) }

            onStatus("配对握手中…")
            val engine = NikonPairingEngine()
            val stage1 = engine.createStage1(saved?.deviceId, saved?.nonce)
            resetNotify()
            write(pairChar, stage1.encode())
            val stage2Raw = awaitNotify(15_000, "相机没有回握手挑战")
            val stage2 = PairingMessage.decode(stage2Raw)
            val built = engine.verifyStage2AndBuildStage3(stage1, stage2)
                ?: throw BleLinkException("握手校验失败：相机固件的 salt 方言可能不同")
            val (stage3, _) = built
            resetNotify()
            write(pairChar, stage3.encode())
            val stage4Raw = awaitNotify(15_000, "相机没有回配对确认")
            val stage4 = PairingMessage.decode(stage4Raw)
            val serial = engine.extractSerial(stage4)
            onStatus("写入客户端名…")
            write(characteristic(NikonBleUuids.NAME) ?: throw BleLinkException("缺少名称特征"), namePayload())
            Thread.sleep(300)

            onStatus("读取 Wi-Fi 凭据…")
            val blob = runCatching { read(characteristic(NikonBleUuids.CONFIG)!!) }.getOrNull()
            val lss = LssMaterial(
                key1 = stage4.encode().copyOfRange(9, 17),
                key2 = stage1.encode().copyOfRange(9, 17),
                saltIndex = built.second,
                cameraNonce = stage2Raw.copyOfRange(1, 9),
                phoneNonce = stage1.encode().copyOfRange(1, 9),
            )
            val credentials = blob?.let { WifiCredentialsDecoder.decode(it, lss) }
            var warning: String? = null
            if (blob == null || blob.isEmpty()) {
                warning = "相机未返回 Wi-Fi 凭据（可能需要在系统蓝牙里先配对）"
            } else if (credentials == null) {
                warning = "Wi-Fi 凭据解密未命中，可手动填入相机热点密码"
            }

            onStatus("唤醒相机 Wi-Fi…")
            val woken = runCatching {
                write(characteristic(NikonBleUuids.WIFI)!!, byteArrayOf(0x01))
                true
            }.getOrDefault(false)
            if (!woken) warning = listOfNotNull(warning, "唤醒 Wi-Fi 失败，请在相机菜单手动开启").joinToString("；")

            return BlePairResult(
                credentials = credentials,
                identity = PairingIdentity(
                    address = hit.address,
                    cameraName = hit.name.ifBlank { saved?.cameraName ?: "Z 30" },
                    deviceId = stage1.device,
                    nonce = stage1.nonce,
                    serial = serial,
                ),
                wifiWoken = woken,
                warning = warning,
            )
        } catch (e: BleLinkException) {
            throw e
        } catch (e: Exception) {
            throw BleLinkException(e.message ?: "蓝牙操作失败")
        } finally {
            // 凭据已拿到、Wi-Fi 也已唤醒：BLE 链路可以断开，省电且让相机专注 Wi-Fi。
            runCatching { g.disconnect() }
            runCatching { g.close() }
            gatt = null
        }
    }

    fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    // ---------- GATT 细节 ----------

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedLatch.countDown()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                lastError = "连接已断开（status=$status）"
                connectedLatch.countDown()
                servicesLatch.countDown()
                opLatch.get().countDown()
                notifyLatch.get().countDown()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                servicesLatch.countDown()
            } else {
                lastError = "服务发现失败（status=$status）"
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            opStatus.set(status)
            opLatch.get().countDown()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            opStatus.set(status)
            opValue.set(ch.value)
            opLatch.get().countDown()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            opStatus.set(status)
            opLatch.get().countDown()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            onPairingNotify(ch, ch.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onPairingNotify(ch, value)
        }

        /**
         * 只把 0x2000(PAIR) 的通知当作握手帧。NOT1/NOT2 也会发通知
         * （相机请求时间/位置），若混进握手 latch，stage2 会被误读成 17B 之外的帧。
         */
        private fun onPairingNotify(ch: BluetoothGattCharacteristic, value: ByteArray?) {
            if (ch.uuid.toString().lowercase() != NikonBleUuids.PAIR.lowercase()) return
            notifyData.set(value)
            notifyLatch.get().countDown()
        }
    }

    private fun buildCharTable(g: BluetoothGatt) {
        characteristics.clear()
        for (service in g.services) {
            for (ch in service.characteristics) {
                characteristics[ch.uuid.toString().lowercase()] = ch
            }
        }
        onStatus("服务就绪（${characteristics.size} 个特征）")
    }

    private fun characteristic(uuid: String): BluetoothGattCharacteristic? =
        characteristics[uuid.lowercase()]

    @SuppressLint("MissingPermission")
    private fun enableIndicate(uuid: String) {
        val ch = characteristic(uuid) ?: return
        val g = gatt ?: throw BleLinkException("连接已断开")
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            ?: throw BleLinkException("缺少通知描述符")
        val indicate = (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        beginOp()
        @Suppress("DEPRECATION")
        run {
            cccd.value = if (indicate) byteArrayOf(0x02, 0x00) else byteArrayOf(0x01, 0x00)
            g.writeDescriptor(cccd)
        }
        awaitOp(5_000, "开启通知失败")
    }

    @SuppressLint("MissingPermission")
    private fun write(ch: BluetoothGattCharacteristic, payload: ByteArray) {
        beginOp()
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        run {
            ch.value = payload
            gatt?.writeCharacteristic(ch) ?: throw BleLinkException("连接已断开")
        }
        awaitOp(8_000, "写入失败（相机未响应）")
    }

    @SuppressLint("MissingPermission")
    private fun read(ch: BluetoothGattCharacteristic): ByteArray? {
        beginOp()
        gatt?.readCharacteristic(ch) ?: throw BleLinkException("连接已断开")
        awaitOp(8_000, "读取失败（相机未响应）")
        return opValue.get()
    }

    private fun beginOp() {
        opStatus.set(-1)
        opValue.set(null)
        opLatch.set(CountDownLatch(1))
    }

    private fun awaitOp(timeoutMs: Long, message: String) {
        val latch = opLatch.get()
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw BleLinkException("$message（超时）")
        }
        if (opStatus.get() != BluetoothGatt.GATT_SUCCESS) {
            throw BleLinkException("$message（status=${opStatus.get()}）")
        }
    }

    private fun resetNotify() {
        notifyData.set(null)
        notifyLatch.set(CountDownLatch(1))
    }

    private fun awaitNotify(timeoutMs: Long, message: String): ByteArray {
        val latch = notifyLatch.get()
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw BleLinkException("$message（超时）")
        }
        return notifyData.get() ?: throw BleLinkException(message)
    }

    private fun await(latch: CountDownLatch, timeoutMs: Long, message: String) {
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw BleLinkException(message)
        lastError?.let { throw BleLinkException(it) }
    }

    /** 0x2002：32B ASCII 零填充的客户端名。 */
    private fun namePayload(): ByteArray {
        val name = "Lumina".toByteArray(Charsets.US_ASCII)
        val out = ByteArray(32)
        name.copyInto(out, 0, 0, minOf(name.size, 32))
        return out
    }
}
