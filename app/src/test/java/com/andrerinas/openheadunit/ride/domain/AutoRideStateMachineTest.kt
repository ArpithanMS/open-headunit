package com.andrerinas.openheadunit.ride.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoRideStateMachineTest {

    // A config with round, easy-to-reason-about thresholds rather than the real defaults.
    private val config = AutoRideStateMachine.Config(
        motionSpeedMetersPerSecond = 5.0,
        ridingCandidateDurationMs = 10_000L,
        startSustainedDurationMs = 30_000L,
        stationarySpeedMetersPerSecond = 1.0,
        stopDwellDurationMs = 60_000L,
        stopGraceDurationMs = 20_000L,
    )
    private val machine = AutoRideStateMachine(config)

    @Test
    fun `starts in IDLE`() {
        assertEquals(AutoRideState.IDLE, machine.state)
    }

    @Test
    fun `a single slow sample keeps it in IDLE and decides nothing`() {
        val decision = machine.onSample(sample(speed = 1.0, atMs = 0L))
        assertEquals(AutoRideState.IDLE, machine.state)
        assertEquals(AutoRideDecision.None, decision)
    }

    @Test
    fun `a null-speed sample (no data) is never treated as motion`() {
        machine.onSample(sample(speed = null, atMs = 0L))
        assertEquals(AutoRideState.IDLE, machine.state)
    }

    @Test
    fun `a single qualifying-speed sample escalates to SUSPECTED_MOTION, not further`() {
        val decision = machine.onSample(sample(speed = 8.0, atMs = 0L))
        assertEquals(AutoRideState.SUSPECTED_MOTION, machine.state)
        assertEquals(AutoRideDecision.None, decision)
    }

    @Test
    fun `motion sustained past ridingCandidateDurationMs escalates to RIDING_CANDIDATE`() {
        machine.onSample(sample(speed = 8.0, atMs = 0L))
        val decision = machine.onSample(sample(speed = 8.0, atMs = 15_000L))
        assertEquals(AutoRideState.RIDING_CANDIDATE, machine.state)
        assertEquals(AutoRideDecision.None, decision)
    }

    @Test
    fun `motion sustained past startSustainedDurationMs fires StartRide and enters RECORDING`() {
        machine.onSample(sample(speed = 8.0, atMs = 0L))
        machine.onSample(sample(speed = 8.0, atMs = 15_000L))
        val decision = machine.onSample(sample(speed = 8.0, atMs = 35_000L))
        assertEquals(AutoRideState.RECORDING, machine.state)
        assertEquals(AutoRideDecision.StartRide, decision)
    }

    @Test
    fun `a brief dip below motion speed resets the escalation clock`() {
        machine.onSample(sample(speed = 8.0, atMs = 0L))
        machine.onSample(sample(speed = 8.0, atMs = 15_000L)) // RIDING_CANDIDATE
        machine.onSample(sample(speed = 2.0, atMs = 20_000L)) // dips below motion threshold
        assertEquals(AutoRideState.IDLE, machine.state)

        // Sustaining again afterwards must restart the clock from this new sample, not fire
        // immediately off the earlier (now-abandoned) motion window.
        val decision = machine.onSample(sample(speed = 8.0, atMs = 20_500L))
        assertEquals(AutoRideState.SUSPECTED_MOTION, machine.state)
        assertEquals(AutoRideDecision.None, decision)
    }

    @Test
    fun `once RECORDING, brief slow samples do not trigger a stop candidate`() {
        startRecordingAt(baseMs = 0L)
        val decision = machine.onSample(sample(speed = 0.5, atMs = 40_000L))
        assertEquals(AutoRideState.RECORDING, machine.state)
        assertEquals(AutoRideDecision.None, decision)
    }

    @Test
    fun `a stationary dwell past stopDwellDurationMs enters STOP_CANDIDATE without deciding yet`() {
        startRecordingAt(baseMs = 0L)
        machine.onSample(sample(speed = 0.5, atMs = 40_000L))
        val decision = machine.onSample(sample(speed = 0.5, atMs = 40_000L + config.stopDwellDurationMs))
        assertEquals(AutoRideState.STOP_CANDIDATE, machine.state)
        assertEquals(AutoRideDecision.None, decision)
    }

    @Test
    fun `motion resuming during STOP_CANDIDATE returns to RECORDING - a stop, not the end`() {
        startRecordingAt(baseMs = 0L)
        enterStopCandidateAt(dwellStartMs = 40_000L)
        val decision = machine.onSample(sample(speed = 8.0, atMs = 40_000L + config.stopDwellDurationMs + 5_000L))
        assertEquals(AutoRideState.RECORDING, machine.state)
        assertEquals(AutoRideDecision.None, decision)
    }

    @Test
    fun `STOP_CANDIDATE persisting past the grace period fires StopRide and resets to IDLE`() {
        startRecordingAt(baseMs = 0L)
        val stopCandidateAtMs = 40_000L + config.stopDwellDurationMs
        enterStopCandidateAt(dwellStartMs = 40_000L)
        val decision = machine.onSample(sample(speed = 0.2, atMs = stopCandidateAtMs + config.stopGraceDurationMs))
        assertEquals(AutoRideState.IDLE, machine.state)
        assertEquals(AutoRideDecision.StopRide, decision)
    }

    @Test
    fun `after an auto-stop the machine can detect a brand new ride from scratch`() {
        startRecordingAt(baseMs = 0L)
        val stopCandidateAtMs = 40_000L + config.stopDwellDurationMs
        enterStopCandidateAt(dwellStartMs = 40_000L)
        machine.onSample(sample(speed = 0.2, atMs = stopCandidateAtMs + config.stopGraceDurationMs)) // StopRide

        val decision = machine.onSample(sample(speed = 8.0, atMs = stopCandidateAtMs + config.stopGraceDurationMs + 1_000L))
        assertEquals(AutoRideState.SUSPECTED_MOTION, machine.state)
        assertEquals(AutoRideDecision.None, decision)
    }

    @Test
    fun `reset() clears in-progress evidence back to IDLE regardless of current state`() {
        machine.onSample(sample(speed = 8.0, atMs = 0L))
        machine.onSample(sample(speed = 8.0, atMs = 15_000L)) // RIDING_CANDIDATE
        machine.reset()
        assertEquals(AutoRideState.IDLE, machine.state)

        // And the escalation clock is genuinely cleared, not just the state label - sustaining
        // motion from here must go through the full duration again, not fire immediately.
        machine.onSample(sample(speed = 8.0, atMs = 15_100L))
        assertEquals(AutoRideState.SUSPECTED_MOTION, machine.state)
    }

    private fun startRecordingAt(baseMs: Long) {
        machine.onSample(sample(speed = 8.0, atMs = baseMs))
        machine.onSample(sample(speed = 8.0, atMs = baseMs + 15_000L))
        val decision = machine.onSample(sample(speed = 8.0, atMs = baseMs + config.startSustainedDurationMs + 5_000L))
        assertEquals(AutoRideDecision.StartRide, decision)
    }

    private fun enterStopCandidateAt(dwellStartMs: Long) {
        machine.onSample(sample(speed = 0.5, atMs = dwellStartMs))
        val decision = machine.onSample(sample(speed = 0.5, atMs = dwellStartMs + config.stopDwellDurationMs))
        assertEquals(AutoRideState.STOP_CANDIDATE, machine.state)
        assertEquals(AutoRideDecision.None, decision)
    }

    private fun sample(speed: Double?, atMs: Long) = AutoRideSample(speedMetersPerSecond = speed, timestampMs = atMs)
}
