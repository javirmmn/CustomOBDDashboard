package com.example.ibizacustommap

import kotlin.math.cos
import kotlin.math.sin

/**
 * Genera los mismos datos que el modo simulación de MainScreen (ondas
 * senoidales), pero como función pura de un "tick", para poder previsualizar
 * el salpicadero fuera de Android Auto (DashboardPreviewActivity, solo debug).
 *
 * Es una COPIA intencionada de esas fórmulas, no una extracción de MainScreen:
 * así un cambio aquí nunca puede afectar al camino real de conexión OBD que ya
 * funciona en el coche. Si algún día se quiere una única fuente de verdad,
 * MainScreen podría pasar a usar este mismo objeto (Fase 2 del plan).
 */
object DashboardSimulator {

    fun at(tick: Float): TelemetrySnapshot {
        val waveSlow = ((sin(tick.toDouble() * 0.5) + 1.0) / 2.0).toFloat()
        val waveFast = ((sin(tick.toDouble() * 1.5) + 1.0) / 2.0).toFloat()
        val waveAFR = ((cos(tick.toDouble() * 0.7) + 1.0) / 2.0).toFloat()

        val speedKmh = 60f + (waveSlow * 90f)
        val gearDisplay = when {
            speedKmh < 3 -> "N"
            speedKmh < 30 -> "1"
            speedKmh < 60 -> "2"
            speedKmh < 90 -> "3"
            speedKmh < 120 -> "4"
            else -> "5"
        }

        return TelemetrySnapshot(
            speedKmh = speedKmh,
            gearDisplay = gearDisplay,
            activeLeds = (waveFast * 24f).toInt(),
            oilTemp = 60f + (waveSlow * 70f),
            oilTempFromCoolant = false,
            turboPressure = -0.2f + (waveFast * 1.8f),
            intakeTemp = 25f + (waveSlow * 40f),
            throttlePercent = waveFast * 100f,
            afr = 10.0f + (waveAFR * 6.5f),
            engineLoad = waveFast * 100f,
            gForce = waveFast * 1.2f,
            maf = 10f + (waveFast * 130f),
            debugRawResponse = "SIM",
            debugMafRaw = "-",
            debugAfrRaw = "-",
            debugGearRatio = Float.NaN,
            modoSimulacion = true,
        )
    }
}
