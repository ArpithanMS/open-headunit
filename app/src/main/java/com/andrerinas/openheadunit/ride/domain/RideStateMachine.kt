package com.andrerinas.openheadunit.ride.domain

/**
 * The full, pure transition table for [RideState]. Stateless by design - like every other policy
 * object in this codebase (see e.g. ProjectionWatchdogPolicy), it holds no instance state of its
 * own; the caller (eventually RideTrackingService) owns the current [RideState] and calls
 * [transition] each time something happens.
 *
 * Every combination not listed as an accepted transition below is [Result.Rejected]. Rejection
 * carries no reason payload: the caller already holds both [current] and the [RideEvent] it just
 * passed in, so it has everything it needs to log or ignore the rejection without this type
 * echoing it back.
 *
 * The MVP (manual Start/Stop buttons) only ever drives IDLE -[StartRequested]-> RIDING and
 * RIDING -[StopRequested]-> FINISHED. Every other accepted transition below exists for a future
 * automatic ride-detection feature and a future manual pause/resume control - modeled now,
 * pinned by tests now, so that feature can land later without touching this table's shape.
 */
object RideStateMachine {

    sealed class Result {
        /** The event was valid from [current]; the new state to move to. */
        data class Accepted(val newState: RideState) : Result()

        /** The event does not apply from the state it was sent in; nothing changes. */
        object Rejected : Result()
    }

    /**
     * Decides whether [event] is valid from [current], and what it leads to when it is.
     *
     * Written as an exhaustive nested `when` rather than a lookup table so the compiler forces
     * this function to be revisited the moment a new [RideState] or [RideEvent] is added.
     */
    fun transition(current: RideState, event: RideEvent): Result = when (current) {
        RideState.IDLE -> when (event) {
            // A manual start is already a confirmed ride - it deliberately skips POSSIBLE_RIDE,
            // which exists only for the unconfirmed, automatic-detection path.
            RideEvent.StartRequested -> Result.Accepted(RideState.RIDING)
            RideEvent.MovementDetected -> Result.Accepted(RideState.POSSIBLE_RIDE)
            RideEvent.StopRequested,
            RideEvent.PauseRequested,
            RideEvent.ResumeRequested,
            RideEvent.InactivityTimeout -> Result.Rejected
        }

        RideState.POSSIBLE_RIDE -> when (event) {
            // Sustained motion confirms a real ride.
            RideEvent.MovementDetected -> Result.Accepted(RideState.RIDING)
            // A false start: motion stopped before confirming.
            RideEvent.InactivityTimeout -> Result.Accepted(RideState.IDLE)
            // A manual override while automatic detection is still unsure.
            RideEvent.StartRequested -> Result.Accepted(RideState.RIDING)
            // The user dismisses a possible-ride prompt.
            RideEvent.StopRequested -> Result.Accepted(RideState.IDLE)
            RideEvent.PauseRequested,
            RideEvent.ResumeRequested -> Result.Rejected
        }

        RideState.RIDING -> when (event) {
            RideEvent.StopRequested -> Result.Accepted(RideState.FINISHED)
            RideEvent.PauseRequested -> Result.Accepted(RideState.PAUSED)
            RideEvent.InactivityTimeout -> Result.Accepted(RideState.PAUSED)
            // Already riding - a double-tap of Start is worth surfacing, not silently ignored.
            RideEvent.StartRequested,
            RideEvent.MovementDetected,
            RideEvent.ResumeRequested -> Result.Rejected
        }

        RideState.PAUSED -> when (event) {
            RideEvent.ResumeRequested -> Result.Accepted(RideState.RIDING)
            RideEvent.MovementDetected -> Result.Accepted(RideState.RIDING)
            RideEvent.StopRequested -> Result.Accepted(RideState.FINISHED)
            RideEvent.PauseRequested,
            RideEvent.StartRequested,
            RideEvent.InactivityTimeout -> Result.Rejected
        }

        // Terminal: nothing moves a finished ride anywhere. A new ride is a fresh IDLE start,
        // never a transition out of FINISHED.
        RideState.FINISHED -> Result.Rejected
    }
}
