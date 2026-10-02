package io.wrtpilot.core.data.repo

import io.wrtpilot.core.data.db.RouterEntity
import io.wrtpilot.core.data.db.SnapshotDao
import io.wrtpilot.core.data.db.SnapshotEntity
import io.wrtpilot.core.network.WrtPilotApi
import io.wrtpilot.core.network.model.Client
import io.wrtpilot.core.network.model.Group
import io.wrtpilot.core.network.model.GroupList
import io.wrtpilot.core.network.model.QosConfig
import io.wrtpilot.core.network.model.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A value as last seen from the router.
 * @param updatedAt epoch millis of the last successful fetch (0 = never)
 * @param error the last refresh failure, cleared by the next success
 * @param fromCache true while showing data restored from the local database
 */
data class Cached<T>(
    val data: T? = null,
    val updatedAt: Long = 0,
    val error: Throwable? = null,
    val fromCache: Boolean = false,
) {
    val isStale: Boolean get() = error != null || fromCache
}

/**
 * Live state of one router: status, clients, groups and QoS, cached on disk
 * so the app opens instantly with the last known data (offline-first).
 * Write actions are optimistic and rolled back when the router refuses.
 */
class RouterSession internal constructor(
    val router: RouterEntity,
    val api: WrtPilotApi,
    private val snapshots: SnapshotDao,
    private val json: Json,
) {
    private val _status = MutableStateFlow(Cached<Status>())
    private val _clients = MutableStateFlow(Cached<List<Client>>())
    private val _groups = MutableStateFlow(Cached<GroupList>())
    private val _qos = MutableStateFlow(Cached<QosConfig>())

    val status: StateFlow<Cached<Status>> = _status.asStateFlow()
    val clients: StateFlow<Cached<List<Client>>> = _clients.asStateFlow()
    val groups: StateFlow<Cached<GroupList>> = _groups.asStateFlow()
    val qos: StateFlow<Cached<QosConfig>> = _qos.asStateFlow()

    private val cacheMutex = Mutex()
    private var cacheLoaded = false

    /** Restores the last snapshots from disk (once). */
    suspend fun loadCache() = cacheMutex.withLock {
        if (cacheLoaded) return@withLock
        cacheLoaded = true
        restore(_status, KIND_STATUS, Status.serializer())
        restore(_clients, KIND_CLIENTS, ListSerializer(Client.serializer()))
        restore(_groups, KIND_GROUPS, GroupList.serializer())
        restore(_qos, KIND_QOS, QosConfig.serializer())
    }

    private suspend fun <T> restore(state: MutableStateFlow<Cached<T>>, kind: String, serializer: KSerializer<T>) {
        if (state.value.data != null) return
        val snap = snapshots.get(router.id, kind) ?: return
        val value = runCatching { json.decodeFromString(serializer, snap.json) }.getOrNull() ?: return
        state.update { if (it.data == null) Cached(value, snap.updatedAt, it.error, fromCache = true) else it }
    }

    suspend fun refreshStatus(): Result<Status> = fetch(_status, KIND_STATUS, Status.serializer()) { api.status() }

    suspend fun refreshClients(): Result<List<Client>> =
        fetch(_clients, KIND_CLIENTS, ListSerializer(Client.serializer())) { api.clients().clients }

    suspend fun refreshGroups(): Result<GroupList> = fetch(_groups, KIND_GROUPS, GroupList.serializer()) { api.groups() }

    suspend fun refreshQos(): Result<QosConfig> = fetch(_qos, KIND_QOS, QosConfig.serializer()) { api.qos() }

    private suspend fun <T> fetch(
        state: MutableStateFlow<Cached<T>>,
        kind: String,
        serializer: KSerializer<T>,
        block: suspend () -> T,
    ): Result<T> = try {
        val value = block()
        val now = System.currentTimeMillis()
        state.value = Cached(value, now)
        runCatching { snapshots.put(SnapshotEntity(router.id, kind, json.encodeToString(serializer, value), now)) }
        Result.success(value)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        state.update { it.copy(error = e) }
        Result.failure(e)
    }

    fun client(mac: String): Flow<Client?> =
        clients.map { c -> c.data?.firstOrNull { it.mac == mac } }.distinctUntilChanged()

    fun group(id: String): Flow<Group?> =
        groups.map { g -> g.data?.groups?.firstOrNull { it.id == id } }.distinctUntilChanged()

    /**
     * Applies [optimistic] to the cached client list immediately, runs
     * [action] and refreshes; on failure the previous list is restored.
     */
    suspend fun <T> mutateClients(
        optimistic: (List<Client>) -> List<Client>,
        action: suspend () -> T,
    ): Result<T> {
        val before = _clients.value
        before.data?.let { _clients.value = before.copy(data = optimistic(it)) }
        return try {
            val result = action()
            refreshClients()
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _clients.value = before
            Result.failure(e)
        }
    }

    /** Same as [mutateClients] for the group list; refreshes groups and clients. */
    suspend fun <T> mutateGroups(
        optimistic: (GroupList) -> GroupList,
        action: suspend () -> T,
    ): Result<T> {
        val before = _groups.value
        before.data?.let { _groups.value = before.copy(data = optimistic(it)) }
        return try {
            val result = action()
            refreshGroups()
            refreshClients()
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _groups.value = before
            Result.failure(e)
        }
    }

    suspend fun <T> mutateQos(optimistic: (QosConfig) -> QosConfig, action: suspend () -> T): Result<T> {
        val before = _qos.value
        before.data?.let { _qos.value = before.copy(data = optimistic(it)) }
        return try {
            val result = action()
            refreshQos()
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _qos.value = before
            Result.failure(e)
        }
    }

    companion object {
        const val KIND_STATUS = "status"
        const val KIND_CLIENTS = "clients"
        const val KIND_GROUPS = "groups"
        const val KIND_QOS = "qos"

        /** Helper: transform one client of a list. */
        fun updateClient(mac: String, f: (Client) -> Client): (List<Client>) -> List<Client> =
            { list -> list.map { if (it.mac == mac) f(it) else it } }

        fun updateGroup(id: String, f: (Group) -> Group): (GroupList) -> GroupList =
            { gl -> gl.copy(groups = gl.groups.map { if (it.id == id) f(it) else it }) }
    }
}

/** Keeps one [RouterSession] per saved router and follows the active one. */
@Singleton
class NetworkRepository @Inject constructor(
    private val routers: RouterRepository,
    private val snapshots: SnapshotDao,
    private val json: Json,
) {
    private val sessions = ConcurrentHashMap<Long, RouterSession>()

    val activeSession: Flow<RouterSession?> =
        routers.activeRouter.map { r -> r?.let { session(it) } }.distinctUntilChanged()

    fun session(router: RouterEntity): RouterSession {
        val api = routers.api(router)
        sessions[router.id]?.let { if (it.api === api) return it }
        val s = RouterSession(router, api, snapshots, json)
        sessions[router.id] = s
        return s
    }

    fun forget(routerId: Long) {
        sessions.remove(routerId)
    }
}
