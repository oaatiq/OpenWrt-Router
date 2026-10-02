package io.wrtpilot.app.ui.qos

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wrtpilot.app.R
import io.wrtpilot.app.ui.common.ltr
import io.wrtpilot.app.ui.components.ConnectionBanner
import io.wrtpilot.app.ui.components.LoadingState
import io.wrtpilot.app.ui.components.MessageEffect
import io.wrtpilot.app.ui.devices.SettingSwitch
import io.wrtpilot.core.network.model.QosConfig

const val SPEED_TEST_URL = "https://fast.com"

private data class Preset(val id: String, val icon: ImageVector, val title: Int, val description: Int)

private val PRESETS = listOf(
    Preset("default", Icons.Rounded.Balance, R.string.preset_default, R.string.preset_default_desc),
    Preset("gaming", Icons.Rounded.SportsEsports, R.string.preset_gaming, R.string.preset_gaming_desc),
    Preset("streaming", Icons.Rounded.Movie, R.string.preset_streaming, R.string.preset_streaming_desc),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QosScreen(onBack: () -> Unit, vm: QosViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(vm.events, snackbar)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qos_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val qos = state.qos
        when {
            qos == null && state.loading -> LoadingState(Modifier.padding(padding))
            qos == null -> Column(Modifier.padding(padding)) {
                ConnectionBanner(state.error, 0, onRetry = {})
            }
            !qos.available -> NotInstalled(Modifier.padding(padding))
            else -> QosForm(qos, state.saving, vm::save, Modifier.padding(padding))
        }
    }
}

@Composable
private fun QosForm(
    qos: QosConfig,
    saving: Boolean,
    onSave: (Boolean, Int, Int, String) -> Unit,
    modifier: Modifier,
) {
    val uri = LocalUriHandler.current
    var enabled by rememberSaveable(qos) { mutableStateOf(qos.enabled) }
    var dl by rememberSaveable(qos) { mutableStateOf(if (qos.dlKbps > 0) mbps(qos.dlKbps) else "") }
    var ul by rememberSaveable(qos) { mutableStateOf(if (qos.ulKbps > 0) mbps(qos.ulKbps) else "") }
    var preset by rememberSaveable(qos) { mutableStateOf(qos.preset) }
    val dlKbps = parseMbps(dl)
    val ulKbps = parseMbps(ul)
    val valid = !enabled || (dlKbps != null && ulKbps != null && dlKbps > 0 && ulKbps > 0)
    val changed = enabled != qos.enabled || dlKbps != qos.dlKbps || ulKbps != qos.ulKbps || preset != qos.preset

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        Text(
            stringResource(R.string.qos_explain),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        SettingSwitch(
            title = stringResource(R.string.qos_enable),
            subtitle = qos.iface?.let { stringResource(R.string.qos_interface, ltr(it)) },
            checked = enabled,
            onChange = { enabled = it },
        )
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedTextField(
                    value = dl,
                    onValueChange = { dl = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(7) },
                    label = { Text(stringResource(R.string.qos_download)) },
                    suffix = { Text(stringResource(R.string.unit_mbps_short)) },
                    singleLine = true,
                    enabled = enabled,
                    isError = enabled && dlKbps == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = ul,
                    onValueChange = { ul = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(7) },
                    label = { Text(stringResource(R.string.qos_upload)) },
                    suffix = { Text(stringResource(R.string.unit_mbps_short)) },
                    singleLine = true,
                    enabled = enabled,
                    isError = enabled && ulKbps == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Speed, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.qos_speedtest_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { uri.openUri(SPEED_TEST_URL) }) {
                        Text(stringResource(R.string.qos_speedtest))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.qos_preset), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.selectableGroup()) {
                for (p in PRESETS.filter { it.id in qos.presets }) {
                    val selected = preset == p.id
                    Card(
                        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { preset = p.id },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(p.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(p.title), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(p.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RadioButton(selected = selected, onClick = null, enabled = enabled)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onSave(enabled, dlKbps ?: qos.dlKbps, ulKbps ?: qos.ulKbps, preset) },
                enabled = changed && valid && !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

@Composable
private fun NotInstalled(modifier: Modifier) {
    Column(modifier.padding(16.dp)) {
        Text(stringResource(R.string.qos_not_installed), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.qos_not_installed_body), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Text(
                ltr("opkg update && opkg install sqm-scripts"),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

/** kbit/s -> "12.5" (wire format, plain digits). */
private fun mbps(kbps: Int): String {
    val v = kbps / 1000.0
    return if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)
}

/** "12,5" / "12.5" -> 12500 kbit/s; null when not a positive number. */
private fun parseMbps(s: String): Int? {
    val v = s.replace(',', '.').toDoubleOrNull() ?: return null
    if (v <= 0 || v > 10_000) return null
    return (v * 1000).toInt()
}
