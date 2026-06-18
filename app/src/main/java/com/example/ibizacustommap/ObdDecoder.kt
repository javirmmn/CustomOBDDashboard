package com.example.ibizacustommap

object ObdDecoder {

    // Extrae los valores hexadecimales y aplica las matemáticas de telemetría

    fun parseRPM(response: String): Int {
        // Verificamos que la respuesta sea válida y de RPM (41 0C)
        if (!response.startsWith("410C") || response.length < 8) return 0
        val a = response.substring(4, 6).toInt(16)
        val b = response.substring(6, 8).toInt(16)
        return ((a * 256) + b) / 4
    }

    fun parseTemp(response: String, pid: String): Int {
        // Sirve para Intake (0F), Coolant (05) u Oil (5C)
        if (!response.startsWith("41$pid") || response.length < 6) return 0
        val a = response.substring(4, 6).toInt(16)
        return a - 40
    }

    fun parsePercentage(response: String, pid: String): Int {
        // Sirve para Engine Load (04) o Throttle (11)
        if (!response.startsWith("41$pid") || response.length < 6) return 0
        val a = response.substring(4, 6).toInt(16)
        return (a * 100) / 255
    }

    fun parseMAF(response: String): Int {
        // Flujo de masa de aire (01 10)
        if (!response.startsWith("4110") || response.length < 8) return 0
        val a = response.substring(4, 6).toInt(16)
        val b = response.substring(6, 8).toInt(16)
        return ((a * 256) + b) / 100
    }

    fun parseBoost(response: String): Double {
        // El coche da la Presión Absoluta (MAP) en kPa (01 0B).
        if (!response.startsWith("410B") || response.length < 6) return 0.0
        val a = response.substring(4, 6).toInt(16)
        // Le restamos 100 kPa de presión atmosférica para sacar el soplado real del turbo
        val boostKpa = a - 100.0

        // Retornamos en Bares (1 Bar = 100 kPa)
        return boostKpa / 100.0
    }
}