package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*

object StopRecordingTool {
    fun create(recordingManager: RecordingManager = RecordingManager.getDefault()): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "stop_recording",
                description = "Stop an active screen recording. Returns the video path, duration, and a coordinate log of tap events recorded during the session with timestamps corrected to video-relative time.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device being recorded")
                        }
                        putJsonObject("recording_id") {
                            put("type", "string")
                            put("description", "The recording ID returned by start_recording")
                        }
                    },
                    required = listOf("device_id", "recording_id")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments["device_id"]?.jsonPrimitive?.content
                val recordingId = request.arguments["recording_id"]?.jsonPrimitive?.content

                if (deviceId == null || recordingId == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("Both device_id and recording_id are required")),
                        isError = true
                    )
                }

                val result = recordingManager.stopRecording(deviceId, recordingId)

                val json = buildJsonObject {
                    put("success", true)
                    put("video_path", result.videoPath)
                    put("duration", result.duration)
                    putJsonArray("coordinate_log") {
                        for (entry in result.coordinateLog) {
                            addJsonObject {
                                put("timestamp", entry.timestamp)
                                put("event", entry.event)
                                put("target", entry.target)
                                putJsonArray("center") {
                                    add(entry.centerX)
                                    add(entry.centerY)
                                }
                            }
                        }
                    }
                }.toString()

                CallToolResult(content = listOf(TextContent(json)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to stop recording: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}
