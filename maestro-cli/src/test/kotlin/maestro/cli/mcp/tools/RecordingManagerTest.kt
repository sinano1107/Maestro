package maestro.cli.mcp.tools

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import util.LocalSimulatorUtils
import java.io.File

class RecordingManagerTest {

    private lateinit var localSimulatorUtils: LocalSimulatorUtils
    private lateinit var recordingManager: RecordingManager
    private var currentTimeMs = 10_000L
    private val fakeDuration = 5.0

    private val fakeProcess = mockk<Process>(relaxed = true) {
        every { isAlive } returns true
    }
    private val fakeVideoFile = File.createTempFile("test-recording", ".mov").also {
        it.writeText("fake video")
        it.deleteOnExit()
    }
    private val fakeScreenRecording = LocalSimulatorUtils.ScreenRecording(fakeProcess, fakeVideoFile)

    @BeforeEach
    fun setUp() {
        localSimulatorUtils = mockk()
        every { localSimulatorUtils.startScreenRecording(any()) } returns fakeScreenRecording
        every { localSimulatorUtils.stopScreenRecording(any()) } returns fakeVideoFile

        recordingManager = RecordingManager(
            localSimulatorUtils = localSimulatorUtils,
            clock = { currentTimeMs },
            videoDurationProvider = { fakeDuration }
        )
    }

    @Test
    fun `startRecording returns state with recording ID`() {
        val state = recordingManager.startRecording("device-1", null)

        assertThat(state.recordingId).isNotEmpty()
        assertThat(state.deviceId).isEqualTo("device-1")
        verify { localSimulatorUtils.startScreenRecording("device-1") }
    }

    @Test
    fun `startRecording throws if recording already active for device`() {
        recordingManager.startRecording("device-1", null)

        val exception = assertThrows<IllegalStateException> {
            recordingManager.startRecording("device-1", null)
        }
        assertThat(exception.message).contains("already active")
    }

    @Test
    fun `startRecording allows different devices concurrently`() {
        val state1 = recordingManager.startRecording("device-1", null)
        val state2 = recordingManager.startRecording("device-2", null)

        assertThat(state1.recordingId).isNotEqualTo(state2.recordingId)
    }

    @Test
    fun `appendTapEvent is no-op when no recording active`() {
        recordingManager.appendTapEvent("device-1", "button", 100, 200)
    }

    @Test
    fun `appendTapEvent accumulates events during recording`() {
        val state = recordingManager.startRecording("device-1", null)

        currentTimeMs = 12_000L
        recordingManager.appendTapEvent("device-1", "button-1", 100, 200)

        currentTimeMs = 14_000L
        recordingManager.appendTapEvent("device-1", "button-2", 300, 400)

        assertThat(state.events).hasSize(2)
        assertThat(state.events[0].event).isEqualTo("tap")
        assertThat(state.events[0].target).isEqualTo("button-1")
        assertThat(state.events[0].wallClockMs).isEqualTo(12_000L)
        assertThat(state.events[1].target).isEqualTo("button-2")
        assertThat(state.events[1].centerX).isEqualTo(300)
    }

    @Test
    fun `appendSwipeEvent is no-op when no recording active`() {
        recordingManager.appendSwipeEvent("device-1", 0, 500, 0, 100)
    }

    @Test
    fun `appendSwipeEvent accumulates swipe events during recording`() {
        val state = recordingManager.startRecording("device-1", null)

        currentTimeMs = 12_000L
        recordingManager.appendSwipeEvent("device-1", 200, 800, 200, 200)

        assertThat(state.events).hasSize(1)
        assertThat(state.events[0].event).isEqualTo("swipe")
        assertThat(state.events[0].target).isNull()
        assertThat(state.events[0].startX).isEqualTo(200)
        assertThat(state.events[0].startY).isEqualTo(800)
        assertThat(state.events[0].endX).isEqualTo(200)
        assertThat(state.events[0].endY).isEqualTo(200)
    }

    @Test
    fun `stopRecording throws if no recording active`() {
        val exception = assertThrows<IllegalStateException> {
            recordingManager.stopRecording("device-1", "some-id")
        }
        assertThat(exception.message).contains("No active recording")
    }

    @Test
    fun `stopRecording throws on recording ID mismatch`() {
        val state = recordingManager.startRecording("device-1", null)

        val exception = assertThrows<IllegalArgumentException> {
            recordingManager.stopRecording("device-1", "wrong-id")
        }
        assertThat(exception.message).contains("mismatch")
        assertThat(exception.message).contains(state.recordingId)
    }

    @Test
    fun `stopRecording returns result with correct duration and video path`() {
        val state = recordingManager.startRecording("device-1", null)

        currentTimeMs = 15_000L
        val result = recordingManager.stopRecording("device-1", state.recordingId)

        assertThat(result.duration).isEqualTo(5.0)
        assertThat(result.videoPath).isEqualTo(fakeVideoFile.absolutePath)
        verify { localSimulatorUtils.stopScreenRecording(fakeScreenRecording) }
    }

