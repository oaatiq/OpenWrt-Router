package io.wrtpilot.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.wrtpilot.app.R
import io.wrtpilot.app.ui.common.agoText
import io.wrtpilot.app.ui.common.errorMessage
import io.wrtpilot.app.ui.common.nowSeconds

enum class BannerKind { INFO, WARNING, ERROR, OFFLINE }

@Composable
fun Banner(
    kind: BannerKind,
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content, icon) = when (kind) {
        BannerKind.INFO -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, Icons.Rounded.Info)
        BannerKind.WARNING -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, Icons.Rounded.WarningAmber)
        BannerKind.ERROR -> Triple(scheme.errorContainer, scheme.onErrorContainer, Icons.Rounded.ErrorOutline)
        BannerKind.OFFLINE -> Triple(scheme.surfaceContainerHighest, scheme.onSurface, Icons.Rounded.CloudOff)
    }
    BannerCard(container, content, icon, title, message, modifier, actionLabel, onAction)
}

@Composable
private fun BannerCard(
    container: Color,
    content: Color,
    icon: ImageVector,
    title: String,
    message: String?,
    modifier: Modifier,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (message != null) {
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel, color = content) }
            }
        }
    }
}

/**
 * Shown when the router could not be reached: explains why and how old the
 * displayed (cached) data is.
 */
@Composable
fun ConnectionBanner(error: Throwable?, updatedAtMillis: Long, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (error == null) return
    val context = LocalContext.current
    val updated = if (updatedAtMillis > 0) {
        stringResource(R.string.last_updated, agoText(context, updatedAtMillis / 1000, nowSeconds()))
    } else {
        null
    }
    Banner(
        kind = BannerKind.OFFLINE,
        title = errorMessage(context, error),
        message = updated,
        modifier = modifier,
        actionLabel = stringResource(R.string.retry),
        onAction = onRetry,
    )
}
