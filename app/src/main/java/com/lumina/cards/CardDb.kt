package com.lumina.cards

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** 卡库实体：stepsJson 保存 v1 schema 原文（解析按需）。 */
@Entity(tableName = "skill_cards")
data class CardEntity(
    @PrimaryKey val cardId: String,
    val name: String,
    val version: Int,
    val stepsJson: String,
    /** 内置种子=false；SAF 导入=true */
    val imported: Boolean,
    val createdAtMs: Long,
)

@Dao
interface CardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entry: CardEntity)

    @Query("SELECT * FROM skill_cards ORDER BY createdAtMs ASC")
    fun all(): List<CardEntity>

    @Query("SELECT COUNT(*) FROM skill_cards")
    fun count(): Int

    @Query("SELECT * FROM skill_cards WHERE cardId = :cardId LIMIT 1")
    fun byId(cardId: String): CardEntity?
}
