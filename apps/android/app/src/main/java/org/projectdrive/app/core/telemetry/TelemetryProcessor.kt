package org.projectdrive.app.core.telemetry

import org.projectdrive.app.core.location.LocationFix
import kotlin.math.*

const val MAX_RECORDING_MILLIS = 14_400_000L
const val MAX_LOCAL_EVENTS = 100
const val LOCAL_RETENTION_MILLIS = 7 * 86_400_000L
const val UPLOAD_WINDOW_MILLIS = 86_400_000L

data class MotionSample(val kind: String, val elapsedMillis: Long, val x: Float, val y: Float, val z: Float, val accuracy: Int)
data class CandidateEvent(val type: String, val magnitude: Double, val confidence: Double, val accuracyMeters: Double, val elapsedMillis: Long, val occurredAtMillis: Long, val orientation: String)
data class TelemetrySummary(
    val startedAtMillis: Long, val endedAtMillis: Long, val durationSeconds: Double, val coveredSeconds: Double,
    val distanceMeters: Double, val meanSpeedMps: Double, val maxSpeedMps: Double, val speedSamples: Int,
    val gpsSamples: Int, val rejectedGPS: Int, val meanAccuracyMeters: Double, val imuSamples: Int, val rotationSamples: Int,
    val acceleration: Int, val braking: Int, val lateral: Int, val droppedEvents: Int,
    val interrupted: Boolean, val orientation: String, val confidence: Double,
)
data class TelemetryResult(val summary: TelemetrySummary, val events: List<CandidateEvent>)

/** Pure on-device estimator. The last GPS fix and a two-second/120-entry sensor
 * ring live only in RAM. No path, raw sensor array or coordinate is serialized. */
