package io.wrtpilot.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.wrtpilot.app.R
import io.wrtpilot.app.ui.common.currentLocale
import io.wrtpilot.app.ui.common.formatKbps
import io.wrtpilot.app.ui.devices.LabeledSlider
import io.wrtpilot.app.ui.devices.SettingSwitch
import io.wrtpilot.app.ui.devices.nearestIndex
import kotlin.math.roundToInt

/** Speed limit steps in kbit/s (slider positions). */
val LIMIT_STEPS = listOf(500, 1000, 2000, 3000, 5000, 8000, 10_000, 15_000, 20_000, 30_000, 50_000, 75_000, 100_000, 200_000, 500_000)

/**
 * Switch + download/upload sliders. Applied when a slider is released,
 * so dragging does not flood the router with requests. 0 = no limit.
 */
@Composable
fun SpeedLimitEditor(
    key: String,
    dlKbps: Int,
    ulKbps: Int,
    coarse: Boolean,
    onApply: (Int, Int) -> Unit,
    title: String = stringResource(R.string.limit_speed),
) {
    val context = LocalContext.current
    val locale = currentLocale()
    val limited = dlKbps > 0 || ulKbps > 0
    var enabled by rememberSaveable(key, limited) { mutableStateOf(limited) }
    var dl by rememberSaveable(key, dlKbps) {
        mutableFloatStateOf(nearestIndex(LIMIT_STEPS, dlKbps.takeIf { it > 0 } ?: 10_000).toFloat())
    }
    var ul by rememberSaveable(key, ulKbps) {
        mutableFloatStateOf(nearestIndex(LIMIT_STEPS, ulKbps.takeIf { it > 0 } ?: 3000).toFloat())
    }
    val apply = { onApply(LIMIT_STEPS[dl.roundToInt()], LIMIT_STEPS[ul.roundToInt()]) }

    SettingSwitch(
        title = title,
        subtitle = if (limited) {
            stringResource(R.string.limit_summary, formatKbps(context, dlKbps, locale), formatKbps(context, ulKbps, locale))
        } else {
            stringResource(R.string.limit_off)
        },
        checked = enabled,
        onChange = {
            enabled = it
            if (it) apply() else onApply(0, 0)
        },
    )
    if (enabled) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            LabeledSlider(
                label = stringResource(R.string.download),
                value = dl,
                steps = LIMIT_STEPS.size,
                valueText = formatKbps(context, LIMIT_STEPS[dl.roundToInt()], locale),
                onChange = { dl = it },
                onDone = apply,
            )
            LabeledSlider(
                label = stringResource(R.string.upload),
                value = ul,
                steps = LIMIT_STEPS.size,
                valueText = formatKbps(context, LIMIT_STEPS[ul.roundToInt()], locale),
                onChange = { ul = it },
                onDone = apply,
            )
            if (coarse) {
                Text(
                    stringResource(R.string.limit_coarse_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}
