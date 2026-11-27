package com.example.ibizacustommap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardGeometryTest {

    @Test
    fun escalaDe0a7SinRepetidos() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), RpmScale.labels)
    }

    @Test
    fun arcoEmpiezaAbajoIzquierdaYAcabaAbajoDerecha() {
        assertEquals(135f, RpmScale.angleFor(0f), 0.001f)
        assertEquals(405f, RpmScale.angleFor(7000f), 0.001f)
        // 3500 rpm, justo arriba (270°).
        assertEquals(270f, RpmScale.angleFor(3500f), 0.001f)
    }

    @Test
    fun arcoAcotadoYSinDatoNoSeRellena() {
        assertEquals(0f, RpmScale.sweepFor(-200f), 0.001f)
        assertEquals(270f, RpmScale.sweepFor(9000f), 0.001f)
        assertEquals(0f, RpmScale.sweepFor(Float.NaN), 0.001f)
    }

    @Test
    fun zonaRojaYCorte() {
        assertFalse(RpmScale.isRed(5499f))
        assertTrue(RpmScale.isRed(5500f))
        assertFalse(RpmScale.isAtLimit(5999f))
        assertTrue(RpmScale.isAtLimit(6000f))
        assertFalse(RpmScale.isRed(Float.NaN))
    }

    @Test
    fun numerosConPuntoDecimalNuncaComa() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("es-ES"))
            assertEquals("0.8", DashFormat.oneDecimal(0.8f))
            assertEquals("1.00", DashFormat.twoDecimals(1f))
            assertEquals("-0.4", DashFormat.oneDecimal(-0.4f))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun sinDatoEsGuiones() {
        assertEquals("--", DashFormat.int(Float.NaN))
        assertEquals("--", DashFormat.oneDecimal(Float.NaN))
        assertEquals("--", DashFormat.twoDecimals(Float.NaN))
        assertEquals("87", DashFormat.int(86.6f))
    }

    @Test
    fun barraAcotadaYVaciaSinDato() {
        assertEquals(0.5f, DashFormat.fraction(85f, 40f, 130f), 0.001f)
        assertEquals(0f, DashFormat.fraction(-5f, 0f, 100f), 0.001f)
        assertEquals(1f, DashFormat.fraction(150f, 0f, 100f), 0.001f)
        assertEquals(0f, DashFormat.fraction(Float.NaN, 0f, 100f), 0.001f)
    }
}
