package org.projectdrive.app.core.navigation

data class Coordinate(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

data class Maneuver(
    val instruction: String,
    val startIndex: Int,
    val endIndex: Int,
    val distanceMeters: Double,
    val durationSeconds: Double,
)

data class Route(
    val geometry: List<Coordinate>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val maneuvers: List<Maneuver>,
) {
    init {
        require(geometry.size >= 2)
        require(distanceMeters.isFinite() && distanceMeters >= 0)
        require(durationSeconds.isFinite() && durationSeconds >= 0)
        require(maneuvers.isNotEmpty())
        require(maneuvers.all { it.startIndex in geometry.indices && it.endIndex in it.startIndex..geometry.lastIndex })
    }
}

// UI and route progress never depend on renderer/router SDK types.
interface NavigationEngine {
    suspend fun route(origin: Coordinate, destination: Coordinate): Route
}
