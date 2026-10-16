package com.arc.downloadmanager.di

import android.content.Context
import androidx.room.Room
import com.arc.downloadmanager.browser.WebViewCookieProvider
import com.arc.downloadmanager.data.db.ArcDatabase
import com.arc.downloadmanager.data.db.RoomDownloadStore
import com.arc.downloadmanager.engine.ConnectivityView
import com.arc.downloadmanager.engine.CookieProvider
import com.arc.downloadmanager.engine.EngineSettingsSource
import com.arc.downloadmanager.engine.store.DownloadStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    /** v1 -> v2: task archiving (new nullable archivedAt column). Data-preserving. */
    private val MIGRATION_1_2 =
        object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN archivedAt INTEGER DEFAULT NULL")
            }
        }

    /** v2 -> v3: encrypted vault file index. Data-preserving. */
    private val MIGRATION_2_3 =
        object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vault_files` (" +
                        "`id` TEXT NOT NULL PRIMARY KEY, " +
                        "`name` TEXT NOT NULL, " +
                        "`mimeType` TEXT, " +
                        "`category` TEXT NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`encSizeBytes` INTEGER NOT NULL, " +
                        "`blobPath` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "`lastOpenedAt` INTEGER DEFAULT NULL" +
                        ")",
                )
            }
        }

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): ArcDatabase =
        Room.databaseBuilder(context, ArcDatabase::class.java, "arc_downloads.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    fun provideDownloadDao(db: ArcDatabase): com.arc.downloadmanager.data.db.DownloadDao = db.downloadDao()

    @Provides
    fun provideSegmentDao(db: ArcDatabase): com.arc.downloadmanager.data.db.SegmentDao = db.segmentDao()

    @Provides
    fun provideBookmarkDao(db: ArcDatabase): com.arc.downloadmanager.data.db.BookmarkDao = db.bookmarkDao()

    @Provides
    fun provideHistoryDao(db: ArcDatabase): com.arc.downloadmanager.data.db.HistoryDao = db.historyDao()

    @Provides
    fun provideVaultFileDao(db: ArcDatabase): com.arc.downloadmanager.data.db.VaultFileDao = db.vaultFileDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds
    @Singleton
    abstract fun bindDownloadStore(impl: RoomDownloadStore): DownloadStore

    @Binds
    @Singleton
    abstract fun bindCookieProvider(impl: WebViewCookieProvider): CookieProvider

    @Binds
    @Singleton
    abstract fun bindEngineSettingsSource(impl: com.arc.downloadmanager.data.settings.SettingsRepository): EngineSettingsSource

    @Binds
    @Singleton
    abstract fun bindTaskStorage(impl: com.arc.downloadmanager.engine.StorageManager): com.arc.downloadmanager.engine.TaskStorage

    @Binds
    @Singleton
    abstract fun bindConnectivityView(impl: com.arc.downloadmanager.engine.NetworkMonitor): ConnectivityView

    @Binds
    @Singleton
    abstract fun bindContentStreams(
        impl: com.arc.downloadmanager.engine.transport.AndroidContentStreams,
    ): com.arc.downloadmanager.engine.transport.ContentStreams
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideBaseOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
}
