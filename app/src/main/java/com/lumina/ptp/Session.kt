package com.lumina.ptp

/**
 * PTP 会话状态机骨架（02 号工单补全执行逻辑）。
 * 状态推进只允许：Closed -> Open -> Ready -> Closed；
 * 任何跨界调用为非法序列，由引擎显式抛错（对应 spec 的非法操作序断言）。
 */
sealed interface SessionState {
    data object Closed : SessionState
    data object Open : SessionState
    data object Ready : SessionState
}
