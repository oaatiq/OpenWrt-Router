package io.wrtpilot.core.data.repo

import io.wrtpilot.core.data.db.RouterDao
import io.wrtpilot.core.data.db.RouterEntity
import io.wrtpilot.core.data.db.UsageDao
import io.wrtpilot.core.data.db.UsageEntity
import io.wrtpilot.core.domain.CsvExport
import io.wrtpilot.core.domain.UsageRecord
import io.wrtpilot.core.network.Resolution
import io.wrtpilot.core.network.WrtPilotApi
import io.wrtpilot.core.network.model.UsagePoint
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Long-term usage history. The router keeps ~24 h of minute data and ~35
 * days of daily totals; the app pulls them regularly and keeps them here.
 */
@Singleton
class UsageRepository @Inject constructor(
    private val usage: UsageDao,
    private val routerDao: RouterDao,
) {
    /** Pulls hourly totals and per-device daily totals since the last sync. */
    suspend fun sync(router: RouterEntity, api: WrtPilotApi, nowSeconds: Long) {
        val hourSince = usage.latest(router.id, HOUR, "") ?: (nowSeconds - DAY_SECONDS)
        val hours = api.history("", Resolution.HOUR, hourSince)
        usage.upsert(hours.points.map { it.toEntity(router.id, "", HOUR) })

        val daySince = usage.latest(router.id, DAY, "") ?: (nowSeconds - 35 * DAY_SECONDS)
        val totals = api.history("", Resolution.DAY, daySince)
        val perDevice = api.historyAll(Resolution.DAY, daySince)
        val rows = totals.points.map { it.toEntity(router.id, "", DAY) } +
            perDevice.devices.keys.flatMap { mac -> perDevice.points(mac).map { it.toEntity(router.id, mac, DAY) } }
        usage.upsert(rows)

        usage.pruneHours(nowSeconds - 8 * DAY_SECONDS)
        routerDao.setHistorySync(router.id, nowSeconds)
    }

    suspend fun range(routerId: Long, mac: String, resolution: Resolution, since: Long): List<UsagePoint> =
        usage.range(routerId, mac, resolution.wire, since).map { UsagePoint(it.ts, it.rx, it.tx) }

    /** Stores points fetched on screen so they are available offline. */
    suspend fun store(routerId: Long, mac: String, resolution: Resolution, points: List<UsagePoint>) {
        if (resolution == Resolution.MINUTE || points.isEmpty()) return
        usage.upsert(points.map { it.toEntity(routerId, mac, resolution.wire) })
    }

    /** CSV of per-device daily usage kept on the phone for [router]. */
    suspend fun exportCsv(router: RouterEntity, names: Map<String, String>, zone: ZoneId): String {
        val rows = usage.dailyPerDevice(router.id).map {
            UsageRecord(router.name, names[it.mac].orEmpty(), it.mac, it.ts, it.rx, it.tx)
        }
        return CsvExport.write(rows, zone, daily = true)
    }

    private fun UsagePoint.toEntity(routerId: Long, mac: String, resolution: String) =
        UsageEntity(routerId, mac, resolution, ts, rx, tx)

    private companion object {
        const val HOUR = "hour"
        const val DAY = "day"
        const val DAY_SECONDS = 86_400L
    }
}
