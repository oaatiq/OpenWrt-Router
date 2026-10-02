package io.wrtpilot.app.ui.dashboard

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
import io.wrtpilot.core.network.PauseFor
import io.wrtpilot.core.network.model.Client
import io.wrtpilot.core.network.model.Group
import io.wrtpilot.core.network.model.LiveData
import io.wrtpilot.core.network.model.QosConfig
import io.wrtpilot.core.network.model.Status
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

data class DashboardState(
    val loading: Boolean = true,
    val status: Status? = null,
    val clients: List<Client> = emptyList(),
    val groups: List<Group> = emptyList(),
    val qos: QosConfig? = null,
    val live: LiveData? = null,
    val error: Throwable? = null,
    val updatedAt: Long = 0,
    val oui: OuiTable? = null,
) {
    val online: Int get() = clients.count { it.online }
    val blocked: Int get() = clients.count { it.isBlocked }
    val paused: Int get() = clients.count { !it.isBlocked && it.paused }
    val totalRx: Long get() = live?.total?.rx?.lastOrNull() ?: clients.sumOf { it.rxBps }
    val totalTx: Long get() = live?.total?.tx?.lastOrNull() ?: clients.sumOf { it.txBps }
    val top: List<Client>
        get() = clients.filter { it.rxBps + it.txBps > 0 }.sortedByDescending { it.rxBps + it.txBps }.take(5)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val network: NetworkRepository,
    private val settings: SettingsRepository,
    private val vendors: VendorRepository,
) : ViewModel() {

    private val live = MutableStateFlow<LiveData?>(null)
    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    init {
        viewModelScope.launch { vendors.load() }
    }

    val state: StateFlow<DashboardState> = network.activeSession.flatMapLatest { s ->
        live.value = null
        if (s == null) flowOf(DashboardState(loading = false)) else merge(poll(s), observe(s))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    private fun observe(s: RouterSession): Flow<DashboardState> =
        combine(
            combine(s.status, s.clients, s.groups) { st, cl, gr -> Triple(st, cl, gr) },
            s.qos,
            live,
            vendors.table,
        ) { (st, cl, gr), qos, lv, oui ->
            DashboardState(
                loading = cl.data == null && cl.error == null,
                status = st.data,
                clients = cl.data.orEmpty(),
                groups = gr.data?.groups.orEmpty(),
                qos = qos.data,
                live = lv,
                error = cl.error ?: st.error,
                updatedAt = cl.updatedAt,
                oui = oui,
            )
        }

    private fun poll(s: RouterSession): Flow<Nothing> = pollingFlow(settings, before = { s.loadCache() }) { i ->
        launch { s.refreshClients() }
        launch { runCatching { live.value = s.api.live(samples = 100, devices = false) } }
        if (i % 10 == 0) {
            launch { s.refreshStatus() }
            launch { s.refreshGroups() }
        }
        if (i == 0) launch { s.refreshQos() }
    }

    fun refresh() {
        viewModelScope.launch {
            val s = network.activeSession.first() ?: return@launch
            s.refreshStatus()
            s.refreshClients()
            s.refreshGroups()
        }
    }

    fun disableOffload() {
        viewModelScope.launch {
            val s = network.activeSession.first() ?: return@launch
            runCatching { s.api.setOffload(software = false, hardware = false) }
                .onSuccess {
                    s.refreshStatus()
                    messages.send(UiMessage(uiText(R.string.offload_disabled)))
                }
                .onFailure { messages.send(UiMessage(UiText.Error(it))) }
        }
    }

    fun pauseGroup(group: Group, duration: PauseFor) {
        viewModelScope.launch {
            val s = network.activeSession.first() ?: return@launch
            s.mutateGroups(
                RouterSession.updateGroup(group.id) { it.copy(pausedUntil = -1) },
            ) { s.api.pauseGroup(group.id, duration) }
                .onFailure { messages.send(UiMessage(UiText.Error(it))) }
        }
    }

    fun resumeGroup(group: Group) {
        viewModelScope.launch {
            val s = network.activeSession.first() ?: return@launch
            s.mutateGroups(RouterSession.updateGroup(group.id) { it.copy(pausedUntil = 0) }) {
                s.api.resumeGroup(group.id)
            }.onFailure { messages.send(UiMessage(UiText.Error(it))) }
        }
    }
}
