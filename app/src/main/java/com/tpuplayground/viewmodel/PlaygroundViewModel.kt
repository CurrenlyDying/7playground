package com.tpuplayground.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tpuplayground.recording.SessionRecorder
import com.tpuplayground.sensors.MemoryMapper
import com.tpuplayground.sensors.MemoryMap
import com.tpuplayground.sensors.RootShell
import com.tpuplayground.sensors.SensorReader
import com.tpuplayground.sensors.SensorSnapshot
import com.tpuplayground.workload.InferenceResult
import com.tpuplayground.workload.TpuWorkloadEngine
import com.tpuplayground.workload.WorkloadConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class TimeSeriesPoint(val timestampMs: Long, val value: Float)

data class PlaygroundState(
    val rootAvailable: Boolean = false,
    val workloadRunning: Boolean = false,
    val recording: Boolean = false,
    val recordingFrames: Int = 0,
    val recordingDurationMs: Long = 0,
    val lastExportPath: String? = null,
    val config: WorkloadConfig = WorkloadConfig(),
    val currentSnapshot: SensorSnapshot? = null,
    val latestInference: InferenceResult? = null,
    val thermalHistory: Map<String, List<TimeSeriesPoint>> = emptyMap(),
    val latencyHistory: List<TimeSeriesPoint> = emptyList(),
    val intensityHistory: List<TimeSeriesPoint> = emptyList(),
    val powerHistory: Map<String, List<TimeSeriesPoint>> = emptyMap(),
    val cpuFreqHistory: Map<Int, List<TimeSeriesPoint>> = emptyMap(),
    val memoryMap: MemoryMap? = null,
    val statusMessage: String = "Initializing...",
    val selectedTab: Int = 0
)

class PlaygroundViewModel(application: Application) : AndroidViewModel(application) {

    private val shell = RootShell()
    private val sensorReader = SensorReader(shell)
    private val memoryMapper = MemoryMapper(shell)
    private val workloadEngine = TpuWorkloadEngine(application)
    private val recorder = SessionRecorder(application)

    private val _state = MutableStateFlow(PlaygroundState())
    val state: StateFlow<PlaygroundState> = _state.asStateFlow()

    private val maxHistorySize = 300

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val hasRoot = shell.isAvailable
            _state.value = _state.value.copy(
                rootAvailable = hasRoot,
                statusMessage = if (hasRoot) "Root OK. Ready." else "No root - sensor reads will be limited"
            )

