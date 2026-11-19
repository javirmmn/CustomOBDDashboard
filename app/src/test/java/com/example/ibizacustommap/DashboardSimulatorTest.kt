package com.example.ibizacustommap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardSimulatorTest {

    @Test
    fun siempreMarcaModoSimulacion() {
        assertEquals(LinkState.SIMULATION, DashboardSimulator.at(0f).link)
        assertEquals(LinkState.SIMULATION, DashboardSimulator.at(37.2f).link)
    }

    @Test
    fun valoresDentroDeRangoFisicoRazonable() {
        for (tick in 0..200) {
            val s = DashboardSimulator.at(tick * 0.1f)
            assertTrue("speedKmh=${s.speedKmh}", s.speedKmh in 0f..200f)
            assertTrue("oilTemp=${s.oilTemp}", s.oilTemp in 0f..150f)
            assertTrue("throttlePercent=${s.throttlePercent}", s.throttlePercent in 0f..100f)
            assertTrue("engineLoad=${s.engineLoad}", s.engineLoad in 0f..100f)
            assertTrue("activeLeds=${s.activeLeds}", s.activeLeds in 0..24)
            assertTrue("afr=${s.afr}", s.afr in 0f..20f)
            assertTrue(listOf("N", "1", "2", "3", "4", "5").contains(s.gearDisplay))
        }
    }

    @Test
    fun nuncaDevuelveNanEnLosCamposQueRenderizaComoNumero() {
        val s = DashboardSimulator.at(12.3f)
        assertTrue(!s.speedKmh.isNaN())
        assertTrue(!s.oilTemp.isNaN())
        assertTrue(!s.turboPressure.isNaN())
        assertTrue(!s.intakeTemp.isNaN())
        assertTrue(!s.throttlePercent.isNaN())
        assertTrue(!s.afr.isNaN())
        assertTrue(!s.engineLoad.isNaN())
        assertTrue(!s.gForce.isNaN())
        assertTrue(!s.maf.isNaN())
    }
}
