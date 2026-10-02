package io.wrtpilot.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wrtpilot.app.R
import io.wrtpilot.app.ui.common.asString
import io.wrtpilot.app.ui.common.ltr
import io.wrtpilot.app.ui.theme.LocalStatusColors
import io.wrtpilot.core.network.Tls
import io.wrtpilot.core.network.model.Status
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterFormScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    vm: RouterFormViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(state.done) {
        if (state.done) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.editing) R.string.form_edit_title else R.string.form_add_title)) },
                navigationIcon = {
                    IconButton(onClick = if (state.step == FormStep.RESULT) vm::backToForm else onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            when (state.step) {
                FormStep.FORM, FormStep.CHECKING -> ConnectionForm(state, vm)
                FormStep.RESULT -> state.status?.let { status ->
                    CapabilityCheck(
                        status = status,
                        busy = state.busy,
                        onDisableOffload = vm::disableOffload,
                        onFinish = {
                            if (!state.editing && Build.VERSION.SDK_INT >= 33) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            vm.save()
                        },
                    )
                }
            }
        }
    }

    state.certificatePrompt?.let { fp ->
        CertificateDialog(
            fingerprint = fp,
            changed = state.certificateChanged,
            onTrust = vm::trustCertificate,
            onCancel = vm::dismissCertificate,
        )
    }
}

@Composable
private fun ConnectionForm(state: RouterFormState, vm: RouterFormViewModel) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val checking = state.step == FormStep.CHECKING

    Text(
        stringResource(R.string.form_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = state.name,
        onValueChange = vm::setName,
        label = { Text(stringResource(R.string.form_name)) },
        placeholder = { Text(stringResource(R.string.form_name_hint)) },
        singleLine = true,
        enabled = !checking,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.address,
        onValueChange = vm::setAddress,
        label = { Text(stringResource(R.string.form_address)) },
        supportingText = { Text(stringResource(R.string.form_address_hint)) },
        singleLine = true,
        enabled = !checking,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            SegmentedButton(
                selected = !state.https,
                onClick = { vm.setHttps(false) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                enabled = !checking,
            ) { Text("HTTP") }
            SegmentedButton(
                selected = state.https,
                onClick = { vm.setHttps(true) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                enabled = !checking,
                icon = { Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) },
            ) { Text("HTTPS") }
        }
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = state.port,
            onValueChange = vm::setPort,
            label = { Text(stringResource(R.string.form_port)) },
            placeholder = { Text(if (state.https) "443" else "80") },
            singleLine = true,
            enabled = !checking,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.width(110.dp),
        )
    }
    if (!state.https) {
        Text(
            stringResource(R.string.form_http_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.username,
        onValueChange = vm::setUsername,
        label = { Text(stringResource(R.string.form_username)) },
        singleLine = true,
        enabled = !checking,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.password,
        onValueChange = vm::setPassword,
        label = { Text(stringResource(R.string.form_password)) },
        placeholder = { if (state.hasSavedPassword) Text(stringResource(R.string.form_password_keep)) },
        singleLine = true,
        enabled = !checking,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (state.canConnect) vm.connect() }),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = stringResource(if (showPassword) R.string.hide_password else R.string.show_password),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    val error = state.error
    if (state.agentMissing) {
        // the router answered but has no WrtPilot yet: install it from here or from a computer
        Spacer(Modifier.height(12.dp))
        InstallAgentCard(state = state, onInstall = vm::installAgent)
    } else if (error != null) {
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(12.dp))
                Text(error.asString(), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    Button(
        onClick = vm::connect,
        enabled = state.canConnect,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        if (checking) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.form_checking))
        } else {
            Text(stringResource(R.string.form_connect))
        }
    }
    Spacer(Modifier.height(20.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.form_help_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.form_help_body), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    ltr("wrtpilot credentials"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.form_help_remote), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

/** One-line installer for the router agent (see router/install.sh). */
const val AGENT_INSTALL_COMMAND =
    "wget -qO- https://github.com/oaatiq/OpenWrt-Router/releases/download/router-latest/install.sh | sh"

/** Shown when the router works but the WrtPilot package is not installed on it. */
@Composable
private fun InstallAgentCard(state: RouterFormState, onInstall: () -> Unit) {
    val install = state.install
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Router, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.agent_missing_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            when (install) {
                is InstallState.Running -> InstallProgress(install.log)
                else -> {
                    Text(
                        stringResource(if (state.username == "root") R.string.agent_install_auto_body else R.string.agent_install_needs_root),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    if (install is InstallState.Failed) {
                        Spacer(Modifier.height(8.dp))
                        Text(install.message.asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        if (install.log.isNotBlank()) LogText(install.log)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onInstall,
                        enabled = state.canInstall,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.agent_install_button))
                    }
                    Spacer(Modifier.height(16.dp))
                    ManualInstall(state.address)
                }
            }
        }
    }
}

