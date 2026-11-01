package com.lumina.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 工单 04 纯逻辑验收：设备分类 + 连接状态机（USB 胶水层由 05/06 真机验证）。
 */
class UsbDeviceLogicTest {

    // ---------- UsbDeviceClassifier ----------

    @Test
    fun `still image device class means PTP`() {
        assertEquals(
            UsbDeviceClassifier.DeviceKind.STILL_IMAGE_PTP,
            UsbDeviceClassifier.classify(deviceClass = 6, interfaceClasses = listOf(0, 1)),
        )
    }

    @Test
    fun `still image interface class means PTP even when device class is per-interface`() {
        assertEquals(
            UsbDeviceClassifier.DeviceKind.STILL_IMAGE_PTP,
            UsbDeviceClassifier.classify(deviceClass = 0, interfaceClasses = listOf(6)),
        )
    }

    @Test
    fun `vendor specific interface is flagged as mtp suspect`() {
        assertEquals(
            UsbDeviceClassifier.DeviceKind.MTP_SUSPECT,
            UsbDeviceClassifier.classify(deviceClass = 0, interfaceClasses = listOf(0xFF)),
        )
    }

    @Test
    fun `unrelated device kind is other`() {
        assertEquals(
            UsbDeviceClassifier.DeviceKind.OTHER,
            UsbDeviceClassifier.classify(deviceClass = 9, interfaceClasses = listOf(9, 1)),
        )
    }

    // ---------- UsbCameraStateMachine ----------

    private fun forward(vararg events: UsbCameraStateMachine.Event): UsbCameraStateMachine {
        val sm = UsbCameraStateMachine()
        events.forEach { sm.on(it) }
        return sm
    }

    @Test
    fun `happy path attach pending ready detach`() {
        val sm = forward(
            UsbCameraStateMachine.Event.ATTACHED,
            UsbCameraStateMachine.Event.PERMISSION_GRANTED,
        )
        assertEquals(UsbCameraStateMachine.State.READY, sm.currentState)
        sm.on(UsbCameraStateMachine.Event.DETACHED)
        assertEquals(UsbCameraStateMachine.State.NOT_CONNECTED, sm.currentState)
    }

    @Test
    fun `permission denied stays pending for retry guidance`() {
        val sm = forward(
            UsbCameraStateMachine.Event.ATTACHED,
            UsbCameraStateMachine.Event.PERMISSION_DENIED,
        )
        assertEquals(UsbCameraStateMachine.State.PENDING_PERMISSION, sm.currentState)
    }

    @Test
    fun `detach from any connected state returns to not connected`() {
        val fromPending = forward(UsbCameraStateMachine.Event.ATTACHED)
        fromPending.on(UsbCameraStateMachine.Event.DETACHED)
        assertEquals(UsbCameraStateMachine.State.NOT_CONNECTED, fromPending.currentState)

        val fromReady = forward(
            UsbCameraStateMachine.Event.ATTACHED,
            UsbCameraStateMachine.Event.PERMISSION_GRANTED,
        )
        fromReady.on(UsbCameraStateMachine.Event.DETACHED)
        assertEquals(UsbCameraStateMachine.State.NOT_CONNECTED, fromReady.currentState)
    }

    @Test
    fun `illegal sequences are rejected`() {
        assertThrows(IllegalStateException::class.java) {
            forward(UsbCameraStateMachine.Event.PERMISSION_GRANTED) // 未接入就授权
            Unit
        }
        val sm = forward(UsbCameraStateMachine.Event.ATTACHED)
        assertThrows(IllegalStateException::class.java) {
            sm.on(UsbCameraStateMachine.Event.ATTACHED) // 重复接入
            Unit
        }
        val empty = UsbCameraStateMachine()
        assertThrows(IllegalStateException::class.java) {
            empty.on(UsbCameraStateMachine.Event.DETACHED) // 未接入就拔出
            Unit
        }
    }
}
