package io.wrtpilot.app.ui.devices

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wrtpilot.app.R
import io.wrtpilot.app.ui.common.agoText
import io.wrtpilot.app.ui.common.bytesText
import io.wrtpilot.app.ui.common.clockText
import io.wrtpilot.app.ui.common.connectionLabel
import io.wrtpilot.app.ui.common.currentLocale
import io.wrtpilot.app.ui.common.dateText
import io.wrtpilot.app.ui.common.displayName
import io.wrtpilot.app.ui.common.formatBytes
import io.wrtpilot.app.ui.common.kind
import io.wrtpilot.app.ui.common.ltr
import io.wrtpilot.app.ui.common.nowSeconds
import io.wrtpilot.app.ui.common.restrictionLabel
import io.wrtpilot.app.ui.common.vendor
import io.wrtpilot.app.ui.components.BlockSheet
import io.wrtpilot.app.ui.components.ChartLegend
import io.wrtpilot.app.ui.components.DeviceAvatar
import io.wrtpilot.app.ui.components.EmptyState
import io.wrtpilot.app.ui.components.InfoRow
import io.wrtpilot.app.ui.components.LoadingState
import io.wrtpilot.app.ui.components.MessageEffect
import io.wrtpilot.app.ui.components.PauseSheet
import io.wrtpilot.app.ui.components.SectionHeader
import io.wrtpilot.app.ui.components.SpeedLimitEditor
import io.wrtpilot.app.ui.components.StatusPill
import io.wrtpilot.app.ui.components.TrafficChart
import io.wrtpilot.app.ui.components.UsageChart
import io.wrtpilot.app.ui.dashboard.RatePair
import io.wrtpilot.app.ui.theme.LocalStatusColors
import io.wrtpilot.core.network.model.Client
import io.wrtpilot.core.network.model.Group
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import kotlin.math.roundToInt

/** Daily quota steps in MB. */
private val QUOTA_STEPS = listOf(100, 250, 500, 1000, 2000, 3000, 5000, 10_000, 20_000, 50_000)

