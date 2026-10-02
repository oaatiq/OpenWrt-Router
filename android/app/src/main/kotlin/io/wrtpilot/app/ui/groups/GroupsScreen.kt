package io.wrtpilot.app.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wrtpilot.app.R
import io.wrtpilot.app.ui.common.currentLocale
import io.wrtpilot.app.ui.common.momentText
import io.wrtpilot.app.ui.common.nowSeconds
import io.wrtpilot.app.ui.components.ConnectionBanner
import io.wrtpilot.app.ui.components.EmptyState
import io.wrtpilot.app.ui.components.LoadingState
import io.wrtpilot.app.ui.components.MessageEffect
import io.wrtpilot.app.ui.components.PauseSheet
import io.wrtpilot.app.ui.components.RouterTopBar
import io.wrtpilot.app.ui.theme.LocalStatusColors
import io.wrtpilot.core.network.model.Group

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    onOpenGroup: (String) -> Unit,
    onManageRouters: () -> Unit,
    vm: GroupsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var creating by rememberSaveable { mutableStateOf(false) }
    var pauseTarget by remember { mutableStateOf<Group?>(null) }
    MessageEffect(vm.events, snackbar)

    LaunchedEffect(Unit) {
        vm.createdGroups.collect { onOpenGroup(it) }
    }

    Scaffold(
        topBar = { RouterTopBar(stringResource(R.string.tab_family), onManageRouters) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.groups.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { creating = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.new_group)) },
                )
            }
        },
    ) { padding ->
        if (state.loading) {
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
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                item { ConnectionBanner(state.error, state.updatedAt, vm::refresh) }
                if (state.groups.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.FamilyRestroom,
                            title = stringResource(R.string.groups_empty_title),
                            message = stringResource(R.string.groups_empty_body),
                            actionLabel = stringResource(R.string.new_group),
                            onAction = { creating = true },
                        )
                    }
                    item { FeatureList() }
                }
                items(state.groups, key = { it.id }) { g ->
                    GroupCard(
                        group = g,
                        onOpen = { onOpenGroup(g.id) },
                        onPause = { pauseTarget = g },
                        onResume = { vm.resume(g) },
                    )
                }
            }
        }
    }

    if (creating) {
        NewGroupDialog(
            onDismiss = { creating = false },
            onCreate = {
                creating = false
                vm.create(it)
            },
        )
    }
    pauseTarget?.let { g ->
        PauseSheet(
            title = stringResource(R.string.pause_group_title, g.name),
            onDismiss = { pauseTarget = null },
            onPause = {
                pauseTarget = null
                vm.pause(g, it)
            },
        )
    }
}

/** One-line status of a group's internet access right now. */
@Composable
fun groupStatus(group: Group): Pair<String, Color> {
    val colors = LocalStatusColors.current
    val locale = currentLocale()
    val now = nowSeconds()
    return when {
        group.pausedUntil == -1L -> stringResource(R.string.state_paused) to colors.paused
        group.paused -> stringResource(R.string.state_paused_until, momentText(group.pausedUntil, locale, now)) to colors.paused
        group.hasSchedule && !group.allowedNow ->
            (if (group.nextChange > 0) stringResource(R.string.group_bedtime_until, momentText(group.nextChange, locale, now))
            else stringResource(R.string.state_bedtime)) to colors.blocked
        group.hasSchedule && group.nextChange > 0 ->
            stringResource(R.string.group_allowed_until, momentText(group.nextChange, locale, now)) to colors.online
        else -> stringResource(R.string.group_always_allowed) to colors.online
    }
}

@Composable
private fun GroupCard(group: Group, onOpen: () -> Unit, onPause: () -> Unit, onResume: () -> Unit) {
    val (statusText, statusColor) = groupStatus(group)
    Card(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FamilyRestroom, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        pluralStringResource(R.plurals.member_count, group.members.size, group.members.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        group.paused -> Icons.Rounded.PauseCircle
                        group.hasSchedule && !group.allowedNow -> Icons.Rounded.Bedtime
                        else -> Icons.Rounded.Public
                    },
                    contentDescription = null,
                    tint = statusColor,
                )
                Spacer(Modifier.width(8.dp))
                Text(statusText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (group.paused) {
                    FilledTonalButton(onClick = onResume) { Text(stringResource(R.string.resume)) }
                } else {
                    OutlinedButton(onClick = onPause, enabled = group.members.isNotEmpty()) { Text(stringResource(R.string.pause)) }
                }
            }
            val features = buildList {
                if (group.dnsFilter != "off") add(stringResource(R.string.feature_filter))
                if (group.safesearch) add(stringResource(R.string.safesearch))
                if (group.blocklist.isNotEmpty()) add(pluralStringResource(R.plurals.blocked_sites_count, group.blocklist.size, group.blocklist.size))
            }
            if (features.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FilterAlt, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        features.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureList() {
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FeatureLine(Icons.Rounded.Bedtime, stringResource(R.string.groups_feature_schedule))
        FeatureLine(Icons.Rounded.PauseCircle, stringResource(R.string.groups_feature_pause))
        FeatureLine(Icons.Rounded.FilterAlt, stringResource(R.string.groups_feature_filter))
    }
}

@Composable
private fun FeatureLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun NewGroupDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_group)) },
        text = {
            Column {
                Text(stringResource(R.string.new_group_hint), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.group_name)) },
                    placeholder = { Text(stringResource(R.string.group_name_example)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
