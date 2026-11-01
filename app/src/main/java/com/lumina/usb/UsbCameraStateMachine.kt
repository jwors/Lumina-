package com.lumina.usb

/**
 * 相机连接状态机（工单 04）：纯逻辑，JVM 可测；Android 侧仅做事件适配。
 *
 * NOT_CONNECTED --attach--> PENDING_PERMISSION --granted--> READY
 *        ↑                        | denied                    |
 *        └─────── detach ─────────┴─────────── detach ────────┘
 * 重插 → 重新走 attach 流程（自动尝试重连，第一次需用户确认权限）。
 */
class UsbCameraStateMachine {

    enum class State { NOT_CONNECTED, PENDING_PERMISSION, READY }

    enum class Event { ATTACHED, DETACHED, PERMISSION_GRANTED, PERMISSION_DENIED }

    private var state: State = State.NOT_CONNECTED

    val currentState: State get() = state

    /** 处理事件并返回新状态；非法事件序抛 IllegalStateException（非法状态显式抛错原则）。 */
    fun on(event: Event): State {
        state = when (event) {
            Event.ATTACHED -> when (state) {
                State.NOT_CONNECTED -> State.PENDING_PERMISSION
                else -> throw IllegalStateException("attach while $state")
            }
            Event.PERMISSION_GRANTED -> when (state) {
                State.PENDING_PERMISSION -> State.READY
                else -> throw IllegalStateException("granted while $state")
            }
            Event.PERMISSION_DENIED -> when (state) {
                State.PENDING_PERMISSION -> State.PENDING_PERMISSION // 留在待授权：界面引导用户重试
                else -> throw IllegalStateException("denied while $state")
            }
            Event.DETACHED -> when (state) {
                State.NOT_CONNECTED -> throw IllegalStateException("detach while NOT_CONNECTED")
                else -> State.NOT_CONNECTED
            }
        }
        return state
    }
}
