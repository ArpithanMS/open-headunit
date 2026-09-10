package com.andrerinas.openheadunit.ride.domain

/**
 * A raw GPS fix as recorded, paired with the verdict [RidePointQualityPolicy] assigned it at
 * recording time. The raw [point] is always kept regardless of [accepted] - this is what makes
 * future reprocessing possible if the quality thresholds ever change: nothing is lost, only
 * which points currently count toward the route is a (re-derivable) decision.
 */
data class RideRawSample(
    val point: RidePoint,
    val accepted: Boolean,
    val rejectionReason: RidePointQualityPolicy.Rejection?,
) {
    init {
        require(accepted == (rejectionReason == null)) {
            "rejectionReason must be set if and only if the sample was rejected"
        }
    }

    companion object {
        fun accepted(point: RidePoint) = RideRawSample(point, accepted = true, rejectionReason = null)

        fun rejected(point: RidePoint, reason: RidePointQualityPolicy.Rejection) =
            RideRawSample(point, accepted = false, rejectionReason = reason)
    }
}