@Composable
private fun InstallProgress(log: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.agent_installing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
    if (log.isNotBlank()) LogText(log)
}

@Composable
private fun LogText(log: String) {
    Spacer(Modifier.height(8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Text(
            ltr(log),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
        )
    }
}

/** The same installation from a computer, for logins that cannot install (or as a fallback). */
@Composable
private fun ManualInstall(address: String) {
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    Text(
        stringResource(R.string.agent_install_manual),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.agent_missing_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
    )
    Spacer(Modifier.height(8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                ltr("ssh root@" + address.ifBlank { "192.168.1.1" }),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                ltr(AGENT_INSTALL_COMMAND),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.agent_missing_after),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
    )
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = {
            clipboard.setText(AnnotatedString(AGENT_INSTALL_COMMAND))
            copied = true
        }) {
            Icon(
                if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (copied) R.string.copied else R.string.copy_command))
        }
        TextButton(onClick = { uri.openUri(INSTALL_GUIDE_URL) }) {
            Text(stringResource(R.string.install_guide))
        }
    }
}

@Composable
private fun CapabilityCheck(
    status: Status,
    busy: Boolean,
    onDisableOffload: () -> Unit,
    onFinish: () -> Unit,
) {
    val colors = LocalStatusColors.current
    val caps = status.capabilities

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.online, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(R.string.check_connected), style = MaterialTheme.typography.titleLarge)
            Text(
                listOf(status.model, status.openwrtDescription.ifBlank { "OpenWrt ${status.openwrtVersion}" })
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(20.dp))
    Text(stringResource(R.string.check_title), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    CheckRow(true, stringResource(R.string.check_agent, status.agentVersion), null)
    CheckRow(
        status.collector.running,
        stringResource(R.string.check_collector),
        if (status.collector.running) null else stringResource(R.string.check_collector_off),
    )
    val offload = status.offloadWarning
    CheckRow(
        !offload,
        stringResource(if (offload) R.string.check_offload_on else R.string.check_offload_off),
        if (offload) stringResource(R.string.check_offload_explain) else null,
        actionLabel = if (offload) stringResource(R.string.check_offload_action) else null,
        onAction = onDisableOffload,
        busy = busy,
    )
    CheckRow(
        caps.tc && caps.ifb,
        stringResource(if (caps.tc && caps.ifb) R.string.check_shaping_exact else R.string.check_shaping_coarse),
        if (caps.tc && caps.ifb) null else stringResource(R.string.check_shaping_hint),
        warning = false,
    )
    CheckRow(
        caps.sqm,
        stringResource(if (caps.sqm) R.string.check_sqm_yes else R.string.check_sqm_no),
        if (caps.sqm) null else stringResource(R.string.check_sqm_hint),
        warning = false,
    )
    CheckRow(
        caps.hostapd,
        stringResource(if (caps.hostapd) R.string.check_wifi_yes else R.string.check_wifi_no),
        null,
        warning = false,
    )
    CheckRow(
        caps.nftset,
        stringResource(if (caps.nftset) R.string.check_nftset_yes else R.string.check_nftset_no),
        null,
        warning = false,
    )

    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onFinish,
        enabled = !busy,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Text(stringResource(R.string.check_finish))
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun CheckRow(
    ok: Boolean,
    title: String,
    detail: String?,
    warning: Boolean = true,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    busy: Boolean = false,
) {
    val colors = LocalStatusColors.current
    val (icon: ImageVector, tint: Color) = when {
        ok -> Icons.Rounded.CheckCircle to colors.online
        warning -> Icons.Rounded.WarningAmber to colors.paused
        else -> Icons.Rounded.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onAction, enabled = !busy) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun CertificateDialog(
    fingerprint: String,
    changed: Boolean,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(if (changed) Icons.Rounded.WarningAmber else Icons.Rounded.Lock, contentDescription = null) },
        title = { Text(stringResource(if (changed) R.string.cert_changed_title else R.string.cert_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (changed) R.string.cert_changed_body else R.string.cert_body))
                Text(stringResource(R.string.cert_fingerprint), style = MaterialTheme.typography.labelLarge)
                Text(
                    ltr(Tls.formatFingerprint(fingerprint)),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.cert_verify_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text(stringResource(R.string.cert_trust)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
