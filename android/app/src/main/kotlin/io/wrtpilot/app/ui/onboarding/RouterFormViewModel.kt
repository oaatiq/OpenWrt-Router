package io.wrtpilot.app.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wrtpilot.app.R
import io.wrtpilot.app.ui.common.UiText
import io.wrtpilot.app.ui.common.uiText
import io.wrtpilot.core.data.db.RouterEntity
import io.wrtpilot.core.data.repo.RouterRepository
import io.wrtpilot.core.network.ApiError
import io.wrtpilot.core.network.Credentials
import io.wrtpilot.core.network.RouterEndpoint
import io.wrtpilot.core.network.WrtPilotApi
import io.wrtpilot.core.network.model.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class FormStep { FORM, CHECKING, RESULT }

data class RouterFormState(
    val editing: Boolean = false,
    val name: String = "",
    val address: String = "192.168.1.1",
    val https: Boolean = false,
    val port: String = "",
    val username: String = "wrtpilot",
    val password: String = "",
    /** editing: an empty password keeps the saved one */
    val hasSavedPassword: Boolean = false,
    val step: FormStep = FormStep.FORM,
    val error: UiText? = null,
    /** fingerprint waiting for the user's decision */
    val certificatePrompt: String? = null,
    /** true when the prompt is about a CHANGED certificate */
    val certificateChanged: Boolean = false,
    val pinnedCertificate: String? = null,
    val status: Status? = null,
    val busy: Boolean = false,
    val done: Boolean = false,
) {
    val canConnect: Boolean
        get() = address.isNotBlank() && username.isNotBlank() && (password.isNotEmpty() || hasSavedPassword) && !busy
}

/** Add a router (onboarding / settings) or edit a saved one; tests the connection first. */
@HiltViewModel
class RouterFormViewModel @Inject constructor(
    private val routers: RouterRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val editId: Long = savedState.get<Long>("editId") ?: 0L
    private var editing: RouterEntity? = null
    private var probe: WrtPilotApi? = null

    private val _state = MutableStateFlow(RouterFormState(editing = editId != 0L))
    val state: StateFlow<RouterFormState> = _state.asStateFlow()

    init {
        if (editId != 0L) {
            viewModelScope.launch {
                val r = routers.get(editId) ?: return@launch
                editing = r
                val defaultPort = if (r.https) RouterEndpoint.HTTPS_PORT else RouterEndpoint.HTTP_PORT
                _state.update {
                    it.copy(
                        name = r.name,
                        address = r.host,
                        https = r.https,
                        port = if (r.port == defaultPort) "" else r.port.toString(),
                        username = r.username,
                        hasSavedPassword = true,
                        pinnedCertificate = r.certSha256,
                    )
                }
            }
        }
    }

    fun setName(v: String) = _state.update { it.copy(name = v) }
    fun setAddress(v: String) = _state.update { it.copy(address = v.trim(), error = null) }
    fun setHttps(v: Boolean) = _state.update { it.copy(https = v, error = null) }
    fun setPort(v: String) = _state.update { it.copy(port = v.filter(Char::isDigit).take(5), error = null) }
    fun setUsername(v: String) = _state.update { it.copy(username = v.trim(), error = null) }
    fun setPassword(v: String) = _state.update { it.copy(password = v, error = null) }

    private fun endpoint(s: RouterFormState): RouterEndpoint? {
        val parsed = RouterEndpoint.parse(s.address, s.https) ?: return null
        val port = s.port.toIntOrNull()?.takeIf { it in 1..65535 } ?: parsed.port
        val pin = if (parsed.https) s.pinnedCertificate else null
        return parsed.copy(port = port, certSha256 = pin)
    }

    private fun credentials(s: RouterFormState): Credentials {
        val saved = editing
        val password = if (s.password.isEmpty() && saved != null) routers.credentials(saved).password else s.password
        return Credentials(s.username, password)
    }

    fun connect() {
        val s = _state.value
        val ep = endpoint(s) ?: run {
            _state.update { it.copy(error = uiText(R.string.form_invalid_address)) }
            return
        }
        // the user may have typed "https://…": reflect what was parsed
        _state.update { it.copy(https = ep.https, step = FormStep.CHECKING, error = null, busy = true) }

        viewModelScope.launch {
            val api = routers.probe(ep, credentials(s))
            try {
                val status = api.status()
                probe = api
                _state.update { it.copy(step = FormStep.RESULT, status = status, busy = false) }
            } catch (e: ApiError.UntrustedCertificate) {
                _state.update {
                    if (e.sha256 != null) {
                        it.copy(step = FormStep.FORM, busy = false, certificatePrompt = e.sha256, certificateChanged = false)
                    } else {
                        it.copy(step = FormStep.FORM, busy = false, error = UiText.Error(e))
                    }
                }
            } catch (e: ApiError.CertificateChanged) {
                _state.update {
                    it.copy(step = FormStep.FORM, busy = false, certificatePrompt = e.actual, certificateChanged = true)
                }
            } catch (e: Exception) {
                _state.update { it.copy(step = FormStep.FORM, busy = false, error = UiText.Error(e)) }
            }
        }
    }

    fun trustCertificate() {
        val fp = _state.value.certificatePrompt ?: return
        _state.update { it.copy(pinnedCertificate = fp, certificatePrompt = null) }
        connect()
    }

    fun dismissCertificate() = _state.update { it.copy(certificatePrompt = null) }

    fun backToForm() = _state.update { it.copy(step = FormStep.FORM) }

    /** Turns flow offloading off (accurate statistics and shaping). */
    fun disableOffload() {
        val api = probe ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                api.setOffload(software = false, hardware = false)
                val status = api.status()
                _state.update { it.copy(status = status, busy = false) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = UiText.Error(e)) }
            }
        }
    }

    fun save() {
        val s = _state.value
        val ep = endpoint(s) ?: return
        val name = s.name.ifBlank { s.status?.hostname?.takeIf { it.isNotBlank() } ?: s.address }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val saved = editing
            if (saved == null) {
                routers.add(name, ep, credentials(s))
            } else {
                routers.update(saved, name, ep, s.username, s.password.ifEmpty { null })
            }
            _state.update { it.copy(busy = false, done = true) }
        }
    }
}
