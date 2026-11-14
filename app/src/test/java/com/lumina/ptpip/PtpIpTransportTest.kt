package com.lumina.ptpip

import com.lumina.ptp.FakeCamera
import com.lumina.ptp.OpCode
import com.lumina.ptp.PtpDataType
import com.lumina.ptp.PtpSession
import com.lumina.ptp.PtpValue
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M5 工单 02：PTP-IP 传输对本地假相机的整事务验收。
 * 覆盖双通道握手、OpenSession、GetDeviceInfo（Data 相）、GetDevicePropDesc、InitiateCapture、关闭。
 */
class PtpIpTransportTest {

    @Test
    fun `full session over ptp-ip against loopback fake camera`() {
        val cam = FakePtpIpCamera()
        cam.start()
        try {
            val transport = PtpIpTransport(
                host = "127.0.0.1",
                port = cam.port,
                connectTimeoutMs = 3_000,
                ioTimeoutMs = 3_000,
            )
            val session = PtpSession(transport)
            session.open()
            assertEquals("NIKON Z 30", session.getModel())

            val desc = session.getDevicePropDesc(0x5008)
            assertEquals(0x5008, desc.code)
            assertEquals(5000L, (desc.current as PtpValue.Num).value)

            session.setDevicePropValue(0x5007, PtpDataType.UINT16, 560)
            assertArrayEquals(byteArrayOf(0x30, 0x02), cam.lastDataSet)

            session.initiateCapture()
            assertEquals(
                listOf(
                    OpCode.OPEN_SESSION,
                    OpCode.GET_DEVICE_INFO,
                    OpCode.GET_DEVICE_PROP_DESC,
                    OpCode.SET_DEVICE_PROP_VALUE,
                    com.lumina.ptp.NikonOpCode.CAPTURE,
                ),
                cam.seenOps.toList(),
            )
            session.close()
        } finally {
            cam.stop()
        }
    }
}

/** 只实现本测试用到的 4 个操作；帧布局与尼康方言一致。 */
private class FakePtpIpCamera {
    private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
    private var loopThread: Thread? = null
    val seenOps = mutableListOf<Int>()
    var lastDataSet: ByteArray = ByteArray(0)
        private set
    val port: Int get() = server.localPort

    fun start() {
        loopThread = Thread({ loop() }, "fake-ptpip").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        runCatching { server.close() }
        loopThread?.interrupt()
    }

    private fun loop() {
        try {
            server.accept().use { cmd ->
                val input = cmd.getInputStream()
                val output = cmd.getOutputStream()
                readFrame(input) // InitCommandRequest
                writeFrame(output, PtpIp.INIT_COMMAND_RESPONSE, initResponseBody())

                server.accept().use { ev ->
                    val evIn = ev.getInputStream()
                    val evOut = ev.getOutputStream()
                    readFrame(evIn) // InitEventRequest
                    writeFrame(evOut, PtpIp.INIT_EVENT_RESPONSE, ByteArray(0))

                    while (true) {
                        val frame = readFrame(input)
                        if (frame.type != PtpIp.OPERATION_REQUEST) continue
                        handle(input, output, frame.body)
                    }
                }
            }
        } catch (_: EOFException) {
            // 测试结束，客户端关闭
        } catch (_: Exception) {
            // 同上：连接被关闭
        }
    }

    private fun handle(input: InputStream, output: OutputStream, body: ByteArray) {
        val opcode = PtpIp.u16(body, 4)
        val tid = PtpIp.u32(body, 6)
        seenOps.add(opcode)
        when (opcode) {
            OpCode.OPEN_SESSION -> response(output, 0x2001, tid)
            OpCode.GET_DEVICE_INFO -> {
                data(output, tid, FakeCamera.deviceInfo("NIKON Z 30"))
                response(output, 0x2001, tid)
            }
            OpCode.GET_DEVICE_PROP_DESC -> {
                data(
                    output, tid,
                    FakeCamera.devicePropDesc(0x5008, PtpDataType.UINT32, 5000),
                )
                response(output, 0x2001, tid)
            }
            OpCode.SET_DEVICE_PROP_VALUE -> {
                lastDataSet = readDataOut(input)
                response(output, 0x2001, tid)
            }
            OpCode.INITIATE_CAPTURE -> response(output, 0x2001, tid)
            com.lumina.ptp.NikonOpCode.CAPTURE -> response(output, 0x2001, tid)
            OpCode.CLOSE_SESSION -> response(output, 0x2001, tid)
            else -> response(output, 0x2005, tid) // OperationNotSupported
        }
    }

    private fun readDataOut(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val frame = readFrame(input)
            when (frame.type) {
                PtpIp.START_DATA -> Unit
                PtpIp.DATA -> out.write(frame.body, 4, frame.body.size - 4)
                PtpIp.END_DATA -> {
                    out.write(frame.body, 4, frame.body.size - 4)
                    return out.toByteArray()
                }
                else -> throw IllegalStateException("数据出阶段收到未知包 type=${frame.type}")
            }
        }
    }

    private fun initResponseBody(): ByteArray {
        val name = "Z 30".toByteArray(Charsets.UTF_16LE)
        val body = ByteArray(4 + 16 + name.size + 2 + 4)
        PtpIp.putU32(body, 0, 1)
        name.copyInto(body, 20)
        PtpIp.putU32(body, 20 + name.size + 2, PtpIp.PROTOCOL_VERSION.toLong())
        return body
    }

    private fun data(output: OutputStream, tid: Long, payload: ByteArray) {
        val start = ByteArray(12)
        PtpIp.putU32(start, 0, tid)
        PtpIp.putU32(start, 4, payload.size.toLong())
        writeFrame(output, PtpIp.START_DATA, start)
        val end = ByteArray(4 + payload.size)
        PtpIp.putU32(end, 0, tid)
        payload.copyInto(end, 4)
        writeFrame(output, PtpIp.END_DATA, end)
    }

    private fun response(output: OutputStream, code: Int, tid: Long) {
        val body = ByteArray(6)
        PtpIp.putU16(body, 0, code)
        PtpIp.putU32(body, 2, tid)
        writeFrame(output, PtpIp.OPERATION_RESPONSE, body)
    }

    private fun writeFrame(output: OutputStream, type: Int, body: ByteArray) {
        val header = ByteArray(8)
        PtpIp.putU32(header, 0, (8 + body.size).toLong())
        PtpIp.putU32(header, 4, type.toLong())
        output.write(header)
        output.write(body)
        output.flush()
    }

    private fun readFrame(input: InputStream): PtpIp.Frame {
        val header = ByteArray(8)
        readFully(input, header)
        val length = PtpIp.u32(header, 0).toInt()
        val type = PtpIp.u32(header, 4).toInt()
        val body = ByteArray(length - 8)
        readFully(input, body)
        return PtpIp.Frame(type, body)
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }
}
