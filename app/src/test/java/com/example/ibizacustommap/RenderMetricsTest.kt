package com.example.ibizacustommap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderMetricsTest {

    private val ms = 1_000_000L

    @Test
    fun mediasP95YFps() {
        val m = RenderMetrics(capacity = 100, summaryEvery = 100)
        var summarized = false
        for (i in 0 until 100) {
            // 30 fps exactos; el frame 99 es lento (20 ms de bloom), el resto 1+2+3 ms.
            val bloom = if (i == 99) 20 * ms else 3 * ms
            summarized = m.record(i * 33_333_333L, 1 * ms, 2 * ms, bloom)
        }
        assertTrue(summarized)
        assertEquals(30f, m.fps, 0.1f)
        assertEquals(1f, m.staticAvg, 1e-4f)
        assertEquals(2f, m.dynamicAvg, 1e-4f)
        assertEquals(3.17f, m.bloomAvg, 1e-3f)
        assertEquals(3f, m.bloomP95, 1e-4f) // un solo frame lento no llega al p95
        assertEquals(6.17f, m.totalAvg, 1e-3f)

        val s = m.summary()
        assertEquals(100, s.frames)
        assertEquals(m.totalP95, s.totalP95Ms, 0f)
    }

    @Test
    fun soloResumeCadaNFramesYGuardaLosUltimos() {
        val m = RenderMetrics(capacity = 10, summaryEvery = 5)
        for (i in 0 until 4) assertFalse(m.record(i * ms, ms, ms, ms))
        assertTrue(m.record(4 * ms, ms, ms, ms))
        // Al llenarse, los nuevos sustituyen a los antiguos.
        for (i in 5 until 25) m.record(i * ms, 10 * ms, 0, 0)
        assertEquals(10f, m.staticAvg, 1e-4f)
        assertEquals(10, m.summary().frames)
    }
}
