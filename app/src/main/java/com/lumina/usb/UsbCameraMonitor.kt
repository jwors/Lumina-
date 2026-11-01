package com.lumina.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Android 侧接线（工单 04）：把 UsbManager 广播适配到 [UsbCameraStateMachine]。
 * 职责：
 * - ATTACHED：设备分类（PTP 才走主路径；MTP 疑似上报诊断，不触发会话）
 * - USB 权限弹窗（运行时权限，无需 manifest 权限——探针保持无联网/无敏感权限）
 * - DETACHED：状态归零；重插自动重新走 attach 流程
 */
class UsbCameraMonitor(
    private val context: Context,
    private val usbManager: UsbManager,
    /** 每次状态变化回调；kind 为最近一次接入设备的分类（未接入时 null） */
    private val onChange: (state: UsbCameraStateMachine.State, kind: UsbDeviceClassifier.DeviceKind?) -> Unit,
) {

    private val sm = UsbCameraStateMachine()
    private var lastKind: UsbDeviceClassifier.DeviceKind? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                    val kind = classify(device)
                    lastKind = kind
                    if (kind != UsbDeviceClassifier.DeviceKind.STILL_IMAGE_PTP) {
                        // 非 PTP（含 MTP 疑似）：记录诊断、不驱动状态机（不阻塞 PTP 主路径）
                        return
                    }
                    sm.on(UsbCameraStateMachine.Event.ATTACHED)
                    requestPermission(device)
                }
                ACTION_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val kind = device?.let { classify(it) }
                    if (kind != UsbDeviceClassifier.DeviceKind.STILL_IMAGE_PTP) return
                    sm.on(
                        if (granted) UsbCameraStateMachine.Event.PERMISSION_GRANTED
                        else UsbCameraStateMachine.Event.PERMISSION_DENIED,
                    )
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                    if (classify(device) == UsbDeviceClassifier.DeviceKind.STILL_IMAGE_PTP) {
                        lastKind = null
                        sm.on(UsbCameraStateMachine.Event.DETACHED)
                    }
                }
            }
            publish()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        context.registerReceiver(receiver, filter)
        publish()
    }

    fun currentState(): UsbCameraStateMachine.State = sm.currentState

    fun lastKind(): UsbDeviceClassifier.DeviceKind? = lastKind

    /** 分类已接入设备（供探针界面诊断显示）。 */
    private fun classify(device: UsbDevice): UsbDeviceClassifier.DeviceKind {
        val interfaceClasses = (0 until device.interfaceCount).map { device.getInterface(it).interfaceClass }
        return UsbDeviceClassifier.classify(device.deviceClass, interfaceClasses)
    }

    private fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_PERMISSION).setPackage(context.packageName),
            flags,
        )
        usbManager.requestPermission(device, intent)
    }

    private fun publish() = onChange(sm.currentState, lastKind)

    companion object {
        /** 私有权限应答 action（显式 setPackage 防域外劫持） */
        const val ACTION_PERMISSION = "com.lumina.USB_PERMISSION"
    }
}