class TelemetryProcessor(private val startedElapsed: Long, private val startedUTC: Long) {
    private var lastFix: LocationFix? = null
    private var lastSeenFix = Long.MIN_VALUE
    private val rawMotion = ArrayDeque<MotionSample>()
    private val lastMotion = mutableMapOf<String, Long>()
    private val events = mutableListOf<CandidateEvent>()
    private val counts = mutableMapOf<String, Int>()
    private val streaks = mutableMapOf<String, Int>()
    private val cooldown = mutableMapOf<String, Long>()
    private var gps = 0; private var rejected = 0; private var imu = 0; private var rotation = 0
    private var accuracyTotal = 0.0
    private var speedCount = 0; private var speedTotal = 0.0; private var maxSpeed = 0.0
    private var distance = 0.0; private var covered = 0.0
    fun onMotion(sample: MotionSample, now: Long) {
        if (sample.kind !in setOf("accelerometer", "rotation", "linear_acceleration") || !sample.x.isFinite() || !sample.y.isFinite() || !sample.z.isFinite() || sample.elapsedMillis < startedElapsed || now - sample.elapsedMillis !in 0..1000) return
        val previous = lastMotion[sample.kind]
        if (previous != null && sample.elapsedMillis - previous < 50) return
        lastMotion[sample.kind] = sample.elapsedMillis
        rawMotion.addLast(sample)
        while (rawMotion.size > 120 || rawMotion.first().elapsedMillis < now - 2000) rawMotion.removeFirst()
        if (sample.kind == "accelerometer") imu++
        if (sample.kind == "rotation") rotation++
    }
    fun onFix(fix: LocationFix, now: Long) {
        if (fix.elapsedRealtimeMillis - startedElapsed !in 0..MAX_RECORDING_MILLIS || fix.elapsedRealtimeMillis <= lastSeenFix) return
        lastSeenFix = fix.elapsedRealtimeMillis
        if (!fix.isFresh(now) || !fix.accuracyMeters.isFinite() || fix.accuracyMeters <= 0 || fix.accuracyMeters > 50) { rejected++; lastFix = null; streaks.clear(); return }
        gps++; accuracyTotal += fix.accuracyMeters
        if (validSpeed(fix, 3.0)) { val speed = checkNotNull(fix.speedMps).toDouble(); speedCount++; speedTotal += speed; maxSpeed = max(maxSpeed, speed) }
        val previous = lastFix; lastFix = fix
        if (previous == null) return
        val dt = (fix.elapsedRealtimeMillis - previous.elapsedRealtimeMillis) / 1000.0
        if (dt !in 0.5..5.0) { streaks.clear(); return }
        val metres = metresBetween(previous, fix)
        if (metres / dt > 100) { lastFix = null; streaks.clear(); rejected++; return }
        covered += dt
        val uncertainty = (previous.accuracyMeters + fix.accuracyMeters).toDouble()
        if (metres > max(3.0, uncertainty / 2) && ((fix.speedMps ?: 0f) > 1 || metres > uncertainty)) distance += metres
        if (dt !in 1.0..3.0 || previous.accuracyMeters > 25 || fix.accuracyMeters > 25 || !validSpeed(previous,2.0) || !validSpeed(fix,2.0)) { streaks.clear(); return }
        val a = (checkNotNull(fix.speedMps) - checkNotNull(previous.speedMps)) / dt
        candidate("acceleration_candidate", a.takeIf { it in 3.0..30.0 }, fix)
        candidate("braking_candidate", (-a).takeIf { it in 3.5..30.0 }, fix)
        val lateral = if (validBearing(previous) && validBearing(fix) && checkNotNull(fix.speedMps) >= 7 && checkNotNull(previous.speedMps) >= 7) {
            val change = abs(((checkNotNull(fix.bearingDegrees) - checkNotNull(previous.bearingDegrees) + 540) % 360) - 180)
            (checkNotNull(fix.speedMps) + checkNotNull(previous.speedMps)) / 2 * Math.toRadians(change.toDouble()) / dt
        } else 0.0
        candidate("lateral_candidate", lateral.takeIf { it in 4.0..30.0 }, fix)
    }
    private fun candidate(type: String, magnitude: Double?, fix: LocationFix) {
        if (magnitude == null) { streaks[type] = 0; return }
        streaks[type] = (streaks[type] ?: 0) + 1
        if (checkNotNull(streaks[type]) < 2 || fix.elapsedRealtimeMillis - (cooldown[type] ?: -10_000_000) < 5000) return
        cooldown[type] = fix.elapsedRealtimeMillis
        counts[type] = (counts[type] ?: 0) + 1
        if (events.size < MAX_LOCAL_EVENTS) {
            val elapsed = fix.elapsedRealtimeMillis - startedElapsed
            val orientation = if (rawMotion.any { it.kind == "rotation" && it.accuracy >= 2 && fix.elapsedRealtimeMillis - it.elapsedMillis in 0..500 }) "available" else "unavailable"
            events += CandidateEvent(type, magnitude, 0.35, fix.accuracyMeters.toDouble(), elapsed, startedUTC + elapsed, orientation)
        }
    }
    fun result(now: Long, interrupted: Boolean = false): TelemetryResult {
        val elapsed = (now - startedElapsed).coerceIn(0, MAX_RECORDING_MILLIS)
        val acceptedEvents = events.filter { it.elapsedMillis <= elapsed }
        val acceleration = counts["acceleration_candidate"] ?: 0; val braking = counts["braking_candidate"] ?: 0; val lateral = counts["lateral_candidate"] ?: 0
        return TelemetryResult(TelemetrySummary(startedUTC, startedUTC + elapsed, elapsed / 1000.0, covered.coerceAtMost(elapsed / 1000.0), distance,
            if (speedCount > 0) speedTotal / speedCount else 0.0, maxSpeed, speedCount, gps, rejected, if (gps > 0) accuracyTotal / gps else 0.0,
            imu, rotation, acceleration, braking, lateral, acceleration + braking + lateral - acceptedEvents.size, interrupted,
            if (rotation > 0) "available" else "unavailable", if (speedCount > 0) 0.35 else 0.0), acceptedEvents)
    }
    fun expireRaw(now: Long) {
        while (rawMotion.isNotEmpty() && rawMotion.first().elapsedMillis < now - 2000) rawMotion.removeFirst()
        if (lastFix?.let { now - it.elapsedRealtimeMillis > 5000 } == true) { lastFix = null; streaks.clear() }
    }
    fun rawBufferSize() = rawMotion.size
    private fun validSpeed(f: LocationFix, accuracy: Double) = f.speedMps?.let { it.isFinite() && it in 0f..100f } == true && f.speedAccuracyMps?.let { it.isFinite() && it > 0 && it <= accuracy } == true
    private fun validBearing(f: LocationFix) = f.bearingDegrees?.let { it.isFinite() && it >= 0 && it < 360 } == true && f.bearingAccuracyDegrees?.let { it.isFinite() && it > 0 && it <= 10 } == true
    private fun metresBetween(a: LocationFix, b: LocationFix): Double {
        val lat1 = Math.toRadians(a.coordinate.latitude); val lat2 = Math.toRadians(b.coordinate.latitude)
        val dlat = lat2-lat1; val dlon = Math.toRadians(b.coordinate.longitude-a.coordinate.longitude)
        val h = sin(dlat/2).pow(2) + cos(lat1)*cos(lat2)*sin(dlon/2).pow(2)
        return 6371000 * 2 * asin(sqrt(h.coerceIn(0.0,1.0)))
    }
}
