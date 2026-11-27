package com.example.ibizacustommap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextBufTest {

    private val buf = TextBuf()

    @Test
    fun enterosComoToString() {
        for (v in listOf(0L, 7L, -7L, 10L, 142L, -1000L, 123456789L)) {
            assertEquals(v.toString(), buf.clear().appendInt(v).toString())
        }
    }

    @Test
    fun mismosTextosQueLasVersionesConString() {
        // Valores típicos del salpicadero, incluidos negativos y mitades.
        var v = -30f
        while (v <= 150f) {
            assertEquals("int $v", DashFormat.int(v), DashFormat.int(v, buf).toString())
            assertEquals("1d $v", DashFormat.oneDecimal(v), DashFormat.oneDecimal(v, buf).toString())
            assertEquals("2d $v", DashFormat.twoDecimals(v), DashFormat.twoDecimals(v, buf).toString())
            v += 0.37f
        }
        for (x in floatArrayOf(0.8f, 1.0f, -0.4f, 0.05f, 0.25f, 0.15f, 22.5f, 6.8f, 0.97f)) {
            assertEquals(DashFormat.oneDecimal(x), DashFormat.oneDecimal(x, buf).toString())
            assertEquals(DashFormat.twoDecimals(x), DashFormat.twoDecimals(x, buf).toString())
        }
    }

    @Test
    fun sinDatoSonGuiones() {
        assertEquals("--", DashFormat.int(Float.NaN, buf).toString())
        assertEquals("--", DashFormat.oneDecimal(Float.NaN, buf).toString())
        assertTrue(buf.contentEquals(DashFormat.NO_DATA))
    }

    @Test
    fun encadenaTextoYNumeros() {
        buf.clear().append("peak ").appendFixed(0.9f, 1).append(' ').append("°C")
        assertEquals("peak 0.9 °C", buf.toString())
    }

    @Test
    fun noSePasaDeSuCapacidad() {
        val small = TextBuf(4)
        small.append("123456")
        assertEquals("1234", small.toString())
    }
}
