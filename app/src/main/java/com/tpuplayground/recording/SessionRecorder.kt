package com.tpuplayground.recording

import android.content.Context
import android.os.Build
import com.tpuplayground.sensors.MemoryMap
import com.tpuplayground.sensors.SensorSnapshot
import com.tpuplayground.workload.InferenceResult
import com.tpuplayground.workload.WorkloadConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class RecordedFrame(
    val timestampMs: Long,
    val snapshot: SensorSnapshot?,
    val inference: InferenceResult?,
    val memoryMap: MemoryMap?
)

class SessionRecorder(private val context: Context) {

    private val frames = mutableListOf<RecordedFrame>()
    private val memorySnapshots = mutableListOf<Pair<Long, MemoryMap>>()
    @Volatile var recording = false
        private set
    private var startTimeMs = 0L
    private var config: WorkloadConfig? = null
    private var delegate: String = ""

    val frameCount: Int get() = frames.size
    val durationMs: Long get() = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0

    fun start(workloadConfig: WorkloadConfig, actualDelegate: String) {
        frames.clear()
        memorySnapshots.clear()
        startTimeMs = System.currentTimeMillis()
        config = workloadConfig
        delegate = actualDelegate
        recording = true
    }

    fun stop() {
        recording = false
    }

    fun recordSensorSnapshot(snapshot: SensorSnapshot) {
        if (!recording) return
        synchronized(frames) {
            frames.add(RecordedFrame(System.currentTimeMillis(), snapshot, null, null))
        }
    }

    fun recordInference(result: InferenceResult) {
        if (!recording) return
        synchronized(frames) {
            frames.add(RecordedFrame(result.timestampMs, null, result, null))
        }
    }

    fun recordMemoryMap(map: MemoryMap) {
        if (!recording) return
        synchronized(memorySnapshots) {
            memorySnapshots.add(System.currentTimeMillis() to map)
        }
    }

