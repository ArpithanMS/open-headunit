package com.andrerinas.openheadunit.ride.domain

/**
 * Whether a raw GPS fix is trustworthy enough to count toward a ride's recorded route, distance,
 * and speed statistics. A rejected point is never discarded - see [RideRawSample] - it just
 * doesn't move the route forward; the next candidate is still evaluated against the last
 * *accepted* point, not the rejected one, so a single bad fix can't drag every point after it out
 * of range too.
 *
 * Stateless, like every other policy object in this codebase: the caller (RideTrackingService)
 * holds "last accepted point" and passes it in each time.
 */
object RidePointQualityPolicy {

    /** Worse (larger) than this, a fix is too imprecise to trust. Typical phone GPS is 3-15m. */
    const val MAX_ACCEPTED_ACCURACY_METERS = 50f

    /**
     * A generous ceiling on plausible ground speed (~300 km/h), used to catch GPS fixes that
     * imply teleportation rather than motorcycle travel. Deliberately loose: the goal is to
     * reject obviously-broken fixes (a multi-kilometer jump between consecutive 1Hz points), not
     * to second-guess a fast but real one.
     */
    const val MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND = 83.3f

    /**
     * Two fixes closer together in time than this are treated as duplicates rather than two real
     * samples - GPS chips occasionally redeliver the same fix, and the 1Hz subscription cadence
     * ([com.andrerinas.openheadunit.ride.location.RideLocationEngine]) means anything under this
     * is not a second, independent reading.
     */
    const val MIN_INTERVAL_MS = 200L

    enum class Rejection {
        /** [RidePoint.accuracyMeters] worse than [MAX_ACCEPTED_ACCURACY_METERS]. */
        POOR_ACCURACY,

        /** This fix's timestamp is not after the last accepted fix's - a clock or ordering fault. */
        NON_MONOTONIC_TIMESTAMP,

        /** Arrived less than [MIN_INTERVAL_MS] after the last accepted fix. */
        DUPLICATE,

        /** Implied ground speed between this fix and the last accepted one exceeds plausibility. */
        IMPOSSIBLE_JUMP,

        /** The fix's own reported [RidePoint.speedMetersPerSecond] exceeds plausibility. */
        IMPLAUSIBLE_SPEED,
    }

    sealed class Result {
        object Accepted : Result()
        data class Rejected(val reason: Rejection) : Result()
    }

    /**
     * Evaluates [candidate] against [previousAccepted] (null for the first fix of a ride, which
     * is always accepted - there is nothing yet to compare it against).
     */
    fun evaluate(candidate: RidePoint, previousAccepted: RidePoint?): Result {
        if (candidate.accuracyMeters > MAX_ACCEPTED_ACCURACY_METERS) {
            return Result.Rejected(Rejection.POOR_ACCURACY)
        }

        candidate.speedMetersPerSecond?.let { reportedSpeed ->
            if (reportedSpeed > MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND) {
                return Result.Rejected(Rejection.IMPLAUSIBLE_SPEED)
            }
        }

        if (previousAccepted == null) return Result.Accepted

        val deltaMs = candidate.timestampMs - previousAccepted.timestampMs
        if (deltaMs <= 0) return Result.Rejected(Rejection.NON_MONOTONIC_TIMESTAMP)
        if (deltaMs < MIN_INTERVAL_MS) return Result.Rejected(Rejection.DUPLICATE)

        val distanceMeters = RideMetrics.haversineMeters(
            previousAccepted.latitude, previousAccepted.longitude,
            candidate.latitude, candidate.longitude
        )
        val impliedSpeed = distanceMeters / (deltaMs / 1000.0)
        if (impliedSpeed > MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND) {
            return Result.Rejected(Rejection.IMPOSSIBLE_JUMP)
        }

        return Result.Accepted
    }
}
