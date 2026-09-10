package com.andrerinas.openheadunit.ride.domain

/**
 * Something that can move a ride from one [RideState] to another, fed into
 * [RideStateMachine.transition]. Modeled as a flat event enum rather than explicit
 * start()/stop()/pause()/resume() methods on the state machine so that MovementDetected and
 * InactivityTimeout - which have no natural user-facing method name - fit the same shape as the
 * manual controls, and so a later automatic-detection feature can add behaviour around an
 * existing event without changing this type's shape.
 *
 * No variant carries a payload: none of the transitions need per-event data, and timestamps
 * belong on [RidePoint], not on the event that triggers a state change.
 */
enum class RideEvent {
    /** MVP: the Start Ride button. */
    StartRequested,

    /** MVP: the Stop Ride button. */
    StopRequested,

    /** Future: a manual pause control. Not fired by anything in the MVP. */
    PauseRequested,

    /** Future: a manual resume control. Not fired by anything in the MVP. */
    ResumeRequested,

    /** Future automatic detection: GPS motion seen. Not fired by anything in the MVP. */
    MovementDetected,

    /**
     * Future automatic detection: motion has stopped for long enough to matter. Not fired by
     * anything in the MVP.
     */
    InactivityTimeout,
}
