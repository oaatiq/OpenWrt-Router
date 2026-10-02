package io.wrtpilot.app.ui.qos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wrtpilot.app.R
import io.wrtpilot.app.ui.common.UiMessage
import io.wrtpilot.app.ui.common.UiText
import io.wrtpilot.app.ui.common.uiText
import io.wrtpilot.core.data.repo.NetworkRepository
import io.wrtpilot.core.network.model.QosConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QosState(
    val loading: Boolean = true,
    val qos: QosConfig? = null,
    val error: Throwable? = null,
    val saving: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class QosViewModel @Inject constructor(
    private val network: NetworkRepository,
) : ViewModel() {

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()
    private val saving = MutableStateFlow(false)

    val state: StateFlow<QosState> = network.activeSession.flatMapLatest { s ->
        if (s == null) {
            flowOf(QosState(loading = false))
        } else {
            merge(
                flow<Nothing> {
                    s.loadCache()
                    s.refreshQos()
                },
                combine(s.qos, saving) { q, sv -> QosState(q.data == null && q.error == null, q.data, q.error, sv) },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QosState())

    fun save(enabled: Boolean, dlKbps: Int, ulKbps: Int, preset: String) {
        viewModelScope.launch {
            val s = network.activeSession.first() ?: return@launch
            saving.value = true
            s.mutateQos({ it.copy(enabled = enabled, dlKbps = dlKbps, ulKbps = ulKbps, preset = preset) }) {
                s.api.setQos(enabled, dlKbps, ulKbps, preset)
            }.onSuccess {
                messages.send(UiMessage(uiText(if (enabled) R.string.qos_saved_on else R.string.qos_saved_off)))
            }.onFailure {
                messages.send(UiMessage(UiText.Error(it)))
            }
            saving.value = false
        }
    }
}
