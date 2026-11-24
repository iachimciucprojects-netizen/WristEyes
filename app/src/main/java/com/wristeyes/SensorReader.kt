package com.wristeyes

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reads one value from a watch sensor on demand. */
class SensorReader(context: Context) {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val thread = HandlerThread("wristeyes-sensors").apply { start() }
    private val handler = Handler(thread.looper)

    /** Friendly names the API accepts, mapped to Android sensor types. */
    private val names = mapOf(
        "heart_rate" to Sensor.TYPE_HEART_RATE,
        "steps" to Sensor.TYPE_STEP_COUNTER,
        "pressure" to Sensor.TYPE_PRESSURE,
        "light" to Sensor.TYPE_LIGHT,
        "accelerometer" to Sensor.TYPE_ACCELEROMETER,
        "gyroscope" to Sensor.TYPE_GYROSCOPE,
        "magnetic_field" to Sensor.TYPE_MAGNETIC_FIELD,
        "compass" to Sensor.TYPE_ROTATION_VECTOR,
        "offbody" to Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT,
    )

    fun available(): JSONArray {
        val out = JSONArray()
        for ((name, type) in names) {
            if (sm.getDefaultSensor(type) != null) out.put(name)
        }
        return out
    }

    fun allHardware(): JSONArray {
        val out = JSONArray()
        for (s in sm.getSensorList(Sensor.TYPE_ALL)) {
            out.put(JSONObject().put("name", s.name).put("vendor", s.vendor).put("type", s.stringType))
        }
        return out
    }

    /**
     * Waits for the first usable reading. Heart rate needs a few seconds
     * on the wrist before it reports anything but zero.
     */
    fun read(name: String, timeoutSec: Long): JSONObject {
        val type = names[name] ?: throw IllegalArgumentException("unknown sensor '$name', use one of ${names.keys}")
        val sensor = sm.getDefaultSensor(type) ?: throw IllegalStateException("this watch has no $name sensor")
        val latch = CountDownLatch(1)
        var result: FloatArray? = null
        var accuracy = 0
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (type == Sensor.TYPE_HEART_RATE && (e.values[0] <= 0f || e.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_LOW)) return
                result = e.values.clone()
                accuracy = e.accuracy
                latch.countDown()
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
        try {
            if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
                throw IllegalStateException("no $name reading within $timeoutSec s" +
                    if (type == Sensor.TYPE_HEART_RATE) " (is the watch on the wrist?)" else "")
            }
        } finally {
            sm.unregisterListener(listener)
        }
        val v = result!!
        val out = JSONObject().put("ok", true).put("sensor", name).put("accuracy", accuracy)
        when (type) {
            Sensor.TYPE_HEART_RATE -> out.put("bpm", v[0].toInt())
            Sensor.TYPE_STEP_COUNTER -> out.put("steps_since_boot", v[0].toLong())
            Sensor.TYPE_PRESSURE -> out.put("hpa", round(v[0]))
                .put("altitude_m", round(SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, v[0])))
            Sensor.TYPE_LIGHT -> out.put("lux", round(v[0]))
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> out.put("on_wrist", v[0] > 0.5f)
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rot = FloatArray(9)
                val orient = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rot, v)
                SensorManager.getOrientation(rot, orient)
                val deg = (Math.toDegrees(orient[0].toDouble()) + 360) % 360
                out.put("heading_deg", deg.toInt()).put("direction", direction(deg))
            }
            else -> out.put("x", round(v[0])).put("y", round(v.getOrElse(1) { 0f })).put("z", round(v.getOrElse(2) { 0f }))
        }
        return out
    }

    private fun round(f: Float) = Math.round(f * 100) / 100.0

    private fun direction(deg: Double): String =
        listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg + 22.5) % 360) / 45).toInt()]
}
