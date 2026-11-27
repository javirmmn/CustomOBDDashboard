package com.example.ibizacustommap

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Fuerza G horizontal (lateral + longitudinal) medida con los sensores del
 * móvil. OBD-II no proporciona aceleraciones, así que no puede salir del escáner.
 *
 * Usa la aceleración lineal (sin gravedad) y le quita la componente vertical
 * proyectándola sobre el vector gravedad: el resultado no depende de cómo
 * esté colocado el móvil en el soporte y no se ve afectado por los baches.
 * El móvil debe ir fijo (soporte), no suelto.
 */
class GForceSensor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
    private val linearSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)

    private val gravity = FloatArray(3)
    private var hasGravity = false

    /** G horizontal suavizada; NaN mientras no haya lectura o si el móvil no tiene los sensores. */
    @Volatile
    var currentG: Float = Float.NaN
        private set

    /** Máximo de [currentG] desde que se creó el sensor (la sesión de Android Auto). */
    @Volatile
    var peakG: Float = Float.NaN
        private set

    fun start() {
        if (sensorManager == null || linearSensor == null || gravitySensor == null) return
        sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, linearSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                event.values.copyInto(gravity, 0, 0, 3)
                hasGravity = true
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> if (hasGravity) {
                val g = horizontalG(event.values, gravity)
                val previous = currentG
                val smoothed = if (previous.isNaN()) g else previous + SMOOTHING * (g - previous)
                currentG = smoothed
                if (peakG.isNaN() || smoothed > peakG) peakG = smoothed
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        // ~50 Hz (SENSOR_DELAY_GAME) con 0.1 → constante de tiempo ≈ 0.2 s.
        private const val SMOOTHING = 0.1f
        private const val STANDARD_GRAVITY = 9.80665f

        /** Módulo de la aceleración perpendicular a la gravedad, en g. */
        fun horizontalG(linear: FloatArray, gravity: FloatArray): Float {
            val a2 = linear[0] * linear[0] + linear[1] * linear[1] + linear[2] * linear[2]
            val gNorm = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
            if (gNorm == 0f) return sqrt(a2) / STANDARD_GRAVITY
            val vertical = (linear[0] * gravity[0] + linear[1] * gravity[1] + linear[2] * gravity[2]) / gNorm
            return sqrt((a2 - vertical * vertical).coerceAtLeast(0f)) / STANDARD_GRAVITY
        }
    }
}
