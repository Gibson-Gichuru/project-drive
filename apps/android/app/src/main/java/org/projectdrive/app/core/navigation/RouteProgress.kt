package org.projectdrive.app.core.navigation

import kotlin.math.*

data class Progress(val maneuver: Maneuver, val distanceToRouteMeters: Double, val remainingMeters: Double, val arrived: Boolean)

fun distanceMeters(a: Coordinate, b: Coordinate): Double {
    val lat = Math.toRadians(b.latitude - a.latitude)
    val lon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
    return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

fun progressOnRoute(route: Route, location: Coordinate): Progress {
    var bestDistance = Double.POSITIVE_INFINITY
    var nearestSegment = 0
    var fraction = 0.0
    val scale = cos(Math.toRadians(location.latitude))
    for (i in 0 until route.geometry.lastIndex) {
        val a = route.geometry[i]
        val b = route.geometry[i + 1]
        val ax = (a.longitude - location.longitude) * scale
        val ay = a.latitude - location.latitude
        val dx = (b.longitude - a.longitude) * scale
        val dy = b.latitude - a.latitude
        val denominator = dx * dx + dy * dy
        val t = if (denominator == 0.0) 0.0 else (-(ax * dx + ay * dy) / denominator).coerceIn(0.0, 1.0)
        val projected = Coordinate(a.latitude + t * (b.latitude - a.latitude), a.longitude + t * (b.longitude - a.longitude))
        val distance = distanceMeters(location, projected)
        if (distance < bestDistance) {
            bestDistance = distance
            nearestSegment = i
            fraction = t
        }
    }
    var remaining = distanceMeters(route.geometry[nearestSegment], route.geometry[nearestSegment + 1]) * (1 - fraction)
    for (i in nearestSegment + 1 until route.geometry.lastIndex) remaining += distanceMeters(route.geometry[i], route.geometry[i + 1])
    val arrived = remaining < 25 && distanceMeters(location, route.geometry.last()) < 25
    val maneuver = if (arrived) route.maneuvers.last() else route.maneuvers.lastOrNull { it.startIndex <= nearestSegment } ?: route.maneuvers.first()
    return Progress(maneuver, bestDistance, remaining, arrived)
}
