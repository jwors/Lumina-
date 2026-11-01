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
 * Android 侧接线（工单 04/05）：把 UsbManager 广播适配到 [UsbCameraStateMachine]。
 * 回调参数携带最近一次接入的 [UsbDevice]（授权后就靠它开 UsbTransport）。
 * 隐私：USB host 模式 + requestPermission 运行时机制，无需任何 manifest 权限。
 */
class UsbCameraMonitor(
    private val context: Context,
    private val usbManager: UsbManager,
    /** (状态, 设备分类, 设备)；未接入时后两者为 null */
    private val onChange: (
        state: UsbCameraStateMachine.State,
        kind: UsbDeviceClassifier.DeviceKind?,
        device: UsbDevice?,
    ) -> Unit,
) {

    private val sm = UsbCameraStateMachine()
    private var lastDevice: UsbDevice? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onAction { attach(intent) }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onAction { detach(intent) }
                ACTION_PERMISSION -> onAction { permissionAnswer(intent) }
            }
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

    /** 广播里 Parcelable 异常不影响探针存活：所有分支包一层兜底 */
    private fun onAction(block: () -> Unit) {
        try {
            block()
        } finally {
            publish()
        }
    }

    private fun attach(intent: Intent) {
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
        val kind = classify(device)
        if (kind != UsbDeviceClassifier.DeviceKind.STILL_IMAGE_PTP) {
            // 非 PTP（MTP 疑似等）：记录最近设备供诊断展示，不驱动状态机
            lastDevice = device
            return
        }
        lastDevice = device
        sm.on(UsbCameraStateMachine.Event.ATTACHED)
        requestPermission(device)
    }

    private fun detach(intent: Intent) {
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
        if (classify(device) != UsbDeviceClassifier.DeviceKind.STILL_IMAGE_PTP) return
        lastDevice = null
        if (sm.currentState != UsbCameraStateMachine.State.NOT_CONNECTED) {
            sm.on(UsbCameraStateMachine.Event.DETACHED)
        }
    }

    private fun permissionAnswer(intent: Intent) {
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
        if (classify(device) != UsbDeviceClassifier.DeviceKind.STILL_IMAGE_PTP) return
        sm.on(
            if (granted) UsbCameraStateMachine.Event.PERMISSION_GRANTED
            else UsbCameraStateMachine.Event.PERMISSION_DENIED,
        )
    }

    fun currentState(): UsbCameraStateMachine.State = sm.currentState

    /** 设备分类（供诊断显示）；未接入为 null。 */
    fun lastKind(): UsbDeviceClassifier.DeviceKind? =
        lastDevice?.let { classify(it) }

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

    private fun publish() {
        onChange(sm.currentState, lastKind(), lastDevice)
    }

    companion object {
        /** 私有权限应答 action（显式 setPackage 防域外劫持） */
        const val ACTION_PERMISSION = "com.lumina.USB_PERMISSION"
    }
}
