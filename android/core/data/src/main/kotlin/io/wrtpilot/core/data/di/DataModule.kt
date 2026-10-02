package io.wrtpilot.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.wrtpilot.core.data.db.RouterDao
import io.wrtpilot.core.data.db.SnapshotDao
import io.wrtpilot.core.data.db.UsageDao
import io.wrtpilot.core.data.db.WrtPilotDatabase
import io.wrtpilot.core.network.WrtJson
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): WrtPilotDatabase =
        Room.databaseBuilder(context, WrtPilotDatabase::class.java, "wrtpilot.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun routerDao(db: WrtPilotDatabase): RouterDao = db.routers()

    @Provides
    fun snapshotDao(db: WrtPilotDatabase): SnapshotDao = db.snapshots()

    @Provides
    fun usageDao(db: WrtPilotDatabase): UsageDao = db.usage()

    @Provides
    @Singleton
    fun json(): Json = WrtJson

    /** Base HTTP client; per-router clients derive from it (certificate pinning). */
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
