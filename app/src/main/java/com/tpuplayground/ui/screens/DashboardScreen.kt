package com.tpuplayground.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tpuplayground.ui.components.*
import com.tpuplayground.ui.theme.Red400
import com.tpuplayground.viewmodel.PlaygroundState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: PlaygroundState,
    onStartWorkload: () -> Unit,
    onStopWorkload: () -> Unit,
    onConfigChange: (com.tpuplayground.workload.WorkloadConfig) -> Unit,
    onTabSelected: (Int) -> Unit,
    onRefreshMemory: () -> Unit,
    onClearHistory: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onExport: () -> Unit
) {
    val tabs = listOf("Workload", "Sensors", "Memory")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("TPU Playground", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.statusMessage,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (state.rootAvailable) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Root available",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "No root",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onClearHistory) {
                        Icon(Icons.Default.Delete, "Clear history", modifier = Modifier.size(20.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SensorStatusBar(snapshot = state.currentSnapshot)

            RecordingBar(
                state = state,
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording,
                onExport = onExport
            )

            TabRow(
                selectedTabIndex = state.selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = state.selectedTab == index,
                        onClick = { onTabSelected(index) },
                        text = {
                            Text(title, style = MaterialTheme.typography.labelSmall)
                        }
                    )
                }
            }

            when (state.selectedTab) {
                0 -> WorkloadTab(state, onStartWorkload, onStopWorkload, onConfigChange)
                1 -> SensorsTab(state)
                2 -> MemoryTab(state, onRefreshMemory)
            }
        }
    }
}

@Composable
private fun RecordingBar(
    state: PlaygroundState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onExport: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (state.recording) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Red400)
            )
            Text(
                "REC",
                style = MaterialTheme.typography.labelSmall,
                color = Red400
            )
            Text(
                "${formatDuration(state.recordingDurationMs)} | ${state.recordingFrames} frames",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            FilledTonalButton(
                onClick = onStopRecording,
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Red400.copy(alpha = 0.2f)
                )
            ) {
                Text("STOP", style = MaterialTheme.typography.labelSmall, color = Red400)
            }
        } else {
            FilledTonalButton(
                onClick = onStartRecording,
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Default.FiberManualRecord, null, modifier = Modifier.size(12.dp), tint = Red400)
                Spacer(Modifier.width(4.dp))
                Text("Record", style = MaterialTheme.typography.labelSmall)
            }

            if (state.recordingFrames > 0 || state.lastExportPath != null) {
                FilledTonalButton(
                    onClick = onExport,
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Export ZIP", style = MaterialTheme.typography.labelSmall)
                }
            }

            state.lastExportPath?.let { path ->
                val fileName = path.substringAfterLast("/")
                Text(
                    fileName,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            } ?: Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun WorkloadTab(
    state: PlaygroundState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onConfigChange: (com.tpuplayground.workload.WorkloadConfig) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(8.dp))

        WaveformControl(
            config = state.config,
            isRunning = state.workloadRunning,
            onConfigChange = onConfigChange,
            onStart = onStart,
            onStop = onStop
        )

        Spacer(Modifier.height(12.dp))

        state.latestInference?.let { result ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricCard("Latency", "${String.format("%.1f", result.latencyMs)}ms", Modifier.weight(1f))
                MetricCard("Intensity", "${(result.currentIntensity * 100).toInt()}%", Modifier.weight(1f))
                MetricCard("Phase", "${(result.waveformPhase * 100).toInt()}%", Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(8.dp))

        ChartCard(modifier = Modifier.padding(horizontal = 12.dp)) {
            SingleSeriesChart(
                points = state.intensityHistory,
                title = "Workload Intensity",
                minY = 0f,
                maxY = 1f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(8.dp))

        ChartCard(modifier = Modifier.padding(horizontal = 12.dp)) {
            SingleSeriesChart(
                points = state.latencyHistory,
                title = "Inference Latency (ms)",
                minY = 0f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(8.dp))

        if (state.thermalHistory.isNotEmpty()) {
            ChartCard(modifier = Modifier.padding(horizontal = 12.dp)) {
                LiveChart(
                    series = state.thermalHistory.filter { (k, _) ->
                        k in setOf("soc_therm", "north_therm", "charging_therm", "flash_therm")
                    }.ifEmpty { state.thermalHistory.entries.take(4).associate { it.key to it.value } },
                    title = "Key Thermals (C)",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SensorsTab(state: PlaygroundState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        if (state.thermalHistory.isNotEmpty()) {
            ChartCard {
                LiveChart(
                    series = state.thermalHistory,
                    title = "All Thermal Zones (C)",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (state.cpuFreqHistory.isNotEmpty()) {
            ChartCard {
                LiveChart(
                    series = state.cpuFreqHistory.mapKeys { (k, _) -> "cpu$k" },
                    title = "CPU Frequencies (MHz)",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (state.powerHistory.isNotEmpty() && state.powerHistory.any { it.value.any { p -> p.value > 0 } }) {
            ChartCard {
                LiveChart(
                    series = state.powerHistory,
                    title = "Power Rails (mW)",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        state.currentSnapshot?.let { snap ->
            ChartCard {
                Column {
                    Text(
                        "Current Readings",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    snap.cpuClusters.forEach { cluster ->
                        SensorRow(
                            "CPU policy${cluster.policy} (${cluster.relatedCpus})",
                            "${cluster.currentFreqKhz / 1000}MHz / ${cluster.maxFreqKhz / 1000}MHz"
                        )
                    }

                    SensorRow("GPU Freq", "${snap.gpuFreqKhz ?: "?"}MHz")
                    SensorRow("GPU Util", "${snap.gpuUtilPct ?: "?"}%")
                    SensorRow("TPU", snap.tpuState)

                    snap.batteryCurrentUa?.let {
                        SensorRow("Battery Current", "${it}uA")
                    }
                    snap.batteryVoltageMv?.let {
                        SensorRow("Battery Voltage", "${it}mV")
                    }

                    SensorRow("Mem Total", "${snap.memTotalKb / 1024}MB")
                    SensorRow("Mem Available", "${snap.memAvailableKb / 1024}MB")
                    SensorRow("Swap", "${(snap.swapTotalKb - snap.swapFreeKb) / 1024}MB / ${snap.swapTotalKb / 1024}MB")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun MemoryTab(state: PlaygroundState, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Refresh", style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(Modifier.height(8.dp))

        ChartCard {
            MemoryHeatMap(
                memoryMap = state.memoryMap,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChartCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp)
    ) {
        content()
    }
}

@Composable
private fun SensorRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val m = s / 60
    return if (m > 0) "${m}m${s % 60}s" else "${s}s"
}
