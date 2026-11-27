package com.example.ibizacustommap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxBlurTest {

    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF

    @Test
    fun imagenUniformeNoCambia() {
        val w = 20
        val h = 10
        val px = IntArray(w * h) { 0xFF336699.toInt() }
        BoxBlur.blurRgb(px, IntArray(w * h), w, h, radius = 3)
        assertTrue(px.all { it == 0xFF336699.toInt() })
    }

    @Test
    fun negroSigueNegroYOpaco() {
        val w = 16
        val h = 16
        val px = IntArray(w * h) { 0 } // transparente
        BoxBlur.blurRgb(px, IntArray(w * h), w, h, radius = 2)
        assertTrue(px.all { it == 0xFF000000.toInt() })
    }

    @Test
    fun unPuntoBrillanteSeReparteAlrededor() {
        val w = 41
        val h = 41
        val px = IntArray(w * h) { 0xFF000000.toInt() }
        val center = 20 * w + 20
        px[center] = 0xFFFFFFFF.toInt()
        BoxBlur.blurRgb(px, IntArray(w * h), w, h, radius = 2, passes = 2)

        // El centro baja, los vecinos se encienden y lo lejano sigue negro.
        assertTrue(red(px[center]) in 1..254)
        assertTrue(red(px[center + 1]) > 0)
        assertTrue(red(px[center + w]) > 0)
        assertEquals(0, red(px[0]))
        // Simétrico.
        assertEquals(red(px[center - 2]), red(px[center + 2]))
        assertEquals(red(px[center - 2 * w]), red(px[center + 2 * w]))
        // Conserva el color (blanco → gris neutro).
        assertEquals(red(px[center + 1]), green(px[center + 1]))
        assertEquals(red(px[center + 1]), blue(px[center + 1]))
    }

    @Test
    fun radioCeroNoHaceNada() {
        val px = intArrayOf(0xFF102030.toInt(), 0xFF405060.toInt())
        BoxBlur.blurRgb(px, IntArray(2), 2, 1, radius = 0)
        assertEquals(0xFF102030.toInt(), px[0])
        assertEquals(0xFF405060.toInt(), px[1])
    }
}
