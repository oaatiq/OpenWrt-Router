package io.wrtpilot.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wrtpilot.core.data.db.RouterEntity
import io.wrtpilot.core.data.repo.NetworkRepository
import io.wrtpilot.core.data.repo.RouterRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RoutersState(
    val loading: Boolean = true,
    val routers: List<RouterEntity> = emptyList(),
    val activeId: Long = 0,
)

/** Saved routers: switch, edit, remove. */
@HiltViewModel
class RoutersViewModel @Inject constructor(
    private val routers: RouterRepository,
    private val network: NetworkRepository,
) : ViewModel() {

    val state: StateFlow<RoutersState> =
        combine(routers.routers, routers.activeRouter) { list, active ->
            RoutersState(false, list, active?.id ?: 0)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutersState())

    fun select(id: Long) {
        viewModelScope.launch { routers.select(id) }
    }

    /** Removes the router from this phone only (nothing changes on the router). */
    fun delete(id: Long) {
        viewModelScope.launch {
            routers.delete(id)
            network.forget(id)
        }
    }
}
