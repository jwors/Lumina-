package com.lumina.llm

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 明细日志（Ledger，GLOSSARY）：云端调用的逐条行内记录（story 20 的云端版）。
 * 不存 prompt 全文（隐私面收敛），只存长度与模型与成败。
 */
@Entity(tableName = "ledger")
data class LedgerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 触发的对象名（Master 引用；-- 批量占位预留） */
    val objectName: String,
    val model: String,
    val promptChars: Int,
    /** 是否带图（视觉调用 / 纯文本调用） */
    val withImage: Boolean,
    val outcome: String,          // OK / RateLimited / Http_400 / NetworkTimeout / Error
    val promptTokens: Int?,       // usage（可空：失败/无 usage）
    val completionTokens: Int?,
    val createdAtMs: Long,
)

@Dao
interface LedgerDao {
    @Insert
    fun insert(entry: LedgerEntity): Long

    @Query("SELECT * FROM ledger ORDER BY createdAtMs DESC LIMIT 200")
    fun recent(): List<LedgerEntity>

    @Query("SELECT COUNT(*) FROM ledger WHERE outcome = 'OK'")
    fun okCount(): Int
}