    fun export(): File? {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timestamp = dateFormat.format(Date(startTimeMs))
        val zipFile = File(context.getExternalFilesDir(null), "tpu_run_${timestamp}.zip")

        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                writeMetadata(zip)
                writeSensorCsv(zip)
                writeInferenceCsv(zip)
                writeThermalCsv(zip)
                writeCpuFreqCsv(zip)
                writePowerCsv(zip)
                writeMemoryMapCsvs(zip)
                writeMemorySummaryCsv(zip)
            }
            return zipFile
        } catch (e: Exception) {
            android.util.Log.e("SessionRecorder", "Export failed", e)
            return null
        }
    }

    private fun writeMetadata(zip: ZipOutputStream) {
        zip.putNextEntry(ZipEntry("metadata.txt"))
        val w = BufferedWriter(OutputStreamWriter(zip))
        val cfg = config
        w.write("# TPU Playground Session Recording\n")
        w.write("device=${Build.MODEL}\n")
        w.write("manufacturer=${Build.MANUFACTURER}\n")
        w.write("android=${Build.VERSION.RELEASE}\n")
        w.write("sdk=${Build.VERSION.SDK_INT}\n")
        w.write("soc=${Build.SOC_MODEL}\n")
        w.write("start_time_ms=$startTimeMs\n")
        w.write("start_time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(startTimeMs))}\n")
        w.write("duration_ms=${durationMs}\n")
        w.write("total_frames=${frames.size}\n")
        w.write("memory_snapshots=${memorySnapshots.size}\n")
        w.write("delegate=$delegate\n")
        if (cfg != null) {
            w.write("waveform=${cfg.waveform.name}\n")
            w.write("matrix_size=${cfg.matrixSize}\n")
            w.write("period_ms=${cfg.periodMs}\n")
            w.write("duty_cycle_pct=${cfg.dutyCyclePct}\n")
            w.write("intensity_layers=${cfg.intensityLayers}\n")
            w.write("use_nnapi=${cfg.useNnapi}\n")
        }
        w.flush()
        zip.closeEntry()
    }

    private fun writeSensorCsv(zip: ZipOutputStream) {
        val sensorFrames = synchronized(frames) { frames.filter { it.snapshot != null } }
        if (sensorFrames.isEmpty()) return

        zip.putNextEntry(ZipEntry("sensors.csv"))
        val w = BufferedWriter(OutputStreamWriter(zip))

        val allThermalTypes = sensorFrames
            .flatMap { it.snapshot!!.thermalZones.map { z -> z.type } }
            .distinct().sorted()

        val header = buildString {
            append("timestamp_ms,relative_ms")
            for (t in allThermalTypes) append(",thermal_${t}_c")
            append(",gpu_freq_khz,gpu_util_pct,tpu_state")
            append(",batt_temp_mc,batt_current_ua,batt_voltage_mv")
            append(",mem_total_kb,mem_available_kb,mem_free_kb")
            append(",swap_total_kb,swap_free_kb")
        }
        w.write(header)
        w.newLine()

        for (frame in sensorFrames) {
            val s = frame.snapshot!!
            val relMs = frame.timestampMs - startTimeMs
            val thermalMap = s.thermalZones.associate { it.type to it.tempC }

            val line = buildString {
                append("${frame.timestampMs},$relMs")
                for (t in allThermalTypes) append(",${thermalMap[t] ?: ""}")
                append(",${s.gpuFreqKhz ?: ""},${s.gpuUtilPct ?: ""},${s.tpuState}")
                append(",${s.batteryTempMilliC ?: ""},${s.batteryCurrentUa ?: ""},${s.batteryVoltageMv ?: ""}")
                append(",${s.memTotalKb},${s.memAvailableKb},${s.memFreeKb}")
                append(",${s.swapTotalKb},${s.swapFreeKb}")
            }
            w.write(line)
            w.newLine()
        }
        w.flush()
        zip.closeEntry()
    }

    private fun writeInferenceCsv(zip: ZipOutputStream) {
        val infFrames = synchronized(frames) { frames.filter { it.inference != null } }
        if (infFrames.isEmpty()) return

        zip.putNextEntry(ZipEntry("inference.csv"))
        val w = BufferedWriter(OutputStreamWriter(zip))
        w.write("timestamp_ms,relative_ms,latency_ms,intensity,waveform_phase")
        w.newLine()

        for (frame in infFrames) {
            val r = frame.inference!!
            val relMs = frame.timestampMs - startTimeMs
            w.write("${frame.timestampMs},$relMs,${r.latencyMs},${r.currentIntensity},${r.waveformPhase}")
            w.newLine()
        }
        w.flush()
        zip.closeEntry()
    }

    private fun writeThermalCsv(zip: ZipOutputStream) {
        val sensorFrames = synchronized(frames) { frames.filter { it.snapshot != null } }
        if (sensorFrames.isEmpty()) return

        val allTypes = sensorFrames
            .flatMap { it.snapshot!!.thermalZones.map { z -> z.type } }
            .distinct().sorted()

        zip.putNextEntry(ZipEntry("thermals.csv"))
        val w = BufferedWriter(OutputStreamWriter(zip))
        w.write("timestamp_ms,relative_ms," + allTypes.joinToString(","))
        w.newLine()

        for (frame in sensorFrames) {
            val s = frame.snapshot!!
            val relMs = frame.timestampMs - startTimeMs
            val thermalMap = s.thermalZones.associate { it.type to it.tempC }
            val values = allTypes.joinToString(",") { thermalMap[it]?.toString() ?: "" }
            w.write("${frame.timestampMs},$relMs,$values")
            w.newLine()
        }
        w.flush()
        zip.closeEntry()
    }

    private fun writeCpuFreqCsv(zip: ZipOutputStream) {
        val sensorFrames = synchronized(frames) { frames.filter { it.snapshot != null } }
        if (sensorFrames.isEmpty()) return

        zip.putNextEntry(ZipEntry("cpu_freq.csv"))
        val w = BufferedWriter(OutputStreamWriter(zip))

        val allPolicies = sensorFrames
            .flatMap { it.snapshot!!.cpuClusters.map { c -> c.policy } }
            .distinct().sorted()

        val header = "timestamp_ms,relative_ms," + allPolicies.joinToString(",") { p ->
            "policy${p}_cur_khz,policy${p}_max_khz,policy${p}_cpus"
        }
        w.write(header)
        w.newLine()

        for (frame in sensorFrames) {
            val s = frame.snapshot!!
            val relMs = frame.timestampMs - startTimeMs
            val clusterMap = s.cpuClusters.associateBy { it.policy }
            val values = allPolicies.joinToString(",") { p ->
                val c = clusterMap[p]
                "${c?.currentFreqKhz ?: ""},${c?.maxFreqKhz ?: ""},${c?.relatedCpus ?: ""}"
            }
            w.write("${frame.timestampMs},$relMs,$values")
            w.newLine()
        }
        w.flush()
        zip.closeEntry()
    }

    private fun writePowerCsv(zip: ZipOutputStream) {
        val sensorFrames = synchronized(frames) { frames.filter { it.snapshot != null } }
        if (sensorFrames.isEmpty()) return

        val allRails = sensorFrames
            .flatMap { it.snapshot!!.powerRails.map { r -> r.name } }
            .distinct().sorted()
        if (allRails.isEmpty()) return

        zip.putNextEntry(ZipEntry("power_rails.csv"))
        val w = BufferedWriter(OutputStreamWriter(zip))
        w.write("timestamp_ms,relative_ms," + allRails.joinToString(",") { "${it}_mw" })
        w.newLine()

        for (frame in sensorFrames) {
            val s = frame.snapshot!!
            val relMs = frame.timestampMs - startTimeMs
            val railMap = s.powerRails.associate { it.name to it.milliWatts }
            val values = allRails.joinToString(",") { railMap[it]?.toString() ?: "" }
            w.write("${frame.timestampMs},$relMs,$values")
            w.newLine()
        }
        w.flush()
        zip.closeEntry()
    }

    private fun writeMemoryMapCsvs(zip: ZipOutputStream) {
        val snapshots = synchronized(memorySnapshots) { memorySnapshots.toList() }
        if (snapshots.isEmpty()) return

        for ((idx, pair) in snapshots.withIndex()) {
            val (ts, map) = pair
            val relMs = ts - startTimeMs
            zip.putNextEntry(ZipEntry("memory_map_${idx}_${relMs}ms.csv"))
            val w = BufferedWriter(OutputStreamWriter(zip))

            w.write("start_addr,end_addr,size_kb,rss_kb,pss_kb,shared_clean_kb,shared_dirty_kb,private_clean_kb,private_dirty_kb,permissions,category,name")
            w.newLine()

            for (r in map.regions) {
                val name = r.name.replace(",", ";")
                w.write("${java.lang.Long.toHexString(r.startAddr)},${java.lang.Long.toHexString(r.endAddr)},${r.sizeKb},${r.rssKb},${r.pssKb},${r.sharedCleanKb},${r.sharedDirtyKb},${r.privateCleanKb},${r.privateDirtyKb},${r.permissions},${r.category.name},${name}")
                w.newLine()
            }
            w.flush()
            zip.closeEntry()
        }
    }

    private fun writeMemorySummaryCsv(zip: ZipOutputStream) {
        val snapshots = synchronized(memorySnapshots) { memorySnapshots.toList() }
        if (snapshots.isEmpty()) return

        zip.putNextEntry(ZipEntry("memory_summary.csv"))
        val w = BufferedWriter(OutputStreamWriter(zip))
        w.write("timestamp_ms,relative_ms,pid,total_vss_kb,total_rss_kb,total_pss_kb")

        val categories = com.tpuplayground.sensors.MemoryCategory.entries
        for (cat in categories) {
            w.write(",${cat.name}_pss_kb")
        }
        w.newLine()

        for ((ts, map) in snapshots) {
            val relMs = ts - startTimeMs
            w.write("$ts,$relMs,${map.pid},${map.totalVssKb},${map.totalRssKb},${map.totalPssKb}")
            for (cat in categories) {
                w.write(",${map.categoryBreakdown[cat] ?: 0}")
            }
            w.newLine()
        }
        w.flush()
        zip.closeEntry()
    }
}
