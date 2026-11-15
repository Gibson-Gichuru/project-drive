package org.projectdrive.app.map

import org.junit.Assert.*
import org.junit.Test
import org.projectdrive.app.core.navigation.*

class CameraTrackingTest {
    @Test fun idleUserLocationFollowsUntilPanAndRecenterWithoutARoute() {
        val camera=CameraTracking();camera.update(null,true);assertTrue(camera.following)
        camera.gesture();repeat(10){camera.update(null,true);assertFalse(camera.following)}
        camera.recenter();assertTrue(camera.following)
    }
    @Test fun approximateOrUnknownAccuracyDoesNotImplyStreetLevelPrecision() {
        assertEquals(16.0,currentLocationZoom(5f),0.0)
        assertTrue(currentLocationZoom(1000f)<12.0)
        assertTrue(currentLocationZoom(100000f)<6.0)
        assertTrue(currentLocationZoom(Float.NaN)<16.0)
        assertTrue(currentLocationZoom(0f)<16.0)
        assertEquals(16.0,currentLocationZoom(null),0.0)
    }
    private fun route() = Route(listOf(Coordinate(-1.28,36.82),Coordinate(-1.30,36.83)),1000.0,120.0,listOf(Maneuver("Continue",0,1,1000.0,120.0)))
    @Test fun gesturesRemainFreeAcrossPreviewAndPositionRefreshes() {
        val camera=CameraTracking();val route=route();camera.update(route,false);camera.gesture()
        repeat(30){camera.update(route,false);assertTrue(camera.exploring);assertFalse(camera.following)}
        camera.recenter();assertFalse(camera.exploring)
    }
    @Test fun guidanceUpdatesCannotStealCameraAfterGesture() {
        val camera=CameraTracking();val route=route();camera.update(route,false);camera.update(route,true)
        assertTrue(camera.following);camera.gesture()
        repeat(30){camera.update(route,true);assertFalse(camera.following)}
        camera.recenter();assertTrue(camera.following)
    }
    @Test fun newRouteAndExplicitGuidanceStartRestoreCentering() {
        val camera=CameraTracking();camera.update(route(),false);camera.gesture();camera.update(route(),false)
        assertFalse(camera.exploring);camera.gesture();camera.update(route(),true);assertTrue(camera.following)
    }
    @Test fun rotationKeepsExplorationUntilRecenterIncludingGuidance() {
        val route=route();val camera=CameraTracking(true);camera.update(route,true)
        repeat(5){camera.update(route,true)}
        assertTrue(camera.exploring);assertFalse(camera.following)
        camera.recenter();assertTrue(camera.following)
    }
    @Test fun lostFixOrStoppingGuidanceDoesNotEndManualExploration() {
        val camera=CameraTracking();val route=route();camera.update(route,true);camera.gesture()
        camera.update(route,false);assertTrue(camera.exploring)
        camera.update(null,false);assertTrue(camera.exploring)
    }
}
