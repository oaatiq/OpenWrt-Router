package io.wrtpilot.app.ui.devices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wrtpilot.app.R
import io.wrtpilot.app.ui.common.UiMessage
import io.wrtpilot.app.ui.common.UiText
import io.wrtpilot.app.ui.common.pollingFlow
import io.wrtpilot.app.ui.common.uiText
import io.wrtpilot.core.data.prefs.SettingsRepository
import io.wrtpilot.core.data.repo.NetworkRepository
import io.wrtpilot.core.data.repo.RouterSession
import io.wrtpilot.core.data.repo.VendorRepository
import io.wrtpilot.core.domain.OuiTable
import io.wrtpilot.core.network.BlockMode
import io.wrtpilot.core.network.PauseFor
import io.wrtpilot.core.network.model.Client
import io.wrtpilot.core.network.model.Group
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DeviceSort { NAME, RATE, USAGE }

/** "all", "online", "wifi", "wired", "blocked", "paused" or "group:<id>". */
typealias DeviceFilter = String

data class DevicesControls(
    val query: String = "",
    val filter: DeviceFilter = "all",
    val sort: DeviceSort = DeviceSort.NAME,
)

data class DevicesState(
    val loading: Boolean = true,
    val controls: DevicesControls = DevicesControls(),
    val devices: List<Client> = emptyList(),
    val total: Int = 0,
    val groups: List<Group> = emptyList(),
    val oui: OuiTable? = null,
    val wifiControl: Boolean = true,
    val error: Throwable? = null,
    val updatedAt: Long = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val network: NetworkRepository,
    private val settings: SettingsRepository,
    private val vendors: VendorRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val controls = MutableStateFlow(DevicesControls(filter = savedState.get<String>("filter") ?: "all"))
    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    init {
        viewModelScope.launch { vendors.load() }
    }

    val state: StateFlow<DevicesState> = network.activeSession.flatMapLatest { s ->
        if (s == null) flowOf(DevicesState(loading = false)) else merge(poll(s), observe(s))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesState())

    private fun observe(s: RouterSession): Flow<DevicesState> =
        combine(s.clients, s.groups, s.status, controls, vendors.table) { cl, gr, st, ctl, oui ->
            val all = cl.data.orEmpty()
            DevicesState(
                loading = cl.data == null && cl.error == null,
                controls = ctl,
                devices = applyControls(all, ctl, oui),
                total = all.size,
                groups = gr.data?.groups.orEmpty(),
                oui = oui,
                wifiControl = st.data?.capabilities?.hostapd ?: true,
                error = cl.error,
                updatedAt = cl.updatedAt,
            )
        }

    private fun poll(s: RouterSession): Flow<Nothing> = pollingFlow(settings, before = { s.loadCache() }) { i ->
        launch { s.refreshClients() }
        if (i % 20 == 0) {
            launch { s.refreshGroups() }
            launch { s.refreshStatus() }
        }
    }

    private fun applyControls(all: List<Client>, c: DevicesControls, oui: OuiTable?): List<Client> {
        val q = c.query.trim().lowercase()
        val filtered = all.filter { d ->
            val matchesFilter = when {
                c.filter == "online" -> d.online
                c.filter == "wifi" -> d.isWifi
                c.filter == "wired" -> d.conn == "lan"
                c.filter == "blocked" -> d.isBlocked
                c.filter == "paused" -> d.paused && !d.isBlocked
                c.filter.startsWith("group:") -> d.group == c.filter.removePrefix("group:")
                else -> true
            }
            val matchesQuery = q.isEmpty() || listOf(d.name, d.hostname, d.ip, d.mac, oui?.lookup(d.mac).orEmpty())
                .any { it.lowercase().contains(q) }
            matchesFilter && matchesQuery
        }
        return when (c.sort) {
            DeviceSort.NAME -> filtered.sortedWith(compareByDescending<Client> { it.online }.thenBy { it.name.lowercase().ifEmpty { "￿" + it.mac } })
            DeviceSort.RATE -> filtered.sortedByDescending { it.rxBps + it.txBps }
            DeviceSort.USAGE -> filtered.sortedByDescending { it.todayRx + it.todayTx }
        }
    }

    fun setQuery(q: String) = controls.update { it.copy(query = q) }
    fun setFilter(f: DeviceFilter) = controls.update { it.copy(filter = f) }
    fun setSort(s: DeviceSort) = controls.update { it.copy(sort = s) }

    fun refresh() {
        viewModelScope.launch { network.activeSession.first()?.refreshClients() }
    }

    fun block(device: Client, name: String, mode: BlockMode, seconds: Int) {
        if (device.isSelf) {
            viewModelScope.launch { messages.send(UiMessage(uiText(R.string.error_self_block))) }
            return
        }
        action(
            optimistic = RouterSession.updateClient(device.mac) { it.copy(blocked = mode.wire) },
            success = UiMessage(
                uiText(R.string.msg_blocked, name),
                actionLabel = uiText(R.string.undo),
                action = { unblock(device, name, quiet = true) },
            ),
        ) { it.api.block(device.mac, mode, seconds) }
    }

    fun unblock(device: Client, name: String, quiet: Boolean = false) {
        action(
            optimistic = RouterSession.updateClient(device.mac) { it.copy(blocked = "", blockedUntil = 0) },
            success = if (quiet) null else UiMessage(uiText(R.string.msg_unblocked, name)),
        ) { it.api.unblock(device.mac) }
    }

    fun pause(device: Client, name: String, duration: PauseFor) {
        if (device.isSelf) {
            viewModelScope.launch { messages.send(UiMessage(uiText(R.string.error_self_block))) }
            return
        }
        action(
            optimistic = RouterSession.updateClient(device.mac) { it.copy(paused = true, pausedUntil = -1, pausedBy = "device") },
            success = UiMessage(
                uiText(R.string.msg_paused, name),
                actionLabel = uiText(R.string.undo),
                action = { resume(device, name) },
            ),
        ) { it.api.pauseDevice(device.mac, duration) }
    }

    fun resume(device: Client, name: String) {
        action(
            optimistic = RouterSession.updateClient(device.mac) { it.copy(paused = false, pausedUntil = 0) },
            success = UiMessage(uiText(R.string.msg_resumed, name)),
        ) { it.api.resumeDevice(device.mac) }
    }

    private fun action(
        optimistic: (List<Client>) -> List<Client>,
        success: UiMessage?,
        call: suspend (RouterSession) -> Any?,
    ) {
        viewModelScope.launch {
            val s = network.activeSession.first() ?: return@launch
            s.mutateClients(optimistic) { call(s) }
                .onSuccess { success?.let { messages.send(it) } }
                .onFailure { messages.send(UiMessage(UiText.Error(it))) }
        }
    }
}
