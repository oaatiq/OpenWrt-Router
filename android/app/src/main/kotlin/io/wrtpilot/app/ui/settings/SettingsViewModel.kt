package io.wrtpilot.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wrtpilot.app.R
import io.wrtpilot.app.ui.common.UiMessage
import io.wrtpilot.app.ui.common.UiText
import io.wrtpilot.app.ui.common.displayName
import io.wrtpilot.app.ui.common.uiText
import io.wrtpilot.core.data.db.RouterEntity
import io.wrtpilot.core.data.prefs.Settings
import io.wrtpilot.core.data.prefs.SettingsRepository
import io.wrtpilot.core.data.prefs.ThemeMode
import io.wrtpilot.core.data.repo.NetworkRepository
import io.wrtpilot.core.data.repo.RouterRepository
import io.wrtpilot.core.data.repo.UsageRepository
import io.wrtpilot.core.data.repo.VendorRepository
import io.wrtpilot.core.network.model.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class SettingsState(
    val settings: Settings = Settings(),
    val active: RouterEntity? = null,
    val routerCount: Int = 0,
    val status: Status? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SettingsRepository,
    private val routers: RouterRepository,
    private val network: NetworkRepository,
    private val usage: UsageRepository,
    private val vendors: VendorRepository,
) : ViewModel() {

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    private val status: Flow<Status?> = network.activeSession.flatMapLatest { s ->
        if (s == null) flowOf(null) else s.status.map { it.data }
    }

    val state: StateFlow<SettingsState> =
        combine(prefs.settings, routers.activeRouter, routers.routers, status) { s, active, list, st ->
            SettingsState(s, active, list.size, st)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    fun setTheme(mode: ThemeMode) = launch { prefs.setTheme(mode) }

    fun setDynamicColor(on: Boolean) = launch { prefs.setDynamicColor(on) }

    fun setPollSeconds(s: Int) = launch { prefs.setPollSeconds(s) }

    fun setBackgroundMinutes(m: Int) = launch { prefs.setBackgroundMinutes(m) }

    fun setNotifyNewDevices(on: Boolean) = launch { prefs.setNotifyNewDevices(on) }

    fun setNotifyQuota(on: Boolean) = launch { prefs.setNotifyQuota(on) }

    /** Suggested file name for the CSV export. */
    fun exportFileName(): String {
        val router = state.value.active?.name.orEmpty()
            .replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifEmpty { "router" }
        return "wrtpilot-$router-${LocalDate.now()}.csv"
    }

    /** Writes the usage history kept on the phone for the active router to [uri]. */
    fun exportCsv(uri: Uri) = launch {
        val router = routers.activeRouter.first() ?: return@launch
        val result = runCatching {
            val session = network.session(router)
            session.loadCache()
            // refresh first so the export includes today's names and history
            session.refreshClients()
            runCatching { usage.sync(router, session.api, System.currentTimeMillis() / 1000) }
            val oui = vendors.load()
            val names = session.clients.value.data.orEmpty().associate { it.mac to it.displayName(context, oui) }
            val csv = usage.exportCsv(router, names, ZoneId.systemDefault())
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(csv.toByteArray(Charsets.UTF_8))
                } ?: error("cannot open $uri")
            }
            csv.count { it == '\n' } - 1
        }
        result.onSuccess { rows ->
            messages.send(
                UiMessage(if (rows > 0) uiText(R.string.export_done) else uiText(R.string.export_empty))
            )
        }.onFailure {
            messages.send(UiMessage(UiText.Error(it)))
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
