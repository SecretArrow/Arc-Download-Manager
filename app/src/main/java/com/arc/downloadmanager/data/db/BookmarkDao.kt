package com.arc.downloadmanager.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    fun observeBookmarked(url: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun delete(url: String)
}
