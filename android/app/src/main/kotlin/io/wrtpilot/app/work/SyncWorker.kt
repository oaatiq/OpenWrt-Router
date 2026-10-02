package io.wrtpilot.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.wrtpilot.app.R
import io.wrtpilot.app.ui.common.formatBytes
import io.wrtpilot.core.data.db.RouterEntity
import io.wrtpilot.core.data.prefs.Settings
import io.wrtpilot.core.data.prefs.SettingsRepository
import io.wrtpilot.core.data.repo.NetworkRepository
import io.wrtpilot.core.data.repo.RouterRepository
import io.wrtpilot.core.data.repo.UsageRepository
import io.wrtpilot.core.data.repo.VendorRepository
import io.wrtpilot.core.network.WrtPilotApi
import io.wrtpilot.core.network.model.Event
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Background sync (WorkManager, every 15+ minutes): fetches router events
 * for notifications and pulls usage history into the local database.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val routers: RouterRepository,
    private val network: NetworkRepository,
    private val usage: UsageRepository,
    private val settings: SettingsRepository,
    private val vendors: VendorRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = settings.current()
        val now = System.currentTimeMillis() / 1000
        for (router in routers.all()) {
            val api = routers.api(router)
            runCatching { syncEvents(router, api, prefs) }
            runCatching { usage.sync(router, api, now) }
        }
        return Result.success()
    }

    private suspend fun syncEvents(router: RouterEntity, api: WrtPilotApi, prefs: Settings) {
        val firstSync = router.lastEventSync == 0L
        var page = api.events(router.lastEventId)
        var events = page.events
        if (page.reset) {
            // router event ids restarted (reset / reinstall): only notify new ones
            page = api.events(0)
            events = page.events.filter { it.ts * 1000 > router.lastEventSync }
        }
        if (!firstSync && events.isNotEmpty()) notify(router, events, prefs)
        routers.setEventCursor(router.id, page.lastId, System.currentTimeMillis())
    }

    private suspend fun notify(router: RouterEntity, events: List<Event>, prefs: Settings) {
        val session = network.session(router)
        session.loadCache()
        val known = session.clients.value.data.orEmpty().associateBy { it.mac }
        val oui = vendors.load()
        val ctx = applicationContext
        val locale = ctx.resources.configuration.locales.get(0)

        for (e in events) {
            val mac = e.mac ?: continue
            val hostname = e.data["hostname"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val label = known[mac]?.name?.takeIf { it.isNotBlank() }
                ?: hostname.takeIf { it.isNotBlank() }
                ?: oui.lookup(mac)?.let { ctx.getString(R.string.device_by_vendor, it) }
                ?: mac
            when (e.type) {
                "new_device" -> if (prefs.notifyNewDevices) {
                    Notifications.notifyNewDevice(ctx, router.id, router.name, e.id, mac, label)
                }
                "quota_exceeded" -> if (prefs.notifyQuota) {
                    val usedMb = e.data["used_mb"]?.jsonPrimitive?.intOrNull ?: 0
                    val quotaMb = e.data["quota_mb"]?.jsonPrimitive?.intOrNull ?: 0
                    val blocked = e.data["action"]?.jsonPrimitive?.contentOrNull == "block"
                    Notifications.notifyQuota(
                        ctx, router.id, e.id, mac, label,
                        formatBytes(ctx, usedMb * 1_000_000L, locale),
                        formatBytes(ctx, quotaMb * 1_000_000L, locale),
                        blocked,
                    )
                }
            }
        }
    }

    companion object {
        const val NAME = "wrtpilot-sync"
    }
}
