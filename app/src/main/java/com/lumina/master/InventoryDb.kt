package com.lumina.master

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import android.content.Context
import androidx.room.Room

import com.lumina.llm.LedgerDao
import com.lumina.llm.LedgerEntity
import com.lumina.llm.LlmConfigDao
import com.lumina.llm.LlmConfigEntity

/**
 * Master 盘点表（ADR-0004）：目录布局是物理真相，本表是查询/去重真相；
 * 两者不一致时按"文件在即为准"由 MasterStore.pruneAbsent 做 reconcile。
 */
@Entity(tableName = "master_inventory")
data class MasterInventoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 相对 Master 根的路径，如 "2026-10-31/DSC_N0379.JPG" */
    val relPath: String,
    /** 相机侧原始文件名（保留原命名） */
    val origFileName: String,
    /** yyyy-MM-dd（拍摄日期，缺省回退修改日期） */
    val dateKey: String,
    val sizeBytes: Long,
    val ingestedAtMs: Long,
)

@Dao
interface MasterInventoryDao {
    @Insert
    fun insert(entry: MasterInventoryEntity): Long

    @Query("SELECT * FROM master_inventory WHERE relPath = :relPath LIMIT 1")
    fun findByRelPath(relPath: String): MasterInventoryEntity?

    @Query("SELECT * FROM master_inventory ORDER BY dateKey DESC, origFileName ASC")
    fun all(): List<MasterInventoryEntity>

    @Query("DELETE FROM master_inventory WHERE relPath = :relPath")
    fun deleteByRelPath(relPath: String): Int

    @Query("DELETE FROM master_inventory")
    fun clear()
}

@Database(
    entities = [MasterInventoryEntity::class, IngestQueueEntity::class, LlmConfigEntity::class, LedgerEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun masterInventoryDao(): MasterInventoryDao
    abstract fun ingestQueueDao(): IngestQueueDao
    abstract fun llmConfigDao(): LlmConfigDao
    abstract fun ledgerDao(): LedgerDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                // M1 开发期破坏性迁移（无用户库可丢）；M2 起换正式 migration
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lumina.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
