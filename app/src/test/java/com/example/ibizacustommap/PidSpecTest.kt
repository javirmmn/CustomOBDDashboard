package com.example.ibizacustommap

import org.junit.Assert.assertEquals
import org.junit.Test

class PidSpecTest {

    @Test
    fun tramasDeLaRespuesta() {
        // 41 0C AA BB 0D CC = 6 bytes → 1 trama
        assertEquals(1, PidSpec.responseFrames(listOf(PidSpec.RPM, PidSpec.SPEED)))
        // + 0B DD = 8 bytes → 2 tramas (como en el coche: "0080:...1:...")
        assertEquals(2, PidSpec.responseFrames(listOf(PidSpec.RPM, PidSpec.SPEED, PidSpec.MAP)))
        // pedal, carga, avance, lambda: 1 + 2+2+2+3 = 10 bytes → 2 tramas
        assertEquals(2, PidSpec.responseFrames(listOf(PidSpec.PEDAL_D, PidSpec.LOAD, PidSpec.TIMING, PidSpec.LAMBDA_CMD)))
        assertEquals(1, PidSpec.responseFrames(listOf(PidSpec.BARO)))
    }

    @Test
    fun comandos() {
        assertEquals("010C0D0B", PidSpec.command(listOf(0x0C, 0x0D, 0x0B), counted = false))
        assertEquals("010C0D0B2", PidSpec.command(listOf(0x0C, 0x0D, 0x0B), counted = true))
        assertEquals("010C0D1", PidSpec.command(listOf(0x0C, 0x0D), counted = true))
        // Todo lo que genera es de solo lectura.
        assertEquals(true, ObdDiagnostics.isReadOnly(PidSpec.command(listOf(0x49, 0x04, 0x0E, 0x44), true)))
    }

    @Test
    fun pedalEscaladoYAprendido() {
        val p = PedalScaler()
        assertEquals(0f, p.scale(14f), 0.01f) // reposo típico
        assertEquals(100f, p.scale(80f), 0.01f) // a fondo típico
        assertEquals(50f, p.scale(47f), 0.01f)
        // En el coche real el reposo dio 0x23 = 13 %: pasa a ser el nuevo 0 %.
        assertEquals(0f, p.scale(13f), 0.01f)
        // Si se pisa más que 80 %, ese pasa a ser el 100 %.
        assertEquals(100f, p.scale(86f), 0.01f)
        assertEquals(100f, p.scale(86f), 0.01f)
        // Una lectura anómala (0) no mueve el reposo.
        assertEquals(0f, p.scale(0f), 0.01f)
        assertEquals(0f, p.scale(13f), 0.01f)
    }
}