            startSensorPolling()
        }
    }

    fun startWorkload() {
        val config = _state.value.config
        _state.value = _state.value.copy(
            workloadRunning = true,
            statusMessage = "Initializing model..."
        )

        workloadEngine.start(
            config,
            statusCallback = { status ->
                viewModelScope.launch {
                    _state.value = _state.value.copy(
                        statusMessage = status,
                        workloadRunning = workloadEngine.isRunning
                    )
                }
            },
            resultCallback = { result ->
                recorder.recordInference(result)
                viewModelScope.launch {
                    val current = _state.value
                    val newLatency = addPoint(current.latencyHistory, result.timestampMs, result.latencyMs)
                    val newIntensity = addPoint(current.intensityHistory, result.timestampMs, result.currentIntensity)
                    _state.value = current.copy(
                        latestInference = result,
                        latencyHistory = newLatency,
                        intensityHistory = newIntensity
                    )
                }
            }
        )
    }

    fun stopWorkload() {
        workloadEngine.stop()
        _state.value = _state.value.copy(
            workloadRunning = false,
            statusMessage = "Stopped. (was: ${workloadEngine.actualDelegate})"
        )
    }

    fun startRecording() {
        recorder.start(_state.value.config, workloadEngine.actualDelegate)
        _state.value = _state.value.copy(
            recording = true,
            recordingFrames = 0,
            recordingDurationMs = 0,
            lastExportPath = null,
            statusMessage = _state.value.statusMessage + " | REC"
        )
        startMemorySnapshotLoop()
    }

    fun stopRecording() {
        recorder.stop()
        _state.value = _state.value.copy(
            recording = false,
            statusMessage = _state.value.statusMessage.replace(" | REC", "")
        )
    }

    fun exportRecording(): String? {
        _state.value = _state.value.copy(statusMessage = "Exporting...")
        val file = recorder.export()
        val path = file?.absolutePath
        _state.value = _state.value.copy(
            lastExportPath = path,
            statusMessage = if (path != null) "Exported: ${file.name}" else "Export failed"
        )
        return path
    }

    fun updateConfig(transform: (WorkloadConfig) -> WorkloadConfig) {
        val newConfig = transform(_state.value.config)
        _state.value = _state.value.copy(config = newConfig)
        if (_state.value.workloadRunning) {
            workloadEngine.updateConfig(newConfig)
            _state.value = _state.value.copy(
                statusMessage = "Running: ${newConfig.waveform.label} | ${newConfig.matrixSize}x${newConfig.matrixSize}"
            )
        }
    }

    fun selectTab(index: Int) {
        _state.value = _state.value.copy(selectedTab = index)
        if (index == 2) {
            refreshMemoryMap()
        }
    }

    fun refreshMemoryMap() {
        viewModelScope.launch(Dispatchers.IO) {
            val map = memoryMapper.readProcessMemoryMap()
            _state.value = _state.value.copy(memoryMap = map)
            recorder.recordMemoryMap(map)
        }
    }

    fun clearHistory() {
        _state.value = _state.value.copy(
            thermalHistory = emptyMap(),
            latencyHistory = emptyList(),
            intensityHistory = emptyList(),
            powerHistory = emptyMap(),
            cpuFreqHistory = emptyMap()
        )
    }

    private fun startMemorySnapshotLoop() {
        viewModelScope.launch(Dispatchers.IO) {
            while (recorder.recording) {
                try {
                    val map = memoryMapper.readProcessMemoryMap()
                    _state.value = _state.value.copy(memoryMap = map)
                    recorder.recordMemoryMap(map)
                    _state.value = _state.value.copy(
                        recordingFrames = recorder.frameCount,
                        recordingDurationMs = recorder.durationMs
                    )
                } catch (_: Exception) {}
                delay(5000)
            }
        }
    }

    private fun startSensorPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val snapshot = sensorReader.takeSnapshot()
                    recorder.recordSensorSnapshot(snapshot)
                    val current = _state.value

                    val newThermals = current.thermalHistory.toMutableMap()
                    for (zone in snapshot.thermalZones) {
                        val key = zone.type
                        val list = newThermals.getOrDefault(key, emptyList())
                        newThermals[key] = addPoint(list, snapshot.timestampMs, zone.tempC)
                    }

                    val newPower = current.powerHistory.toMutableMap()
                    for (rail in snapshot.powerRails) {
                        val list = newPower.getOrDefault(rail.name, emptyList())
                        newPower[rail.name] = addPoint(list, snapshot.timestampMs, rail.milliWatts)
                    }

                    val newCpuFreq = current.cpuFreqHistory.toMutableMap()
                    for (cluster in snapshot.cpuClusters) {
                        val list = newCpuFreq.getOrDefault(cluster.policy, emptyList())
                        newCpuFreq[cluster.policy] = addPoint(
                            list, snapshot.timestampMs, cluster.currentFreqKhz / 1000f
                        )
                    }

                    _state.value = current.copy(
                        currentSnapshot = snapshot,
                        thermalHistory = newThermals,
                        powerHistory = newPower,
                        cpuFreqHistory = newCpuFreq,
                        recordingFrames = if (recorder.recording) recorder.frameCount else current.recordingFrames,
                        recordingDurationMs = if (recorder.recording) recorder.durationMs else current.recordingDurationMs
                    )
                } catch (_: Exception) {
                }

                delay(500)
            }
        }
    }

    private fun addPoint(list: List<TimeSeriesPoint>, ts: Long, value: Float): List<TimeSeriesPoint> {
        val mutable = list.toMutableList()
        mutable.add(TimeSeriesPoint(ts, value))
        if (mutable.size > maxHistorySize) {
            return mutable.subList(mutable.size - maxHistorySize, mutable.size)
        }
        return mutable
    }

    override fun onCleared() {
        super.onCleared()
        workloadEngine.release()
    }
}
