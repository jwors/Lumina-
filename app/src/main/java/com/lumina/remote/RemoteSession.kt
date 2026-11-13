package com.lumina.remote

import android.content.Context
import com.lumina.ble.BleCameraHit
import com.lumina.ble.BleCameraLink
import com.lumina.ble.BlePairResult
import com.lumina.ble.PairingIdentity
import com.lumina.ble.WifiCredentials
import com.lumina.ptp.PtpSession
import com.lumina.ptp.PtpResponseException
import com.lumina.ptpip.PtpIpTransport
import java.io.File
import java.time.Instant

/** 一次连接的结果：成功给型号/网关，失败给能上屏的说明。 */
data class RemoteConnectOutcome(
    val model: String? = null,
    val gateway: String? = null,
    val warning: String? = null,
    val failure: String? = null,
    /** Android 12：需要用户去系统 Wi-Fi 面板手动连相机热点，然后调 [RemoteSession.connectWifiOnly]。 */
    val manualWifiSsid: String? = null,
) {
    val ok: Boolean get() = failure == null && manualWifiSsid == null
}

/**
 * 遥控会话编排：BLE 配对唤醒 → 加入相机热点 → PTP-IP 连相机 → 读参数/按快门。
 *
 * 阻塞式方法，调用方放在工作线程。凭据/配对身份存私有 SharedPreferences；
 * 日志（files/m5-remote.log）不写 Wi-Fi 密码。
 */
