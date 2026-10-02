package io.wrtpilot.app.ui.devices

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wrtpilot.app.R
import io.wrtpilot.app.ui.common.bytesText
import io.wrtpilot.app.ui.common.displayName
import io.wrtpilot.app.ui.common.kind
import io.wrtpilot.app.ui.common.nowSeconds
import io.wrtpilot.app.ui.common.restrictionLabel
import io.wrtpilot.app.ui.common.subtitle
import io.wrtpilot.app.ui.components.BlockSheet
import io.wrtpilot.app.ui.components.ConnectionBanner
import io.wrtpilot.app.ui.components.DeviceAvatar
import io.wrtpilot.app.ui.components.EmptyState
import io.wrtpilot.app.ui.components.LoadingState
import io.wrtpilot.app.ui.components.MessageEffect
import io.wrtpilot.app.ui.components.PauseSheet
import io.wrtpilot.app.ui.components.RouterTopBar
import io.wrtpilot.app.ui.components.StatusPill
import io.wrtpilot.app.ui.dashboard.RatePair
import io.wrtpilot.app.ui.theme.LocalStatusColors
import io.wrtpilot.core.domain.OuiTable
import io.wrtpilot.core.network.model.Client

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onOpenDevice: (String) -> Unit,
    onManageRouters: () -> Unit,
    vm: DevicesViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var searching by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var pauseTarget by remember { mutableStateOf<Client?>(null) }
    var blockTarget by remember { mutableStateOf<Client?>(null) }
    MessageEffect(vm.events, snackbar)

    Scaffold(
        topBar = {
            Column {
                RouterTopBar(
                    title = stringResource(R.string.tab_devices),
                    onManageRouters = onManageRouters,
                    actions = {
                        IconButton(onClick = {
                            searching = !searching
                            if (!searching) vm.setQuery("")
                        }) {
                            Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search))
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = stringResource(R.string.sort))
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                for ((sort, label) in listOf(
                                    DeviceSort.NAME to R.string.sort_name,
                                    DeviceSort.RATE to R.string.sort_rate,
                                    DeviceSort.USAGE to R.string.sort_usage,
                                )) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(label)) },
                                        leadingIcon = { RadioButton(selected = state.controls.sort == sort, onClick = null) },
                                        onClick = {
                                            sortMenu = false
                                            vm.setSort(sort)
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
                AnimatedVisibility(searching) {
                    OutlinedTextField(
                        value = state.controls.query,
                        onValueChange = vm::setQuery,
                        placeholder = { Text(stringResource(R.string.search_devices)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = {
                            if (state.controls.query.isNotEmpty()) {
                                IconButton(onClick = { vm.setQuery("") }) {
                                    Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.clear))
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                FilterRow(state, vm::setFilter)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            else -> PullToRefreshBox(
                isRefreshing = false,
                onRefresh = vm::refresh,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item { ConnectionBanner(state.error, state.updatedAt, vm::refresh) }
                    if (state.devices.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Rounded.SearchOff,
                                title = stringResource(if (state.total == 0) R.string.devices_empty else R.string.devices_no_match),
                                message = if (state.total == 0) stringResource(R.string.devices_empty_hint) else null,
                            )
                        }
                    }
                    items(state.devices, key = { it.mac }) { device ->
                        val name = device.displayName(context, state.oui)
                        SwipeableDevice(
                            device = device,
                            oui = state.oui,
                            onClick = { onOpenDevice(device.mac) },
                            onPauseToggle = {
                                if (device.paused) vm.resume(device, name) else pauseTarget = device
                            },
                            onBlockToggle = {
                                if (device.isBlocked) vm.unblock(device, name) else blockTarget = device
                            },
                        )
                    }
                }
            }
        }
    }

    pauseTarget?.let { d ->
        val name = d.displayName(context, state.oui)
        PauseSheet(
            title = stringResource(R.string.pause_device_title, name),
            onDismiss = { pauseTarget = null },
            onPause = {
                pauseTarget = null
                vm.pause(d, name, it)
            },
        )
    }
    blockTarget?.let { d ->
        val name = d.displayName(context, state.oui)
        BlockSheet(
            deviceName = name,
            wifiAvailable = state.wifiControl && d.conn != "lan",
            onDismiss = { blockTarget = null },
            onBlock = { mode, seconds ->
                blockTarget = null
                vm.block(d, name, mode, seconds)
            },
        )
    }
}

@Composable
private fun FilterRow(state: DevicesState, onFilter: (String) -> Unit) {
    val filters = buildList {
        add("all" to stringResource(R.string.filter_all))
        add("online" to stringResource(R.string.filter_online))
        add("wifi" to stringResource(R.string.filter_wifi))
        add("wired" to stringResource(R.string.filter_wired))
        add("blocked" to stringResource(R.string.filter_blocked))
        add("paused" to stringResource(R.string.filter_paused))
        for (g in state.groups) add("group:${g.id}" to g.name)
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(filters, key = { it.first }) { (key, label) ->
            FilterChip(
                selected = state.controls.filter == key,
                onClick = { onFilter(key) },
                label = { Text(label) },
            )
        }
    }
}

/**
 * Device row. Swipe towards the end to pause / resume, towards the start to
 * block / unblock. The same actions are exposed to accessibility services.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableDevice(
    device: Client,
    oui: OuiTable?,
    onClick: () -> Unit,
    onPauseToggle: () -> Unit,
    onBlockToggle: () -> Unit,
) {
    val colors = LocalStatusColors.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onPauseToggle()
                SwipeToDismissBoxValue.EndToStart -> onBlockToggle()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false // always snap back; the list reflects the new state
        },
    )
    val pauseLabel = stringResource(if (device.paused) R.string.resume else R.string.pause)
    val blockLabel = stringResource(if (device.isBlocked) R.string.unblock else R.string.block)

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val toEnd = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val (bg, icon, label) = if (toEnd) {
                Triple(colors.paused, if (device.paused) Icons.Rounded.PlayCircle else Icons.Rounded.PauseCircle, pauseLabel)
            } else {
                Triple(colors.blocked, if (device.isBlocked) Icons.Rounded.LockOpen else Icons.Rounded.Lock, blockLabel)
            }
            SwipeBackground(bg, icon, label, alignEnd = !toEnd)
        },
    ) {
        DeviceRow(
            device = device,
            oui = oui,
            modifier = Modifier
                .clickable(onClick = onClick)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(pauseLabel) { onPauseToggle(); true },
                        CustomAccessibilityAction(blockLabel) { onBlockToggle(); true },
                    )
                },
        )
    }
}

@Composable
private fun SwipeBackground(color: Color, icon: ImageVector, label: String, alignEnd: Boolean) {
    Row(
        Modifier
            .fillMaxSize()
            .background(color.copy(alpha = 0.85f))
            .padding(horizontal = 24.dp),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun DeviceRow(device: Client, oui: OuiTable?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = LocalStatusColors.current
    val now = nowSeconds()
    val restriction = device.restrictionLabel(context, now)

    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    device.displayName(oui),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (device.isSelf) {
                    Spacer(Modifier.width(8.dp))
                    StatusPill(stringResource(R.string.this_device), MaterialTheme.colorScheme.primary)
                }
            }
        },
        supportingContent = {
            Text(device.subtitle(context, oui, now), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { DeviceAvatar(device.kind(oui), device.online, !device.hasInternet) },
        trailingContent = {
            when {
                restriction != null -> StatusPill(
                    restriction,
                    if (device.isBlocked || device.quotaExceeded) colors.blocked else colors.paused,
                )
                device.online && (device.rxBps > 0 || device.txBps > 0) -> RatePair(device.rxBps, device.txBps)
                device.todayRx + device.todayTx > 0 -> Text(
                    bytesText(device.todayRx + device.todayTx),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Unit
            }
        },
        modifier = modifier,
    )
}
