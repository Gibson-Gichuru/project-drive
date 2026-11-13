package org.projectdrive.app.core.telemetry

import org.junit.Assert.*
import org.junit.Test
import org.projectdrive.app.core.location.LocationFix
import org.projectdrive.app.core.navigation.Coordinate

class TelemetryProcessorTest {
    private fun fix(t: Long,speed: Float=10f,bearing: Float=0f,lat: Double=-1.2,accuracy: Float=10f,speedAccuracy: Float?=1f,bearingAccuracy: Float?=5f)=LocationFix(Coordinate(lat,36.8),accuracy,t,speed,speedAccuracy,bearing,bearingAccuracy)
    @Test fun speedDistanceCoverageAndUTCUseMonotonicIntervals() {
        val p=TelemetryProcessor(1000,100000)
        p.onFix(fix(1000,5f),1000);p.onFix(fix(2000,9f,lat=-1.1999),2000);p.onFix(fix(3000,13f,lat=-1.1998),3000)
        val r=p.result(4000)
        assertEquals(3.0,r.summary.durationSeconds,0.0);assertEquals(2.0,r.summary.coveredSeconds,0.0)
        assertEquals(22.24,r.summary.distanceMeters,0.1);assertEquals(9.0,r.summary.meanSpeedMps,0.001)
        assertEquals(1,r.summary.acceleration);assertEquals(102000L,r.events.single().occurredAtMillis)
    }
    @Test fun rejectsPoorUnknownAccuracyOldFixesAndTeleportation() {
        val p=TelemetryProcessor(1000,0)
        p.onFix(fix(1000),1000);p.onFix(fix(2000,lat=40.0),2000)
        p.onFix(fix(3000,accuracy=500f),3000);p.onFix(fix(4000,accuracy=Float.NaN),4000)
        p.onFix(fix(4001),30000)
        val r=p.result(30000);assertEquals(0.0,r.summary.distanceMeters,0.0);assertEquals(4,r.summary.rejectedGPS);assertTrue(r.events.isEmpty())
    }
    @Test fun stationaryJitterDoesNotAccumulateDistance() {
        val p=TelemetryProcessor(0,0)
        for(t in 1L..30)p.onFix(fix(t*1000,0f,lat=-1.2+(t%2)*0.00001),t*1000)
        assertEquals(0.0,p.result(30000).summary.distanceMeters,0.0)
    }
    @Test fun gapsNeverBridgeDistanceOrCreateSpeedCandidates() {
        val p=TelemetryProcessor(0,0);p.onFix(fix(1000,0f),1000);p.expireRaw(7000)
        p.onFix(fix(8000,30f,lat=-1.19),8000)
        val r=p.result(9000);assertEquals(0.0,r.summary.distanceMeters,0.0);assertTrue(r.events.isEmpty())
    }
    @Test fun missingSpeedAccuracySuppressesCandidatesAndMisleadingSpeed() {
        val p=TelemetryProcessor(0,0)
        for(i in 1L..10)p.onFix(fix(i*1000,i*5f,speedAccuracy=null),i*1000)
        val r=p.result(10000);assertEquals(0,r.summary.speedSamples);assertTrue(r.events.isEmpty())
    }
    @Test fun bearingWrapDoesNotLookLikeSevereLateralMotion() {
        val p=TelemetryProcessor(0,0)
        p.onFix(fix(1000,bearing=359f),1000);p.onFix(fix(2000,bearing=1f),2000);p.onFix(fix(3000,bearing=2f),3000)
        assertEquals(0,p.result(3000).summary.lateral)
    }
    @Test fun sustainedBrakingAndLateralChangesAreOnlyCandidates() {
        val p=TelemetryProcessor(0,0)
        p.onFix(fix(1000,30f,0f),1000);p.onFix(fix(2000,25f,15f),2000);p.onFix(fix(3000,20f,30f),3000)
        val r=p.result(3000);assertEquals(1,r.summary.braking);assertEquals(1,r.summary.lateral)
        assertTrue(r.events.all { it.confidence<=0.6 && it.orientation=="unavailable" })
    }
    @Test fun sensorBufferIsBoundedAndExpiresAndMissingSensorsAreSafe() {
        val p=TelemetryProcessor(0,0)
        for(t in 0L..1000)p.onMotion(MotionSample("accelerometer",t*50,0f,0f,9.8f,3),t*50)
        assertTrue(p.rawBufferSize()<=41);p.expireRaw(53000);assertEquals(0,p.rawBufferSize())
        assertEquals(0,p.result(53000).summary.rotationSamples)
        val empty=TelemetryProcessor(0,0).result(1000,true);assertTrue(empty.summary.interrupted);assertEquals(0.0,empty.summary.confidence,0.0)
    }
    @Test fun eventDetailsRemainBoundedAndCountsExplainDroppedDetails() {
        val p=TelemetryProcessor(0,0)
        for(t in 0L..1500) {val speed=if(t%8<4)(t%8)*5f else (7-t%8)*5f;p.onFix(fix(t*1000,speed),t*1000)}
        val r=p.result(1500000);assertEquals(100,r.events.size);assertTrue(r.summary.droppedEvents>0)
        assertEquals(r.summary.acceleration+r.summary.braking+r.summary.lateral,r.events.size+r.summary.droppedEvents)
    }
}
