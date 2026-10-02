package io.wrtpilot.app.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wrtpilot.app.R
import io.wrtpilot.app.ui.common.displayName
import io.wrtpilot.app.ui.common.kind
import io.wrtpilot.app.ui.common.ltr
import io.wrtpilot.app.ui.components.DeviceAvatar
import io.wrtpilot.app.ui.components.EmptyState
import io.wrtpilot.app.ui.components.LoadingState
import io.wrtpilot.app.ui.components.MessageEffect
import io.wrtpilot.app.ui.components.PauseSheet
import io.wrtpilot.app.ui.components.SectionHeader
import io.wrtpilot.app.ui.components.SpeedLimitEditor
import io.wrtpilot.app.ui.devices.SettingSwitch
import io.wrtpilot.core.domain.ScheduleGrid
import io.wrtpilot.core.network.model.Client
import io.wrtpilot.core.network.model.Group

private val SITE_SUGGESTIONS = listOf(
    "tiktok.com", "instagram.com", "snapchat.com", "facebook.com", "x.com",
    "youtube.com", "roblox.com", "fortnite.com", "twitch.tv", "discord.com",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    onBack: () -> Unit,
    onOpenDevice: (String) -> Unit,
    vm: GroupDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var pausing by remember { mutableStateOf(false) }
    var pickingMembers by remember { mutableStateOf(false) }
    MessageEffect(vm.events, snackbar)

    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    val group = state.group
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (group != null) {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename)) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = { menu = false; renaming = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_group)) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                                onClick = { menu = false; deleting = true },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            group == null && state.loading -> LoadingState(Modifier.padding(padding))
            group == null -> EmptyState(Icons.Rounded.DeleteOutline, stringResource(R.string.group_not_found), null, Modifier.padding(padding))
            else -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp),
            ) {
                PauseCard(group, onPause = { pausing = true }, onResume = vm::resume)

                SectionHeader(stringResource(R.string.members))
                MembersSection(state, onOpenDevice, vm::removeMember, onAdd = { pickingMembers = true })

                SectionHeader(stringResource(R.string.schedule))
                ScheduleSection(state, vm)

                SectionHeader(stringResource(R.string.content_filter))
                FilterSection(state, vm)

                SectionHeader(stringResource(R.string.blocked_sites))
                BlocklistSection(state, vm)

                SectionHeader(stringResource(R.string.speed))
                SpeedLimitEditor(
                    key = group.id,
                    dlKbps = group.dlLimitKbps,
                    ulKbps = group.ulLimitKbps,
                    coarse = !(state.capabilities.tc && state.capabilities.ifb),
                    onApply = vm::setLimits,
                    title = stringResource(R.string.group_limit),
                )
                Text(
                    stringResource(R.string.group_limit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    if (renaming && group != null) {
        TextInputDialog(
            title = stringResource(R.string.rename_group),
            initial = group.name,
            label = stringResource(R.string.group_name),
            onDismiss = { renaming = false },
            onConfirm = { renaming = false; vm.rename(it) },
        )
    }
    if (deleting && group != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.delete_group_title, group.name)) },
            text = { Text(stringResource(R.string.delete_group_body)) },
            confirmButton = { TextButton(onClick = { deleting = false; vm.delete() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (pausing && group != null) {
        PauseSheet(
            title = stringResource(R.string.pause_group_title, group.name),
            onDismiss = { pausing = false },
            onPause = { pausing = false; vm.pause(it) },
        )
    }
    if (pickingMembers && group != null) {
        MemberPicker(
            state = state,
            onDismiss = { pickingMembers = false },
            onSave = { pickingMembers = false; vm.setMembers(it) },
        )
    }
}

@Composable
private fun PauseCard(group: Group, onPause: () -> Unit, onResume: () -> Unit) {
    val (statusText, statusColor) = groupStatus(group)
    Card(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.internet_access), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(statusText, style = MaterialTheme.typography.titleLarge, color = statusColor)
            Spacer(Modifier.height(16.dp))
            if (group.paused) {
                FilledTonalButton(onClick = onResume, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Rounded.PlayCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.resume_internet), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Button(
                    onClick = onPause,
                    enabled = group.members.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Rounded.PauseCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pause_internet), style = MaterialTheme.typography.titleMedium)
                }
                if (group.members.isEmpty()) {
                    Text(
                        stringResource(R.string.add_members_first),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MembersSection(
    state: GroupDetailState,
    onOpenDevice: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val members = state.members
    if (members.isEmpty()) {
        Text(
            stringResource(R.string.no_members),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    for (d in members) {
        ListItem(
            headlineContent = { Text(d.displayName(state.oui), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = { DeviceAvatar(d.kind(state.oui), d.online, !d.hasInternet, size = 40.dp) },
            trailingContent = {
                IconButton(onClick = { onRemove(d.mac) }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.remove_from_group))
                }
            },
            modifier = Modifier.clickable { onOpenDevice(d.mac) },
        )
    }
    OutlinedButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.add_devices))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleSection(state: GroupDetailState, vm: GroupDetailViewModel) {
    val group = state.group ?: return
    SettingSwitch(
        title = stringResource(R.string.allowed_hours),
        subtitle = stringResource(if (group.scheduleEnabled) R.string.allowed_hours_on else R.string.allowed_hours_off),
        checked = group.scheduleEnabled && (group.schedule.isNotEmpty() || state.draft != null),
        onChange = { on ->
            if (on && group.schedule.isEmpty() && state.draft == null) vm.editSchedule(ScheduleGrid.schoolNights())
            else vm.setScheduleEnabled(on)
        },
    )
    val grid = state.grid
    if (grid == null) {
        // rules with minutes cannot be shown on the hour grid
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.schedule_custom), style = MaterialTheme.typography.bodyMedium)
            for (r in group.schedule) Text(ltr(r), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.editSchedule(ScheduleGrid.schoolNights()) }) { Text(stringResource(R.string.schedule_start_over)) }
        }
        return
    }
    if (!group.scheduleEnabled && state.draft == null) return

    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.schedule_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        ScheduleGridEditor(grid = grid, onChange = vm::editSchedule)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { vm.editSchedule(ScheduleGrid.schoolNights()) }, label = { Text(stringResource(R.string.preset_school)) })
            AssistChip(onClick = { vm.editSchedule(ScheduleGrid.daytime()) }, label = { Text(stringResource(R.string.preset_daytime)) })
            AssistChip(onClick = { vm.editSchedule(ScheduleGrid.full()) }, label = { Text(stringResource(R.string.preset_always)) })
            AssistChip(onClick = { vm.editSchedule(ScheduleGrid.empty()) }, label = { Text(stringResource(R.string.preset_clear)) })
        }
        if (state.draft != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = vm::discardSchedule) { Text(stringResource(R.string.discard)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = vm::saveSchedule) { Text(stringResource(R.string.save_schedule)) }
            }
            if (state.draft.isEmpty) {
                Text(
                    stringResource(R.string.schedule_empty_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private data class FilterOption(val id: String, val title: Int, val description: Int)

private val FILTER_OPTIONS = listOf(
    FilterOption("off", R.string.dns_off, R.string.dns_off_desc),
    FilterOption("cleanbrowsing_family", R.string.dns_cleanbrowsing, R.string.dns_cleanbrowsing_desc),
    FilterOption("cloudflare_family", R.string.dns_cloudflare, R.string.dns_cloudflare_desc),
    FilterOption("adguard_family", R.string.dns_adguard, R.string.dns_adguard_desc),
    FilterOption("opendns_family", R.string.dns_opendns, R.string.dns_opendns_desc),
    FilterOption("adguard_local", R.string.dns_adguard_local, R.string.dns_adguard_local_desc),
    FilterOption("custom", R.string.dns_custom, R.string.dns_custom_desc),
)

@Composable
private fun FilterSection(state: GroupDetailState, vm: GroupDetailViewModel) {
    val group = state.group ?: return
    var customDialog by remember { mutableStateOf(false) }
    val options = FILTER_OPTIONS.filter { state.dnsFilters.isEmpty() || it.id in state.dnsFilters }

    Column(Modifier.selectableGroup()) {
        for (o in options) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = group.dnsFilter == o.id,
                        role = Role.RadioButton,
                        onClick = {
                            if (o.id == "custom") customDialog = true else vm.setDnsFilter(o.id)
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = group.dnsFilter == o.id, onClick = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(o.title), style = MaterialTheme.typography.bodyLarge)
                    val desc = if (o.id == "custom" && group.dnsFilter == "custom" && group.dnsCustom.isNotEmpty()) {
                        ltr(group.dnsCustom.joinToString(", "))
                    } else {
                        stringResource(o.description)
                    }
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    SettingSwitch(
        title = stringResource(R.string.safesearch),
        subtitle = stringResource(R.string.safesearch_desc),
        checked = group.safesearch,
        onChange = vm::setSafeSearch,
    )
    if (group.dnsFilter != "off" || group.safesearch || group.blocklist.isNotEmpty()) {
        Text(
            stringResource(R.string.filter_bypass_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    if (customDialog) {
        TextInputDialog(
            title = stringResource(R.string.dns_custom),
            initial = group.dnsCustom.joinToString(", "),
            label = stringResource(R.string.dns_custom_label),
            keyboardType = KeyboardType.Uri,
            onDismiss = { customDialog = false },
            onConfirm = { text ->
                customDialog = false
                val servers = text.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
                if (servers.isNotEmpty()) vm.setDnsFilter("custom", servers)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlocklistSection(state: GroupDetailState, vm: GroupDetailViewModel) {
    val group = state.group ?: return
    var input by rememberSaveable { mutableStateOf("") }
    val add = {
        val parts = input.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isNotEmpty()) vm.addBlockedSites(parts)
        input = ""
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            stringResource(if (state.capabilities.nftset) R.string.blocked_sites_hint else R.string.blocked_sites_hint_dns),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = add, enabled = input.isNotBlank()) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.add))
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (d in group.blocklist) {
                InputChip(
                    selected = false,
                    onClick = { vm.removeBlockedSite(d) },
                    label = { Text(ltr(d)) },
                    trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.remove), modifier = Modifier.size(16.dp)) },
                )
            }
        }
        val suggestions = SITE_SUGGESTIONS.filter { it !in group.blocklist }
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.suggestions), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in suggestions) {
                    SuggestionChip(onClick = { vm.addBlockedSites(listOf(s)) }, label = { Text(ltr(s)) })
                }
            }
        }
    }
}

@Composable
private fun MemberPicker(state: GroupDetailState, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val context = LocalContext.current
    val selected = remember { mutableStateListOf<String>().apply { addAll(state.members.map { it.mac }) } }
    val devices = remember(state.devices) {
        state.devices.sortedWith(compareByDescending<Client> { it.group == state.id }.thenByDescending { it.online }
            .thenBy { it.displayName(context, state.oui).lowercase() })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_devices)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(devices, key = { it.mac }) { d ->
                    val checked = d.mac in selected
                    val otherGroup = state.allGroups.firstOrNull { it.id == d.group && it.id != state.id }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(checked, role = Role.Checkbox) {
                                if (checked) selected.remove(d.mac) else selected.add(d.mac)
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        DeviceAvatar(d.kind(state.oui), d.online, false, size = 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(d.displayName(state.oui), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (otherGroup != null) {
                                Text(
                                    stringResource(R.string.in_group, otherGroup.name),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected.toList()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(128) },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
