package com.example.ibizacustommap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdDiagnosticsTest {

    @Test
    fun mapaDePidsSoportados() {
        // Ejemplo clásico: BE 3F A8 13
        val perEcu = ObdDiagnostics.parseSupportedPids("4100BE3FA813", 0x00)
        assertEquals(1, perEcu.size)
        assertEquals(
            setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
                0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20),
            perEcu[0],
        )
    }

    @Test
    fun mapaConVariasEcusYBaseDistinta() {
        val perEcu = ObdDiagnostics.parseSupportedPids("SEARCHING...412080000001412000000000", 0x20)
        assertEquals(2, perEcu.size)
        assertEquals(setOf(0x21, 0x40), perEcu[0])
        assertTrue(perEcu[1].isEmpty())
    }

    @Test
    fun mapaInvalido() {
        assertTrue(ObdDiagnostics.parseSupportedPids("NODATA", 0x00).isEmpty())
        assertTrue(ObdDiagnostics.parseSupportedPids("UNABLETOCONNECT", 0x00).isEmpty())
        assertTrue(ObdDiagnostics.parseSupportedPids("4100BE3F", 0x00).isEmpty())
    }

    @Test
    fun protocolo() {
        assertEquals("automático: ISO 15765-4 CAN (11 bit, 500 kbaud)", ObdDiagnostics.describeProtocol("A6"))
        assertEquals("ISO 14230-4 KWP (fast init)", ObdDiagnostics.describeProtocol("5"))
        assertEquals("automático: SAE J1939 CAN", ObdDiagnostics.describeProtocol("AA"))
        assertEquals("desconocido", ObdDiagnostics.describeProtocol("?"))
        assertEquals("desconocido", ObdDiagnostics.describeProtocol("TIMEOUT"))
    }
}
