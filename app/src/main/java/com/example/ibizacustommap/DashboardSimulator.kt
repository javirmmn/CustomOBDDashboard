package com.example.ibizacustommap

import kotlin.math.cos
import kotlin.math.sin

/**
 * Datos simulados (ondas senoidales) como función pura de un "tick". Es la
 * ÚNICA fuente de simulación: la usan la vista previa del móvil
 * (DashboardPreviewActivity) y la pantalla del coche (MainScreen) cuando se
 * activa la simulación desde la app del móvil. Nunca se activa sola.
 *
 * Las RPM salen de la velocidad y la marcha con las relaciones reales de la
 * caja, así que la marcha, la velocidad y el arco son coherentes entre sí.
 */
object DashboardSimulator {

    /** rpm por km/h en cada marcha (mismas relaciones que GearEstimator). */
    private val RPM_PER_KMH = floatArrayOf(121.0f, 62.8f, 41.1f, 29.8f, 23.8f)

    /** Velocidad a la que se sube de marcha: justo antes del corte, para que se vea la zona roja. */
    private val UPSHIFT_KMH = floatArrayOf(50f, 97f, 148f, 205f)

    fun at(tick: Float): TelemetrySnapshot {
        val waveSlow = ((sin(tick.toDouble() * 0.5) + 1.0) / 2.0).toFloat()
        val waveFast = ((sin(tick.toDouble() * 1.5) + 1.0) / 2.0).toFloat()
        val waveAFR = ((cos(tick.toDouble() * 0.7) + 1.0) / 2.0).toFloat()

        val speedKmh = 60f + (waveSlow * 90f)
        val gearIndex = UPSHIFT_KMH.indexOfFirst { speedKmh < it }.let { if (it < 0) 4 else it }
        val rpm = (speedKmh * RPM_PER_KMH[gearIndex]).coerceAtMost(6400f)

        return TelemetrySnapshot(
            speedKmh = speedKmh,
            gearDisplay = (gearIndex + 1).toString(),
            rpm = rpm,
            oilTemp = 60f + (waveSlow * 50f),
            oilTempFromCoolant = true,
            turboPressure = -0.4f + (waveFast * 1.4f),
            intakeTemp = 25f + (waveSlow * 30f),
            throttlePercent = waveFast * 100f,
            throttleIsPedal = true,
            lambda = 0.85f + (waveAFR * 0.2f),
            engineLoad = waveFast * 100f,
            gForce = waveFast * 0.9f,
            ignitionTiming = 35f - (waveFast * 30f),
            debugRawResponse = "SIM",
            debugGearRatio = Float.NaN,
            link = LinkState.SIMULATION,
            gForcePeak = 0.9f,
            ambientTemp = 22.5f,
            fuelConsumption = 5.5f + waveFast * 3f,
            rangeKm = 410f,
        )
    }
}
