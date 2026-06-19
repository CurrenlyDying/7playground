package com.tpuplayground.sensors

data class MemoryRegion(
    val startAddr: Long,
    val endAddr: Long,
    val sizeKb: Long,
    val rssKb: Long,
    val pssKb: Long,
    val sharedCleanKb: Long,
    val sharedDirtyKb: Long,
    val privateCleanKb: Long,
    val privateDirtyKb: Long,
    val permissions: String,
    val name: String,
    val category: MemoryCategory
)

enum class MemoryCategory(val label: String, val colorIndex: Int) {
    NATIVE_HEAP("Native Heap", 0),
    JAVA_HEAP("Java Heap", 1),
    CODE("Code (.so/.dex)", 2),
    STACK("Stack", 3),
    GPU_BUFFER("GPU Buffer", 4),
    DEVICE_MAPPED("Device Mapped", 5),
    MMAP_FILE("Memory Mapped File", 6),
    ANONYMOUS("Anonymous", 7),
    OTHER("Other", 8)
}

data class MemoryMap(
    val pid: Int,
    val regions: List<MemoryRegion>,
    val totalVssKb: Long,
    val totalRssKb: Long,
    val totalPssKb: Long,
    val categoryBreakdown: Map<MemoryCategory, Long>
)

class MemoryMapper(private val shell: RootShell) {

    fun readProcessMemoryMap(pid: Int = android.os.Process.myPid()): MemoryMap {
        val raw = shell.exec("cat /proc/$pid/smaps 2>/dev/null | head -3000")
        if (raw.isBlank()) return emptyMap(pid)

        val regions = mutableListOf<MemoryRegion>()
        var currentStart = 0L
        var currentEnd = 0L
        var currentPerms = ""
        var currentName = ""
        var rss = 0L
        var pss = 0L
        var sharedClean = 0L
        var sharedDirty = 0L
        var privateClean = 0L
        var privateDirty = 0L
        var inRegion = false

        for (line in raw.lines()) {
            val headerMatch = HEADER_REGEX.matchEntire(line)
            if (headerMatch != null) {
                if (inRegion) {
                    regions.add(buildRegion(
                        currentStart, currentEnd, rss, pss,
                        sharedClean, sharedDirty, privateClean, privateDirty,
                        currentPerms, currentName
                    ))
                }
                currentStart = headerMatch.groupValues[1].toLong(16)
                currentEnd = headerMatch.groupValues[2].toLong(16)
                currentPerms = headerMatch.groupValues[3]
                currentName = headerMatch.groupValues[4].trim()
                rss = 0; pss = 0; sharedClean = 0; sharedDirty = 0
                privateClean = 0; privateDirty = 0
                inRegion = true
            } else if (inRegion) {
                when {
                    line.startsWith("Rss:") -> rss = extractKb(line)
                    line.startsWith("Pss:") -> pss = extractKb(line)
                    line.startsWith("Shared_Clean:") -> sharedClean = extractKb(line)
                    line.startsWith("Shared_Dirty:") -> sharedDirty = extractKb(line)
                    line.startsWith("Private_Clean:") -> privateClean = extractKb(line)
                    line.startsWith("Private_Dirty:") -> privateDirty = extractKb(line)
                }
            }
        }
        if (inRegion) {
            regions.add(buildRegion(
                currentStart, currentEnd, rss, pss,
                sharedClean, sharedDirty, privateClean, privateDirty,
                currentPerms, currentName
            ))
        }

        val totalVss = regions.sumOf { it.sizeKb }
        val totalRss = regions.sumOf { it.rssKb }
        val totalPss = regions.sumOf { it.pssKb }
        val breakdown = regions.groupBy { it.category }
            .mapValues { (_, v) -> v.sumOf { it.pssKb } }

        return MemoryMap(pid, regions, totalVss, totalRss, totalPss, breakdown)
    }

    fun readSystemMemorySummary(): Map<String, Long> {
        val raw = shell.exec("cat /proc/meminfo")
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

    private fun buildRegion(
        start: Long, end: Long, rss: Long, pss: Long,
        sharedClean: Long, sharedDirty: Long,
        privateClean: Long, privateDirty: Long,
        perms: String, name: String
    ): MemoryRegion {
        val sizeKb = (end - start) / 1024
        val category = categorize(name, perms)
        return MemoryRegion(
            start, end, sizeKb, rss, pss,
            sharedClean, sharedDirty, privateClean, privateDirty,
            perms, name, category
        )
    }

    private fun categorize(name: String, perms: String): MemoryCategory {
        return when {
            name.contains("[heap]") || name.contains("jemalloc") ||
                name.contains("scudo") -> MemoryCategory.NATIVE_HEAP
            name.contains("dalvik") || name.contains("/dev/ashmem") -> MemoryCategory.JAVA_HEAP
            name.endsWith(".so") || name.endsWith(".dex") ||
                name.endsWith(".oat") || name.endsWith(".art") ||
                name.endsWith(".vdex") || name.endsWith(".apk") -> MemoryCategory.CODE
            name.contains("[stack") -> MemoryCategory.STACK
            name.contains("kgsl") || name.contains("mali") ||
                name.contains("gpu") || name.contains("pvr") ||
                name.contains("powervr") -> MemoryCategory.GPU_BUFFER
            name.contains("/dev/") -> MemoryCategory.DEVICE_MAPPED
            name.isNotBlank() && !name.startsWith("[") -> MemoryCategory.MMAP_FILE
            name.isBlank() || name == "[anon]" -> MemoryCategory.ANONYMOUS
            else -> MemoryCategory.OTHER
        }
    }

    private fun extractKb(line: String): Long {
        return line.split(":").getOrNull(1)?.trim()?.split(" ")?.firstOrNull()?.toLongOrNull() ?: 0
    }

    private fun emptyMap(pid: Int) = MemoryMap(pid, emptyList(), 0, 0, 0, emptyMap())

    companion object {
        private val HEADER_REGEX = Regex(
            "^([0-9a-f]+)-([0-9a-f]+)\\s+(\\S+)\\s+\\S+\\s+\\S+\\s+\\S+\\s*(.*)"
        )
    }
}
