package com.arc.downloadmanager.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<HistoryEntity>>

    @Query("INSERT INTO history (url, title, visitedAt) VALUES (:url, :title, :visitedAt)")
    suspend fun insert(
        url: String,
        title: String,
        visitedAt: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM history")
    suspend fun clear()
}
