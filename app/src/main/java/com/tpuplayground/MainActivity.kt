package com.tpuplayground

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tpuplayground.ui.screens.DashboardScreen
import com.tpuplayground.ui.theme.TPUPlaygroundTheme
import com.tpuplayground.viewmodel.PlaygroundViewModel
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TPUPlaygroundTheme {
                val vm: PlaygroundViewModel = viewModel()
                val state by vm.state.collectAsState()

                DashboardScreen(
                    state = state,
                    onStartWorkload = vm::startWorkload,
                    onStopWorkload = vm::stopWorkload,
                    onConfigChange = { config -> vm.updateConfig { config } },
                    onTabSelected = vm::selectTab,
                    onRefreshMemory = vm::refreshMemoryMap,
                    onClearHistory = vm::clearHistory,
                    onStartRecording = vm::startRecording,
                    onStopRecording = vm::stopRecording,
                    onExport = {
                        val path = vm.exportRecording()
                        if (path != null) {
                            shareFile(File(path))
                        } else {
                            Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Export TPU Run"))
        } catch (e: Exception) {
            Toast.makeText(this, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }
}
