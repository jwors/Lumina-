package com.lumina.master

import androidx.room.Insert
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

/**
 * Room 实装的 InventoryStore（运行时配 AppDatabase.masterInventoryDao）。
 * 只做转发——语义以 MasterStore/InventoryStore 为准。
 */
class RoomInventoryStore(private val dao: MasterInventoryDao) : InventoryStore {
    override fun insert(entry: MasterInventoryEntity): Long = dao.insert(entry)
    override fun findByRelPath(relPath: String): MasterInventoryEntity? = dao.findByRelPath(relPath)
    override fun all(): List<MasterInventoryEntity> = dao.all()
    override fun deleteByRelPath(relPath: String): Int = dao.deleteByRelPath(relPath)
    override fun updateSizeBytes(relPath: String, sizeBytes: Long): Int = dao.updateSizeBytes(relPath, sizeBytes)
}
