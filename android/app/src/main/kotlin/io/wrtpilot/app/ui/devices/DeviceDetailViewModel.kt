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
import io.wrtpilot.core.data.repo.UsageRepository
import io.wrtpilot.core.data.repo.VendorRepository
import io.wrtpilot.core.domain.OuiTable
import io.wrtpilot.core.network.BlockMode
import io.wrtpilot.core.network.PauseFor
import io.wrtpilot.core.network.Resolution
import io.wrtpilot.core.network.model.Capabilities
import io.wrtpilot.core.network.model.Client
import io.wrtpilot.core.network.model.Group
import io.wrtpilot.core.network.model.LiveData
import io.wrtpilot.core.network.model.Status
import io.wrtpilot.core.network.model.UsagePoint
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
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class UsageRange(val resolution: Resolution, val seconds: Long) {
    DAY(Resolution.HOUR, 24 * 3600L),
    WEEK(Resolution.DAY, 7 * 86_400L),
    MONTH(Resolution.DAY, 30 * 86_400L),
}

data class UsageState(
    val range: UsageRange = UsageRange.DAY,
    val points: List<UsagePoint> = emptyList(),
    val loading: Boolean = true,
    val offline: Boolean = false,
)

data class DeviceDetailState(
    val mac: String = "",
    val device: Client? = null,
    val loading: Boolean = true,
    val groups: List<Group> = emptyList(),
    val capabilities: Capabilities = Capabilities(),
    val coarseLimiting: Boolean = false,
    val live: LiveData? = null,
    val usage: UsageState = UsageState(),
    val oui: OuiTable? = null,
    val gone: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    private val network: NetworkRepository,
    private val settings: SettingsRepository,
    private val vendors: VendorRepository,
    private val usageRepo: UsageRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    val mac: String = savedState.get<String>("mac").orEmpty()

    private val live = MutableStateFlow<LiveData?>(null)
    private val usage = MutableStateFlow(UsageState())
    private val gone = MutableStateFlow(false)
    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    init {
        viewModelScope.launch { vendors.load() }
        loadUsage(UsageRange.DAY)
    }

    val state: StateFlow<DeviceDetailState> = network.activeSession.flatMapLatest { s ->
        if (s == null) flowOf(DeviceDetailState(mac = mac, loading = false)) else merge(poll(s), observe(s))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceDetailState(mac = mac))

    private data class SessionParts(
        val device: Client?,
        val clientsLoaded: Boolean,
        val groups: List<Group>,
        val status: Status?,
    )

    private fun observe(s: RouterSession): Flow<DeviceDetailState> =
        combine(
            combine(s.client(mac), s.clients, s.groups, s.status) { d, cl, gr, st ->
                SessionParts(d, cl.data != null, gr.data?.groups.orEmpty(), st.data)
            },
            live, usage, vendors.table, gone,
        ) { p, lv, us, oui, isGone ->
            DeviceDetailState(
                mac = mac,
                device = p.device,
                loading = p.device == null && !p.clientsLoaded,
                groups = p.groups,
                capabilities = p.status?.capabilities ?: Capabilities(),
                coarseLimiting = p.status?.coarseLimiting ?: false,
                live = lv,
                usage = us,
                oui = oui,
                gone = isGone,
            )
        }

    private fun poll(s: RouterSession): Flow<Nothing> = pollingFlow(settings, before = { s.loadCache() }) { i ->
        launch { s.refreshClients() }
        launch { runCatching { live.value = s.api.live(macs = listOf(mac), samples = 100) } }
        if (i == 0) {
            launch { s.refreshGroups() }
            launch { s.refreshStatus() }
        }
    }

    fun loadUsage(range: UsageRange) {
        usage.value = usage.value.copy(range = range, loading = true)
        viewModelScope.launch {
            val s = network.activeSession.first() ?: return@launch
            val since = System.currentTimeMillis() / 1000 - range.seconds
            val points = try {
                s.api.history(mac, range.resolution, since).points.also {
                    usageRepo.store(s.router.id, mac, range.resolution, it)
                }
            } catch (e: Exception) {
                if (usage.value.range == range) {
                    usage.value = UsageState(range, usageRepo.range(s.router.id, mac, range.resolution, since), false, offline = true)
                }
                return@launch
            }
            if (usage.value.range == range) usage.value = UsageState(range, points, false)
        }
    }

    private fun mutate(optimistic: (Client) -> Client, success: UiMessage? = null, call: suspend (RouterSession) -> Any?) {
        viewModelScope.launch {
            val s = network.activeSession.first() ?: return@launch
            s.mutateClients(RouterSession.updateClient(mac, optimistic)) { call(s) }
                .onSuccess { success?.let { messages.send(it) } }
                .onFailure { messages.send(UiMessage(UiText.Error(it))) }
        }
    }

    fun rename(name: String) = mutate({ it.copy(customName = name, name = name.ifBlank { it.hostname }) }) {
        it.api.setDevice(mac, name = name.trim())
    }

    fun setGroup(groupId: String) = mutate({ it.copy(group = groupId) }) { s ->
        s.api.setDevice(mac, group = groupId).also { s.refreshGroups() }
    }

    fun block(mode: BlockMode, seconds: Int) = mutate(
        { it.copy(blocked = mode.wire) },
        UiMessage(uiText(R.string.msg_blocked_short)),
    ) { it.api.block(mac, mode, seconds) }

    fun unblock() = mutate({ it.copy(blocked = "", blockedUntil = 0) }, UiMessage(uiText(R.string.msg_unblocked_short))) {
        it.api.unblock(mac)
    }

    fun pause(duration: PauseFor) = mutate(
        { it.copy(paused = true, pausedUntil = -1, pausedBy = "device") },
        UiMessage(uiText(R.string.msg_paused_short)),
    ) { it.api.pauseDevice(mac, duration) }

    fun resume() = mutate({ it.copy(paused = false, pausedUntil = 0) }, UiMessage(uiText(R.string.msg_resumed_short))) {
        it.api.resumeDevice(mac)
    }

    fun setLimits(dlKbps: Int, ulKbps: Int) {
        viewModelScope.launch {
            val s = network.activeSession.first() ?: return@launch
            s.mutateClients(RouterSession.updateClient(mac) { it.copy(dlLimitKbps = dlKbps, ulLimitKbps = ulKbps) }) {
                s.api.setDeviceLimit(mac, dlKbps, ulKbps)
            }.onSuccess { coarse ->
                if (coarse && (dlKbps > 0 || ulKbps > 0)) messages.send(UiMessage(uiText(R.string.msg_coarse_limit)))
            }.onFailure { messages.send(UiMessage(UiText.Error(it))) }
        }
    }

    fun setQuota(mb: Int, action: String) = mutate({ it.copy(dailyQuotaMb = mb, quotaAction = action) }) {
        it.api.setDevice(mac, dailyQuotaMb = mb, quotaAction = action)
    }

    fun forget() {
        viewModelScope.launch {
            val s = network.activeSession.first() ?: return@launch
            runCatching { s.api.forgetDevice(mac) }
                .onSuccess {
                    s.refreshClients()
                    gone.value = true
                }
                .onFailure { messages.send(UiMessage(UiText.Error(it))) }
        }
    }
}
