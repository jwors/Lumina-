package com.lumina.remote

import com.lumina.ptp.ObjectFormat
import com.lumina.ptp.ObjectInfo

/** 相机对象 + handle（GetObjectInfo 不带 handle，由枚举方配对）。 */
data class ObjectCandidate(val handle: Long, val info: ObjectInfo)

/** 拍后回传的选择逻辑：只挑新出现的 JPEG，取 handle 最大（最新）那张。 */
object AutoDownload {
    fun pickNewestJpeg(candidates: List<ObjectCandidate>): ObjectCandidate? =
        candidates
            .filter { !it.info.isFolder && it.info.format == ObjectFormat.EXIF_JPEG }
            .maxByOrNull { it.handle }
}
