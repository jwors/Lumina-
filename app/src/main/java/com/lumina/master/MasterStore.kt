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

    sealed interface RegisterResult {
        data class Registered(val relPath: String) : RegisterResult
        data class AlreadyPresent(val relPath: String) : RegisterResult
        data class MissingFile(val relPath: String, val reason: String) : RegisterResult
    }
}
