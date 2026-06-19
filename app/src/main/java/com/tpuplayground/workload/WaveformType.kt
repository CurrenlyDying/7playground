package com.tpuplayground.workload

enum class WaveformType(val label: String, val description: String) {
    CONSTANT("Constant", "Steady inference at fixed rate"),
    PULSE("Pulse", "Burst of N inferences, then idle"),
    SQUARE("Square Wave", "Alternating full load and idle"),
    SAWTOOTH("Sawtooth", "Ramp up, instant drop, repeat"),
    SINE("Sine Wave", "Smoothly varying inference rate"),
    RAMP("Ramp", "Linearly increasing inference rate"),
    SPIKE("Spike Train", "Brief max-load spikes with gaps"),
    STAIRCASE("Staircase", "Step-wise increasing load levels")
}

data class WorkloadConfig(
    val waveform: WaveformType = WaveformType.CONSTANT,
    val matrixSize: Int = 256,
    val periodMs: Long = 2000,
    val dutyCyclePct: Int = 50,
    val intensityLayers: Int = 4,
    val useNnapi: Boolean = true
)
