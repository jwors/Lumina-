package com.lumina.transport

import java.nio.ByteBuffer

/**
 * M0 唯一新缝隙（见 .scratch/m0-usb-ptp/spec.md）：
 * transport 层之上的协议层（ptp 包）只知道"有序字节块的读写"，
 * 不知道 USB 的存在。02/03 号工单的全部逻辑都对着本接口跑 JVM 单测。
 */
interface Transport : AutoCloseable {
    /** 写出一个字节块，返回实际写出的字节数。 */
    fun write(chunk: ByteArray): Int

    /** 读入一个字节块填充 buffer，返回读到的字节数（0 = 流结束）。 */
    fun read(buffer: ByteBuffer): Int

    override fun close()
}

/** 缝隙契约断言：读写必须成对可往返，close 幂等。 */
interface TransportContract {
    fun open(): Transport
}
