package com.tpuplayground

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tpuplayground.ui.screens.DashboardScreen
import com.tpuplayground.ui.theme.TPUPlaygroundTheme
import com.tpuplayground.viewmodel.PlaygroundViewModel

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
                    onClearHistory = vm::clearHistory
                )
            }
        }
    }
}
