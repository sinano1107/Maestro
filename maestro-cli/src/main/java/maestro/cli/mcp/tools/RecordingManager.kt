package maestro.cli.mcp.tools

import maestro.utils.TempFileHandler
import util.LocalSimulatorUtils
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class InteractionEvent(
    val wallClockMs: Long,
    val event: String,
    val target: String?,
    val centerX: Int?,
    val centerY: Int?,
    val startX: Int? = null,
    val startY: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null
)

data class RecordingState(
    val recordingId: String,
    val deviceId: String,
    val screenRecording: LocalSimulatorUtils.ScreenRecording,
    val outputPath: String?,
    val events: MutableList<InteractionEvent> = mutableListOf()
)

data class StopRecordingResult(
    val videoPath: String,
    val duration: Double,
    val coordinateLog: List<CoordinateLogEntry>
)

data class CoordinateLogEntry(
    val timestamp: Double,
    val event: String,
    val target: String?,
    val centerX: Int?,
    val centerY: Int?,
    val startX: Int? = null,
    val startY: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null
)

class RecordingManager(
    private val localSimulatorUtils: LocalSimulatorUtils,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val videoDurationProvider: (String) -> Double = ::getVideoDurationWithFfprobe
) {
    private val activeRecordings = ConcurrentHashMap<String, RecordingState>()

    fun startRecording(deviceId: String, outputPath: String?): RecordingState {
        val screenRecording = localSimulatorUtils.startScreenRecording(deviceId)
        val recordingId = UUID.randomUUID().toString()
        val state = RecordingState(
            recordingId = recordingId,
            deviceId = deviceId,
            screenRecording = screenRecording,
            outputPath = outputPath
        )
        val existing = activeRecordings.putIfAbsent(deviceId, state)
        if (existing != null) {
            localSimulatorUtils.stopScreenRecording(screenRecording)
            throw IllegalStateException("Recording already active for device $deviceId")
        }
        return state
    }

    fun captureTimestamp(): Long = clock()

    fun appendTapEvent(deviceId: String, target: String, centerX: Int, centerY: Int, timestampMs: Long) {
        val state = activeRecordings[deviceId] ?: return
        synchronized(state.events) {
            state.events.add(InteractionEvent(
                wallClockMs = timestampMs, event = "tap", target = target,
                centerX = centerX, centerY = centerY
            ))
        }
    }

    fun appendSwipeEvent(deviceId: String, startX: Int, startY: Int, endX: Int, endY: Int, timestampMs: Long) {
        val state = activeRecordings[deviceId] ?: return
        synchronized(state.events) {
            state.events.add(InteractionEvent(
                wallClockMs = timestampMs, event = "swipe", target = null,
                centerX = null, centerY = null,
                startX = startX, startY = startY, endX = endX, endY = endY
            ))
        }
    }

    fun stopRecording(deviceId: String, recordingId: String): StopRecordingResult {
        val state = activeRecordings[deviceId]
            ?: throw IllegalStateException("No active recording for device $deviceId")
        if (state.recordingId != recordingId) {
            throw IllegalArgumentException("Recording ID mismatch: expected ${state.recordingId}, got $recordingId")
        }

        try {
            val stopWallClockMs = clock()

            val videoFile = localSimulatorUtils.stopScreenRecording(state.screenRecording)

            val finalPath = if (state.outputPath != null) {
                val dest = File(state.outputPath)
                dest.parentFile?.mkdirs()
                videoFile.copyTo(dest, overwrite = true)
                videoFile.delete()
                dest.absolutePath
            } else {
                videoFile.absolutePath
            }

            val duration = videoDurationProvider(finalPath)

            val actualStartMs = stopWallClockMs - (duration * 1000).toLong()
            val coordinateLog = synchronized(state.events) {
                state.events.map { event ->
                    val offsetSec = (event.wallClockMs - actualStartMs) / 1000.0
                    CoordinateLogEntry(
                        timestamp = offsetSec.coerceIn(0.0, duration),
                        event = event.event,
                        target = event.target,
                        centerX = event.centerX,
                        centerY = event.centerY,
                        startX = event.startX,
                        startY = event.startY,
                        endX = event.endX,
                        endY = event.endY
                    )
                }
            }

            return StopRecordingResult(
                videoPath = finalPath,
                duration = duration,
                coordinateLog = coordinateLog
            )
        } finally {
            activeRecordings.remove(deviceId)
        }
    }

    fun shutdown() {
        activeRecordings.values.forEach { state ->
            try {
                localSimulatorUtils.stopScreenRecording(state.screenRecording)
            } catch (_: Exception) {}
        }
    }

    companion object {
        private var defaultInstance: RecordingManager? = null

        fun getDefault(): RecordingManager {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: run {
                    val tempFileHandler = TempFileHandler()
                    val utils = LocalSimulatorUtils(tempFileHandler)
                    val manager = RecordingManager(utils)
                    Runtime.getRuntime().addShutdownHook(Thread {
                        manager.shutdown()
                        tempFileHandler.close()
                    })
                    defaultInstance = manager
                    manager
                }
            }
        }

        fun getVideoDurationWithFfprobe(videoPath: String): Double {
            val process = ProcessBuilder(
                "ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                videoPath
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exited = process.waitFor(10, TimeUnit.SECONDS)

            if (!exited || process.exitValue() != 0) {
                throw RuntimeException("ffprobe failed: $output")
            }

            return output.toDoubleOrNull()
                ?: throw RuntimeException("ffprobe returned non-numeric duration: $output")
        }
    }
}
