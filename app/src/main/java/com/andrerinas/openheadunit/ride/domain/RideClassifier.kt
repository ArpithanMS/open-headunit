package com.andrerinas.openheadunit.ride.domain

/**
 * Which of the user's saved places (if any) a finished ride started and/or ended near - naming
 * context for Ride History ("Home → Work"), never something that decided whether the ride got
 * recorded (see [SavedPlace]'s KDoc). A null [from]/[to] means that endpoint wasn't within any
 * saved place's radius - "somewhere else", not "unknown place named null".
 */
data class RideClassification(val from: SavedPlaceRole?, val to: SavedPlaceRole?)

/**
 * Pure - no Android/Room dependency - so it's unit-testable. Classifies purely from a *finished*
 * ride's own persisted start/end coordinates against the user's saved places; never influences
 * whether a ride is recorded or how long it runs (that's [RideStateMachine]'s job, unrelated).
 */
object RideClassifier {

    /**
     * Null when there's nothing meaningful to show: the ride has no persisted start/end
     * coordinates (finished with zero accepted fixes), or neither endpoint fell within any saved
     * place's radius.
     */
    fun classify(ride: Ride, savedPlaces: List<SavedPlace>): RideClassification? {
        val startLat = ride.startLatitude
        val startLon = ride.startLongitude
        val endLat = ride.endLatitude
        val endLon = ride.endLongitude
        if (startLat == null || startLon == null || endLat == null || endLon == null) return null
        if (savedPlaces.isEmpty()) return null

        val from = nearestRole(startLat, startLon, savedPlaces)
        val to = nearestRole(endLat, endLon, savedPlaces)
        if (from == null && to == null) return null
        return RideClassification(from, to)
    }

    private fun nearestRole(latitude: Double, longitude: Double, places: List<SavedPlace>): SavedPlaceRole? =
        places
            .filter { RideMetrics.haversineMeters(latitude, longitude, it.latitude, it.longitude) <= it.radiusMeters }
            .minByOrNull { RideMetrics.haversineMeters(latitude, longitude, it.latitude, it.longitude) }
            ?.role
}
