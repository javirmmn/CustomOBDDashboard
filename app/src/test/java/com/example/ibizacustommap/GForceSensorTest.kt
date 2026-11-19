package com.example.ibizacustommap

import org.junit.Assert.assertEquals
import org.junit.Test

class GForceSensorTest {

    private val g = 9.80665f

    @Test
    fun movilPlanoFrenada() {
        // Gravedad en Z; 0.5 g hacia delante en Y.
        assertEquals(0.5f, GForceSensor.horizontalG(floatArrayOf(0f, 0.5f * g, 0f), floatArrayOf(0f, 0f, g)), 1e-4f)
    }

    @Test
    fun baches() {
        // Aceleración solo vertical (bache): no cuenta como G horizontal.
        assertEquals(0f, GForceSensor.horizontalG(floatArrayOf(0f, 0f, 3f), floatArrayOf(0f, 0f, g)), 1e-4f)
    }

    @Test
    fun movilEnSoporteInclinado() {
        // Móvil vertical en el soporte: gravedad en Y. Curva: 0.3 g en X y 0.4 g en Z → 0.5 g.
        val linear = floatArrayOf(0.3f * g, 0f, 0.4f * g)
        assertEquals(0.5f, GForceSensor.horizontalG(linear, floatArrayOf(0f, g, 0f)), 1e-4f)
    }
}
