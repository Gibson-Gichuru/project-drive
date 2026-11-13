package org.projectdrive.app.core.telemetry

import android.content.Context
import android.hardware.*
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

interface TelemetrySource {
    fun start(onSample: (MotionSample) -> Unit): Set<String>
    fun stop()
}
class AndroidTelemetrySource @Inject constructor(@ApplicationContext context: Context) : TelemetrySource, SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var callback: ((MotionSample) -> Unit)? = null
    private val last = mutableMapOf<Int, Long>()
    private val kinds = mapOf(Sensor.TYPE_ACCELEROMETER to "accelerometer", Sensor.TYPE_ROTATION_VECTOR to "rotation", Sensor.TYPE_LINEAR_ACCELERATION to "linear_acceleration")
    override fun start(onSample: (MotionSample) -> Unit): Set<String> {
        stop(); callback = onSample
        return kinds.mapNotNull { (type,name) ->
            val sensor = manager.getDefaultSensor(type) ?: return@mapNotNull null
            if (manager.registerListener(this, sensor, 50_000)) name else null
        }.toSet()
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return
        val elapsed = event.timestamp / 1_000_000
        if (SystemClock.elapsedRealtime() - elapsed !in 0..1000 || last[event.sensor.type]?.let { elapsed-it < 50 } == true) return
        val kind = kinds[event.sensor.type] ?: return
        last[event.sensor.type] = elapsed
        callback?.invoke(MotionSample(kind,elapsed,event.values[0],event.values[1],event.values[2],event.accuracy))
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }
    override fun stop() { manager.unregisterListener(this); callback = null; last.clear() }
}
