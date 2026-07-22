package com.example.ibizacustommap

object ObdDecoder {

    fun parseRPM(response: String): Int {
        if (!response.startsWith("410C") || response.length < 8) return -1
        return try {
            val a = response.substring(4, 6).toInt(16)
            val b = response.substring(6, 8).toInt(16)
            ((a * 256) + b) / 4
        } catch (e: Exception) { -1 }
    }

    fun parseTemp(response: String, pid: String): Int {
        if (!response.startsWith("41$pid") || response.length < 6) return -999
        return try {
            val a = response.substring(4, 6).toInt(16)
            a - 40
        } catch (e: Exception) { -999 }
    }

    fun parsePercentage(response: String, pid: String): Int {
        if (!response.startsWith("41$pid") || response.length < 6) return -1
        return try {
            val a = response.substring(4, 6).toInt(16)
            (a * 100) / 255
        } catch (e: Exception) { -1 }
    }

    fun parseMAF(response: String): Int {
        if (!response.startsWith("4110") || response.length < 8) return -1
        return try {
            val a = response.substring(4, 6).toInt(16)
            val b = response.substring(6, 8).toInt(16)
            ((a * 256) + b) / 100
        } catch (e: Exception) { -1 }
    }

    fun parseBoost(response: String): Double {
        if (!response.startsWith("410B") || response.length < 6) return -999.0
        return try {
            val a = response.substring(4, 6).toInt(16)
            // Aproximación: asume presión atmosférica de referencia = 100 kPa.
            // No compensa altitud/meteorología real.
            val boostKpa = a - 100.0
            boostKpa / 100.0
        } catch (e: Exception) { -999.0 }
    }

    fun parseSpeed(response: String): Int {
        if (!response.startsWith("410D") || response.length < 6) return -1
        return try {
            response.substring(4, 6).toInt(16)
        } catch (e: Exception) { -1 }
    }

    fun parseAFR(response: String): Double {
        if (!response.startsWith("4144") || response.length < 8) return -1.0
        return try {
            val a = response.substring(4, 6).toInt(16)
            val b = response.substring(6, 8).toInt(16)
            val ratio = ((a * 256) + b) / 32768.0
            ratio * 14.7
        } catch (e: Exception) { -1.0 }
    }
}