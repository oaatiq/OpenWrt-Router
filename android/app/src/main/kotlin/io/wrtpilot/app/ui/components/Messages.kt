package io.wrtpilot.app.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import io.wrtpilot.app.ui.common.UiMessage
import kotlinx.coroutines.flow.Flow

/** Shows a ViewModel's one-shot messages in a snackbar (errors after a rollback, confirmations). */
@Composable
fun MessageEffect(events: Flow<UiMessage>, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(events) {
        events.collect { msg ->
            val result = snackbar.showSnackbar(
                message = msg.text.resolve(context),
                actionLabel = msg.actionLabel?.resolve(context),
                withDismissAction = msg.actionLabel == null,
                duration = if (msg.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) msg.action?.invoke()
        }
    }
}
