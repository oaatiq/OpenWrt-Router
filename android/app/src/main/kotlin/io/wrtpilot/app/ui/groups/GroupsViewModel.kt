package io.wrtpilot.app.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wrtpilot.app.ui.common.UiMessage
import io.wrtpilot.app.ui.common.UiText
import io.wrtpilot.app.ui.common.pollingFlow
import io.wrtpilot.core.data.prefs.SettingsRepository
import io.wrtpilot.core.data.repo.NetworkRepository
import io.wrtpilot.core.data.repo.RouterSession
import io.wrtpilot.core.network.PauseFor
import io.wrtpilot.core.network.model.Client
import io.wrtpilot.core.network.model.Group
import io.wrtpilot.core.network.model.GroupUpdate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
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

data class GroupsState(
    val loading: Boolean = true,
    val groups: List<Group> = emptyList(),
    val clients: List<Client> = emptyList(),
    val error: Throwable? = null,
    val updatedAt: Long = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val network: NetworkRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    /** Emits the id of a group that was just created (to open it). */
    private val created = Channel<String>(Channel.BUFFERED)
    val createdGroups: Flow<String> = created.receiveAsFlow()

    val state: StateFlow<GroupsState> = network.activeSession.flatMapLatest { s ->
        if (s == null) {
            flowOf(GroupsState(loading = false))
        } else {
            merge(
                pollingFlow(settings, before = { s.loadCache() }) { i ->
                    if (i % 5 == 0) launch { s.refreshGroups() }
                    launch { s.refreshClients() }
                },
                combine(s.groups, s.clients) { gr, cl ->
                    GroupsState(
                        loading = gr.data == null && gr.error == null,
                        groups = gr.data?.groups.orEmpty(),
                        clients = cl.data.orEmpty(),
                        error = gr.error,
                        updatedAt = gr.updatedAt,
                    )
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupsState())

    fun refresh() {
        viewModelScope.launch { network.activeSession.first()?.refreshGroups() }
    }

    fun create(name: String) {
        viewModelScope.launch {
            val s = network.activeSession.first() ?: return@launch
            runCatching { s.api.setGroup(GroupUpdate(name = name.trim())) }
                .onSuccess { g ->
                    s.refreshGroups()
                    created.send(g.id)
                }
                .onFailure { messages.send(UiMessage(UiText.Error(it))) }
        }
    }

    fun pause(group: Group, duration: PauseFor) {
        viewModelScope.launch {
            val s = network.activeSession.first() ?: return@launch
            s.mutateGroups(RouterSession.updateGroup(group.id) { it.copy(pausedUntil = -1) }) {
                s.api.pauseGroup(group.id, duration)
            }.onFailure { messages.send(UiMessage(UiText.Error(it))) }
        }
    }

    fun resume(group: Group) {
        viewModelScope.launch {
            val s = network.activeSession.first() ?: return@launch
            s.mutateGroups(RouterSession.updateGroup(group.id) { it.copy(pausedUntil = 0) }) {
                s.api.resumeGroup(group.id)
            }.onFailure { messages.send(UiMessage(UiText.Error(it))) }
        }
    }
}