internal fun nearestIndex(steps: List<Int>, value: Int): Int =
    steps.indices.minByOrNull { kotlin.math.abs(steps[it] - value) } ?: 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    onBack: () -> Unit,
    onOpenGroup: (String) -> Unit,
    vm: DeviceDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf(false) }
    var pausing by remember { mutableStateOf(false) }
    var blocking by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    MessageEffect(vm.events, snackbar)

    LaunchedEffect(state.gone) {
        if (state.gone) onBack()
    }

    val device = state.device
    val name = device?.displayName(state.oui) ?: ltr(vm.mac)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (device != null) {
                        IconButton(onClick = { renaming = true }) {
                            Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.rename))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            device == null && state.loading -> LoadingState(Modifier.padding(padding))
            device == null -> EmptyState(
                icon = Icons.Rounded.DeleteOutline,
                title = stringResource(R.string.device_not_found),
                message = null,
                modifier = Modifier.padding(padding),
            )
            else -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp),
            ) {
                Header(device, state)
                Actions(
                    device = device,
                    onPause = { if (device.paused) vm.resume() else pausing = true },
                    onBlock = { if (device.isBlocked) vm.unblock() else blocking = true },
                )
                LiveCard(state)
                UsageCard(state, vm::loadUsage)

                SectionHeader(stringResource(R.string.section_rules))
                GroupPicker(device, state.groups, vm::setGroup, onOpenGroup)
                SpeedLimitEditor(
                    key = device.mac,
                    dlKbps = device.dlLimitKbps,
                    ulKbps = device.ulLimitKbps,
                    coarse = state.coarseLimiting,
                    onApply = vm::setLimits,
                )
                QuotaSettings(device, vm::setQuota)

                SectionHeader(stringResource(R.string.section_details))
                Details(device, state)

                if (!device.online) {
                    TextButton(
                        onClick = { forgetting = true },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.forget_device))
                    }
                }
            }
        }
    }

    if (renaming && device != null) {
        RenameDialog(
            current = device.customName.ifBlank { device.name },
            hasCustomName = device.customName.isNotBlank(),
            onDismiss = { renaming = false },
            onSave = {
                renaming = false
                vm.rename(it)
            },
        )
    }
    if (pausing) {
        PauseSheet(
            title = stringResource(R.string.pause_device_title, name),
            onDismiss = { pausing = false },
            onPause = {
                pausing = false
                vm.pause(it)
            },
        )
    }
    if (blocking && device != null) {
        BlockSheet(
            deviceName = name,
            wifiAvailable = state.capabilities.hostapd && device.conn != "lan",
            onDismiss = { blocking = false },
            onBlock = { mode, seconds ->
                blocking = false
                vm.block(mode, seconds)
            },
        )
    }
    if (forgetting) {
        AlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text(stringResource(R.string.forget_title)) },
            text = { Text(stringResource(R.string.forget_body)) },
            confirmButton = {
                TextButton(onClick = {
                    forgetting = false
                    vm.forget()
                }) { Text(stringResource(R.string.forget_device)) }
            },
            dismissButton = { TextButton(onClick = { forgetting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Header(device: Client, state: DeviceDetailState) {
    val context = LocalContext.current
    val colors = LocalStatusColors.current
    val restriction = device.restrictionLabel(context, nowSeconds())
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        DeviceAvatar(device.kind(state.oui), device.online, !device.hasInternet, size = 64.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(device.displayName(state.oui), style = MaterialTheme.typography.titleLarge)
            device.vendor(state.oui)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(
                    stringResource(if (device.online) R.string.online else R.string.offline),
                    if (device.online) colors.online else colors.offline,
                )
                if (restriction != null) StatusPill(restriction, if (device.isBlocked) colors.blocked else colors.paused)
                if (device.isSelf) StatusPill(stringResource(R.string.this_device), MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Actions(device: Client, onPause: () -> Unit, onBlock: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(
            onClick = onPause,
            enabled = !device.isSelf || device.paused,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
        ) {
            Icon(if (device.paused) Icons.Rounded.PlayCircle else Icons.Rounded.PauseCircle, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (device.paused) R.string.resume else R.string.pause))
        }
        FilledTonalButton(
            onClick = onBlock,
            enabled = !device.isSelf || device.isBlocked,
            colors = if (device.isBlocked) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
        ) {
            Icon(if (device.isBlocked) Icons.Rounded.LockOpen else Icons.Rounded.Lock, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (device.isBlocked) R.string.unblock else R.string.block))
        }
    }
    if (device.isSelf) {
        Text(
            stringResource(R.string.self_device_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun LiveCard(state: DeviceDetailState) {
    val device = state.device ?: return
    val series = state.live?.devices?.get(device.mac)
    val locale = currentLocale()
    Card(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.live_traffic), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                RatePair(device.rxBps, device.txBps)
            }
            Spacer(Modifier.height(8.dp))
            val ts = state.live?.ts.orEmpty()
            val labels = remember(ts, locale) { ts.map { clockText(it, locale) } }
            TrafficChart(series?.rx.orEmpty(), series?.tx.orEmpty(), labels)
            ChartLegend(Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun UsageCard(state: DeviceDetailState, onRange: (UsageRange) -> Unit) {
    val usage = state.usage
    val locale = currentLocale()
    val zone = ZoneId.systemDefault()
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.usage), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                UsageRange.entries.forEachIndexed { i, r ->
                    SegmentedButton(
                        selected = usage.range == r,
                        onClick = { onRange(r) },
                        shape = SegmentedButtonDefaults.itemShape(i, UsageRange.entries.size),
                    ) {
                        Text(
                            stringResource(
                                when (r) {
                                    UsageRange.DAY -> R.string.range_24h
                                    UsageRange.WEEK -> R.string.range_7d
                                    UsageRange.MONTH -> R.string.range_30d
                                }
                            )
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val rx = usage.points.sumOf { it.rx }
            val tx = usage.points.sumOf { it.tx }
            Text(bytesText(rx + tx), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.usage_split, bytesText(rx), bytesText(tx)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (usage.offline) {
                Text(stringResource(R.string.usage_offline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.height(8.dp))
            val labels = remember(usage.points, locale) {
                usage.points.map { p ->
                    val t = Instant.ofEpochSecond(p.ts).atZone(zone)
                    if (usage.range == UsageRange.DAY) clockText(p.ts, locale)
                    else if (usage.range == UsageRange.WEEK) t.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                    else java.text.NumberFormat.getIntegerInstance(locale).format(t.dayOfMonth)
                }
            }
            UsageChart(usage.points.map { it.rx }, usage.points.map { it.tx }, labels)
            ChartLegend(Modifier.padding(top = 4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupPicker(device: Client, groups: List<Group>, onSelect: (String) -> Unit, onOpenGroup: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = groups.firstOrNull { it.id == device.group }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = current?.name ?: stringResource(R.string.group_none),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.family_group)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_none)) },
                    onClick = {
                        expanded = false
                        onSelect("")
                    },
                )
                for (g in groups) {
                    DropdownMenuItem(
                        text = { Text(g.name) },
                        onClick = {
                            expanded = false
                            onSelect(g.id)
                        },
                    )
                }
            }
        }
        if (current != null) {
            TextButton(onClick = { onOpenGroup(current.id) }) {
                Text(stringResource(R.string.open_group_rules, current.name))
            }
        } else {
            Text(
                stringResource(R.string.group_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun QuotaSettings(device: Client, onApply: (Int, String) -> Unit) {
    val context = LocalContext.current
    val locale = currentLocale()
    val active = device.dailyQuotaMb > 0
    var enabled by rememberSaveable(device.mac, active) { mutableStateOf(active) }
    var index by rememberSaveable(device.dailyQuotaMb) {
        mutableFloatStateOf(nearestIndex(QUOTA_STEPS, device.dailyQuotaMb.takeIf { it > 0 } ?: 2000).toFloat())
    }
    var action by rememberSaveable(device.quotaAction) { mutableStateOf(device.quotaAction.ifBlank { "notify" }) }
    val mb = QUOTA_STEPS[index.roundToInt()]
    val apply = { onApply(QUOTA_STEPS[index.roundToInt()], action) }

    SettingSwitch(
        title = stringResource(R.string.quota),
        subtitle = if (active) {
            stringResource(
                R.string.quota_summary,
                formatBytes(context, device.dailyQuotaMb * 1_000_000L, locale),
                formatBytes(context, device.todayRx + device.todayTx, locale),
            )
        } else {
            stringResource(R.string.quota_off)
        },
        checked = enabled,
        onChange = {
            enabled = it
            if (it) apply() else onApply(0, action)
        },
    )
    if (enabled) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            LabeledSlider(
                label = stringResource(R.string.quota_per_day),
                value = index,
                steps = QUOTA_STEPS.size,
                valueText = formatBytes(context, mb * 1_000_000L, locale),
                onChange = { index = it },
                onDone = apply,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = action == "notify",
                    onClick = { action = "notify"; onApply(mb, "notify") },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(stringResource(R.string.quota_notify)) }
                SegmentedButton(
                    selected = action == "block",
                    onClick = { action = "block"; onApply(mb, "block") },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(stringResource(R.string.quota_block)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
internal fun LabeledSlider(
    label: String,
    value: Float,
    steps: Int,
    valueText: String,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = 0f..(steps - 1).toFloat(),
            steps = (steps - 2).coerceAtLeast(0),
        )
    }
}

@Composable
private fun Details(device: Client, state: DeviceDetailState) {
    val context = LocalContext.current
    val locale = currentLocale()
    val clipboard = LocalClipboardManager.current
    val now = nowSeconds()
    Row(verticalAlignment = Alignment.CenterVertically) {
        InfoRow(stringResource(R.string.mac_address), ltr(device.mac), Modifier.weight(1f))
        IconButton(onClick = { clipboard.setText(AnnotatedString(device.mac)) }) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.copy), modifier = Modifier.size(18.dp))
        }
    }
    if (device.randomMac) {
        Text(
            stringResource(R.string.random_mac_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    if (device.ip.isNotBlank()) InfoRow(stringResource(R.string.ip_address), ltr(device.ip))
    for (ip6 in device.ipv6.take(3)) InfoRow(stringResource(R.string.ipv6_address), ltr(ip6))
    if (device.hostname.isNotBlank()) InfoRow(stringResource(R.string.hostname), ltr(device.hostname))
    InfoRow(stringResource(R.string.connection), connectionLabel(context, device.conn))
    if (device.isWifi && device.ssid.isNotBlank()) InfoRow(stringResource(R.string.network_name), device.ssid)
    if (device.isWifi && device.signal != 0) {
        InfoRow(stringResource(R.string.signal), stringResource(R.string.signal_dbm, java.text.NumberFormat.getIntegerInstance(locale).format(device.signal)))
    }
    InfoRow(stringResource(R.string.today), bytesText(device.todayRx + device.todayTx))
    if (device.firstSeen > 0) InfoRow(stringResource(R.string.first_seen), dateText(device.firstSeen, locale))
    if (!device.online && device.lastSeen > 0) InfoRow(stringResource(R.string.last_seen), agoText(context, device.lastSeen, now))
}

@Composable
private fun RenameDialog(current: String, hasCustomName: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(64) },
                singleLine = true,
                label = { Text(stringResource(R.string.device_name)) },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (hasCustomName) {
                    TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.reset_name)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
