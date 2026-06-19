package com.tpuplayground.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tpuplayground.sensors.MemoryCategory
import com.tpuplayground.sensors.MemoryMap
import com.tpuplayground.sensors.MemoryRegion
import com.tpuplayground.ui.theme.HeatMapColors

@Composable
fun MemoryHeatMap(
    memoryMap: MemoryMap?,
    modifier: Modifier = Modifier
) {
    if (memoryMap == null || memoryMap.regions.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Text(
                "No memory map data. Tap refresh.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Column(modifier = modifier) {
        Text(
            "Memory Map (PID ${memoryMap.pid})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            "VSS: ${formatKb(memoryMap.totalVssKb)} | RSS: ${formatKb(memoryMap.totalRssKb)} | PSS: ${formatKb(memoryMap.totalPssKb)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        CategoryBreakdown(memoryMap)

        Spacer(Modifier.height(8.dp))

        TreeMap(memoryMap.regions)
    }
}

@Composable
private fun CategoryBreakdown(memoryMap: MemoryMap) {
    val sorted = memoryMap.categoryBreakdown.entries
        .sortedByDescending { it.value }
        .filter { it.value > 0 }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for ((category, pssKb) in sorted) {
            val totalPss = memoryMap.totalPssKb.coerceAtLeast(1)
            val fraction = pssKb.toFloat() / totalPss
            val color = categoryColor(category)

            Row(
                modifier = Modifier.fillMaxWidth().height(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Canvas(modifier = Modifier.size(10.dp)) {
                    drawRect(color)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    category.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatKb(pssKb),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Box(modifier = Modifier.width(60.dp).height(8.dp)) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(Color(0xFF21262D))
                        drawRect(color, size = Size(size.width * fraction, size.height))
                    }
                }
            }
        }
    }
}

@Composable
private fun TreeMap(regions: List<MemoryRegion>) {
    val significant = regions
        .filter { it.rssKb > 0 }
        .sortedByDescending { it.rssKb }
        .take(100)

    if (significant.isEmpty()) return

    val totalRss = significant.sumOf { it.rssKb }.coerceAtLeast(1)
    val textMeasurer = rememberTextMeasurer()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Region Heatmap (by RSS, top 100)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
        ) {
            val totalW = size.width
            val totalH = size.height
            val cols = 10
            val rows = ((significant.size + cols - 1) / cols)
            val cellW = totalW / cols
            val cellH = if (rows > 0) totalH / rows else totalH

            significant.forEachIndexed { idx, region ->
                val col = idx % cols
                val row = idx / cols
                val x = col * cellW
                val y = row * cellH

                val intensity = (region.rssKb.toFloat() / (totalRss / significant.size.coerceAtLeast(1)))
                    .coerceIn(0f, 1f)
                val colorIdx = (intensity * (HeatMapColors.size - 1)).toInt()
                    .coerceIn(0, HeatMapColors.size - 1)
                val color = categoryColor(region.category).copy(alpha = 0.3f + intensity * 0.7f)

                drawRect(color, Offset(x + 1, y + 1), Size(cellW - 2, cellH - 2))

                if (cellW > 30 && cellH > 15) {
                    val label = region.name.takeLastWhile { it != '/' }.take(8)
                    if (label.isNotBlank()) {
                        val style = TextStyle(fontSize = 7.sp, color = Color.White)
                        val measured = textMeasurer.measure(label, style, maxLines = 1)
                        if (measured.size.width < cellW - 4) {
                            drawText(measured, topLeft = Offset(x + 2, y + 2))
                        }
                    }
                }
            }
        }
    }
}

private fun categoryColor(category: MemoryCategory): Color {
    return when (category) {
        MemoryCategory.NATIVE_HEAP -> Color(0xFFEF5350)
        MemoryCategory.JAVA_HEAP -> Color(0xFFAB47BC)
        MemoryCategory.CODE -> Color(0xFF42A5F5)
        MemoryCategory.STACK -> Color(0xFFFFCA28)
        MemoryCategory.GPU_BUFFER -> Color(0xFF26C6DA)
        MemoryCategory.DEVICE_MAPPED -> Color(0xFFFF7043)
        MemoryCategory.MMAP_FILE -> Color(0xFF66BB6A)
        MemoryCategory.ANONYMOUS -> Color(0xFF78909C)
        MemoryCategory.OTHER -> Color(0xFF546E7A)
    }
}

private fun formatKb(kb: Long): String {
    return when {
        kb >= 1_048_576 -> "${kb / 1_048_576}GB"
        kb >= 1024 -> "${kb / 1024}MB"
        else -> "${kb}KB"
    }
}
