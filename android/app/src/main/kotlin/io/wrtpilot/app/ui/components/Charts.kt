package io.wrtpilot.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.ColumnCartesianLayerModel
import com.patrykandpatrick.vico.core.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import io.wrtpilot.app.R
import io.wrtpilot.app.ui.common.currentLocale
import io.wrtpilot.app.ui.common.formatBytes
import io.wrtpilot.app.ui.common.formatRate
import io.wrtpilot.app.ui.theme.LocalStatusColors

/**
 * Live download/upload rates (bits per second), one point per sample.
 * [labels] are the bottom axis labels, one per sample.
 */
@Composable
fun TrafficChart(
    rx: List<Long>,
    tx: List<Long>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
) {
    if (rx.size < 2 || rx.size != tx.size) {
        ChartPlaceholder(modifier, height)
        return
    }
    val colors = LocalStatusColors.current
    val context = LocalContext.current
    val locale = currentLocale()

    val model = remember(rx, tx) {
        val x = rx.indices.toList()
        CartesianChartModel(
            LineCartesianLayerModel.build {
                series(x, rx)
                series(x, tx)
            }
        )
    }
    val download = LineCartesianLayer.rememberLine(
        fill = remember(colors.download) { LineCartesianLayer.LineFill.single(fill(colors.download)) },
        areaFill = remember(colors.download) {
            LineCartesianLayer.AreaFill.single(fill(colors.download.copy(alpha = 0.18f)))
        },
    )
    val upload = LineCartesianLayer.rememberLine(
        fill = remember(colors.upload) { LineCartesianLayer.LineFill.single(fill(colors.upload)) },
    )
    val rateFormatter = remember(locale) {
        CartesianValueFormatter { _, value, _ -> formatRate(context, value.toLong(), locale) }
    }
    val spacing = (rx.size / 4).coerceAtLeast(1)
    val labelFormatter = remember(labels, spacing) { distinctLabels(labels, spacing) }

    val chart = rememberCartesianChart(
        rememberLineCartesianLayer(lineProvider = LineCartesianLayer.LineProvider.series(download, upload)),
        startAxis = VerticalAxis.rememberStart(
            valueFormatter = rateFormatter,
            itemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 4 }) },
        ),
        bottomAxis = HorizontalAxis.rememberBottom(
            valueFormatter = labelFormatter,
            guideline = null,
            itemPlacer = remember(spacing) { HorizontalAxis.ItemPlacer.aligned(spacing = { spacing }) },
        ),
    )
    CartesianChartHost(
        chart = chart,
        model = model,
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
    )
}

/** Stacked download/upload usage per bucket (hour or day), in bytes. */
@Composable
fun UsageChart(
    rx: List<Long>,
    tx: List<Long>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
) {
    if (rx.isEmpty() || rx.size != tx.size) {
        ChartPlaceholder(modifier, height)
        return
    }
    val colors = LocalStatusColors.current
    val context = LocalContext.current
    val locale = currentLocale()

    val model = remember(rx, tx) {
        val x = rx.indices.toList()
        CartesianChartModel(
            ColumnCartesianLayerModel.build {
                series(x, rx)
                series(x, tx)
            }
        )
    }
    val thickness = if (rx.size > 20) 6.dp else 12.dp
    val columns = ColumnCartesianLayer.ColumnProvider.series(
        rememberLineComponent(fill(colors.download), thickness),
        rememberLineComponent(fill(colors.upload), thickness),
    )
    val bytesFormatter = remember(locale) {
        CartesianValueFormatter { _, value, _ -> formatBytes(context, value.toLong(), locale) }
    }
    val labelFormatter = remember(labels) {
        CartesianValueFormatter { _, value, _ -> labels.getOrNull(value.toInt()) ?: " " }
    }
    val spacing = when {
        rx.size > 20 -> 6
        rx.size > 10 -> 2
        else -> 1
    }

    val chart = rememberCartesianChart(
        rememberColumnCartesianLayer(
            columnProvider = columns,
            mergeMode = { ColumnCartesianLayer.MergeMode.Stacked },
        ),
        startAxis = VerticalAxis.rememberStart(
            valueFormatter = bytesFormatter,
            itemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 4 }) },
        ),
        bottomAxis = HorizontalAxis.rememberBottom(
            valueFormatter = labelFormatter,
            guideline = null,
            itemPlacer = remember(spacing) { HorizontalAxis.ItemPlacer.aligned(spacing = { spacing }) },
        ),
    )
    CartesianChartHost(
        chart = chart,
        model = model,
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
    )
}

@Composable
private fun ChartPlaceholder(modifier: Modifier, height: Dp) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.chart_collecting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "● Download  ● Upload" */
@Composable
fun ChartLegend(modifier: Modifier = Modifier) {
    val colors = LocalStatusColors.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(colors.download, stringResource(R.string.download))
        LegendItem(colors.upload, stringResource(R.string.upload))
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Axis labels every [spacing] points; a label equal to the previous one is
 * left blank (minute-resolution clock labels on a few minutes of data).
 */
private fun distinctLabels(labels: List<String>, spacing: Int) = CartesianValueFormatter { _, value, _ ->
    val i = value.toInt()
    val label = labels.getOrNull(i) ?: return@CartesianValueFormatter " "
    if (i >= spacing && labels.getOrNull(i - spacing) == label) " " else label
}
