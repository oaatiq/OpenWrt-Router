package io.wrtpilot.app.ui.groups

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
import io.wrtpilot.core.domain.ScheduleGrid
import io.wrtpilot.core.network.PauseFor
import io.wrtpilot.core.network.model.Capabilities
import io.wrtpilot.core.network.model.Client
import io.wrtpilot.core.network.model.Group
import io.wrtpilot.core.network.model.GroupUpdate
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

data class GroupDetailState(
    val id: String = "",
    val group: Group? = null,
    val loading: Boolean = true,
    val devices: List<Client> = emptyList(),
    val allGroups: List<Group> = emptyList(),
    val dnsFilters: List<String> = emptyList(),
    val capabilities: Capabilities = Capabilities(),
    val oui: OuiTable? = null,
    /** unsaved schedule edits (null = none) */
    val draft: ScheduleGrid? = null,
    val deleted: Boolean = false,
) {
    val members: List<Client> get() = devices.filter { it.group == id }

    /** Schedule shown in the editor: draft, or the saved rules on an hour grid (null if not representable). */
    val grid: ScheduleGrid? get() = draft ?: group?.let { ScheduleGrid.fromRules(it.schedule) }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    private val network: NetworkRepository,
    private val settings: SettingsRepository,
    private val vendors: VendorRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    val id: String = savedState.get<String>("id").orEmpty()

    private val draft = MutableStateFlow<ScheduleGrid?>(null)
    private val deleted = MutableStateFlow(false)
    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    init {
        viewModelScope.launch { vendors.load() }
    }

    val state: StateFlow<GroupDetailState> = network.activeSession.flatMapLatest { s ->
        if (s == null) {
            flowOf(GroupDetailState(id = id, loading = false))
        } else {
            merge(
                pollingFlow(settings, before = { s.loadCache() }) { i ->
                    launch { s.refreshClients() }
                    if (i % 5 == 0) launch { s.refreshGroups() }
                    if (i == 0) launch { s.refreshStatus() }
                },
                combine(
                    combine(s.groups, s.clients, s.status) { gr, cl, st -> Triple(gr, cl, st) },
                    vendors.table, draft, deleted,
                ) { (gr, cl, st), oui, d, del ->
                    GroupDetailState(
                        id = id,
                        group = gr.data?.groups?.firstOrNull { it.id == id },
                        loading = gr.data == null,
                        devices = cl.data.orEmpty(),
                        allGroups = gr.data?.groups.orEmpty(),
                        dnsFilters = gr.data?.dnsFilters.orEmpty(),
                        capabilities = st.data?.capabilities ?: Capabilities(),
                        oui = oui,
                        draft = d,
                        deleted = del,
                    )
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupDetailState(id = id))

    private fun update(
        optimistic: (Group) -> Group,
        success: UiMessage? = null,
        call: suspend (RouterSession) -> Unit,
    ) {
        viewModelScope.launch {
            val s = network.activeSession.first() ?: return@launch
            s.mutateGroups(RouterSession.updateGroup(id, optimistic)) { call(s) }
                .onSuccess { success?.let { messages.send(it) } }
                .onFailure { messages.send(UiMessage(UiText.Error(it))) }
        }
    }

    private fun set(update: GroupUpdate, success: UiMessage? = null, optimistic: (Group) -> Group) =
        update(optimistic, success) { it.api.setGroup(update.copy(id = id)) }

    fun rename(name: String) = set(GroupUpdate(name = name)) { it.copy(name = name) }

    fun pause(duration: PauseFor) = update({ it.copy(pausedUntil = -1) }) { it.api.pauseGroup(id, duration) }

    fun resume() = update({ it.copy(pausedUntil = 0) }) { it.api.resumeGroup(id) }

    fun setMembers(macs: List<String>) = set(GroupUpdate(members = macs)) { it.copy(members = macs) }

    fun removeMember(mac: String) {
        val current = state.value.members.map { it.mac }
        setMembers(current - mac)
    }

    // --- schedule ---
    fun editSchedule(grid: ScheduleGrid) {
        draft.value = grid
    }

    fun discardSchedule() {
        draft.value = null
    }

    fun saveSchedule() {
        val grid = draft.value ?: return
        val rules = grid.toRules()
        set(
            GroupUpdate(schedule = rules, scheduleEnabled = true),
            UiMessage(uiText(R.string.msg_schedule_saved)),
        ) { it.copy(schedule = rules, scheduleEnabled = true) }
        draft.value = null
    }

    fun setScheduleEnabled(on: Boolean) =
        set(GroupUpdate(scheduleEnabled = on)) { it.copy(scheduleEnabled = on) }

    // --- content filtering ---
    fun setDnsFilter(filter: String, custom: List<String>? = null) =
        set(GroupUpdate(dnsFilter = filter, dnsCustom = custom)) { g ->
            g.copy(dnsFilter = filter, dnsCustom = custom ?: g.dnsCustom)
        }

    fun setSafeSearch(on: Boolean) = set(GroupUpdate(safesearch = on)) { it.copy(safesearch = on) }

    fun addBlockedSites(domains: List<String>) {
        val g = state.value.group ?: return
        val list = (g.blocklist + domains.map { it.trim().lowercase() }.filter { it.isNotEmpty() }).distinct()
        set(GroupUpdate(blocklist = list)) { it.copy(blocklist = list) }
    }

    fun removeBlockedSite(domain: String) {
        val g = state.value.group ?: return
        val list = g.blocklist - domain
        set(GroupUpdate(blocklist = list)) { it.copy(blocklist = list) }
    }

    fun setLimits(dlKbps: Int, ulKbps: Int) =
        set(GroupUpdate(dlKbps = dlKbps, ulKbps = ulKbps)) { it.copy(dlLimitKbps = dlKbps, ulLimitKbps = ulKbps) }

    fun delete() {
        viewModelScope.launch {
            val s = network.activeSession.first() ?: return@launch
            runCatching { s.api.deleteGroup(id) }
                .onSuccess {
                    s.refreshGroups()
                    s.refreshClients()
                    deleted.value = true
                }
                .onFailure { messages.send(UiMessage(UiText.Error(it))) }
        }
    }
}
