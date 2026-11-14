package com.lumina.pass

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Processing Pass 结果（一张 Master × 一个步骤 = 一条；M2 的"智能结果"）。 */
@Entity(tableName = "smart_results")
data class SmartResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Master 原始文件名 */
    val name: String,
    val cardId: String,
    /** 步骤类型（annotate/caption/filter_suggest/generative） */
    val kind: String,
    val text: String,
    val model: String,
    val createdAtMs: Long,
)

/** 补跑队列行（Room 真相：断线不丢；引擎是内存镜像）。 */
@Entity(tableName = "processing_queue")
data class PassQueueEntity(
    @PrimaryKey val name: String,
    val cardId: String,
    val queuedAtMs: Long,
)

@Dao
interface SmartResultsDao {
    @Insert
    fun insert(entry: SmartResultEntity): Long

    @Query("SELECT * FROM smart_results ORDER BY createdAtMs DESC LIMIT 500")
    fun recent(): List<SmartResultEntity>

    @Query("SELECT * FROM smart_results WHERE name = :name ORDER BY createdAtMs DESC")
    fun byName(name: String): List<SmartResultEntity>

    /** 图库删除 Master 时级联清该片全部智能结果（名称键控；无同名 Master 才调用）。 */
    @Query("DELETE FROM smart_results WHERE name = :name")
    fun deleteByName(name: String): Int
}

@Dao
interface PassQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entry: PassQueueEntity)

    @Query("DELETE FROM processing_queue WHERE name = :name")
    fun deleteByName(name: String): Int

    /** M2-07：删卡时级联清该卡的全部待处理行。 */
    @Query("DELETE FROM processing_queue WHERE cardId = :cardId")
    fun deleteByCardId(cardId: String): Int

    @Query("SELECT * FROM processing_queue ORDER BY queuedAtMs ASC")
    fun all(): List<PassQueueEntity>

    @Query("SELECT COUNT(*) FROM processing_queue")
    fun count(): Int
}
