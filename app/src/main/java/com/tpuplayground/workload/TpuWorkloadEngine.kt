package com.tpuplayground.workload

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

data class InferenceResult(
    val timestampMs: Long,
    val latencyMs: Float,
    val currentIntensity: Float,
    val waveformPhase: Float
)

class TpuWorkloadEngine(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    @Volatile private var running = false
    @Volatile var config = WorkloadConfig()
        private set

    private var onResult: ((InferenceResult) -> Unit)? = null
    private var workloadThread: Thread? = null
    private var startTimeMs = 0L

    val isRunning: Boolean get() = running

    fun initialize(cfg: WorkloadConfig) {
        release()
        config = cfg

        val assetName = if (cfg.useNnapi) {
            "matmul_${cfg.matrixSize}_int8.tflite"
        } else {
            "matmul_${cfg.matrixSize}.tflite"
        }

        val modelBuffer = loadModelFromAssets(assetName)
            ?: loadModelFromAssets("matmul_${cfg.matrixSize}.tflite")
            ?: loadModelFromAssets("matmul_256.tflite")!!

        val options = Interpreter.Options().apply {
            numThreads = 4
            if (cfg.useNnapi) {
                setUseNNAPI(true)
            }
        }
        interpreter = Interpreter(modelBuffer, options)

        val inputTensor = interpreter!!.getInputTensor(0)
        val outputTensor = interpreter!!.getOutputTensor(0)

        val inputBytes = inputTensor.numBytes()
        val outputBytes = outputTensor.numBytes()

        inputBuffer = ByteBuffer.allocateDirect(inputBytes).apply {
            order(ByteOrder.nativeOrder())
        }
        outputBuffer = ByteBuffer.allocateDirect(outputBytes).apply {
            order(ByteOrder.nativeOrder())
        }

        fillInputWithNoise()
    }

    fun start(workloadConfig: WorkloadConfig, callback: (InferenceResult) -> Unit) {
        if (interpreter == null || workloadConfig.matrixSize != config.matrixSize ||
            workloadConfig.useNnapi != config.useNnapi) {
            initialize(workloadConfig)
        }
        config = workloadConfig
        onResult = callback
        running = true
        startTimeMs = System.currentTimeMillis()

        workloadThread = Thread({
            runWorkloadLoop()
        }, "TPU-Workload").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        workloadThread?.join(2000)
        workloadThread = null
    }

    fun release() {
        stop()
        interpreter?.close()
        interpreter = null
        inputBuffer = null
        outputBuffer = null
    }

    fun updateConfig(newConfig: WorkloadConfig) {
        val needsReinit = newConfig.matrixSize != config.matrixSize || newConfig.useNnapi != config.useNnapi
        config = newConfig
        if (needsReinit && running) {
            val cb = onResult ?: return
            stop()
            initialize(newConfig)
            start(newConfig, cb)
        }
    }

    private fun runWorkloadLoop() {
        while (running) {
            val elapsed = System.currentTimeMillis() - startTimeMs
            val intensity = computeIntensity(elapsed)

            if (intensity <= 0.01f) {
                Thread.sleep(10)
                onResult?.invoke(InferenceResult(
                    System.currentTimeMillis(), 0f, 0f,
                    (elapsed % config.periodMs).toFloat() / config.periodMs
                ))
                continue
            }

            val inferenceCount = (intensity * config.intensityLayers).toInt().coerceIn(1, config.intensityLayers)

            val t0 = System.nanoTime()
            repeat(inferenceCount) {
                runSingleInference()
            }
            val latencyMs = (System.nanoTime() - t0) / 1_000_000f

            onResult?.invoke(InferenceResult(
                System.currentTimeMillis(),
                latencyMs,
                intensity,
                (elapsed % config.periodMs).toFloat() / config.periodMs
            ))

            val sleepFactor = (1f - intensity) * 20
            if (sleepFactor > 1) {
                Thread.sleep(sleepFactor.toLong())
            }
        }
    }

    private fun computeIntensity(elapsedMs: Long): Float {
        val phase = (elapsedMs % config.periodMs).toFloat() / config.periodMs
        val duty = config.dutyCyclePct / 100f

        return when (config.waveform) {
            WaveformType.CONSTANT -> 1f
            WaveformType.PULSE -> if (phase < duty) 1f else 0f
            WaveformType.SQUARE -> if (phase < duty) 1f else 0f
            WaveformType.SAWTOOTH -> phase
            WaveformType.SINE -> ((sin(2 * PI * phase) + 1) / 2).toFloat()
            WaveformType.RAMP -> min(1f, elapsedMs.toFloat() / (config.periodMs * 5))
            WaveformType.SPIKE -> {
                val spikeWidth = 0.1f
                val spikeCount = 3
                var v = 0f
                for (i in 0 until spikeCount) {
                    val center = (i + 0.5f) / spikeCount
                    val dist = abs(phase - center)
                    if (dist < spikeWidth / 2) {
                        v = 1f - (dist / (spikeWidth / 2))
                    }
                }
                v
            }
            WaveformType.STAIRCASE -> {
                val steps = 5
                val step = (phase * steps).toInt()
                (step + 1).toFloat() / steps
            }
        }
    }

    private fun runSingleInference() {
        val input = inputBuffer ?: return
        val output = outputBuffer ?: return
        val interp = interpreter ?: return

        input.rewind()
        output.rewind()

        try {
            interp.run(input, output)
        } catch (_: Exception) {
        }
    }

    private fun fillInputWithNoise() {
        val buf = inputBuffer ?: return
        buf.rewind()
        val random = java.util.Random()
        val isInt8 = config.useNnapi
        while (buf.hasRemaining()) {
            if (isInt8) {
                buf.put((random.nextInt(256) - 128).toByte())
            } else {
                buf.putFloat(random.nextFloat() * 2f - 1f)
            }
        }
    }

    private fun loadModelFromAssets(name: String): MappedByteBuffer? {
        return try {
            val fd = context.assets.openFd(name)
            val stream = FileInputStream(fd.fileDescriptor)
            stream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength
            )
        } catch (_: Exception) {
            null
        }
    }
}
