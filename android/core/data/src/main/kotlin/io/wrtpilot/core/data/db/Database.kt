package io.wrtpilot.core.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A saved router. The password is encrypted with an Android Keystore key. */
@Entity(tableName = "routers")
data class RouterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int,
    val https: Boolean,
    val username: String,
    val passwordEnc: String,
    /** Pinned SHA-256 of a self-signed certificate (hex), or null. */
    val certSha256: String? = null,
    val lastEventId: Long = 0,
    val lastEventSync: Long = 0,
    val lastHistorySync: Long = 0,
    val createdAt: Long = 0,
)

/** Last known JSON of a router response, for offline-first display. */
@Entity(tableName = "snapshots", primaryKeys = ["routerId", "kind"])
data class SnapshotEntity(
    val routerId: Long,
    /** "status", "clients", "groups", "qos" */
    val kind: String,
    val json: String,
    val updatedAt: Long,
)

/** Usage history bucket. mac "" = all devices. resolution "hour" | "day". */
@Entity(tableName = "usage", primaryKeys = ["routerId", "mac", "resolution", "ts"])
data class UsageEntity(
    val routerId: Long,
    val mac: String,
    val resolution: String,
    val ts: Long,
    val rx: Long,
    val tx: Long,
)

@Dao
interface RouterDao {
    @Query("SELECT * FROM routers ORDER BY createdAt, id")
    fun observeAll(): Flow<List<RouterEntity>>

    @Query("SELECT * FROM routers ORDER BY createdAt, id")
    suspend fun all(): List<RouterEntity>

    @Query("SELECT * FROM routers WHERE id = :id")
    suspend fun get(id: Long): RouterEntity?

    @Insert
    suspend fun insert(router: RouterEntity): Long

    @Update
    suspend fun update(router: RouterEntity)

    @Query("DELETE FROM routers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE routers SET lastEventId = :eventId, lastEventSync = :ts WHERE id = :id")
    suspend fun setEventCursor(id: Long, eventId: Long, ts: Long)

    @Query("UPDATE routers SET lastHistorySync = :ts WHERE id = :id")
    suspend fun setHistorySync(id: Long, ts: Long)

    @Query("UPDATE routers SET certSha256 = :sha256 WHERE id = :id")
    suspend fun setCertificate(id: Long, sha256: String?)
}

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM snapshots WHERE routerId = :routerId AND kind = :kind")
    suspend fun get(routerId: Long, kind: String): SnapshotEntity?

    @Upsert
    suspend fun put(snapshot: SnapshotEntity)

    @Query("DELETE FROM snapshots WHERE routerId = :routerId")
    suspend fun clear(routerId: Long)
}

@Dao
interface UsageDao {
    @Upsert
    suspend fun upsert(rows: List<UsageEntity>)

    @Query(
        "SELECT * FROM usage WHERE routerId = :routerId AND mac = :mac AND resolution = :resolution " +
            "AND ts >= :since ORDER BY ts"
    )
    suspend fun range(routerId: Long, mac: String, resolution: String, since: Long): List<UsageEntity>

    @Query("SELECT * FROM usage WHERE routerId = :routerId AND resolution = 'day' AND mac != '' ORDER BY ts, mac")
    suspend fun dailyPerDevice(routerId: Long): List<UsageEntity>

    @Query("SELECT MAX(ts) FROM usage WHERE routerId = :routerId AND resolution = :resolution AND mac = :mac")
    suspend fun latest(routerId: Long, resolution: String, mac: String): Long?

    @Query("DELETE FROM usage WHERE resolution = 'hour' AND ts < :before")
    suspend fun pruneHours(before: Long)

    @Query("DELETE FROM usage WHERE routerId = :routerId")
    suspend fun clear(routerId: Long)
}

@Database(
    entities = [RouterEntity::class, SnapshotEntity::class, UsageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class WrtPilotDatabase : RoomDatabase() {
    abstract fun routers(): RouterDao
    abstract fun snapshots(): SnapshotDao
    abstract fun usage(): UsageDao
}
