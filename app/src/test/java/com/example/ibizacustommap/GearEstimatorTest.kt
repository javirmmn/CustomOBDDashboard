package com.example.ibizacustommap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GearEstimatorTest {

    /** RPM que corresponde a una relación rpm/(km/h) dada a cierta velocidad. */
    private fun rpm(ratio: Float, speed: Float) = (ratio * speed).toInt()

    @Test
    fun detectaCadaMarchaDeReferencia() {
        val g = GearEstimator()
        GearEstimator.DEFAULT_RATIOS.forEachIndexed { i, ratio ->
            assertEquals((i + 1).toString(), g.detect(rpm(ratio, 60f), 60f))
        }
    }

    @Test
    fun casosObservadosEnElCoche() {
        // Con los umbrales antiguos, la 2ª (62.8) salía como "3" y la 3ª (41.1) como "4".
        val g = GearEstimator()
        assertEquals("2", g.detect(2000, 31.8f)) // 62.9
        assertEquals("3", g.detect(2200, 53.5f)) // 41.1
        // 4ª y 5ª según la ficha de SEAT: 33.6 y 42.1 km/h a 1000 rpm.
        assertEquals("4", g.detect(1000, 33.6f))
        assertEquals("5", g.detect(1000, 42.1f))
        // Neumático un 5 % distinto sigue dando la marcha correcta.
        assertEquals("4", g.detect(1000, 33.6f * 1.05f))
    }

    @Test
    fun paradoEsPuntoMuerto() {
        assertEquals("N", GearEstimator().detect(900, 0f))
    }

    @Test
    fun embragadoOCambiandoNoDecide() {
        val g = GearEstimator()
        assertNull(g.detect(800, 40f)) // ralentí rodando: embrague pisado
        assertNull(g.detect(rpm(90f, 30f), 30f)) // entre 1ª y 2ª: en pleno cambio
    }

    @Test
    fun necesitaDosLecturasSeguidasParaCambiar() {
        val g = GearEstimator()
        assertEquals("N", g.update(rpm(66f, 30f), 30f))
        assertEquals("2", g.update(rpm(66f, 32f), 32f))
    }

    @Test
    fun noSaltaAQuintaAlPasarDeSegundaATercera() {
        val g = GearEstimator()
        repeat(2) { g.update(rpm(66f, 40f), 40f) }
        assertEquals("2", g.current)
        // Durante el cambio las rpm caen un instante (relación de 5ª)...
        assertEquals("2", g.update(rpm(26f, 42f), 42f))
        // ...y enseguida se estabilizan en 3ª.
        g.update(rpm(44f, 42f), 42f)
        assertEquals("3", g.update(rpm(44f, 43f), 43f))
    }

    @Test
    fun lecturaFallidaConservaLaMarcha() {
        val g = GearEstimator()
        repeat(2) { g.update(rpm(44f, 50f), 50f) }
        assertEquals("3", g.update(-1, 50f))
        assertEquals("3", g.update(2200, -1f))
    }
}
