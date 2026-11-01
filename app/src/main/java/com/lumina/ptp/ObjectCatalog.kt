package com.lumina.ptp

/**
 * 03 号工单：对象清单遍历与汇总。
 *
 * 策略：对存储做一次 GetObjectHandles(parent=PARENT_ALL) 全量枚举，
 * 每个句柄一次 GetObjectInfo；层级与目录判定直接来自 ObjectInfo 数据集
 * （ParentObject / AssociationType 字段——gphoto2 与 Canon 真机示例一致），
 * 不做逐父下钻。终止性由"句柄唯一 + visited 集合"保证。
 */
data class CameraObject(
    val handle: Long,
    /** 归一化后的父句柄；root 见 [ObjectCatalog.PARENT_ROOT] */
    val parent: Long,
    val isFolder: Boolean,
    val name: String,
    val sizeBytes: Long,
    val captureEpochMillis: Long?,
)

/** 日期分组：day = epoch 天（floorDiv)；null 分组 = 无拍摄/修改日期的对象。 */
data class DateGroup(val day: Long?, val objects: List<CameraObject>)

data class CatalogSummary(
    val files: List<CameraObject>,
    val folders: List<CameraObject>,
) {
    val totalCount: Int get() = files.size + folders.size
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /** 按拍摄日期（缺失回退修改日期）分组，从新到旧；无日期的排最后。 */
    val byDate: List<DateGroup>
        get() {
            val dated = files.filter { it.captureEpochMillis != null }
                .groupBy { floorDay(it.captureEpochMillis!!) }
                .map { (day, objs) -> DateGroup(day, objs.sortedByDescending { it.captureEpochMillis }) }
                .sortedByDescending { it.day }
            val undated = files.filter { it.captureEpochMillis == null }
            return if (undated.isEmpty()) dated else dated + DateGroup(null, undated)
        }

    private fun floorDay(millis: Long): Long = Math.floorDiv(millis, MILLIS_PER_DAY)

    private companion object {
        const val MILLIS_PER_DAY = 24L * 3600 * 1000
    }
}

class ObjectCatalog(private val session: PtpSession) {

    /** 归一化根句柄：相机可能对根目录报 0 或 0xFFFFFFFF，统一成 -1 哨兵值。 */
    companion object {
        const val PARENT_ROOT = -1L

        /** 多存储分区清单合并（ProbeController 复用，避免逐次手写 reduce）。 */
        fun merge(parts: List<CatalogSummary>): CatalogSummary = CatalogSummary(
            files = parts.flatMap { it.files },
            folders = parts.flatMap { it.folders },
        )
    }

    fun traverse(storageId: Long): CatalogSummary {
        val handles = session.getObjectHandles(storageId)
        val folders = mutableListOf<CameraObject>()
        val files = mutableListOf<CameraObject>()
        val visited = mutableSetOf<Long>()
        for (handle in handles) {
            if (!visited.add(handle)) continue // 相机重复给同一句柄：只取一次，杜绝环
            val info = session.getObjectInfo(handle)
            val parent = normalizeParent(info.parentObject)
            val obj = CameraObject(
                handle = handle,
                parent = parent,
                isFolder = info.isFolder,
                name = info.fileName,
                sizeBytes = info.sizeBytes,
                captureEpochMillis = info.captureEpochMillis,
            )
            if (obj.isFolder) folders.add(obj) else files.add(obj)
        }
        return CatalogSummary(files = files, folders = folders)
    }

    private fun normalizeParent(raw: Long): Long =
        if (raw == 0L || raw == PtpSession.PARENT_ALL) PARENT_ROOT else raw
}
