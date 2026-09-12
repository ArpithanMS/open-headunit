package com.andrerinas.openheadunit.ride.domain

enum class AutoRideState { IDLE, SUSPECTED_MOTION, RIDING_CANDIDATE, RECORDING, STOP_CANDIDATE }

/** A real location sample observed while passively watching for movement - never a synthetic or
 *  estimated one (see [AutoRideStateMachine]'s KDoc). */
data class AutoRideSample(
    val speedMetersPerSecond: Double?,
    val timestampMs: Long,
)

sealed class AutoRideDecision {
    object None : AutoRideDecision()
    /** Start recording now - the same effect a manual Start Ride tap has. */
    object StartRide : AutoRideDecision()
    /** Stop recording now - the same effect a manual End Ride tap has. */
    object StopRide : AutoRideDecision()
}

/**
 * Capture-first passive ride detection: auto-starts on reasonable movement evidence with NO
 * confirmation step. The user's own explicit direction this session - a missed real ride can
 * never be recovered, a garbage short recording can just be deleted, so false positives are the
 * acceptable failure mode here, not false negatives.
 *
 * Auto-stop is the opposite trade-off on purpose: a long stationary dwell (minutes, not seconds),
 * not a brief one - falsely ending mid-ride (a traffic light, a fuel stop) is worse than a late
 * stop, so this stays conservative even though start doesn't.
 *
 * State machine (exactly the shape from this session's product-planning discussion):
 * IDLE -> SUSPECTED_MOTION -> RIDING_CANDIDATE -> RECORDING -> STOP_CANDIDATE -> RECORDING (motion
 * resumed) or IDLE (dwell + grace persisted, [AutoRideDecision.StopRide]).
 *
 * [Config]'s thresholds are the user's own first-pass estimates from that discussion, not measured
 * from real field data - initial/tunable values, not a claim of correctness. This class only
 * decides *when* to start/stop; it never talks to RideTrackingService or GPS itself, so it's pure
 * and fully unit-testable (see AutoRideStateMachineTest) - something that gathers real samples and
 * acts on [AutoRideDecision] is a separate, Android-dependent piece.
 */
class AutoRideStateMachine(private val config: Config = Config()) {

    data class Config(
        /** A single sample at/above this speed is enough to leave IDLE - see [AutoRideState.SUSPECTED_MOTION]. */
        val motionSpeedMetersPerSecond: Double = 10.0 / 3.6, // ~10 km/h
        /** Qualifying speed sustained this long escalates SUSPECTED_MOTION -> RIDING_CANDIDATE. */
        val ridingCandidateDurationMs: Long = 10_000L,
        /** Qualifying speed sustained this long (from the same motion start) escalates to
         *  RECORDING and fires [AutoRideDecision.StartRide]. */
        val startSustainedDurationMs: Long = 30_000L,
        /** Below this speed counts as "stationary" for stop-dwell purposes - matches
         *  [RouteStatisticsCalculator]'s own moving-speed floor, the same real-world definition
         *  of "not moving" this app already uses elsewhere. */
        val stationarySpeedMetersPerSecond: Double = RouteStatisticsCalculator.MOVING_SPEED_THRESHOLD_METERS_PER_SECOND,
        /** Stationary this long while RECORDING enters STOP_CANDIDATE. */
        val stopDwellDurationMs: Long = 3 * 60_000L,
        /** STOP_CANDIDATE persisting stationary this long fires [AutoRideDecision.StopRide]. */
        val stopGraceDurationMs: Long = 60_000L,
    )

    var state: AutoRideState = AutoRideState.IDLE
        private set

    private var motionStartedAtMs: Long? = null
    private var stationarySinceMs: Long? = null
    private var stopCandidateEnteredAtMs: Long? = null

    fun onSample(sample: AutoRideSample): AutoRideDecision {
        val speed = sample.speedMetersPerSecond
        val isQualifyingMotion = speed != null && speed >= config.motionSpeedMetersPerSecond
        val isStationary = speed != null && speed < config.stationarySpeedMetersPerSecond

        return when (state) {
            AutoRideState.IDLE, AutoRideState.SUSPECTED_MOTION, AutoRideState.RIDING_CANDIDATE ->
                onWatchingForStart(sample, isQualifyingMotion)
            AutoRideState.RECORDING -> onRecording(sample, isStationary)
            AutoRideState.STOP_CANDIDATE -> onStopCandidate(sample, isStationary)
        }
    }

    /** Resets to IDLE, clearing all in-progress evidence - e.g. the user manually started or
     *  stopped a ride, which should never leave this machine mid-escalation. */
    fun reset() {
        state = AutoRideState.IDLE
        motionStartedAtMs = null
        stationarySinceMs = null
        stopCandidateEnteredAtMs = null
    }

    /** Sync point for a ride that is now active for *any* reason - a manual Start Ride tap, or
     *  this machine's own prior [AutoRideDecision.StartRide]. Whoever owns this machine must call
     *  this whenever a ride starts outside of [onSample] noticing it itself, or the machine would
     *  keep escalating start-evidence while a ride is already running. */
    fun onRideStarted() {
        state = AutoRideState.RECORDING
        motionStartedAtMs = null
        stationarySinceMs = null
        stopCandidateEnteredAtMs = null
    }

    /** Sync point for a ride that is no longer active for any reason (manual End Ride included) -
     *  equivalent to [reset]; a distinct name because the call site's intent differs. */
    fun onRideStopped() = reset()

    private fun onWatchingForStart(sample: AutoRideSample, isQualifyingMotion: Boolean): AutoRideDecision {
        if (!isQualifyingMotion) {
            motionStartedAtMs = null
            state = AutoRideState.IDLE
            return AutoRideDecision.None
        }
        val startedAt = motionStartedAtMs ?: sample.timestampMs.also { motionStartedAtMs = it }
        val sustainedMs = sample.timestampMs - startedAt

        return when {
            sustainedMs >= config.startSustainedDurationMs -> {
                state = AutoRideState.RECORDING
                motionStartedAtMs = null
                AutoRideDecision.StartRide
            }
            sustainedMs >= config.ridingCandidateDurationMs -> {
                state = AutoRideState.RIDING_CANDIDATE
                AutoRideDecision.None
            }
            else -> {
                state = AutoRideState.SUSPECTED_MOTION
                AutoRideDecision.None
            }
        }
    }

    private fun onRecording(sample: AutoRideSample, isStationary: Boolean): AutoRideDecision {
        if (!isStationary) {
            stationarySinceMs = null
            return AutoRideDecision.None
        }
        val since = stationarySinceMs ?: sample.timestampMs.also { stationarySinceMs = it }
        if (sample.timestampMs - since >= config.stopDwellDurationMs) {
            state = AutoRideState.STOP_CANDIDATE
            stopCandidateEnteredAtMs = sample.timestampMs
        }
        return AutoRideDecision.None
    }

    private fun onStopCandidate(sample: AutoRideSample, isStationary: Boolean): AutoRideDecision {
        if (!isStationary) {
            // Motion resumed - the dwell was a stop along the ride, not the end of it.
            state = AutoRideState.RECORDING
            stationarySinceMs = null
            stopCandidateEnteredAtMs = null
            return AutoRideDecision.None
        }
        val enteredAt = stopCandidateEnteredAtMs ?: sample.timestampMs.also { stopCandidateEnteredAtMs = it }
        if (sample.timestampMs - enteredAt >= config.stopGraceDurationMs) {
            reset()
            return AutoRideDecision.StopRide
        }
        return AutoRideDecision.None
    }
}
