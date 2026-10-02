package io.wrtpilot.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wrtpilot.core.data.db.RouterEntity
import io.wrtpilot.core.data.prefs.Settings
import io.wrtpilot.core.data.prefs.SettingsRepository
import io.wrtpilot.core.data.repo.RouterRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppState(
    val loading: Boolean = true,
    val settings: Settings = Settings(),
    val routers: List<RouterEntity> = emptyList(),
    val active: RouterEntity? = null,
)

/** App-wide state: saved routers, the active one, theme settings. */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val routers: RouterRepository,
    settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<AppState> =
        combine(routers.routers, routers.activeRouter, settings.settings) { list, active, s ->
            AppState(loading = false, settings = s, routers = list, active = active)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AppState())

    fun selectRouter(id: Long) {
        viewModelScope.launch { routers.select(id) }
    }
}
