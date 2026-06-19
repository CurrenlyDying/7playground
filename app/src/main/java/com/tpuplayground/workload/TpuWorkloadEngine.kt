package com.tpuplayground.workload

import android.content.Context
import android.util.Log
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
    @Volatile var actualDelegate = "none"
        private set
    @Volatile var lastError: String? = null
        private set

    private var onResult: ((InferenceResult) -> Unit)? = null
    private var onStatusChange: ((String) -> Unit)? = null
    private var workloadThread: Thread? = null
    private var startTimeMs = 0L

    val isRunning: Boolean get() = running

    private fun initialize(cfg: WorkloadConfig): Boolean {
        releaseInterpreter()
        config = cfg

        val modelName = "matmul_${cfg.matrixSize}.tflite"
        val modelInt8Name = "matmul_${cfg.matrixSize}_int8.tflite"

        if (cfg.useNnapi) {
            if (tryInitInterpreter(modelInt8Name, useNnapi = true)) {
                actualDelegate = "NNAPI (int8)"
                Log.i(TAG, "Initialized with NNAPI + int8 model")
                return true
            }
            Log.w(TAG, "NNAPI + int8 failed, trying NNAPI + float32")

            if (tryInitInterpreter(modelName, useNnapi = true)) {
                actualDelegate = "NNAPI (float32)"
                Log.i(TAG, "Initialized with NNAPI + float32 model")
                return true
            }
            Log.w(TAG, "NNAPI failed entirely, falling back to CPU")
        }

        if (tryInitInterpreter(modelName, useNnapi = false)) {
            actualDelegate = "CPU"
            Log.i(TAG, "Initialized with CPU delegate")
            return true
        }

        val fallbacks = listOf("matmul_256.tflite", "matmul_128.tflite", "matmul_64.tflite")
        for (fb in fallbacks) {
            if (tryInitInterpreter(fb, useNnapi = false)) {
                actualDelegate = "CPU (fallback)"
                Log.i(TAG, "Initialized with CPU fallback model: $fb")
                return true
            }
        }

        lastError = "All model loading attempts failed"
        Log.e(TAG, lastError!!)
        return false
    }

    private fun tryInitInterpreter(assetName: String, useNnapi: Boolean): Boolean {
        return try {
            val modelBuffer = loadModelFromAssets(assetName) ?: run {
                Log.w(TAG, "Could not load asset: $assetName")
                return false
            }

            val options = Interpreter.Options().apply {
                setNumThreads(4)
                if (useNnapi) {
                    @Suppress("DEPRECATION")
                    setUseNNAPI(true)
                }
            }

            val interp = Interpreter(modelBuffer, options)

            val inputTensor = interp.getInputTensor(0)
            val outputTensor = interp.getOutputTensor(0)
            Log.i(TAG, "Model $assetName loaded: input=${inputTensor.shape().toList()} " +
                    "type=${inputTensor.dataType()}, output=${outputTensor.shape().toList()}")

            val inBuf = ByteBuffer.allocateDirect(inputTensor.numBytes()).apply {
                order(ByteOrder.nativeOrder())
            }
            val outBuf = ByteBuffer.allocateDirect(outputTensor.numBytes()).apply {
                order(ByteOrder.nativeOrder())
            }

            // Warmup inference to catch native crashes early
            fillBuffer(inBuf, inputTensor.dataType().name.contains("INT", ignoreCase = true))
            inBuf.rewind()
            outBuf.rewind()
            interp.run(inBuf, outBuf)

            interpreter = interp
            inputBuffer = inBuf
            outputBuffer = outBuf
            lastError = null
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init with $assetName (nnapi=$useNnapi): ${e.message}", e)
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            false
        } catch (e: Error) {
            Log.e(TAG, "Native crash with $assetName (nnapi=$useNnapi): ${e.message}", e)
            lastError = "Native: ${e.message}"
            false
        }
    }

    fun start(workloadConfig: WorkloadConfig, statusCallback: (String) -> Unit, resultCallback: (InferenceResult) -> Unit) {
        onResult = resultCallback
        onStatusChange = statusCallback

        val needsInit = interpreter == null ||
                workloadConfig.matrixSize != config.matrixSize ||
                workloadConfig.useNnapi != config.useNnapi

        running = true
        startTimeMs = System.currentTimeMillis()

        workloadThread = Thread({
            if (needsInit) {
                statusCallback("Initializing model...")
                if (!initialize(workloadConfig)) {
                    statusCallback("FAILED: $lastError")
                    running = false
                    return@Thread
                }
                statusCallback("Running: ${config.waveform.label} | ${config.matrixSize}x | $actualDelegate")
            }
            config = workloadConfig
            runWorkloadLoop()
        }, "TPU-Workload").apply {
            start()
        }
    }

    fun stop() {
        running = false
        workloadThread?.join(3000)
        workloadThread = null
    }

    fun release() {
        stop()
        releaseInterpreter()
    }

    private fun releaseInterpreter() {
        try { interpreter?.close() } catch (_: Exception) {}
        interpreter = null
        inputBuffer = null
        outputBuffer = null
    }

    fun updateConfig(newConfig: WorkloadConfig) {
        val needsReinit = newConfig.matrixSize != config.matrixSize || newConfig.useNnapi != config.useNnapi
        config = newConfig
        if (needsReinit && running) {
            val resCb = onResult ?: return
            val statusCb = onStatusChange ?: { _: String -> }
            stop()
            start(newConfig, statusCb, resCb)
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

    private fun fillBuffer(buf: ByteBuffer, isInt8: Boolean) {
        buf.rewind()
        val random = java.util.Random()
        while (buf.hasRemaining()) {
            if (isInt8) {
                buf.put((random.nextInt(256) - 128).toByte())
            } else {
                if (buf.remaining() >= 4) {
                    buf.putFloat(random.nextFloat() * 2f - 1f)
                } else {
                    buf.put(random.nextInt(256).toByte())
                }
            }
        }
    }

    private fun loadModelFromAssets(name: String): MappedByteBuffer? {
        return try {
            val fd = context.assets.openFd(name)
            val stream = FileInputStream(fd.fileDescriptor)
            val mapped = stream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength
            )
            fd.close()
            mapped
        } catch (e: Exception) {
            Log.w(TAG, "loadModelFromAssets($name) failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "TpuWorkload"
    }
}
