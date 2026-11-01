package com.lumina.ptp

/**
 * 环形会话日志（03 号工单）：只保留最近 [capacity] 行（默认 200），
 * 供探针 UI 在 USB 连接期间滚动显示最新的协议往来。
 * 线程性：M0 探针是单线程顺序驱动会话，暂不加锁（票内注明）。
 */
class RingLog(private val capacity: Int = 200) {

    private val lines = ArrayDeque<String>(capacity)

    fun log(line: String) {
        while (lines.size >= capacity) lines.removeFirst()
        lines.addLast(line)
    }

    /** 快照读取：返回当前全部行的不可变拷贝（从旧到新）。 */
    fun snapshot(): List<String> = lines.toList()

    val size: Int get() = lines.size

    fun clear() = lines.clear()
}
