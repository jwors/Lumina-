package com.lumina.remote

/**
 * 定时/间隔拍摄的纯计划器（不碰 Android/相机，便于单测）。
 *
 * 第 i 张的计划时刻 = [startAt] + i * [intervalMs]（i 从 0 起）。
 * [startAt] 已经包含"延时开始"秒数。
 */
data class IntervalPlan(
    val startAt: Long,
    val intervalMs: Long,
    val count: Int,
) {
    init {
        require(intervalMs > 0) { "intervalMs must be > 0" }
        require(count > 0) { "count must be > 0" }
    }

    fun shotAt(index: Int): Long = startAt + index.toLong() * intervalMs

    /** 截至 [now] 应该已经拍了几张（含到点那一张），封顶 [count]。 */
    fun dueIndex(now: Long): Int =
        if (now < startAt) 0
        else (((now - startAt) / intervalMs) + 1).toInt().coerceAtMost(count)

    /** 最后一张拍完后整组结束的时刻。 */
    val endAt: Long get() = shotAt(count - 1)
}
