package com.example.ibizacustommap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BloomMathTest {

    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF

    private fun pass(pixel: Int, emissive: Int = 0xFF000000.toInt()): Int {
        val frame = intArrayOf(pixel)
        BloomMath.brightPass(frame, intArrayOf(emissive), 1, threshold = 0.6f, knee = 0.5f, intensity = 0.35f)
        return frame[0]
    }

    @Test
    fun loOscuroNoBrilla() {
        // Gris de panel (#1C1C20) y rojo puro (luminancia ~0.21): por debajo del umbral.
        assertEquals(0xFF000000.toInt(), pass(0xFF1C1C20.toInt()))
        assertEquals(0xFF000000.toInt(), pass(0xFFFF0000.toInt()))
    }

    @Test
    fun elBlancoBrillaConLaIntensidadIndicada() {
        val out = pass(0xFFFFFFFF.toInt())
        // (1 - 0.6) / 1 * 0.35 = 0.14 → ~35 de 255, neutro.
        assertTrue("r=${red(out)}", red(out) in 33..37)
        assertEquals(red(out), green(out))
        assertEquals(red(out), blue(out))
    }

    @Test
    fun rodillaSuaveSinCorteBrusco() {
        // Justo por debajo del umbral ya aporta algo, pero menos que por encima.
        val below = red(pass(0xFF8C8C8C.toInt())) // ~0.55
        val above = red(pass(0xFFB4B4B4.toInt())) // ~0.71
        assertTrue("below=$below", below > 0)
        assertTrue("above=$above below=$below", above > below)
    }

    @Test
    fun laCapaEmisivaSeSumaSinUmbral() {
        val out = pass(0xFF000000.toInt(), emissive = 0xFF801010.toInt())
        assertEquals(0x80, red(out))
        assertEquals(0x10, green(out))
        assertEquals(0x10, blue(out))
    }

    @Test
    fun noSePasaDe255() {
        val out = pass(0xFFFFFFFF.toInt(), emissive = 0xFFFFFFFF.toInt())
        assertEquals(0xFFFFFFFF.toInt(), out)
    }
}
