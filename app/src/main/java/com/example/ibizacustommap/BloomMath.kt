package com.example.ibizacustommap

import kotlin.math.max

/**
 * Paso de "brillo" del bloom por umbral (estilo motor de juego), sobre píxeles
 * ARGB a 1/4 de resolución:
 *
 * 1. De la imagen del frame se queda solo lo que supera [threshold] de
 *    luminancia, con una rodilla suave ([knee], fracción del umbral) para que
 *    no haya corte brusco, y lo multiplica por [intensity].
 * 2. Le suma la capa emisiva (halos de neón, halo de la marcha, aguja...), que
 *    brilla siempre, sin umbral.
 *
 * El resultado (opaco) se desenfoca después con [BoxBlur] y se suma al frame.
 * Trabaja en el sitio sobre [frame] y no reserva memoria.
 */
object BloomMath {

    fun brightPass(frame: IntArray, emissive: IntArray, count: Int, threshold: Float, knee: Float, intensity: Float) {
        val k = max(threshold * knee, 1e-4f)
        for (i in 0 until count) {
            val p = frame[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            var soft = (lum - threshold + k).coerceIn(0f, 2f * k)
            soft = soft * soft / (4f * k)
            val contribution = max(soft, lum - threshold) / max(lum, 1e-4f) * intensity

            val e = emissive[i]
            val outR = (r * contribution * 255f).toInt() + ((e shr 16) and 0xFF)
            val outG = (g * contribution * 255f).toInt() + ((e shr 8) and 0xFF)
            val outB = (b * contribution * 255f).toInt() + (e and 0xFF)
            frame[i] = (0xFF shl 24) or (outR.coerceAtMost(255) shl 16) or (outG.coerceAtMost(255) shl 8) or outB.coerceAtMost(255)
        }
    }
}
