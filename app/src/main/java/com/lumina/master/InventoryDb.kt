package com.lumina.master

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import androidx.room.Room

import com.lumina.cards.CardDao
import com.lumina.cards.CardEntity
import com.lumina.llm.LedgerDao
import com.lumina.llm.LedgerEntity
import com.lumina.llm.LlmConfigDao
import com.lumina.llm.LlmConfigEntity
import com.lumina.pass.PassQueueDao
import com.lumina.pass.PassQueueEntity
import com.lumina.pass.SmartResultsDao
import com.lumina.pass.SmartResultEntity

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

    @Query("UPDATE master_inventory SET sizeBytes = :sizeBytes WHERE relPath = :relPath")
    fun updateSizeBytes(relPath: String, sizeBytes: Long): Int

    @Query("DELETE FROM master_inventory")
    fun clear()
}

/** M3-02：用户导入的 .cube 滤镜库（SAF 导入 → 解析校验 → 文件落盘 luts/ → 行入库）。 */
@Entity(tableName = "lut_library")
data class LutLibraryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    /** 展示名（SAF 显示名去扩展）；选型时 suggestion 命中此名即用该 LUT */
    val name: String,
    /** 相对 filesDir 的 .cube 文件路径（原文件内容落盘） */
    val relPath: String,
    val sizeBytes: Long,
    /** 格位 N（解析取得；渲染 shader 参数） */
    val lattice: Int,
    val importedAtMs: Long,
)

@Dao
interface LutLibraryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entry: LutLibraryEntity): Long

    @Query("SELECT * FROM lut_library ORDER BY name ASC")
    fun all(): List<LutLibraryEntity>

    @Query("DELETE FROM lut_library WHERE relPath = :relPath")
    fun deleteByPath(relPath: String): Int
}

@Database(
    entities = [MasterInventoryEntity::class, IngestQueueEntity::class, LlmConfigEntity::class, LedgerEntity::class, CardEntity::class, SmartResultEntity::class, PassQueueEntity::class, LutLibraryEntity::class],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun masterInventoryDao(): MasterInventoryDao
    abstract fun ingestQueueDao(): IngestQueueDao
    abstract fun llmConfigDao(): LlmConfigDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun cardDao(): CardDao
    abstract fun smartResultsDao(): SmartResultsDao
    abstract fun passQueueDao(): PassQueueDao
    abstract fun lutLibraryDao(): LutLibraryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** M3-02：v7 加 lut_library（纯建表迁移，用户数据不丢——不走破坏性降级路）。 */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lut_library` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `relPath` TEXT NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, `lattice` INTEGER NOT NULL, " +
                        "`importedAtMs` INTEGER NOT NULL)",
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                // M1 开发期破坏性迁移已退役：v6→v7 起走正式正向迁移（用户库保全）
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lumina.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
