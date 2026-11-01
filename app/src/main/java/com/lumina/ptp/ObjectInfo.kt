package com.lumina.ptp

import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 对象格式 / 关联类型码（PIMA 15740）。相机专有格式（NEF / MOV 等）原样透传。
 */
object ObjectFormat {
    const val ASSOCIATION_FOLDER = 0x3001 // 关联类型同义（AssociationType 里实际用的是 0x0001）
    const val EXIF_JPEG = 0x3801
}

/** AssociationType：1 = 通用目录（PTP_AT_GenericFolder） */
const val ASSOC_TYPE_FOLDER = 0x0001

/**
 * GetObjectInfo (0x1008) 数据集（gphoto2 ptp_unpack_OI 对齐的真实厂商线格式）：
 *
 *   StorageID u32, ObjectFormat u16, ProtectionStatus u16, ObjectSize u32,
 *   ThumbFormat u16, ThumbSize u32, ThumbPixWidth u32, ThumbPixHeight u32,
 *   ImagePixWidth u32, ImagePixHeight u32, ImageBitDepth u32,
 *   ParentObject u32, AssociationType u16, AssociationDesc u32, SequenceNumber u32,
 *   Filename（MTP string）, CaptureDate（PTP TIME string）, ModificationDate（同前）
 *
 * 日期格式 "YYYYMMDDTHHMMSS"（ISO 8601 子集，无时区）→ 按 UTC 钉死换算 epoch：
 * 相机写的是本地时间（无时区信息），M0 探针按相机时钟原样分组即可。
 *
 * 已知厂商怪癖（05/06 号真机校验挂起项）：
 * - Samsung 某些机型 ObjectSize 是 64 位（gphoto2 有特判）；M0 先按 32 位实现。
 * - 部分相机 ParentObject 对根目录给出 0x00000000 或 0xFFFFFFFF，目录归一化见 ObjectCatalog。
 */
data class ObjectInfo(
    val storageId: Long,
    val format: Int,
    val sizeBytes: Long,
    val parentObject: Long,
    val associationType: Int,
    /** 文件名（MTP string 解出的短名；长名场景真机校验时确认） */
    val fileName: String,
    /** PTP TIME 原串，如 "20261031T123045"；空串 = 相机未提供 */
    val captureDateRaw: String,
    val modificationDateRaw: String,
) {
    val isFolder: Boolean get() = associationType == ASSOC_TYPE_FOLDER

    /** 拍摄日期优先；缺失时回退修改日期；都缺时为 null（时间戳毫秒）。 */
    val captureEpochMillis: Long?
        get() = dateToEpoch(captureDateRaw) ?: dateToEpoch(modificationDateRaw)

    companion object {
        /** PTP TIME 字符串 → epoch millis（UTC）；短于 15 位返回 null。 */
        fun dateToEpoch(raw: String): Long? {
            if (raw.length < 15) return null
            val y = raw.substring(0, 4).toIntOrNull() ?: return null
            val mo = raw.substring(4, 6).toIntOrNull() ?: return null
            val d = raw.substring(6, 8).toIntOrNull() ?: return null
            val h = raw.substring(9, 11).toIntOrNull() ?: return null
            val mi = raw.substring(11, 13).toIntOrNull() ?: return null
            val s = raw.substring(13, 15).toIntOrNull() ?: return null
            return runCatching {
                LocalDateTime.of(y, mo, d, h, mi, s).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()
        }
    }
}

/** 按上述线格式顺序游标读取（全部小端，字符串是 MTP string）。 */
fun parseObjectInfo(payload: ByteArray): ObjectInfo {
    val r = MtpReader(payload)
    val storageId = r.u32()
    val format = r.u16()
    r.u16()                 // ProtectionStatus
    val size = r.u32()      // ObjectSize
    r.u16()                 // ThumbFormat
    r.u32()                 // ThumbSize
    r.u32()                 // ThumbPixWidth
    r.u32()                 // ThumbPixHeight
    r.u32()                 // ImagePixWidth
    r.u32()                 // ImagePixHeight
    r.u32()                 // ImageBitDepth
    val parent = r.u32()    // ParentObject
    val associationType = r.u16()
    r.u32()                 // AssociationDesc
    r.u32()                 // SequenceNumber
    val fileName = r.mtpString()
    val captureDate = r.mtpString()
    val modificationDate = r.mtpString()
    // PIMA 15740:2000 在日期后还有 Keywords（gphoto2 也不读它）；M0 不消费
    return ObjectInfo(
        storageId = storageId,
        format = format,
        sizeBytes = size,
        parentObject = parent,
        associationType = associationType,
        fileName = fileName,
        captureDateRaw = captureDate,
        modificationDateRaw = modificationDate,
    )
}
