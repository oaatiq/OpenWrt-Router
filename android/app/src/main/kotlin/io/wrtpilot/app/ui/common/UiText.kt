package io.wrtpilot.app.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.wrtpilot.app.R
import io.wrtpilot.core.network.AgentErrors
import io.wrtpilot.core.network.ApiError

/** Text produced by a ViewModel and resolved in the UI's language. */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Error(val error: Throwable) : UiText
    data class Plain(val text: String) : UiText

    fun resolve(context: Context): String = when (this) {
        is Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *args.toTypedArray())
        is Error -> errorMessage(context, error)
        is Plain -> text
    }
}

@Composable
fun UiText.asString(): String = resolve(LocalContext.current)

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

/** A one-shot message for a snackbar, optionally with an undo/retry action. */
data class UiMessage(
    val text: UiText,
    val actionLabel: UiText? = null,
    val action: (() -> Unit)? = null,
    val id: Long = System.nanoTime(),
)

/** Human readable, localised explanation of an error. */
fun errorMessage(context: Context, error: Throwable): String = context.getString(errorRes(error))

@StringRes
fun errorRes(error: Throwable): Int = when (error) {
    is ApiError.Network -> R.string.error_network
    is ApiError.UntrustedCertificate -> R.string.error_untrusted_certificate
    is ApiError.CertificateChanged -> R.string.error_certificate_changed
    is ApiError.Http -> if (error.status == 404) R.string.error_ubus_disabled else R.string.error_http
    ApiError.BadCredentials -> R.string.error_bad_credentials
    ApiError.AccessDenied -> R.string.error_access_denied
    ApiError.AgentMissing -> R.string.error_agent_missing
    is ApiError.AgentOutdated -> R.string.error_agent_outdated
    is ApiError.Ubus -> if (error.status == ApiError.UBUS_TIMEOUT) R.string.error_timeout else R.string.error_generic
    is ApiError.Agent -> when (error.code) {
        AgentErrors.SELF_BLOCK -> R.string.error_self_block
        AgentErrors.INVALID_DOMAIN -> R.string.error_invalid_domain
        AgentErrors.INVALID_SCHEDULE -> R.string.error_invalid_schedule
        AgentErrors.UNKNOWN_GROUP -> R.string.error_unknown_group
        AgentErrors.SQM_MISSING -> R.string.error_sqm_missing
        AgentErrors.NO_WAN -> R.string.error_no_wan
        AgentErrors.COLLECTOR_UNAVAILABLE -> R.string.error_collector
        AgentErrors.NFT_FAILED, AgentErrors.APPLY_FAILED -> R.string.error_apply_failed
        AgentErrors.INVALID_ARGUMENT, AgentErrors.INVALID_MAC, AgentErrors.INVALID_GROUP_ID -> R.string.error_invalid_input
        else -> R.string.error_generic
    }
    is ApiError.Protocol -> R.string.error_protocol
    else -> R.string.error_generic
}
