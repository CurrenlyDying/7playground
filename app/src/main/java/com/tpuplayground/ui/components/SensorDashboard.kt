package com.tpuplayground.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tpuplayground.sensors.SensorSnapshot
import com.tpuplayground.ui.theme.*

@Composable
fun SensorStatusBar(
    snapshot: SensorSnapshot?,
    modifier: Modifier = Modifier
) {
    if (snapshot == null) {
        Box(modifier = modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
            Text("Reading sensors...", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val keyThermals = snapshot.thermalZones
            .filter { it.type in setOf("soc_therm", "north_therm", "charging_therm") }
            .take(3)

        for (zone in keyThermals) {
            SensorPill(
                label = zone.type.removeSuffix("_therm").take(6),
                value = "${zone.tempC}C",
                color = tempColor(zone.tempC)
            )
        }

        SensorPill(
            label = "TPU",
            value = snapshot.tpuState.take(12),
            color = Cyan400
        )

        snapshot.gpuFreqKhz?.let { freq ->
            SensorPill(
                label = "GPU",
                value = "${freq}MHz",
                color = Purple400
            )
        }

        val battTempC = snapshot.batteryTempMilliC?.let { it / 10f }
        if (battTempC != null) {
            SensorPill(
                label = "Batt",
                value = "${battTempC}C",
                color = tempColor(battTempC)
            )
        }

        val memUsedPct = if (snapshot.memTotalKb > 0) {
            ((snapshot.memTotalKb - snapshot.memAvailableKb) * 100 / snapshot.memTotalKb).toInt()
        } else 0
        SensorPill(
            label = "RAM",
            value = "${memUsedPct}%",
            color = if (memUsedPct > 80) Red400 else Green400
        )
    }
}

@Composable
private fun SensorPill(
    label: String,
    value: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = color
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

private fun tempColor(tempC: Float): Color {
    return when {
        tempC < 35 -> Green400
        tempC < 42 -> Amber400
        tempC < 50 -> Orange400
        else -> Red400
    }
}
