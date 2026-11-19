package com.example.ibizacustommap

import kotlin.math.abs
import kotlin.math.ln

/**
 * Estima la marcha a partir de la relación RPM / velocidad (rpm por km/h),
 * que en cada marcha es constante (desmultiplicación × grupo / rueda).
 *
 * - Elige la marcha cuya relación de referencia está más cerca, solo si
 *   la diferencia es menor que [tolerance]. Si no (embrague pisado, en
 *   pleno cambio, rueda patinando), conserva la marcha anterior.
 * - Una marcha nueva debe detectarse [confirmSamples] veces seguidas antes
 *   de mostrarse: evita saltos momentáneos (p. ej. 2ª→3ª pasando por 5ª).
 * - Si la lectura de RPM o velocidad falla, conserva la marcha anterior.
 */
class GearEstimator(
    private val ratios: FloatArray = DEFAULT_RATIOS,
    private val tolerance: Float = 0.12f,
    private val confirmSamples: Int = 2,
) {
    var current: String = "N"
        private set

    private var candidate: String? = null
    private var candidateCount = 0

    fun update(rpm: Int, speedKmh: Float): String {
        if (rpm < 0 || speedKmh < 0f) return current
        val detected = detect(rpm, speedKmh) ?: return current

        if (detected == current) {
            candidate = null
            candidateCount = 0
        } else {
            if (detected == candidate) candidateCount++ else {
                candidate = detected
                candidateCount = 1
            }
            if (candidateCount >= confirmSamples) {
                current = detected
                candidate = null
                candidateCount = 0
            }
        }
        return current
    }

    /** Marcha que corresponde a una lectura aislada, o null si no encaja con ninguna. */
    fun detect(rpm: Int, speedKmh: Float): String? {
        if (speedKmh < STOPPED_KMH) return "N"
        // Por debajo de este régimen en marcha lo normal es ir embragado o en punto muerto.
        if (rpm < IDLE_MAX_RPM) return null
        val ratio = rpm / speedKmh
        // Distancia relativa (logarítmica) a cada relación de referencia.
        val distances = ratios.map { abs(ln(ratio / it)) }
        val best = distances.indices.minBy { distances[it] }
        return if (distances[best] <= ln(1f + tolerance)) (best + 1).toString() else null
    }

    companion object {
        private const val STOPPED_KMH = 3f
        private const val IDLE_MAX_RPM = 1000

        /**
         * rpm por km/h de 1ª a 5ª. SEAT Ibiza KJ 1.0 Eco TSI 95 CV, manual 5 vel.
         * (ficha técnica oficial SEAT): marchas 3.769 / 1.955 / 1.281 / 0.927 / 0.740,
         * grupo 3.625, 33.6 km/h a 1000 rpm en 4ª y 42.1 en 5ª. Con la rueda que
         * implican esos dos últimos datos: rpm/(km/h) = marcha × grupo × 8.857.
         * El overlay de debug muestra "R:" = rpm/velocidad actual por si hay que
         * afinar (neumáticos distintos, desgaste…).
         */
        val DEFAULT_RATIOS = floatArrayOf(121.0f, 62.8f, 41.1f, 29.8f, 23.8f)
    }
}
