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
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): ArcDatabase =
        Room.databaseBuilder(context, ArcDatabase::class.java, "arc_downloads.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideDownloadDao(db: ArcDatabase): com.arc.downloadmanager.data.db.DownloadDao = db.downloadDao()

    @Provides
    fun provideSegmentDao(db: ArcDatabase): com.arc.downloadmanager.data.db.SegmentDao = db.segmentDao()

    @Provides
    fun provideBookmarkDao(db: ArcDatabase): com.arc.downloadmanager.data.db.BookmarkDao = db.bookmarkDao()

    @Provides
    fun provideHistoryDao(db: ArcDatabase): com.arc.downloadmanager.data.db.HistoryDao = db.historyDao()
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
