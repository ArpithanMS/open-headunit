package com.andrerinas.openheadunit.ride.data

import com.andrerinas.openheadunit.ride.domain.SavedPlace
import com.andrerinas.openheadunit.ride.domain.SavedPlaceRole

/** Wraps [SavedPlaceDao] the same way [RideRepository] wraps [RideDao] - the seam that keeps
 *  Room's entity types out of ride/domain and ride/presentation. */
class SavedPlaceRepository(private val dao: SavedPlaceDao) {

    suspend fun save(place: SavedPlace) = dao.upsert(place.toEntity())

    suspend fun delete(role: SavedPlaceRole) = dao.delete(role.name)

    suspend fun all(): List<SavedPlace> = dao.all().map { it.toDomain() }
}
