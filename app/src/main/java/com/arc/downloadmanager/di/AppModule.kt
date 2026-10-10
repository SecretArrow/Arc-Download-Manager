package com.arc.downloadmanager.di

import android.content.Context
import androidx.room.Room
import com.arc.downloadmanager.data.db.ArcDatabase
import com.arc.downloadmanager.data.db.RoomDownloadStore
import com.arc.downloadmanager.engine.CookieProvider
import com.arc.downloadmanager.engine.store.DownloadStore
import com.arc.downloadmanager.browser.WebViewCookieProvider
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
    fun provideDatabase(@ApplicationContext context: Context): ArcDatabase =
        Room.databaseBuilder(context, ArcDatabase::class.java, "arc_downloads.db")
            .fallbackToDestructiveMigration()
            .build()
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
