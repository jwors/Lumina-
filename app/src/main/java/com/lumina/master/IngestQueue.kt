package com.lumina.master

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Ingest 队列表（工单 03）：勾选状态的持久化真相——断线不丢失，重连后还 原。
 * 真相唯一源=本表；IngestEngine 是会话内存镜像（重连时由本表重建队列）。
 */
@Entity(tableName = "ingest_queue")
data class IngestQueueEntity(
    /** 相机侧原始文件名（唯一键：重连后 handle 可能变化） */
    @PrimaryKey val name: String,
    val handle: Long,
    val sizeBytes: Long,
    /** 拍摄日期（epoch ms），可空 = 回退修改日期 */
    val captureMs: Long?,
    val modifiedMs: Long?,
    val queuedAtMs: Long,
)

@Dao
interface IngestQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entry: IngestQueueEntity)

    @Query("DELETE FROM ingest_queue WHERE name = :name")
    fun deleteByName(name: String): Int

    @Query("DELETE FROM ingest_queue WHERE name IN (:names)")
    fun deleteByNames(names: List<String>): Int

    @Query("SELECT * FROM ingest_queue ORDER BY queuedAtMs ASC")
    fun all(): List<IngestQueueEntity>

    @Query("DELETE FROM ingest_queue")
    fun clear()
}