    @Test
    fun `stopRecording corrects timestamps to video-relative time`() {
        val state = recordingManager.startRecording("device-1", null)

        currentTimeMs = 12_000L
        recordingManager.appendTapEvent("device-1", "button-1", 100, 200)

        currentTimeMs = 14_000L
        recordingManager.appendTapEvent("device-1", "button-2", 300, 400)

        // actual_start = 15_000 - 5000 = 10_000
        currentTimeMs = 15_000L
        val result = recordingManager.stopRecording("device-1", state.recordingId)

        assertThat(result.coordinateLog).hasSize(2)
        assertThat(result.coordinateLog[0].timestamp).isEqualTo(2.0)
        assertThat(result.coordinateLog[0].target).isEqualTo("button-1")
        assertThat(result.coordinateLog[1].timestamp).isEqualTo(4.0)
        assertThat(result.coordinateLog[1].target).isEqualTo("button-2")
    }

    @Test
    fun `stopRecording handles recording startup delay correctly`() {
        val delayedManager = RecordingManager(
            localSimulatorUtils = localSimulatorUtils,
            clock = { currentTimeMs },
            videoDurationProvider = { 3.0 }
        )

        val state = delayedManager.startRecording("device-1", null)

        currentTimeMs = 12_500L
        delayedManager.appendTapEvent("device-1", "button", 150, 250)

        // actual_start = 15_000 - 3000 = 12_000
        currentTimeMs = 15_000L
        val result = delayedManager.stopRecording("device-1", state.recordingId)

        assertThat(result.coordinateLog[0].timestamp).isEqualTo(0.5)
    }

    @Test
    fun `stopRecording clamps timestamps to 0 and duration`() {
        val state = recordingManager.startRecording("device-1", null)

        currentTimeMs = 9_000L
        recordingManager.appendTapEvent("device-1", "early", 10, 20)

        currentTimeMs = 16_000L
        recordingManager.appendTapEvent("device-1", "late", 30, 40)

        currentTimeMs = 15_000L
        val result = recordingManager.stopRecording("device-1", state.recordingId)

        assertThat(result.coordinateLog[0].timestamp).isEqualTo(0.0)
        assertThat(result.coordinateLog[1].timestamp).isEqualTo(5.0)
    }

    @Test
    fun `stopRecording returns mixed tap and swipe events with correct timestamps`() {
        val state = recordingManager.startRecording("device-1", null)

        currentTimeMs = 12_000L
        recordingManager.appendTapEvent("device-1", "General", 91, 343)

        currentTimeMs = 13_000L
        recordingManager.appendSwipeEvent("device-1", 200, 800, 200, 200)

        currentTimeMs = 14_000L
        recordingManager.appendTapEvent("device-1", "Keyboard", 116, 543)

        currentTimeMs = 15_000L
        val result = recordingManager.stopRecording("device-1", state.recordingId)

        assertThat(result.coordinateLog).hasSize(3)

        val tap1 = result.coordinateLog[0]
        assertThat(tap1.event).isEqualTo("tap")
        assertThat(tap1.timestamp).isEqualTo(2.0)
        assertThat(tap1.centerX).isEqualTo(91)

        val swipe = result.coordinateLog[1]
        assertThat(swipe.event).isEqualTo("swipe")
        assertThat(swipe.timestamp).isEqualTo(3.0)
        assertThat(swipe.startX).isEqualTo(200)
        assertThat(swipe.endY).isEqualTo(200)
        assertThat(swipe.target).isNull()

        val tap2 = result.coordinateLog[2]
        assertThat(tap2.event).isEqualTo("tap")
        assertThat(tap2.timestamp).isEqualTo(4.0)
        assertThat(tap2.target).isEqualTo("Keyboard")
    }

    @Test
    fun `stopRecording removes state so device can record again`() {
        val state = recordingManager.startRecording("device-1", null)
        recordingManager.stopRecording("device-1", state.recordingId)

        val state2 = recordingManager.startRecording("device-1", null)
        assertThat(state2.recordingId).isNotEqualTo(state.recordingId)
    }

    @Test
    fun `stopRecording with no events returns empty coordinate log`() {
        val state = recordingManager.startRecording("device-1", null)

        currentTimeMs = 15_000L
        val result = recordingManager.stopRecording("device-1", state.recordingId)

        assertThat(result.coordinateLog).isEmpty()
        assertThat(result.duration).isEqualTo(5.0)
    }

    @Test
    fun `stopRecording copies file to output path when specified`() {
        val outputFile = File.createTempFile("output-recording", ".mov")
        outputFile.delete()
        outputFile.deleteOnExit()

        val state = recordingManager.startRecording("device-1", outputFile.absolutePath)

        currentTimeMs = 15_000L
        val result = recordingManager.stopRecording("device-1", state.recordingId)

        assertThat(result.videoPath).isEqualTo(outputFile.absolutePath)
        assertThat(outputFile.exists()).isTrue()
        outputFile.delete()
    }
}
