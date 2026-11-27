package com.example.ibizacustommap

import java.util.Arrays

/** Resumen de rendimiento del renderer: medias y p95 en milisegundos, y FPS reales. */
data class MetricsSummary(
    val fps: Float,
    val totalAvgMs: Float,
    val totalP95Ms: Float,
    val staticAvgMs: Float,
    val staticP95Ms: Float,
    val dynamicAvgMs: Float,
    val dynamicP95Ms: Float,
    val bloomAvgMs: Float,
    val bloomP95Ms: Float,
    val frames: Int,
)

/**
 * Tiempos de los últimos [capacity] frames, en búferes circulares reservados
 * una vez: [record] no crea objetos. Cada [summaryEvery] frames recalcula
 * medias, p95 y FPS en campos primitivos (que el overlay lee sin reservar);
 * solo [summary] crea un objeto, y se llama ~1 vez por segundo.
 *
 * Ojo: con un Canvas de hardware los tiempos son de grabar las órdenes de
 * dibujo en la CPU; la GPU dibuja después, fuera de esta medida.
 */
class RenderMetrics(private val capacity: Int = 120, private val summaryEvery: Int = 30) {

    private val total = FloatArray(capacity)
    private val static = FloatArray(capacity)
    private val dynamic = FloatArray(capacity)
    private val bloom = FloatArray(capacity)
    private val starts = LongArray(capacity)
    private val sorted = FloatArray(capacity)
    private var index = 0
    private var count = 0
    private var sinceSummary = 0

    var fps = 0f; private set
    var totalAvg = 0f; private set
    var totalP95 = 0f; private set
    var staticAvg = 0f; private set
    var staticP95 = 0f; private set
    var dynamicAvg = 0f; private set
    var dynamicP95 = 0f; private set
    var bloomAvg = 0f; private set
    var bloomP95 = 0f; private set

    /** Apunta un frame. Devuelve true si se acaba de recalcular el resumen. */
    fun record(frameStartNs: Long, staticNs: Long, dynamicNs: Long, bloomNs: Long): Boolean {
        static[index] = staticNs / 1e6f
        dynamic[index] = dynamicNs / 1e6f
        bloom[index] = bloomNs / 1e6f
        total[index] = (staticNs + dynamicNs + bloomNs) / 1e6f
        starts[index] = frameStartNs
        index = (index + 1) % capacity
        if (count < capacity) count++
        if (++sinceSummary < summaryEvery) return false
        sinceSummary = 0
        summarize()
        return true
    }

    private fun summarize() {
        totalAvg = avg(total); totalP95 = p95(total)
        staticAvg = avg(static); staticP95 = p95(static)
        dynamicAvg = avg(dynamic); dynamicP95 = p95(dynamic)
        bloomAvg = avg(bloom); bloomP95 = p95(bloom)
        fps = if (count < 2) 0f else {
            val newest = starts[(index - 1 + capacity) % capacity]
            val oldest = starts[(index - count + capacity) % capacity]
            val seconds = (newest - oldest) / 1e9f
            if (seconds > 0f) (count - 1) / seconds else 0f
        }
    }

    private fun avg(a: FloatArray): Float {
        var sum = 0f
        for (i in 0 until count) sum += a[i]
        return if (count == 0) 0f else sum / count
    }

    private fun p95(a: FloatArray): Float {
        if (count == 0) return 0f
        System.arraycopy(a, 0, sorted, 0, count)
        Arrays.sort(sorted, 0, count)
        val i = (Math.ceil(0.95 * count).toInt() - 1).coerceIn(0, count - 1)
        return sorted[i]
    }

    fun summary() = MetricsSummary(
        fps, totalAvg, totalP95, staticAvg, staticP95, dynamicAvg, dynamicP95, bloomAvg, bloomP95, count,
    )
}
