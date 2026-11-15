package org.projectdrive.app.core.telemetry

import org.junit.Assert.*
import org.junit.Test
import org.projectdrive.app.core.location.LocationFix
import org.projectdrive.app.core.navigation.Coordinate

class SpeedHistoryTest {
    @Test fun tenSecondAggregatesRetainMeanPeakAndExplicitGaps() {
        val history=SpeedHistory()
        history.add(1000,10.0);history.add(9000,20.0);history.add(31000,5.0)
        val points=history.snapshot()
        assertEquals(listOf(0,30),points.map{it.startSeconds})
        assertEquals(SpeedBucket(0,2,15.0,20.0),points.first())
        assertEquals(SpeedBucket(30,1,5.0,5.0),points.last())
        history.add(32000,15.0)
        assertEquals(1,points.last().samples) // Published snapshots are stable.
    }
    @Test fun boundsRejectInvalidValuesAndNeverExceedFourHourWindow() {
        val history=SpeedHistory()
        for(t in 0L..MAX_RECORDING_MILLIS step SPEED_BUCKET_MILLIS)history.add(t,10.0)
        history.add(-1,10.0);history.add(MAX_RECORDING_MILLIS+1,20.0)
        history.add(1000,Double.NaN);history.add(1000,Double.POSITIVE_INFINITY);history.add(1000,-1.0);history.add(1000,101.0)
        assertEquals(MAX_SPEED_BUCKETS,history.snapshot().size)
        assertEquals(14390,history.snapshot().last().startSeconds)
        assertEquals(2,history.snapshot().last().samples)
        assertTrue(history.snapshot().all{it.meanMps==10.0&&it.maxMps==10.0})
    }
    @Test fun chartUsesOnlyFreshPositionAndQualifiedSpeedSamples() {
        val processor=TelemetryProcessor(1000,0)
        fun fix(t:Long,speed:Float=10f,speedAccuracy:Float?=1f,accuracy:Float=5f)=LocationFix(Coordinate(-1.2864,36.82),accuracy,t,speed,speedAccuracy)
        processor.onFix(fix(1000),1000)
        processor.onFix(fix(2000,speedAccuracy=null),2000)
        processor.onFix(fix(3000,accuracy=60f),3000)
        processor.onFix(fix(4000,speed=Float.NaN),4000)
        processor.onFix(fix(5000),30000)
        processor.onFix(fix(32000,speed=20f),32000)
        val result=processor.result(33000)
        assertEquals(listOf(0,30),result.speedBuckets.map{it.startSeconds})
        assertEquals(result.summary.speedSamples,result.speedBuckets.sumOf{it.samples})
        assertEquals(listOf(10.0,20.0),result.speedBuckets.map{it.meanMps})
    }
}