class RemoteSession(
    private val context: Context,
    private val onLog: (String) -> Unit = {},
) {

    private val prefs = context.getSharedPreferences("lumina_remote", Context.MODE_PRIVATE)
    private var ble: BleCameraLink? = null
    private var wifi: WifiLink? = null
    private var transport: PtpIpTransport? = null
    private var session: PtpSession? = null
    private var model: String? = null

    fun scan(): List<BleCameraHit> {
        val link = BleCameraLink(context) { line -> log(line) }
        ble = link
        return link.scan()
    }

    /** 扫描页点一台相机：BLE 配对 + 唤醒 + 入网 + PTP。 */
    fun connect(hit: BleCameraHit): RemoteConnectOutcome {
        // Android 的 BLE 地址是轮换 RPA：按广播名认旧身份，不能按地址。
        val saved = savedIdentity()?.takeIf { it.cameraName.isNotBlank() && it.cameraName == hit.name }
        val link = ble ?: BleCameraLink(context) { line -> log(line) }.also { ble = it }
        log("BLE 配对开始：${hit.name} ${hit.address}")
        var pair: BlePairResult? = null
        try {
            pair = link.pair(hit, saved)
            saveIdentity(pair.identity)
        } catch (e: Exception) {
            // 配对失败不代表没救：相机 Wi-Fi 可能已经开着，且上次凭据还在。
            log("BLE 配对失败：${e.message}（尝试用已保存的 Wi-Fi 凭据继续）")
        }
        val credentials = pair?.credentials ?: savedCredentials()
        if (credentials == null) {
            log("没有可用的相机 Wi-Fi 凭据")
            return RemoteConnectOutcome(
                failure = "蓝牙配对失败，也没存过相机 Wi-Fi 密码。请在相机菜单打开 Wi-Fi，用下方「手动 Wi-Fi」填一次。",
                warning = pair?.warning,
            )
        }
        saveCredentials(credentials)
        log("Wi-Fi 凭据就绪（${credentials.keySource}，密码不落日志）")

        // 热点刚被唤醒：给它 2~3 秒广播，最多试 4 次。
        var lastFailure = "加入相机热点失败"
        repeat(4) { attempt ->
            when (val joined = (wifi ?: WifiLink(context).also { wifi = it }).join(credentials.ssid, credentials.password)) {
                is WifiJoinResult.Joined -> {
                    log("已入网，网关=${joined.gatewayIp}")
                    return openPtp(joined.gatewayIp, pair?.warning)
                }
                is WifiJoinResult.NeedsManual -> {
                    log("Android 12 降级为手动入网：${joined.ssid}")
                    return RemoteConnectOutcome(
                        manualWifiSsid = joined.ssid,
                        warning = pair?.warning,
                    )
                }
                is WifiJoinResult.Failed -> {
                    lastFailure = joined.message
                    log("入网第 ${attempt + 1} 次失败：${joined.message}")
                    Thread.sleep(1_500)
                }
            }
        }
        return RemoteConnectOutcome(failure = lastFailure, warning = pair?.warning)
    }

    /** Android 12 手动入网后继续：取当前 Wi-Fi 网关并连 PTP。 */
    fun connectWifiOnly(): RemoteConnectOutcome {
        val gateway = (wifi ?: WifiLink(context).also { wifi = it }).activeWifiGateway()
            ?: WifiLink.FALLBACK_GATEWAY
        return openPtp(gateway, null)
    }

    fun connectManual(ssid: String, password: String): RemoteConnectOutcome {
        val credentials = WifiCredentials(ssid, password, if (password.isEmpty()) 0 else 1, "manual")
        return when (val joined = (wifi ?: WifiLink(context).also { wifi = it }).join(ssid, password)) {
            is WifiJoinResult.Joined -> {
                saveCredentials(credentials)
                openPtp(joined.gatewayIp, null)
            }
            is WifiJoinResult.NeedsManual -> RemoteConnectOutcome(manualWifiSsid = joined.ssid)
            is WifiJoinResult.Failed -> RemoteConnectOutcome(failure = joined.message)
        }
    }

    private fun openPtp(gateway: String, warning: String?): RemoteConnectOutcome {
        var lastError: String? = null
        repeat(4) { attempt ->
            val t = PtpIpTransport(gateway) { line -> log(line) }
            val s = PtpSession(t)
            try {
                log("PTP-IP 连接 $gateway:15740（第 ${attempt + 1} 次）")
                s.open()
                val m = s.getModel()
                transport = t
                session = s
                model = m
                log("PTP-IP 就绪：$m")
                return RemoteConnectOutcome(model = m, gateway = gateway, warning = warning)
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                log("PTP-IP 失败：$lastError")
                runCatching { s.close() }
                runCatching { t.close() }
                Thread.sleep(1_500)
            }
        }
        return RemoteConnectOutcome(
            failure = "连上相机热点但 PTP 打不开：$lastError",
            warning = warning,
        )
    }

    fun readParams(): CameraParams {
        val s = session ?: return CameraParams()
        return try {
            CameraParamsReader.read(s)
        } catch (e: Exception) {
            log("读参数失败：${e.message}")
            CameraParams()
        }
    }

    fun capture(): Long {
        val s = session ?: throw IllegalStateException("相机未连接")
        try {
            s.initiateCapture()
        } catch (e: PtpResponseException) {
            if (e.code == com.lumina.ptp.RespCode.DEVICE_BUSY) {
                Thread.sleep(700)
                s.initiateCapture()
            } else {
                throw IllegalStateException(humanizeCaptureError(e.code), e)
            }
        }
        log("快门已触发")
        return System.currentTimeMillis()
    }

    fun videoMode(): Boolean = model?.contains("Z 30", true) == true

    fun cameraModel(): String? = model

    fun close() {
        runCatching { session?.close() }
        runCatching { transport?.close() }
        session = null
        transport = null
        runCatching { ble?.close() }
        ble = null
        runCatching { wifi?.unbind() }
        wifi = null
    }

    // ---------- 持久化 ----------

    private fun savedIdentity(): PairingIdentity? {
        val address = prefs.getString("address", null) ?: return null
        return PairingIdentity(
            address = address,
            cameraName = prefs.getString("cameraName", "") ?: "",
            deviceId = prefs.getInt("deviceId", 0),
            nonce = prefs.getInt("nonce", 0),
            serial = prefs.getString("serial", "") ?: "",
        )
    }

    private fun saveIdentity(identity: PairingIdentity) {
        prefs.edit()
            .putString("address", identity.address)
            .putString("cameraName", identity.cameraName)
            .putInt("deviceId", identity.deviceId)
            .putInt("nonce", identity.nonce)
            .putString("serial", identity.serial)
            .apply()
    }

    private fun savedCredentials(): WifiCredentials? {
        val ssid = prefs.getString("ssid", null) ?: return null
        return WifiCredentials(
            ssid = ssid,
            password = prefs.getString("password", "") ?: "",
            security = prefs.getInt("security", 1),
            keySource = "saved",
        )
    }

    private fun saveCredentials(c: WifiCredentials) {
        prefs.edit()
            .putString("ssid", c.ssid)
            .putString("password", c.password)
            .putInt("security", c.security)
            .apply()
    }

    private fun log(line: String) {
        onLog(line)
        runCatching {
            File(context.filesDir, "m5-remote.log")
                .appendText("[${Instant.now()}] $line\n")
        }
    }
}
