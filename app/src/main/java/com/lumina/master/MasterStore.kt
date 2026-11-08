package com.lumina.master

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Master 目录布局（ADR-0004）：`<root>/Master/<yyyy-MM-dd>/<保留原文件名>`
 * 日期源：拍摄日期优先，回退文件修改日期（调用方决定传哪个，这里只负责格式化）。
 */
object MasterPaths {

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

    /** 相机给出拍摄日期（epoch ms）用它；否则 fallbackModifiedMs。两者都无效用 1970 占位。 */
    fun relativePath(captureMs: Long?, fallbackModifiedMs: Long?, origFileName: String): String {
        val dateMs = captureMs ?: fallbackModifiedMs ?: 0L
        val dateKey = DATE_FMT.format(Instant.ofEpochMilli(dateMs))
        return "Master/$dateKey/$origFileName"
    }

    fun dateKeyOf(captureMs: Long?, fallbackModifiedMs: Long?): String =
        relativePath(captureMs, fallbackModifiedMs, "x")
            .removePrefix("Master/").substringBefore('/')
}

/** 盘点存取的窄接口：Room 实装 + 测试内存实装共用。 */
interface InventoryStore {
    fun insert(entry: MasterInventoryEntity): Long
    fun findByRelPath(relPath: String): MasterInventoryEntity?
    fun all(): List<MasterInventoryEntity>
    fun deleteByRelPath(relPath: String): Int
    /** 覆盖 Master 后体积同步（M3 保存收尾用）。 */
    fun updateSizeBytes(relPath: String, sizeBytes: Long): Int
}

/** JVM/单测用内存实现（语义与 Room DAO 对齐）。 */
class InMemoryInventoryStore : InventoryStore {    private val rows = mutableListOf<MasterInventoryEntity>()
    private var nextId = 1L

    override fun insert(entry: MasterInventoryEntity): Long {
        val id = nextId++
        rows += entry.copy(id = id)
        return id
    }

    override fun findByRelPath(relPath: String): MasterInventoryEntity? =
        rows.firstOrNull { it.relPath == relPath }

    override fun all(): List<MasterInventoryEntity> = rows.toList()

    override fun deleteByRelPath(relPath: String): Int {
        val count = rows.count { it.relPath == relPath }
        rows.removeAll { it.relPath == relPath }
        return count
    }

    override fun updateSizeBytes(relPath: String, sizeBytes: Long): Int {
        val idx = rows.indexOfFirst { it.relPath == relPath }
        if (idx < 0) return 0
        rows[idx] = rows[idx].copy(sizeBytes = sizeBytes)
        return 1
    }
}

/**
 * Master 门面（ADR-0004）：复制后调用 [register] 登记；图库读取走 [snapshot]；
 * [pruneAbsent] 做"文件在即为准"的对账。
 */
