package com.tpuplayground.sensors

data class ThermalZone(
    val index: Int,
    val type: String,
    val tempMilliC: Int
) {
    val tempC: Float get() = tempMilliC / 1000f
}

data class PowerRail(
    val name: String,
    val microWatts: Long
) {
    val milliWatts: Float get() = microWatts / 1000f
}

data class CpuClusterInfo(
    val policy: Int,
    val currentFreqKhz: Long,
    val maxFreqKhz: Long,
    val minFreqKhz: Long,
    val availableFreqs: List<Long>,
    val relatedCpus: String
)

data class SensorSnapshot(
    val timestampMs: Long,
    val thermalZones: List<ThermalZone>,
    val powerRails: List<PowerRail>,
    val cpuClusters: List<CpuClusterInfo>,
    val gpuFreqKhz: Long?,
    val gpuUtilPct: Int?,
    val tpuState: String,
    val batteryTempMilliC: Int?,
    val batteryCurrentUa: Int?,
    val batteryVoltageMv: Int?,
    val memTotalKb: Long,
    val memAvailableKb: Long,
    val memFreeKb: Long,
    val swapTotalKb: Long,
    val swapFreeKb: Long
)

class SensorReader(private val shell: RootShell) {

    private val thermalBasePath = "/sys/class/thermal"
    private var thermalZoneCount = -1

    fun discoverThermalZones(): Int {
        if (thermalZoneCount >= 0) return thermalZoneCount
        thermalZoneCount = 0
        for (i in 0..50) {
            val type = shell.readFile("$thermalBasePath/thermal_zone$i/type")
            if (type.isBlank()) break
            thermalZoneCount = i + 1
        }
        return thermalZoneCount
    }

    fun readThermalZones(): List<ThermalZone> {
        val count = discoverThermalZones()
        if (count == 0) return emptyList()

        val typesCmd = (0 until count).joinToString("; ") {
            "cat $thermalBasePath/thermal_zone$it/type"
        }
        val tempsCmd = (0 until count).joinToString("; ") {
            "cat $thermalBasePath/thermal_zone$it/temp"
        }

        val types = shell.exec(typesCmd).lines()
        val temps = shell.exec(tempsCmd).lines()

        return (0 until count).mapNotNull { i ->
            val type = types.getOrNull(i)?.trim() ?: return@mapNotNull null
            val temp = temps.getOrNull(i)?.trim()?.toIntOrNull() ?: return@mapNotNull null
            ThermalZone(i, type, temp)
        }
    }

    fun readKeyThermals(): Map<String, Float> {
        val zones = readThermalZones()
        val keyTypes = setOf(
            "soc_therm", "north_therm", "south_therm",
            "charging_therm", "rfpa_therm", "quiet_therm",
            "usb_pwr_therm", "inner_disp_therm", "outer_disp_therm",
            "flash_therm", "battery"
        )
        val result = mutableMapOf<String, Float>()
        for (zone in zones) {
            if (zone.type in keyTypes || zone.type.contains("tpu", ignoreCase = true) ||
                zone.type.contains("gpu", ignoreCase = true) ||
                zone.type.contains("cpu", ignoreCase = true) ||
                zone.type.contains("npu", ignoreCase = true)) {
                result[zone.type] = zone.tempC
            }
        }
        return result
    }

    fun readCpuClusters(): List<CpuClusterInfo> {
        val policies = shell.listDir("/sys/devices/system/cpu/cpufreq/")
            .filter { it.startsWith("policy") }
            .mapNotNull { it.removePrefix("policy").toIntOrNull() }
            .sorted()

        return policies.map { policy ->
            val base = "/sys/devices/system/cpu/cpufreq/policy$policy"
            val curFreq = shell.readFileLong("$base/scaling_cur_freq") ?: 0L
            val maxFreq = shell.readFileLong("$base/scaling_max_freq") ?: 0L
            val minFreq = shell.readFileLong("$base/scaling_min_freq") ?: 0L
            val relatedCpus = shell.readFile("$base/related_cpus")
            val availFreqs = shell.readFile("$base/scaling_available_frequencies")
                .split(" ").mapNotNull { it.trim().toLongOrNull() }

            CpuClusterInfo(policy, curFreq, maxFreq, minFreq, availFreqs, relatedCpus.trim())
        }
    }

    fun readGpuFreq(): Long? {
        val paths = listOf(
            "/sys/devices/platform/34f00000.gpu0/devfreq/34f00000.gpu0/cur_freq",
            "/sys/class/devfreq/34f00000.gpu0/cur_freq"
        )
        for (path in paths) {
            val v = shell.readFileLong(path)
            if (v != null && v > 0) return v / 1000
        }
        return null
    }

