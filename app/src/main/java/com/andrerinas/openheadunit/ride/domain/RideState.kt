package com.andrerinas.openheadunit.ride.domain

/**
 * Where a ride is in its lifecycle. See [RideStateMachine] for which transitions between these
 * are actually allowed.
 *
 * The MVP (manual Start/Stop buttons) only ever drives IDLE -> RIDING -> FINISHED. POSSIBLE_RIDE
 * and PAUSED exist now, and are already wired into [RideStateMachine]'s transition table, purely
 * so a future automatic ride-detection feature can slot in without redesigning this core - nothing
 * in the MVP reaches them, so don't read their presence here as dead code.
 */
enum class RideState {
    /**
     * Nothing is being tracked. The state a fresh [RideStateMachine] caller starts from, and the
     * only state a new ride can be started from by explicit user action.
     */
    IDLE,

    /**
     * Future automatic detection only: motion has been seen but not yet confirmed as a real ride
     * (e.g. the phone could be walked around, not ridden). Unreachable by the MVP's manual
     * Start/Stop controls.
     */
    POSSIBLE_RIDE,

    /** Actively recording [RidePoint]s for the current ride. */
    RIDING,

    /**
     * Future feature only: the ride is temporarily suspended (a manual pause control, or
     * automatic detection noticing a stop - traffic light, fuel, a short break) without ending
     * it. Unreachable by the MVP's manual Start/Stop controls.
     */
    PAUSED,

    /**
     * Terminal. A ride, once finished, is never resumed - starting another ride means a fresh
     * [IDLE] beginning, not a transition out of FINISHED. [RideStateMachine] rejects every event
     * from this state to protect that invariant.
     */
    FINISHED,
}
