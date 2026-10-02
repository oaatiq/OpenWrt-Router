package io.wrtpilot.core.data.repo

import io.wrtpilot.core.data.db.RouterDao
import io.wrtpilot.core.data.db.RouterEntity
import io.wrtpilot.core.data.db.SnapshotDao
import io.wrtpilot.core.data.db.UsageDao
import io.wrtpilot.core.data.prefs.SettingsRepository
import io.wrtpilot.core.data.security.SecretBox
import io.wrtpilot.core.network.Credentials
import io.wrtpilot.core.network.RouterEndpoint
import io.wrtpilot.core.network.UbusClient
import io.wrtpilot.core.network.WrtPilotApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Saved routers (multi-router support) and the API clients to reach them. */
@Singleton
class RouterRepository @Inject constructor(
    private val dao: RouterDao,
    private val snapshots: SnapshotDao,
    private val usage: UsageDao,
    private val secretBox: SecretBox,
    private val settings: SettingsRepository,
    private val http: OkHttpClient,
    private val json: Json,
) {
    val routers: Flow<List<RouterEntity>> = dao.observeAll()

    /** The router shown in the UI: the selected one, else the first saved one. */
    val activeRouter: Flow<RouterEntity?> =
        combine(routers, settings.settings.map { it.activeRouterId }.distinctUntilChanged()) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.distinctUntilChanged()

    private data class Key(
        val host: String,
        val port: Int,
        val https: Boolean,
        val cert: String?,
        val user: String,
        val pass: String,
    )

    private val apis = ConcurrentHashMap<Long, Pair<Key, WrtPilotApi>>()

    private fun keyOf(r: RouterEntity) = Key(r.host, r.port, r.https, r.certSha256, r.username, r.passwordEnc)

    fun endpointOf(r: RouterEntity) = RouterEndpoint(r.host, r.port, r.https, r.certSha256)

    fun credentials(router: RouterEntity): Credentials =
        Credentials(router.username, runCatching { secretBox.decrypt(router.passwordEnc) }.getOrDefault(""))

    /** API client for a saved router (re-used while its connection settings stay the same). */
    fun api(router: RouterEntity): WrtPilotApi {
        val key = keyOf(router)
        apis[router.id]?.let { (k, api) -> if (k == key) return api }
        val api = WrtPilotApi(UbusClient(endpointOf(router), credentials(router), http, json), json)
        apis[router.id] = key to api
        return api
    }

    /** Client for a router that is not saved yet (onboarding connection test). */
    fun probe(endpoint: RouterEndpoint, credentials: Credentials): WrtPilotApi =
        WrtPilotApi(UbusClient(endpoint, credentials, http, json), json)

    suspend fun get(id: Long): RouterEntity? = dao.get(id)

    suspend fun all(): List<RouterEntity> = dao.all()

    suspend fun add(name: String, endpoint: RouterEndpoint, credentials: Credentials): Long {
        val id = dao.insert(
            RouterEntity(
                name = name.trim(),
                host = endpoint.host,
                port = endpoint.port,
                https = endpoint.https,
                username = credentials.username,
                passwordEnc = secretBox.encrypt(credentials.password),
                certSha256 = endpoint.certSha256,
                createdAt = System.currentTimeMillis(),
            )
        )
        settings.setActiveRouter(id)
        return id
    }

    /** Updates connection settings; [password] null keeps the stored one. */
    suspend fun update(
        router: RouterEntity,
        name: String,
        endpoint: RouterEndpoint,
        username: String,
        password: String?,
    ) {
        dao.update(
            router.copy(
                name = name.trim(),
                host = endpoint.host,
                port = endpoint.port,
                https = endpoint.https,
                certSha256 = endpoint.certSha256,
                username = username,
                passwordEnc = password?.let { secretBox.encrypt(it) } ?: router.passwordEnc,
            )
        )
    }

    suspend fun pinCertificate(id: Long, sha256: String?) = dao.setCertificate(id, sha256)

    suspend fun delete(id: Long) {
        dao.delete(id)
        snapshots.clear(id)
        usage.clear(id)
        apis.remove(id)
    }

    suspend fun select(id: Long) = settings.setActiveRouter(id)

    suspend fun setEventCursor(id: Long, eventId: Long, ts: Long) = dao.setEventCursor(id, eventId, ts)
}
