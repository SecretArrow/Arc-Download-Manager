package com.arc.downloadmanager.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultFileDao {
    @Query("SELECT * FROM vault_files ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<VaultFileEntity>>

    @Query("SELECT * FROM vault_files WHERE id = :id")
    suspend fun getById(id: String): VaultFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: VaultFileEntity)

    @Query("UPDATE vault_files SET name = :name WHERE id = :id")
    suspend fun rename(
        id: String,
        name: String,
    )

    @Query("UPDATE vault_files SET lastOpenedAt = :at WHERE id = :id")
    suspend fun touchOpened(
        id: String,
        at: Long,
    )

    @Query("DELETE FROM vault_files WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM vault_files")
    suspend fun count(): Int
}
