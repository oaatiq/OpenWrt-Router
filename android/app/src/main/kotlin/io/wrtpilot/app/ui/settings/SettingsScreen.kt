package io.wrtpilot.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wrtpilot.app.BuildConfig
import io.wrtpilot.app.R
import io.wrtpilot.app.ui.common.AppLanguage
import io.wrtpilot.app.ui.common.durationText
import io.wrtpilot.app.ui.common.ltr
import io.wrtpilot.app.ui.components.ContentPadding
import io.wrtpilot.app.ui.components.InfoRow
import io.wrtpilot.app.ui.components.LanguageDialog
import io.wrtpilot.app.ui.components.MessageEffect
import io.wrtpilot.app.ui.components.SectionHeader
import io.wrtpilot.app.ui.components.languageLabel
import io.wrtpilot.app.ui.onboarding.INSTALL_GUIDE_URL
import io.wrtpilot.app.work.Notifications
import io.wrtpilot.core.data.prefs.ThemeMode

const val SOURCE_URL = "https://github.com/oaatiq/OpenWrt-Router"

private val POLL_OPTIONS = listOf(2, 3, 5, 10)
private val BACKGROUND_OPTIONS = listOf(15, 30, 60, 180)

private enum class SettingsDialog { NONE, LANGUAGE, THEME, POLL, BACKGROUND, LICENSES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenRouters: () -> Unit,
    onOpenQos: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(vm.events, snackbar)
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var dialog by rememberSaveable { mutableStateOf(SettingsDialog.NONE) }
    // re-checked whenever the user comes back from the system settings
    var canNotify by remember { mutableStateOf(Notifications.canNotify(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canNotify = Notifications.canNotify(context) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canNotify = Notifications.canNotify(context)
        if (!granted) openNotificationSettings(context)
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { picked ->
        if (picked != null) vm.exportCsv(picked)
    }
    val settings = state.settings

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, scrollBehavior = scroll)
        },
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ContentPadding),
        ) {
            // --- router ---------------------------------------------------
            SectionHeader(stringResource(R.string.settings_router))
            SettingsItem(
                icon = Icons.Rounded.Router,
                title = stringResource(R.string.routers_title),
                subtitle = state.active?.let { active ->
                    if (state.routerCount > 1) {
                        pluralStringResource(R.plurals.routers_active_of, state.routerCount, active.name, state.routerCount)
                    } else {
                        active.name
                    }
                },
                onClick = onOpenRouters,
                chevron = true,
            )
            SettingsItem(
                icon = Icons.Rounded.Speed,
                title = stringResource(R.string.qos_title),
                subtitle = when {
                    state.status?.capabilities?.sqm == false -> stringResource(R.string.qos_not_installed)
                    else -> stringResource(R.string.settings_qos_summary)
                },
                onClick = onOpenQos,
                chevron = true,
            )
            state.status?.let { st ->
                RouterInfoCard(
                    model = st.model,
                    openwrt = st.openwrtVersion,
                    agent = st.agentVersion,
                    host = state.active?.let { "${it.host}:${it.port}" }.orEmpty(),
                )
            }

            // --- appearance -----------------------------------------------
            SectionHeader(stringResource(R.string.settings_appearance))
            SettingsItem(
                icon = Icons.Rounded.Language,
                title = stringResource(R.string.language),
                subtitle = languageLabel(AppLanguage.current()),
                onClick = { dialog = SettingsDialog.LANGUAGE },
            )
            SettingsItem(
                icon = Icons.Rounded.DarkMode,
                title = stringResource(R.string.theme),
                subtitle = themeLabel(settings.theme),
                onClick = { dialog = SettingsDialog.THEME },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingsSwitch(
                    icon = Icons.Rounded.Palette,
                    title = stringResource(R.string.dynamic_color),
                    subtitle = stringResource(R.string.dynamic_color_desc),
                    checked = settings.dynamicColor,
                    onChange = vm::setDynamicColor,
                )
            }

            // --- updates --------------------------------------------------
            SectionHeader(stringResource(R.string.settings_updates))
            SettingsItem(
                icon = Icons.Rounded.Refresh,
                title = stringResource(R.string.poll_interval),
                subtitle = pluralStringResource(R.plurals.every_seconds, settings.pollSeconds, settings.pollSeconds),
                onClick = { dialog = SettingsDialog.POLL },
            )
            SettingsItem(
                icon = Icons.Rounded.Schedule,
                title = stringResource(R.string.background_interval),
                subtitle = stringResource(R.string.every_duration, durationText(context, settings.backgroundMinutes * 60L)),
                onClick = { dialog = SettingsDialog.BACKGROUND },
            )

            // --- notifications --------------------------------------------
            SectionHeader(stringResource(R.string.settings_notifications))
            if (!canNotify) {
                NotificationsOffCard(
                    onEnable = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openNotificationSettings(context)
                        }
                    },
                )
            }
            SettingsSwitch(
                icon = null,
                title = stringResource(R.string.notify_new_devices),
                subtitle = stringResource(R.string.notify_new_devices_desc),
                checked = settings.notifyNewDevices,
                onChange = vm::setNotifyNewDevices,
                enabled = canNotify,
            )
            SettingsSwitch(
                icon = null,
                title = stringResource(R.string.notify_quota),
                subtitle = stringResource(R.string.notify_quota_desc),
                checked = settings.notifyQuota,
                onChange = vm::setNotifyQuota,
                enabled = canNotify,
            )

            // --- data -----------------------------------------------------
            SectionHeader(stringResource(R.string.settings_data))
            SettingsItem(
                icon = Icons.Rounded.FileDownload,
                title = stringResource(R.string.export_csv),
                subtitle = stringResource(R.string.export_csv_desc),
                onClick = { export.launch(vm.exportFileName()) },
                enabled = state.active != null,
            )

            // --- about ----------------------------------------------------
            SectionHeader(stringResource(R.string.settings_about))
            SettingsItem(
                icon = Icons.Rounded.Info,
                title = stringResource(R.string.app_name),
                subtitle = stringResource(R.string.version_name, ltr(BuildConfig.VERSION_NAME)),
            )
            SettingsItem(
                icon = Icons.AutoMirrored.Rounded.MenuBook,
                title = stringResource(R.string.install_guide),
                subtitle = stringResource(R.string.install_guide_desc),
                onClick = { uri.openUri(INSTALL_GUIDE_URL) },
            )
            SettingsItem(
                icon = Icons.Rounded.Code,
                title = stringResource(R.string.source_code),
                subtitle = ltr(SOURCE_URL.removePrefix("https://")),
                onClick = { uri.openUri(SOURCE_URL) },
            )
            SettingsItem(
                icon = Icons.Rounded.Description,
                title = stringResource(R.string.licenses),
                subtitle = null,
                onClick = { dialog = SettingsDialog.LICENSES },
            )
        }
    }

    when (dialog) {
        SettingsDialog.NONE -> Unit
        SettingsDialog.LANGUAGE -> LanguageDialog(
            current = AppLanguage.current(),
            onDismiss = { dialog = SettingsDialog.NONE },
            onSelect = {
                dialog = SettingsDialog.NONE
                AppLanguage.set(it)
            },
        )
        SettingsDialog.THEME -> ChoiceDialog(
            title = stringResource(R.string.theme),
            options = ThemeMode.entries,
            selected = settings.theme,
            label = { themeLabel(it) },
            onDismiss = { dialog = SettingsDialog.NONE },
            onSelect = {
                dialog = SettingsDialog.NONE
                vm.setTheme(it)
            },
        )
        SettingsDialog.POLL -> ChoiceDialog(
            title = stringResource(R.string.poll_interval),
            message = stringResource(R.string.poll_interval_desc),
            options = POLL_OPTIONS,
            selected = settings.pollSeconds,
            label = { pluralStringResource(R.plurals.every_seconds, it, it) },
            onDismiss = { dialog = SettingsDialog.NONE },
            onSelect = {
                dialog = SettingsDialog.NONE
                vm.setPollSeconds(it)
            },
        )
        SettingsDialog.BACKGROUND -> ChoiceDialog(
            title = stringResource(R.string.background_interval),
            message = stringResource(R.string.background_interval_desc),
            options = BACKGROUND_OPTIONS,
            selected = settings.backgroundMinutes,
            label = { stringResource(R.string.every_duration, durationText(context, it * 60L)) },
            onDismiss = { dialog = SettingsDialog.NONE },
            onSelect = {
                dialog = SettingsDialog.NONE
                vm.setBackgroundMinutes(it)
            },
        )
        SettingsDialog.LICENSES -> LicensesDialog(onDismiss = { dialog = SettingsDialog.NONE })
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }
)

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** A tappable settings row: icon, title, current value. */
@Composable
private fun SettingsItem(
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    chevron: Boolean = false,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            Spacer(Modifier.width(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        }
        if (chevron) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsSwitch(
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = checked, enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            Spacer(Modifier.width(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun RouterInfoCard(model: String, openwrt: String, agent: String, host: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            if (model.isNotBlank()) InfoRow(stringResource(R.string.router_model), model)
            if (host.isNotBlank()) InfoRow(stringResource(R.string.router_address), ltr(host))
            if (openwrt.isNotBlank()) InfoRow(stringResource(R.string.openwrt_version), ltr(openwrt))
            if (agent.isNotBlank()) InfoRow(stringResource(R.string.agent_version), ltr(agent))
        }
    }
}

@Composable
private fun NotificationsOffCard(onEnable: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.NotificationsOff, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.notifications_off), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onEnable) { Text(stringResource(R.string.turn_on)) }
        }
    }
}

/** Single-choice dialog with radio buttons. */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
    message: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                if (message != null) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
                for (option in options) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == selected, role = Role.RadioButton) { onSelect(option) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private val LIBRARIES = listOf(
    "AndroidX, Jetpack Compose, Material 3, Room, WorkManager, Hilt" to "Apache License 2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0",
    "OkHttp" to "Apache License 2.0",
    "Vico" to "Apache License 2.0",
)

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.licenses)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.licenses_app), style = MaterialTheme.typography.bodyMedium)
                for ((name, license) in LIBRARIES) {
                    Text(ltr(name), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                    Text(ltr(license), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    stringResource(R.string.licenses_oui),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
