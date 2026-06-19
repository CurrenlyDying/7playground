package com.tpuplayground.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tpuplayground.workload.WaveformType
import com.tpuplayground.workload.WorkloadConfig

@Composable
fun WaveformControl(
    config: WorkloadConfig,
    isRunning: Boolean,
    onConfigChange: (WorkloadConfig) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 12.dp)) {
        Text(
            "Waveform",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(WaveformType.entries) { waveform ->
                val selected = config.waveform == waveform
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (selected)
                                Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            else
                                Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        )
                        .clickable { onConfigChange(config.copy(waveform = waveform)) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            waveform.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Matrix: ${config.matrixSize}x${config.matrixSize}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val sizes = listOf(64, 128, 256, 512)
                val sizeIndex = sizes.indexOf(config.matrixSize).coerceAtLeast(0)
                Slider(
                    value = sizeIndex.toFloat(),
                    onValueChange = { onConfigChange(config.copy(matrixSize = sizes[it.toInt()])) },
                    valueRange = 0f..3f,
                    steps = 2,
                    modifier = Modifier.height(32.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Period: ${config.periodMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = config.periodMs.toFloat(),
                    onValueChange = { onConfigChange(config.copy(periodMs = it.toLong())) },
                    valueRange = 500f..10000f,
                    modifier = Modifier.height(32.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Duty: ${config.dutyCyclePct}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = config.dutyCyclePct.toFloat(),
                    onValueChange = { onConfigChange(config.copy(dutyCyclePct = it.toInt())) },
                    valueRange = 10f..100f,
                    modifier = Modifier.height(32.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Layers: ${config.intensityLayers}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = config.intensityLayers.toFloat(),
                    onValueChange = { onConfigChange(config.copy(intensityLayers = it.toInt())) },
                    valueRange = 1f..16f,
                    steps = 14,
                    modifier = Modifier.height(32.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Switch(
                    checked = config.useNnapi,
                    onCheckedChange = { onConfigChange(config.copy(useNnapi = it)) },
                    modifier = Modifier.height(24.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (config.useNnapi) "NNAPI (TPU)" else "CPU only",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = { if (isRunning) onStop() else onStart() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    if (isRunning) "STOP" else "START",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
