package org.projectdrive.app.core.telemetry

/** Derived local-only averages/peaks, not a raw GPS speed or location series. */
const val SPEED_BUCKET_MILLIS = 10_000L
const val MAX_SPEED_BUCKETS = 1440
data class SpeedBucket(val startSeconds: Int, val samples: Int, val meanMps: Double, val maxMps: Double)

class SpeedHistory {
    private data class Bucket(var samples:Int=0,var total:Double=0.0,var max:Double=0.0)
    private val buckets=sortedMapOf<Int,Bucket>()
    fun add(elapsedMillis:Long,speedMps:Double) {
        if(elapsedMillis !in 0..MAX_RECORDING_MILLIS || !speedMps.isFinite() || speedMps !in 0.0..100.0)return
        val index=(elapsedMillis/SPEED_BUCKET_MILLIS).toInt().coerceAtMost(MAX_SPEED_BUCKETS-1)
        val bucket=buckets.getOrPut(index){Bucket()}
        bucket.samples++;bucket.total+=speedMps;bucket.max=maxOf(bucket.max,speedMps)
    }
    fun snapshot()=buckets.map { (index,b) -> SpeedBucket(index*10,b.samples,b.total/b.samples,b.max) }
}
