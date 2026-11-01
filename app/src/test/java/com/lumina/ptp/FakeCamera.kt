package com.lumina.ptp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * 测试共享的"伪相机字节构建器"（code-review 去重结果：
 * PtpSessionTest 与 ObjectCatalogTest 曾各有一份且已漂移——字符串终止符不一致）。
 * 唯一约定 = 真机口径：PTP string 的 u8 长度含终结 NUL。
 */
object FakeCamera {

    fun bb(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    fun u16(v: Int): ByteArray = bb(2).putShort(v.toShort()).array()

    fun u32(v: Long): ByteArray = bb(4).putInt(v.toInt()).array()

    /** 容器：u32 length + u16 type + u16 code + u32 tid + payload（头部 12 字节） */
    fun container(type: Int, code: Int, tid: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        u32((12 + payload.size).toLong()) + u16(type) + u16(code) + u32(tid.toLong()) + payload

    fun resp(code: Int, tid: Int): ByteArray = container(ContainerType.RESPONSE, code, tid)

    fun dataPack(code: Int, tid: Int, payload: ByteArray): ByteArray =
        container(ContainerType.DATA, code, tid, payload)

    /** PTP string：u8 长度（含终结 NUL）+ UTF-16LE 字符 + NUL；空串 = 单个 0 字节 */
    fun mtpString(s: String): ByteArray {
        if (s.isEmpty()) return byteArrayOf(0)
        val chars = s.toByteArray(StandardCharsets.UTF_16LE)
        val lenInChars = chars.size / 2
        require(lenInChars <= 255) { "PTP string max 255 chars, got $lenInChars" }
        return byteArrayOf((lenInChars + 1).toByte()) + chars + byteArrayOf(0, 0)
    }

    /** uint32 计数 + 若干 uint32 元素 */
    fun u32Array(vararg values: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u32(values.size.toLong()))
        for (v in values) out.write(u32(v))
        return out.toByteArray()
    }

    /** uint32 计数 + 若干 uint16 元素（DeviceInfo 代码数组） */
    fun u16Array(vararg codes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u32(codes.size.toLong()))
        for (c in codes) out.write(u16(c))
        return out.toByteArray()
    }

    /** DeviceInfo 数据集（M0 只消费 Model 字段） */
    fun deviceInfo(model: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u16(0x0100))                       // StandardVersion
        out.write(u32(6L))                           // VendorExtensionID (MTP)
        out.write(u16(0x0100))                       // VendorExtensionVersion
        out.write(mtpString("microsoft.com/1.0"))    // VendorExtensionDesc
        out.write(u16(0))                            // FunctionalMode
        out.write(u16Array(0x1001, 0x1002))          // Operationcodes
        out.write(u16Array())                        // Eventcodes
        out.write(u16Array())                        // DevicePropcodes
        out.write(u16Array())                        // CaptureFormats
        out.write(u16Array(0x3801))                  // ImageFormats (EXIF/JPEG)
        out.write(mtpString("Nikon"))                // Manufacturer
        out.write(mtpString(model))                  // Model
        out.write(mtpString("1.00"))                 // DeviceVersion
        out.write(mtpString(""))                     // SerialNumber
        return out.toByteArray()
    }

    /** ObjectInfo 数据集（gphoto2 ptp_unpack_OI 线格式） */
    fun objectInfo(
        name: String,
        format: Int = 0,
        size: Long = 0,
        parent: Long = 0,
        assocType: Int = 0,
        captureDate: String = "",
        modificationDate: String = "",
        storageId: Long = 0x00010001,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u32(storageId))             // StorageID
        out.write(u16(format))                // ObjectFormat
        out.write(u16(0))                     // ProtectionStatus
        out.write(u32(size))                  // ObjectSize
        out.write(u16(0))                     // ThumbFormat
        out.write(u32(0))                     // ThumbSize
        out.write(u32(0)); out.write(u32(0))  // ThumbPixWidth/Height
        out.write(u32(0)); out.write(u32(0))  // ImagePixWidth/Height
        out.write(u32(0))                     // ImageBitDepth
        out.write(u32(parent))                // ParentObject
        out.write(u16(assocType))             // AssociationType
        out.write(u32(0))                     // AssociationDesc
        out.write(u32(0))                     // SequenceNumber
        out.write(mtpString(name))            // Filename
        out.write(mtpString(captureDate))     // CaptureDate
        out.write(mtpString(modificationDate)) // ModificationDate
        return out.toByteArray()
    }
}
