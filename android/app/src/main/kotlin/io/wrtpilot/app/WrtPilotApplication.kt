package io.wrtpilot.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.wrtpilot.app.work.Notifications
import io.wrtpilot.app.work.SyncScheduler
import javax.inject.Inject

@HiltAndroidApp
class WrtPilotApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncScheduler: SyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        syncScheduler.start()
    }
}
