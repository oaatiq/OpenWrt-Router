package io.wrtpilot.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.wrtpilot.app.R
import io.wrtpilot.core.network.BlockMode
import io.wrtpilot.core.network.PauseFor
import java.time.LocalDate
import java.time.ZoneId

/** Seconds from now until the next local midnight ("until tomorrow"). */
fun secondsUntilTomorrow(): Int {
    val zone = ZoneId.systemDefault()
    val midnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toEpochSecond()
    return (midnight - System.currentTimeMillis() / 1000).toInt().coerceAtLeast(60)
}

private data class PauseOption(val label: Int, val icon: ImageVector, val value: PauseFor)

/** "Pause internet" duration picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PauseSheet(
    title: String,
    onDismiss: () -> Unit,
    onPause: (PauseFor) -> Unit,
) {
    val options = listOf(
        PauseOption(R.string.pause_15_min, Icons.Rounded.Timer, PauseFor.Seconds(15 * 60)),
        PauseOption(R.string.pause_1_hour, Icons.Rounded.Timer, PauseFor.Seconds(3600)),
        PauseOption(R.string.pause_until_tomorrow, Icons.Rounded.Bedtime, PauseFor.UntilTomorrow),
        PauseOption(R.string.pause_indefinitely, Icons.Rounded.AllInclusive, PauseFor.Indefinitely),
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Text(
                stringResource(R.string.pause_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            for (o in options) {
                ListItem(
                    headlineContent = { Text(stringResource(o.label)) },
                    leadingContent = { Icon(o.icon, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .selectable(selected = false, role = Role.Button, onClick = { onPause(o.value) }),
                )
            }
        }
    }
}

private enum class BlockDuration { FOREVER, ONE_HOUR, TOMORROW }

/**
 * Block options: internet only (device stays on the home network) or Wi-Fi
 * (disconnected now and refused until unblocked), for how long.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockSheet(
    deviceName: String,
    wifiAvailable: Boolean,
    onDismiss: () -> Unit,
    onBlock: (BlockMode, Int) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(BlockMode.INTERNET) }
    var duration by rememberSaveable { mutableStateOf(BlockDuration.FOREVER) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(stringResource(R.string.block_title, deviceName), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Column(Modifier.selectableGroup()) {
                ModeOption(
                    selected = mode == BlockMode.INTERNET,
                    title = stringResource(R.string.block_mode_internet),
                    subtitle = stringResource(R.string.block_mode_internet_desc),
                    onClick = { mode = BlockMode.INTERNET },
                )
                ModeOption(
                    selected = mode == BlockMode.WIFI,
                    title = stringResource(R.string.block_mode_wifi),
                    subtitle = stringResource(
                        if (wifiAvailable) R.string.block_mode_wifi_desc else R.string.block_mode_wifi_unavailable
                    ),
                    enabled = wifiAvailable,
                    onClick = { mode = BlockMode.WIFI },
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.block_duration), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = duration == BlockDuration.FOREVER,
                    onClick = { duration = BlockDuration.FOREVER },
                    label = { Text(stringResource(R.string.block_until_unblocked)) },
                )
                FilterChip(
                    selected = duration == BlockDuration.ONE_HOUR,
                    onClick = { duration = BlockDuration.ONE_HOUR },
                    label = { Text(stringResource(R.string.pause_1_hour)) },
                )
                FilterChip(
                    selected = duration == BlockDuration.TOMORROW,
                    onClick = { duration = BlockDuration.TOMORROW },
                    label = { Text(stringResource(R.string.pause_until_tomorrow)) },
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val seconds = when (duration) {
                        BlockDuration.FOREVER -> 0
                        BlockDuration.ONE_HOUR -> 3600
                        BlockDuration.TOMORROW -> secondsUntilTomorrow()
                    }
                    onBlock(mode, seconds)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.block_confirm))
            }
        }
    }
}

@Composable
private fun ModeOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
