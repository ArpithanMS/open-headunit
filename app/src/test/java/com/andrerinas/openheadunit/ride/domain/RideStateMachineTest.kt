package com.andrerinas.openheadunit.ride.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RideStateMachineTest {

    @Test
    fun `IDLE accepts StartRequested and moves to RIDING`() {
        val result = RideStateMachine.transition(RideState.IDLE, RideEvent.StartRequested)
        assertEquals(RideStateMachine.Result.Accepted(RideState.RIDING), result)
    }

    @Test
    fun `RIDING accepts StopRequested and moves to FINISHED`() {
        val result = RideStateMachine.transition(RideState.RIDING, RideEvent.StopRequested)
        assertEquals(RideStateMachine.Result.Accepted(RideState.FINISHED), result)
    }

    @Test
    fun `rejects IDLE to FINISHED`() {
        val result = RideStateMachine.transition(RideState.IDLE, RideEvent.StopRequested)
        assertEquals(RideStateMachine.Result.Rejected, result)
    }

    @Test
    fun `FINISHED is terminal and rejects every event`() {
        for (event in RideEvent.values()) {
            val result = RideStateMachine.transition(RideState.FINISHED, event)
            assertEquals("event=$event", RideStateMachine.Result.Rejected, result)
        }
    }

    @Test
    fun `RIDING rejects a second StartRequested`() {
        val result = RideStateMachine.transition(RideState.RIDING, RideEvent.StartRequested)
        assertEquals(RideStateMachine.Result.Rejected, result)
    }

    @Test
    fun `IDLE rejects PauseRequested and ResumeRequested`() {
        assertEquals(
            RideStateMachine.Result.Rejected,
            RideStateMachine.transition(RideState.IDLE, RideEvent.PauseRequested)
        )
        assertEquals(
            RideStateMachine.Result.Rejected,
            RideStateMachine.transition(RideState.IDLE, RideEvent.ResumeRequested)
        )
    }

    @Test
    fun `PAUSED rejects a redundant PauseRequested`() {
        val result = RideStateMachine.transition(RideState.PAUSED, RideEvent.PauseRequested)
        assertEquals(RideStateMachine.Result.Rejected, result)
    }

    @Test
    fun `IDLE MovementDetected moves to POSSIBLE_RIDE`() {
        val result = RideStateMachine.transition(RideState.IDLE, RideEvent.MovementDetected)
        assertEquals(RideStateMachine.Result.Accepted(RideState.POSSIBLE_RIDE), result)
    }

    @Test
    fun `POSSIBLE_RIDE MovementDetected confirms RIDING`() {
        val result =
            RideStateMachine.transition(RideState.POSSIBLE_RIDE, RideEvent.MovementDetected)
        assertEquals(RideStateMachine.Result.Accepted(RideState.RIDING), result)
    }

    @Test
    fun `POSSIBLE_RIDE InactivityTimeout falls back to IDLE`() {
        val result =
            RideStateMachine.transition(RideState.POSSIBLE_RIDE, RideEvent.InactivityTimeout)
        assertEquals(RideStateMachine.Result.Accepted(RideState.IDLE), result)
    }

    @Test
    fun `POSSIBLE_RIDE StartRequested overrides straight into RIDING`() {
        val result = RideStateMachine.transition(RideState.POSSIBLE_RIDE, RideEvent.StartRequested)
        assertEquals(RideStateMachine.Result.Accepted(RideState.RIDING), result)
    }

    @Test
    fun `POSSIBLE_RIDE StopRequested cancels back to IDLE`() {
        val result = RideStateMachine.transition(RideState.POSSIBLE_RIDE, RideEvent.StopRequested)
        assertEquals(RideStateMachine.Result.Accepted(RideState.IDLE), result)
    }

    @Test
    fun `RIDING InactivityTimeout pauses the ride`() {
        val result = RideStateMachine.transition(RideState.RIDING, RideEvent.InactivityTimeout)
        assertEquals(RideStateMachine.Result.Accepted(RideState.PAUSED), result)
    }

    @Test
    fun `PAUSED ResumeRequested and MovementDetected both return to RIDING`() {
        assertEquals(
            RideStateMachine.Result.Accepted(RideState.RIDING),
            RideStateMachine.transition(RideState.PAUSED, RideEvent.ResumeRequested)
        )
        assertEquals(
            RideStateMachine.Result.Accepted(RideState.RIDING),
            RideStateMachine.transition(RideState.PAUSED, RideEvent.MovementDetected)
        )
    }

    @Test
    fun `PAUSED StopRequested finishes the ride`() {
        val result = RideStateMachine.transition(RideState.PAUSED, RideEvent.StopRequested)
        assertEquals(RideStateMachine.Result.Accepted(RideState.FINISHED), result)
    }

    @Test
    fun `every state and event combination not explicitly accepted is rejected`() {
        val accepted: Map<Pair<RideState, RideEvent>, RideState> = mapOf(
            (RideState.IDLE to RideEvent.StartRequested) to RideState.RIDING,
            (RideState.IDLE to RideEvent.MovementDetected) to RideState.POSSIBLE_RIDE,
            (RideState.POSSIBLE_RIDE to RideEvent.MovementDetected) to RideState.RIDING,
            (RideState.POSSIBLE_RIDE to RideEvent.InactivityTimeout) to RideState.IDLE,
            (RideState.POSSIBLE_RIDE to RideEvent.StartRequested) to RideState.RIDING,
            (RideState.POSSIBLE_RIDE to RideEvent.StopRequested) to RideState.IDLE,
            (RideState.RIDING to RideEvent.StopRequested) to RideState.FINISHED,
            (RideState.RIDING to RideEvent.PauseRequested) to RideState.PAUSED,
            (RideState.RIDING to RideEvent.InactivityTimeout) to RideState.PAUSED,
            (RideState.PAUSED to RideEvent.ResumeRequested) to RideState.RIDING,
            (RideState.PAUSED to RideEvent.MovementDetected) to RideState.RIDING,
            (RideState.PAUSED to RideEvent.StopRequested) to RideState.FINISHED,
        )

        for (state in RideState.values()) {
            for (event in RideEvent.values()) {
                val expected = accepted[state to event]
                    ?.let { RideStateMachine.Result.Accepted(it) }
                    ?: RideStateMachine.Result.Rejected
                val actual = RideStateMachine.transition(state, event)
                assertEquals("state=$state event=$event", expected, actual)
            }
        }
    }
}
