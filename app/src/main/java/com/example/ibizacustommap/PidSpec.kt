package com.example.ibizacustommap

import java.util.Locale

/** Tabla de los PIDs de modo 01 que usa la app y utilidades para pedirlos en grupo. */
object PidSpec {

    const val RPM = 0x0C
    const val SPEED = 0x0D
    const val MAP = 0x0B
    const val THROTTLE = 0x11
    const val PEDAL_D = 0x49
    const val PEDAL_E = 0x4A
    const val LOAD = 0x04
    const val TIMING = 0x0E
    const val LAMBDA_CMD = 0x44
    const val COOLANT = 0x05
    const val OIL = 0x5C
    const val INTAKE = 0x0F
    const val BARO = 0x33

    /** Bytes de datos de cada PID (sin contar el propio PID). */
    val DATA_BYTES: Map<Int, Int> = mapOf(
        RPM to 2, SPEED to 1, MAP to 1, THROTTLE to 1, PEDAL_D to 1, PEDAL_E to 1,
        LOAD to 1, TIMING to 1, LAMBDA_CMD to 2, COOLANT to 1, OIL to 1, INTAKE to 1, BARO to 1,
    )

    /**
     * Tramas CAN (ISO-TP) que ocupa la respuesta a pedir [pids] juntos:
     * "41" + (PID + datos) por cada uno. Una trama simple lleva hasta 7 bytes;
     * si no cabe, la primera lleva 6 y cada consecutiva 7.
     */
    fun responseFrames(pids: List<Int>): Int {
        val payload = 1 + pids.sumOf { 1 + DATA_BYTES.getValue(it) }
        return if (payload <= 7) 1 else 1 + (payload - 6 + 6) / 7
    }

    /**
     * Comando de modo 01 para [pids]. Con [counted] añade el nº de tramas
     * esperadas: el ELM327 contesta en cuanto las recibe, sin esperar a más
     * ECUs. (El diagnóstico real mostró que cuenta TRAMAS: "010C0D0B1" corta
     * la respuesta de 2 tramas.)
     */
    fun command(pids: List<Int>, counted: Boolean): String {
        val base = "01" + pids.joinToString("") { "%02X".format(Locale.ROOT, it) }
        return if (counted) base + responseFrames(pids) else base
    }
}

/**
 * Escala el pedal del acelerador (PID 49/4A) a 0–100 %. El sensor no va de 0 a
 * 100: en reposo da ~14 % y a fondo ~80 % (varía según coche). Se parte de esos
 * valores y se ajusta solo con lo que se observa: el mínimo baja si se ve un
 * reposo menor y el máximo sube si se pisa más a fondo.
 */
class PedalScaler(private var low: Float = DEFAULT_LOW, private var high: Float = DEFAULT_HIGH) {

    fun scale(raw: Float): Float {
        if (raw.isNaN()) return Float.NaN
        if (raw >= MIN_PLAUSIBLE) low = minOf(low, raw)
        high = maxOf(high, raw)
        return ((raw - low) / (high - low) * 100f).coerceIn(0f, 100f)
    }

    companion object {
        const val DEFAULT_LOW = 14f
        const val DEFAULT_HIGH = 80f
        /** Por debajo de esto es una lectura anómala, no el reposo real. */
        private const val MIN_PLAUSIBLE = 5f
    }
}
