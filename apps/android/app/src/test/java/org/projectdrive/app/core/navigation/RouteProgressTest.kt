package org.projectdrive.app.core.navigation

import org.junit.Assert.*
import org.junit.Test

class RouteProgressTest {
    private val route = Route(
        listOf(Coordinate(0.0, 36.0), Coordinate(0.0, 36.01), Coordinate(0.01, 36.01)),
        2224.0, 300.0,
        listOf(Maneuver("Go east", 0, 1, 1112.0, 150.0), Maneuver("Turn north", 1, 2, 1112.0, 150.0), Maneuver("Arrive", 2, 2, 0.0, 0.0)),
    )
    @Test fun progressChangesInstructionAndReducesDistance() {
        val first = progressOnRoute(route, Coordinate(0.0, 36.005))
        val second = progressOnRoute(route, Coordinate(0.005, 36.01))
        assertEquals("Go east", first.maneuver.instruction)
        assertEquals("Turn north", second.maneuver.instruction)
        assertTrue(second.remainingMeters < first.remainingMeters)
        assertTrue(first.distanceToRouteMeters < 1)
        assertFalse(first.arrived)
    }
    @Test fun arrivalRequiresProximityToDestination() {
        assertTrue(progressOnRoute(route, Coordinate(0.01, 36.01)).arrived)
        assertFalse(progressOnRoute(route, Coordinate(0.02, 36.01)).arrived)
    }
    @Test fun offRouteIsMeasuredAndDuplicatePointsAreSafe() {
        assertTrue(progressOnRoute(route, Coordinate(0.005, 36.015)).distanceToRouteMeters > 500)
        val duplicate = route.copy(geometry = listOf(route.geometry[0], route.geometry[0], route.geometry[2]))
        assertTrue(progressOnRoute(duplicate, route.geometry[0]).remainingMeters.isFinite())
    }
    @Test fun invalidCoordinatesAndManeuversAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Coordinate(Double.NaN, 36.0) }
        assertThrows(IllegalArgumentException::class.java) { Coordinate(91.0, 36.0) }
        assertThrows(IllegalArgumentException::class.java) { route.copy(maneuvers = listOf(Maneuver("Invalid", 0, 99, 0.0, 0.0))) }
    }
}
