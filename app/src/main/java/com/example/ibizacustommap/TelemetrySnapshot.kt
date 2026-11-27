package com.example.ibizacustommap

/**
 * Snapshot inmutable de telemetría lista para dibujar. Lo publica ObdPoller
 * (o DashboardSimulator) de golpe y se pinta sin volver a tocar variables
 * mutables compartidas con otro hilo. [link] es el estado de la fuente de
 * datos, que se pinta en la pantalla del coche.
 *
 * Todo valor sin lectura válida es NaN y se pinta como "--".
 */
data class TelemetrySnapshot(
    val speedKmh: Float,
    val gearDisplay: String,
    /** RPM del motor (PID 0C). */
    val rpm: Float,
    val oilTemp: Float,
    val oilTempFromCoolant: Boolean,
    val turboPressure: Float,
    val intakeTemp: Float,
    /** Pedal del acelerador escalado 0-100 % si [throttleIsPedal]; si no, mariposa (PID 11). */
    val throttlePercent: Float,
    val throttleIsPedal: Boolean,
    /** Lambda que pide la ECU (PID 44). La ECU de este coche no da la medida (PID 34). */
    val lambda: Float,
    val engineLoad: Float,
    val gForce: Float,
    /** Avance de encendido en grados antes del PMS (PID 0E). */
    val ignitionTiming: Float,
    val debugRawResponse: String,
    val debugGearRatio: Float,
    val link: LinkState,
    /** Pico de fuerza G de la sesión (sensores del móvil). */
    val gForcePeak: Float = Float.NaN,
    /** Temperatura exterior en °C (PID 46). */
    val ambientTemp: Float = Float.NaN,
    /** Consumo medio estimado en L/100 km (esta ECU no da MAF ni caudal de combustible). */
    val fuelConsumption: Float = Float.NaN,
    /** Autonomía estimada en km. */
    val rangeKm: Float = Float.NaN,
) {
    companion object {
        /** Sin ningún dato: todo "--" y marcha "--". */
        fun empty(link: LinkState) = TelemetrySnapshot(
            speedKmh = Float.NaN,
            gearDisplay = "--",
            rpm = Float.NaN,
            oilTemp = Float.NaN,
            oilTempFromCoolant = false,
            turboPressure = Float.NaN,
            intakeTemp = Float.NaN,
            throttlePercent = Float.NaN,
            throttleIsPedal = true,
            lambda = Float.NaN,
            engineLoad = Float.NaN,
            gForce = Float.NaN,
            ignitionTiming = Float.NaN,
            debugRawResponse = "-",
            debugGearRatio = Float.NaN,
            link = link,
        )
    }
}
