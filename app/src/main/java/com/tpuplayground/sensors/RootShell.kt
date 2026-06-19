package com.tpuplayground.sensors

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

class RootShell {
    private var process: Process? = null
    private var stdin: DataOutputStream? = null
    private var stdout: BufferedReader? = null

    val isAvailable: Boolean
        get() = try {
            val p = Runtime.getRuntime().exec("su -c id")
            val result = p.inputStream.bufferedReader().readText()
            p.waitFor()
            result.contains("uid=0")
        } catch (e: Exception) {
            false
        }

    fun exec(command: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = p.inputStream.bufferedReader().readText().trim()
            p.errorStream.bufferedReader().readText()
            p.waitFor()
            output
        } catch (e: Exception) {
            ""
        }
    }

    fun readFile(path: String): String {
        return exec("cat $path")
    }

    fun readFileInt(path: String): Int? {
        return readFile(path).trim().toIntOrNull()
    }

    fun readFileLong(path: String): Long? {
        return readFile(path).trim().toLongOrNull()
    }

    fun listDir(path: String): List<String> {
        return exec("ls $path").lines().filter { it.isNotBlank() }
    }
}
