package com.lumina.remote

/**
 * 曝光包围计划：EV 以 1/1000 EV 为单位（PTP 0x5010 的口径）。
 * 第 i 张 = start + i * step。
 */
data class ExposureBracketPlan(
    val startMilliEv: Int,
    val stepMilliEv: Int,
    val count: Int,
) {
    init {
        require(count in 1..99) { "count out of range: $count" }
        require(count == 1 || stepMilliEv != 0) { "step must be non-zero for count > 1" }
    }

    fun evAt(index: Int): Int = (startMilliEv + index * stepMilliEv).coerceIn(-5000, 5000)

    val values: List<Int> get() = List(count) { evAt(it) }
}

/**
 * 对焦包围计划：MfDrive 的 (direction, step) 两参。
 * direction 由 UI 给（1=近→远 / 0=远→近），结束按 returnStep 反向回位。
 */
data class FocusBracketPlan(
    val step: Int,
    val count: Int,
    val direction: Int,
) {
    init {
        require(step in 1..20) { "step out of range: $step" }
        require(count in 1..99) { "count out of range: $count" }
        require(direction == 0 || direction == 1) { "direction must be 0 or 1" }
    }

    val oppositeDirection: Int get() = if (direction == 1) 0 else 1

    /** 拍完回位需要的总步数（第一张就是起点，不需要回）。 */
    val returnStep: Int get() = step * (count - 1)
}
