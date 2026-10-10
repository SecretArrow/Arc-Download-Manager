package com.arc.downloadmanager.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val url: String,
    val title: String,
    val faviconUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
