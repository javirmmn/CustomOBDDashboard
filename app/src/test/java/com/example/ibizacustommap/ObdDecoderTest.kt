package com.example.ibizacustommap

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests de caracterización de ObdDecoder: fijan el comportamiento ACTUAL con
 * respuestas de ejemplo del estándar SAE J1979 (ya limpias, como las deja
 * ObdManager.readResponse). Los marcados con (M2) documentan defectos
 * conocidos de la auditoría y cambiarán en la Fase 1.
 */
class ObdDecoderTest {

    @Test
    fun rpm() {
        assertEquals(1726, ObdDecoder.parseRPM("410C1AF8")) // (0x1A*256 + 0xF8) / 4
        assertEquals(0, ObdDecoder.parseRPM("410C0000"))
    }

    @Test
    fun rpmRechazaRespuestasInvalidas() {
        assertEquals(-1, ObdDecoder.parseRPM("410C"))
        assertEquals(-1, ObdDecoder.parseRPM("NODATA"))
        assertEquals(-1, ObdDecoder.parseRPM("TIMEOUT"))
        assertEquals(-1, ObdDecoder.parseRPM("410D32")) // respuesta de otro PID (desincronización)
        assertEquals(-1, ObdDecoder.parseRPM("010C410C1AF8")) // eco sin desactivar (ATE0 fallido)
    }

    @Test
    fun rpmConVariasEcusTomaLaPrimera() {
        assertEquals(1726, ObdDecoder.parseRPM("410C1AF8410C1AF8"))
    }

    @Test
    fun velocidad() {
        assertEquals(50, ObdDecoder.parseSpeed("410D32"))
        assertEquals(-1, ObdDecoder.parseSpeed("410D"))
    }

    @Test
    fun temperaturas() {
        assertEquals(83, ObdDecoder.parseTemp("41057B", "05"))
        assertEquals(-40, ObdDecoder.parseTemp("410F00", "0F"))
        assertEquals(-999, ObdDecoder.parseTemp("41057B", "5C")) // el PID no coincide
    }

    @Test
    fun porcentajes() {
        assertEquals(100, ObdDecoder.parsePercentage("4111FF", "11"))
        assertEquals(50, ObdDecoder.parsePercentage("411180", "11")) // 128*100/255 = 50.2
        assertEquals(-1, ObdDecoder.parsePercentage("4104", "04"))
    }

    @Test
    fun mafConDecimales() {
        assertEquals(4.0, ObdDecoder.parseMAF("41100190"), 1e-9) // 400 / 100
        assertEquals(3.5, ObdDecoder.parseMAF("4110015E"), 1e-9) // antes se truncaba a 3
        assertEquals(-1.0, ObdDecoder.parseMAF("NODATA"), 1e-9)
    }

    @Test
    fun boostConPresionAtmosfericaFija_M1() {
        assertEquals(0.0, ObdDecoder.parseBoost("410B64"), 1e-9) // 100 kPa
        assertEquals(1.0, ObdDecoder.parseBoost("410BC8"), 1e-9) // 200 kPa
        assertEquals(-0.7, ObdDecoder.parseBoost("410B1E"), 1e-9) // 30 kPa (vacío al ralentí)
    }

    @Test
    fun afr() {
        assertEquals(14.7, ObdDecoder.parseAFR("41448000", "44"), 1e-9) // lambda 1.0
        assertEquals(14.7, ObdDecoder.parseAFR("413480000080", "34"), 1e-9)
        assertEquals(-1.0, ObdDecoder.parseAFR("41448000", "34"), 1e-9)
    }

    @Test
    fun afrSaturadoEnCorteDeInyeccion() {
        // 0xFFFF → lambda ≈ 2.0 → 29.4: el "29.algo" observado al conducir.
        assertEquals(29.4, ObdDecoder.parseAFR("4144FFFF", "44"), 0.01)
    }

    @Test
    fun mapYLambdaConDatosDelCocheReal() {
        // Respuestas del diagnóstico real: ralentí, MAP 0x45 = 69 kPa, lambda pedida 1.0.
        assertEquals(69, ObdDecoder.parseMap("410B45"))
        assertEquals(-1, ObdDecoder.parseMap("NODATA"))
        assertEquals(1.0, ObdDecoder.parseLambda("41448000"), 1e-9)
        assertEquals(2.0, ObdDecoder.parseLambda("4144FFFF"), 0.001) // corte de inyección
        assertEquals(-1.0, ObdDecoder.parseLambda(""), 1e-9)
        assertEquals(101, ObdDecoder.parseBarometricPressure("413365"))
        assertEquals(-21, ObdDecoder.parseTimingAdvance("410E56"))
    }

    @Test
    fun parsersSinUsoTodavia() {
        assertEquals(0, ObdDecoder.parseTimingAdvance("410E80"))
        assertEquals(95, ObdDecoder.parseBarometricPressure("41335F"))
        assertEquals(50, ObdDecoder.parseFuelLevel("412F80"))
        assertEquals(258, ObdDecoder.parseRunTime("411F0102"))
    }
}
