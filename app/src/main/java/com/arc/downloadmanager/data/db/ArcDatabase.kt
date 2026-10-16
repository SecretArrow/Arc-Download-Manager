package com.arc.downloadmanager.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        DownloadEntity::class,
        SegmentEntity::class,
        BookmarkEntity::class,
        HistoryEntity::class,
        VaultFileEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class ArcDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    abstract fun segmentDao(): SegmentDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun historyDao(): HistoryDao

    abstract fun vaultFileDao(): VaultFileDao
}
