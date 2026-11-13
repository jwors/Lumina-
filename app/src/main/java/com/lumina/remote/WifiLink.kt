package com.lumina.remote

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.provider.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 相机热点接入结果。 */
sealed interface WifiJoinResult {
    /** 已入网；gateway 是相机地址（通常 192.168.1.1），拿不到时回退常见地址。 */
    data class Joined(val gatewayIp: String) : WifiJoinResult

    /** Android 12：系统不允许 App 静默入网，已打开 Wi-Fi 面板，等用户手点。 */
    data class NeedsManual(val ssid: String) : WifiJoinResult

    data class Failed(val message: String) : WifiJoinResult
}

/**
 * 把手机接入相机热点。
 *
 * - Android 13+：`WifiNetworkSpecifier` 自动加入（系统会弹一次确认），
 *   成功后把进程网络绑定到该热点，PTP-IP 的 TCP 才不会走蜂窝。
 * - Android 12：系统仍要求定位权限才能静默入网，本 App 不申请定位，
 *   所以降级为打开 Wi-Fi 面板，用户在系统里点相机热点；随后 [activeWifiGateway]
 *   取当前 Wi-Fi 网关。
 */
class WifiLink(private val context: Context) {

    private val cm: ConnectivityManager
        get() = context.getSystemService(ConnectivityManager::class.java)

    private var boundNetwork: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun join(ssid: String, password: String, timeoutMs: Long = 30_000): WifiJoinResult {
        if (Build.VERSION.SDK_INT < 33) {
            openWifiPanel()
            return WifiJoinResult.NeedsManual(ssid)
        }
        if (ssid.isBlank()) return WifiJoinResult.Failed("相机 Wi-Fi 名为空")
        val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (password.isNotEmpty()) {
            builder.setWpa2Passphrase(password)
        }
        val specifier = builder.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val latch = CountDownLatch(1)
        var available: Network? = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available = network
                latch.countDown()
            }

            override fun onUnavailable() {
                latch.countDown()
            }
        }
        callback = cb
        return try {
            cm.requestNetwork(request, cb)
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                cm.unregisterNetworkCallback(cb)
                WifiJoinResult.Failed("加入相机热点超时：确认相机 Wi-Fi 已开且在附近")
            } else {
                val network = available
                if (network == null) {
                    cm.unregisterNetworkCallback(cb)
                    WifiJoinResult.Failed("系统未加入相机热点（密码不对或热点未开）")
                } else {
                    cm.bindProcessToNetwork(network)
                    boundNetwork = network
                    WifiJoinResult.Joined(gatewayOf(network) ?: FALLBACK_GATEWAY)
                }
            }
        } catch (e: Exception) {
            WifiJoinResult.Failed("加入热点失败：${e.message}")
        }
    }

    /** 打开系统 Wi-Fi 面板（Android 12 降级路径）。 */
    fun openWifiPanel() {
        val intent = if (Build.VERSION.SDK_INT >= 29) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** 当前活动网络若是 Wi-Fi，返回其网关（相机地址）。 */
    fun activeWifiGateway(): String? {
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        return gatewayOf(network)
    }

    fun unbind() {
        runCatching { cm.bindProcessToNetwork(null) }
        boundNetwork = null
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }

    private fun gatewayOf(network: Network): String? {
        val props = cm.getLinkProperties(network) ?: return null
        return props.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress
            ?: props.routes.firstOrNull { it.gateway != null }?.gateway?.hostAddress
    }

    companion object {
        /** 尼康相机热点的常见网关（拿不到 DHCP 路由时兜底）。 */
        const val FALLBACK_GATEWAY = "192.168.1.1"
    }
}
