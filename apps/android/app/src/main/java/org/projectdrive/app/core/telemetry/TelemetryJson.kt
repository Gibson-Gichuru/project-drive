package org.projectdrive.app.core.telemetry

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

fun TelemetrySummary.toJSON() = JSONObject().put("started_at", Instant.ofEpochMilli(startedAtMillis).toString()).put("ended_at",Instant.ofEpochMilli(endedAtMillis).toString())
    .put("duration_s",durationSeconds).put("covered_s",coveredSeconds).put("distance_m",distanceMeters).put("mean_speed_mps",meanSpeedMps).put("max_speed_mps",maxSpeedMps)
    .put("speed_samples",speedSamples).put("gps_samples",gpsSamples).put("rejected_gps_samples",rejectedGPS).put("mean_accuracy_meters",meanAccuracyMeters).put("imu_samples",imuSamples).put("rotation_samples",rotationSamples)
    .put("acceleration_candidates",acceleration).put("braking_candidates",braking).put("lateral_candidates",lateral).put("dropped_events",droppedEvents).put("interrupted",interrupted)
    .put("source","phone_gps_imu_v1").put("orientation",orientation).put("calibration","uncalibrated").put("confidence",confidence)
fun TelemetryResult.eventsJSON() = JSONArray().also { array -> events.forEach { e -> array.put(JSONObject().put("type",e.type).put("magnitude",e.magnitude).put("confidence",e.confidence)
    .put("source","gps_delta").put("accuracy_meters",e.accuracyMeters).put("elapsed_ms",e.elapsedMillis).put("occurred_at",Instant.ofEpochMilli(e.occurredAtMillis).toString()).put("orientation",e.orientation).put("calibration","uncalibrated")) } }
fun summaryFromJSON(s: JSONObject) = TelemetrySummary(Instant.parse(s.getString("started_at")).toEpochMilli(),Instant.parse(s.getString("ended_at")).toEpochMilli(),s.getDouble("duration_s"),s.getDouble("covered_s"),s.getDouble("distance_m"),s.getDouble("mean_speed_mps"),s.getDouble("max_speed_mps"),s.getInt("speed_samples"),s.getInt("gps_samples"),s.getInt("rejected_gps_samples"),s.getDouble("mean_accuracy_meters"),s.getInt("imu_samples"),s.getInt("rotation_samples"),s.getInt("acceleration_candidates"),s.getInt("braking_candidates"),s.getInt("lateral_candidates"),s.getInt("dropped_events"),s.getBoolean("interrupted"),s.getString("orientation"),s.getDouble("confidence"))