    fun readGpuUtil(): Int? {
        val paths = listOf(
            "/sys/devices/platform/34f00000.gpu0/utilization",
            "/sys/class/devfreq/34f00000.gpu0/gpu_busy"
        )
        for (path in paths) {
            val v = shell.readFileInt(path)
            if (v != null) return v
        }
        return null
    }

    fun readTpuState(): String {
        val freqPaths = listOf(
            "/sys/devices/platform/1a000000.edgetpu/devfreq/1a000000.edgetpu/cur_freq",
            "/sys/class/devfreq/1a000000.edgetpu/cur_freq"
        )
        for (path in freqPaths) {
            val v = shell.readFile(path)
            if (v.isNotBlank()) return "freq=${v.trim()}"
        }

        val coolPaths = listOf(
            "/sys/class/thermal/cooling_device*/type"
        )
        val coolDevs = shell.exec("grep -r 'tpu\\|edgetpu\\|gxp' /sys/class/thermal/cooling_device*/type 2>/dev/null")
        if (coolDevs.isNotBlank()) {
            val parts = coolDevs.lines().first().split(":")
            if (parts.size == 2) {
                val dir = parts[0].removeSuffix("/type")
                val curState = shell.readFile("$dir/cur_state")
                val maxState = shell.readFile("$dir/max_state")
                return "cool_state=$curState/$maxState"
            }
        }
        return "unknown"
    }

    fun readBatteryInfo(): Triple<Int?, Int?, Int?> {
        val temp = shell.readFileInt("/sys/class/power_supply/battery/temp")
        val current = shell.readFileInt("/sys/class/power_supply/battery/current_now")
        val voltage = shell.readFileInt("/sys/class/power_supply/battery/voltage_now")
        return Triple(
            temp,
            current,
            voltage?.let { it / 1000 }
        )
    }

    fun readMemInfo(): Map<String, Long> {
        val raw = shell.readFile("/proc/meminfo")
        val result = mutableMapOf<String, Long>()
        for (line in raw.lines()) {
            val parts = line.split(":")
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim().split(" ")[0].toLongOrNull()
                if (value != null) result[key] = value
            }
        }
        return result
    }

    fun readPowerRails(): List<PowerRail> {
        val railNames = listOf(
            "S7M_VDD_TPU", "S4M_VDD_CPU", "S3M_VDD_CPU1",
            "S2M_VDD_CPU2", "S2S_VDD_GPU", "S8S_VDD_GMC"
        )
        val result = mutableListOf<PowerRail>()

        val odpmOutput = shell.exec("dumpsys android.hardware.power.stats.IPowerStats/default 2>/dev/null | head -200")
        if (odpmOutput.isNotBlank()) {
            for (rail in railNames) {
                val regex = Regex("$rail.*?energy.*?(\\d+)", RegexOption.IGNORE_CASE)
                val match = regex.find(odpmOutput)
                if (match != null) {
                    result.add(PowerRail(rail, match.groupValues[1].toLongOrNull() ?: 0))
                }
            }
        }

        if (result.isEmpty()) {
            for (rail in railNames) {
                result.add(PowerRail(rail, 0))
            }
        }
        return result
    }

    fun takeSnapshot(): SensorSnapshot {
        val thermals = readKeyThermals()
        val cpus = readCpuClusters()
        val gpuFreq = readGpuFreq()
        val gpuUtil = readGpuUtil()
        val tpuState = readTpuState()
        val (battTemp, battCurrent, battVoltage) = readBatteryInfo()
        val memInfo = readMemInfo()
        val powerRails = readPowerRails()

        val thermalZones = thermals.map { (type, tempC) ->
            ThermalZone(0, type, (tempC * 1000).toInt())
        }

        return SensorSnapshot(
            timestampMs = System.currentTimeMillis(),
            thermalZones = thermalZones,
            powerRails = powerRails,
            cpuClusters = cpus,
            gpuFreqKhz = gpuFreq,
            gpuUtilPct = gpuUtil,
            tpuState = tpuState,
            batteryTempMilliC = battTemp,
            batteryCurrentUa = battCurrent,
            batteryVoltageMv = battVoltage,
            memTotalKb = memInfo["MemTotal"] ?: 0,
            memAvailableKb = memInfo["MemAvailable"] ?: 0,
            memFreeKb = memInfo["MemFree"] ?: 0,
            swapTotalKb = memInfo["SwapTotal"] ?: 0,
            swapFreeKb = memInfo["SwapFree"] ?: 0
        )
    }
}
