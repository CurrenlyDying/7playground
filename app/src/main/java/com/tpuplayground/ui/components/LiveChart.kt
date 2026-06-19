package com.tpuplayground.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tpuplayground.ui.theme.ChartColors
import com.tpuplayground.viewmodel.TimeSeriesPoint

@Composable
fun LiveChart(
    series: Map<String, List<TimeSeriesPoint>>,
    modifier: Modifier = Modifier,
    title: String = "",
    yAxisLabel: String = "",
    minY: Float? = null,
    maxY: Float? = null
) {
    val textMeasurer = rememberTextMeasurer()

    Column(modifier = modifier) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        if (series.isEmpty() || series.all { it.value.isEmpty() }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text(
                    "Waiting for data...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }

        val allPoints = series.values.flatten()
        val computedMinY = minY ?: (allPoints.minOfOrNull { it.value } ?: 0f)
        val computedMaxY = maxY ?: (allPoints.maxOfOrNull { it.value } ?: 1f)
        val yRange = (computedMaxY - computedMinY).coerceAtLeast(0.1f)
        val minTs = allPoints.minOfOrNull { it.timestampMs } ?: 0L
        val maxTs = allPoints.maxOfOrNull { it.timestampMs } ?: 1L
        val tsRange = (maxTs - minTs).coerceAtLeast(1L)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            val w = size.width
            val h = size.height
            val padLeft = 45f
            val padRight = 8f
            val padTop = 8f
            val padBottom = 20f
            val plotW = w - padLeft - padRight
            val plotH = h - padTop - padBottom

            drawGridLines(padLeft, padTop, plotW, plotH, computedMinY, computedMaxY, yRange, textMeasurer)

            series.entries.forEachIndexed { colorIdx, (_, points) ->
                if (points.size < 2) return@forEachIndexed
                val color = ChartColors[colorIdx % ChartColors.size]
                val path = Path()
                var started = false

                for (pt in points) {
                    val x = padLeft + ((pt.timestampMs - minTs).toFloat() / tsRange) * plotW
                    val y = padTop + plotH - ((pt.value - computedMinY) / yRange) * plotH

                    if (!started) {
                        path.moveTo(x, y.coerceIn(padTop, padTop + plotH))
                        started = true
                    } else {
                        path.lineTo(x, y.coerceIn(padTop, padTop + plotH))
                    }
                }

                drawPath(path, color, style = Stroke(width = 2f))
            }
        }

        if (series.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                series.keys.forEachIndexed { idx, name ->
                    val color = ChartColors[idx % ChartColors.size]
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Canvas(modifier = Modifier.size(8.dp)) {
                            drawCircle(color)
                        }
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = name.take(12),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawGridLines(
    padLeft: Float, padTop: Float, plotW: Float, plotH: Float,
    minY: Float, maxY: Float, yRange: Float,
    textMeasurer: TextMeasurer
) {
    val gridColor = Color(0xFF30363D)
    val textStyle = androidx.compose.ui.text.TextStyle(
        fontSize = 9.sp,
        color = Color(0xFF8B949E)
    )

    val gridLines = 4
    for (i in 0..gridLines) {
        val frac = i.toFloat() / gridLines
        val y = padTop + plotH - frac * plotH

        drawLine(gridColor, Offset(padLeft, y), Offset(padLeft + plotW, y), strokeWidth = 0.5f)

        val value = minY + frac * yRange
        val label = if (value >= 1000) "${(value / 1000).toInt()}k"
        else if (value >= 100) "${value.toInt()}"
        else String.format("%.1f", value)

        val measured = textMeasurer.measure(label, textStyle)
        drawText(measured, topLeft = Offset(padLeft - measured.size.width - 4f, y - measured.size.height / 2f))
    }
}

@Composable
fun SingleSeriesChart(
    points: List<TimeSeriesPoint>,
    modifier: Modifier = Modifier,
    title: String = "",
    color: Color = ChartColors[0],
    minY: Float? = null,
    maxY: Float? = null
) {
    LiveChart(
        series = if (points.isNotEmpty()) mapOf("" to points) else emptyMap(),
        modifier = modifier,
        title = title,
        minY = minY,
        maxY = maxY
    )
}
