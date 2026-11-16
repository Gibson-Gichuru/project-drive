package org.projectdrive.app

import org.junit.Assert.*
import org.junit.Test
import org.projectdrive.app.core.navigation.*
import org.projectdrive.app.core.reports.*

class RoadReportsTest {
    private val route=Route(listOf(Coordinate(0.0,36.0),Coordinate(0.0,36.1)),11119.0,900.0,listOf(Maneuver("Continue",0,1,11119.0,900.0)))
    private val now=1_000_000L
    private fun report(lon:Double=36.015,lat:Double=0.0)=RoadReport("synthetic",ReportCategory.Obstacle,Coordinate(lat,lon),"",now-1000,now+10_000)
    @Test fun warningUsesAheadCorridorRatherThanStraightLineProximity(){
        val ahead=report();val behind=report(36.005);val otherRoad=report(lat=.005);val far=report(36.09)
        assertEquals(ahead,upcomingReport(listOf(behind,otherRoad,far,ahead),route,Coordinate(0.0,36.01),now))
        assertNull(upcomingReport(listOf(ahead),route,Coordinate(.005,36.01),now))
    }
    @Test fun expiredWithdrawnDisputedAndCameraReportsNeverWarn(){
        val p=Coordinate(0.0,36.01);val r=report()
        for(invalid in listOf(r.copy(expiresAt=now),r.copy(status="withdrawn"),r.copy(confidence="disputed"),r.copy(category=ReportCategory.Camera))){assertNull(upcomingReport(listOf(invalid),route,p,now))}
        assertNull(upcomingReport(listOf(r),null,p,now));assertNull(upcomingReport(listOf(r),route,null,now))
    }
    @Test fun nearestUpcomingObservationWinsAndArrivalClearsWarning(){
        val near=report(36.012);val farther=report(36.018)
        assertEquals(near,upcomingReport(listOf(farther,near),route,Coordinate(0.0,36.01),now))
        assertNull(upcomingReport(listOf(report(36.09999)),route,Coordinate(0.0,36.1),now))
    }
}