class MasterStore(
    private val root: File,
    private val inventory: InventoryStore,
) {

    /** 复制引擎把文件放到 dest 后调用：校验存在 + 登记（同名已存在则跳过不覆盖）。 */
    fun register(
        captureMs: Long?,
        fallbackModifiedMs: Long?,
        origFileName: String,
        sizeBytes: Long,
        ingestedAtMs: Long = System.currentTimeMillis(),
    ): RegisterResult {
        val relPath = MasterPaths.relativePath(captureMs, fallbackModifiedMs, origFileName)
        if (inventory.findByRelPath(relPath) != null) return RegisterResult.AlreadyPresent(relPath)
        val file = fileOf(relPath)
        if (!file.exists()) return RegisterResult.MissingFile(relPath, "file not copied: ${file.path}")
        inventory.insert(
            MasterInventoryEntity(
                relPath = relPath,
                origFileName = origFileName,
                dateKey = MasterPaths.dateKeyOf(captureMs, fallbackModifiedMs),
                sizeBytes = sizeBytes,
                ingestedAtMs = ingestedAtMs,
            ),
        )
        return RegisterResult.Registered(relPath)
    }

    fun fileOf(relPath: String): File = File(root, relPath)

    /** 覆盖 Master 后体积同步（M3 保存收尾用）。 */
    fun updateSizeBytes(relPath: String, sizeBytes: Long): Int =
        inventory.updateSizeBytes(relPath, sizeBytes)

    /** 同名/同路径是否已登记（去重判断用）。 */
    fun findByRelPath(relPath: String): MasterInventoryEntity? = inventory.findByRelPath(relPath)

    /** 图库快照（日期分组由调用方基于 dateKey/re者 path 干）。 */
    fun snapshot(): List<MasterInventoryEntity> = inventory.all().sortedWith(
        compareByDescending<MasterInventoryEntity> { it.dateKey }.thenBy { it.origFileName },
    )

    /** 对账：盘点里有但文件不存的条目作废。返回清除数。 */
    fun pruneAbsent(): Int {
        var removed = 0
        for (entry in inventory.all()) {
            if (!fileOf(entry.relPath).exists()) {
                inventory.deleteByRelPath(entry.relPath)
                removed++
            }
        }
        return removed
    }

    /**
     * 对账另一向：目录里存在但盘点缺条目的文件 → 收养（Room 补登记）。
     * relPath 直接来自磁盘发现（不按日期重derive，尊重物理位置）。
     */
    fun adoptExisting(relPath: String): Boolean {
        val file = fileOf(relPath)
        if (!file.exists()) return false
        if (inventory.findByRelPath(relPath) != null) return false
        // dateKey 取路径内第二段（按布局契约）；"+" 开头的坏目录不算有效日期
        val second = relPath.removePrefix("Master/").substringBefore('/')
        val dateKey = second.takeIf { second.length == 10 && !second.startsWith("+") }
            ?: MasterPaths.dateKeyOf(null, file.lastModified())
        inventory.insert(
            MasterInventoryEntity(
                relPath = relPath,
                origFileName = file.name,
                dateKey = dateKey,
                sizeBytes = file.length(),
                ingestedAtMs = System.currentTimeMillis(),
            ),
        )
        return true
    }


    /** 历史数据修复：double-multiplied dateKey（目录像 "+57687-03-19"）：
     * 错误毫秒 = 真实毫秒 × 1000（当时把框架已给的毫秒再当秒乘了一遍）→ 反推真实日期。
     * 返回 (oldRelPath, newRelPath, 新 dateKey) 迁移三元组；识别不出 + 前缀的返回空表。
     */
    fun repairsForDateDoubleBind(): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        for (entry in inventory.all()) {
            if (!entry.dateKey.startsWith("+")) continue
            val wrongDate = try {
                java.time.LocalDate.parse(entry.dateKey.removePrefix("+"))
            } catch (_: Exception) {
                continue
            }
            val wrongMs = wrongDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            val trueMs = wrongMs / 1000
            val trueKey = MasterPaths.dateKeyOf(trueMs, null)
            val namePart = entry.relPath.substringAfterLast('/')
            out += Triple(entry.relPath, "Master/$trueKey/$namePart", trueKey)
        }
        return out
    }

    /** 把清单行指向修复后的路径/日期（表内改写）。 */
    fun repointDate(oldRel: String, newRel: String, newDateKey: String) {
        val entry = inventory.findByRelPath(oldRel) ?: return
        inventory.deleteByRelPath(oldRel)
        inventory.insert(entry.copy(relPath = newRel, dateKey = newDateKey))
    }

    /** 对账另一向：目录里存在但盘点缺条目的文件 → 收养（Room 补登记）。 */
    fun adoptExistingDiskFile(relPath: String): Boolean {
        val file = fileOf(relPath)
        if (!file.exists()) return false
        if (inventory.findByRelPath(relPath) != null) return false
        // dateKey 取路径内第二段（按布局契约）；取不到回退修改日期
        val second = relPath.removePrefix("Master/").substringBefore('/')
        val dateKey = second.takeIf { it.length == 10 && it[0] != '+' }
            ?: MasterPaths.dateKeyOf(null, file.lastModified())
        inventory.insert(
            MasterInventoryEntity(
                relPath = relPath,
                origFileName = file.name,
                dateKey = dateKey,
                sizeBytes = file.length(),
                ingestedAtMs = System.currentTimeMillis(),
            ),
        )
        return true
    }

    sealed interface RegisterResult {
        data class Registered(val relPath: String) : RegisterResult
        data class AlreadyPresent(val relPath: String) : RegisterResult
        data class MissingFile(val relPath: String, val reason: String) : RegisterResult
    }
}
