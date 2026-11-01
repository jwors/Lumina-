package com.lumina.transport

import java.nio.ByteBuffer

/**
 * M0 唯一新缝隙（见 .scratch/m0-usb-ptp/spec.md）：
 * transport 层之上的协议层（ptp 包）只知道"打开 → 字节块读写 → 关闭"，
 * 不知道 USB 的存在。02/03 号工单的全部逻辑都对着本接口跑 JVM 单测。
 */
interface Transport : AutoCloseable {

    /** 打开通道。必须先于任何 IO 调用；一个 Transport 实例只 open 一次。 */
    fun open()

    /** 写出一个字节块，返回实际写出的字节数。 */
    fun write(chunk: ByteArray): Int

    /**
     * 读入一个字节块填充 buffer，返回读到的字节数（0 = 流结束）。
     * 语义与底层流一致：返回值 < 需求数时不丢数据，余量留待下一次 read。
     */
    fun read(buffer: ByteBuffer): Int

    /** 终结操作；之后本实例不可再 open。 */
    override fun close()
}
