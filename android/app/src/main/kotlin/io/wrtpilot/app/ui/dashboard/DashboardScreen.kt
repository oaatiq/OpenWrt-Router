package io.wrtpilot.app.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.PublicOff
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wrtpilot.app.R
import io.wrtpilot.app.ui.common.clockText
import io.wrtpilot.app.ui.common.currentLocale
import io.wrtpilot.app.ui.common.displayName
import io.wrtpilot.app.ui.common.durationText
import io.wrtpilot.app.ui.common.kind
import io.wrtpilot.app.ui.common.ltr
import io.wrtpilot.app.ui.common.momentText
import io.wrtpilot.app.ui.common.nowSeconds
import io.wrtpilot.app.ui.common.rateText
import io.wrtpilot.app.ui.components.Banner
import io.wrtpilot.app.ui.components.BannerKind
import io.wrtpilot.app.ui.components.ChartLegend
import io.wrtpilot.app.ui.components.ConnectionBanner
import io.wrtpilot.app.ui.components.DeviceAvatar
import io.wrtpilot.app.ui.components.LoadingState
import io.wrtpilot.app.ui.components.MessageEffect
import io.wrtpilot.app.ui.components.PauseSheet
import io.wrtpilot.app.ui.components.RouterTopBar
import io.wrtpilot.app.ui.components.SectionHeader
import io.wrtpilot.app.ui.components.TrafficChart
import io.wrtpilot.app.ui.theme.LocalStatusColors
import io.wrtpilot.core.network.model.Group

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenDevices: (String) -> Unit,
    onOpenDevice: (String) -> Unit,
    onOpenQos: () -> Unit,
    onOpenGroups: () -> Unit,
    onManageRouters: () -> Unit,
    vm: DashboardViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var offloadDialog by remember { mutableStateOf(false) }
    var pauseGroup by remember { mutableStateOf<Group?>(null) }
    MessageEffect(vm.events, snackbar)

    Scaffold(
        topBar = { RouterTopBar(stringResource(R.string.tab_home), onManageRouters) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading && state.status == null) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = vm::refresh,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                item { ConnectionBanner(state.error, state.updatedAt, vm::refresh) }
                state.status?.let { st ->
                    if (st.offloadWarning) {
                        item {
                            Banner(
                                BannerKind.WARNING,
                                stringResource(R.string.alert_offload_title),
                                stringResource(R.string.alert_offload_body),
                                actionLabel = stringResource(R.string.fix),
                                onAction = { offloadDialog = true },
                            )
                        }
                    }
                    if (!st.collector.running) {
                        item {
                            Banner(BannerKind.WARNING, stringResource(R.string.alert_collector_title), stringResource(R.string.alert_collector_body))
                        }
                    }
                    if (!st.enabled) {
                        item { Banner(BannerKind.INFO, stringResource(R.string.alert_disabled_title), stringResource(R.string.alert_disabled_body)) }
                    }
                }
                item { InternetCard(state) }
                item { CountsRow(state, onOpenDevices) }

                item { SectionHeader(stringResource(R.string.dash_top_devices)) }
                if (state.top.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.dash_no_traffic),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    items(state.top, key = { it.mac }) { c ->
                        ListItem(
                            headlineContent = { Text(c.displayName(state.oui), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { DeviceAvatar(c.kind(state.oui), c.online, !c.hasInternet) },
                            trailingContent = { RatePair(c.rxBps, c.txBps) },
                            modifier = Modifier.clickable { onOpenDevice(c.mac) },
                        )
                    }
                }
                item {
                    TextButton(onClick = { onOpenDevices("all") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.dash_all_devices))
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }

                item { SectionHeader(stringResource(R.string.tab_family)) }
                if (state.groups.isEmpty()) {
                    item {
                        ShortcutCard(
                            icon = Icons.Rounded.FamilyRestroom,
                            title = stringResource(R.string.dash_family_setup_title),
                            body = stringResource(R.string.dash_family_setup_body),
                            onClick = onOpenGroups,
                        )
                    }
                } else {
                    items(state.groups, key = { "g_" + it.id }) { g ->
                        GroupRow(g, onOpenGroups, onPause = { pauseGroup = g }, onResume = { vm.resumeGroup(g) })
                    }
                }

                item { SectionHeader(stringResource(R.string.qos_title)) }
                item {
                    val qos = state.qos
                    ShortcutCard(
                        icon = Icons.Rounded.Speed,
                        title = stringResource(
                            when {
                                qos == null || !qos.available -> R.string.dash_qos_unavailable
                                qos.enabled -> R.string.dash_qos_on
                                else -> R.string.dash_qos_off
                            }
                        ),
                        body = stringResource(R.string.qos_explain_short),
                        onClick = onOpenQos,
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (offloadDialog) {
        AlertDialog(
            onDismissRequest = { offloadDialog = false },
            title = { Text(stringResource(R.string.offload_dialog_title)) },
            text = { Text(stringResource(R.string.offload_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    offloadDialog = false
                    vm.disableOffload()
                }) { Text(stringResource(R.string.offload_dialog_confirm)) }
            },
            dismissButton = { TextButton(onClick = { offloadDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    pauseGroup?.let { g ->
        PauseSheet(
            title = stringResource(R.string.pause_group_title, g.name),
            onDismiss = { pauseGroup = null },
            onPause = {
                pauseGroup = null
                vm.pauseGroup(g, it)
            },
        )
    }
}

@Composable
private fun InternetCard(state: DashboardState) {
    val status = LocalStatusColors.current
    val wan = state.status?.wan
    val online = wan?.up == true
    val context = LocalContext.current
    val live = state.live

    ElevatedCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (online) Icons.Rounded.Public else Icons.Rounded.PublicOff,
                    contentDescription = null,
                    tint = if (online) status.online else status.blocked,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (online) R.string.internet_online else R.string.internet_offline),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val details = buildList {
                        wan?.ipv4?.let { add(ltr(it)) }
                        val uptime = wan?.uptime ?: 0
                        if (online && uptime > 0) add(stringResource(R.string.wan_uptime, durationText(context, uptime)))
                    }
                    if (details.isNotEmpty()) {
                        Text(
                            details.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                BigRate(Icons.Rounded.ArrowDownward, stringResource(R.string.download), state.totalRx, status.download)
                BigRate(Icons.Rounded.ArrowUpward, stringResource(R.string.upload), state.totalTx, status.upload)
            }
            if (live != null && live.ts.size >= 2) {
                Spacer(Modifier.height(12.dp))
                val locale = currentLocale()
                val labels = remember(live.ts, locale) { live.ts.map { clockText(it, locale) } }
                TrafficChart(live.total.rx, live.total.tx, labels, height = 140.dp)
                ChartLegend(Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun BigRate(icon: ImageVector, label: String, bps: Long, color: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(rateText(bps), style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
fun RatePair(rx: Long, tx: Long) {
    val status = LocalStatusColors.current
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ArrowDownward, contentDescription = stringResource(R.string.download), tint = status.download, modifier = Modifier.size(14.dp))
            Text(rateText(rx), style = MaterialTheme.typography.labelLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.upload), tint = status.upload, modifier = Modifier.size(14.dp))
            Text(rateText(tx), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CountsRow(state: DashboardState, onOpenDevices: (String) -> Unit) {
    val status = LocalStatusColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CountTile(
            Modifier.weight(1f), Icons.Rounded.Wifi, state.online,
            pluralStringResource(R.plurals.count_online, state.online), status.online,
        ) { onOpenDevices("online") }
        CountTile(
            Modifier.weight(1f), Icons.Rounded.Block, state.blocked,
            pluralStringResource(R.plurals.count_blocked, state.blocked), status.blocked,
        ) { onOpenDevices("blocked") }
        CountTile(
            Modifier.weight(1f), Icons.Rounded.PauseCircle, state.paused,
            pluralStringResource(R.plurals.count_paused, state.paused), status.paused,
        ) { onOpenDevices("paused") }
    }
}

@Composable
private fun CountTile(modifier: Modifier, icon: ImageVector, count: Int, label: String, color: Color, onClick: () -> Unit) {
    val locale = currentLocale()
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = null, tint = color)
            Spacer(Modifier.height(8.dp))
            Text(
                java.text.NumberFormat.getIntegerInstance(locale).format(count),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun GroupRow(group: Group, onOpen: () -> Unit, onPause: () -> Unit, onResume: () -> Unit) {
    val context = LocalContext.current
    val locale = currentLocale()
    val now = nowSeconds()
    val statusText = when {
        group.pausedUntil == -1L -> stringResource(R.string.state_paused)
        group.paused -> stringResource(R.string.state_paused_until, momentText(group.pausedUntil, locale, now))
        group.hasSchedule && !group.allowedNow && group.nextChange > 0 ->
            stringResource(R.string.group_bedtime_until, momentText(group.nextChange, locale, now))
        group.hasSchedule && group.nextChange > 0 ->
            stringResource(R.string.group_allowed_until, momentText(group.nextChange, locale, now))
        else -> stringResource(R.string.group_always_allowed)
    }
    ListItem(
        headlineContent = { Text(group.name) },
        supportingContent = {
            Text(pluralStringResource(R.plurals.member_count, group.members.size, group.members.size) + " · " + statusText)
        },
        leadingContent = { Icon(Icons.Rounded.FamilyRestroom, contentDescription = null) },
        trailingContent = {
            if (group.paused) {
                FilledTonalButton(onClick = onResume) { Text(stringResource(R.string.resume)) }
            } else {
                OutlinedButton(onClick = onPause, enabled = group.members.isNotEmpty()) { Text(stringResource(R.string.pause)) }
            }
        },
        colors = ListItemDefaults.colors(),
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

@Composable
private fun ShortcutCard(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
        }
    }
}
