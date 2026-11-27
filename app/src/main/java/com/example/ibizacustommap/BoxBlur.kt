package com.example.ibizacustommap

/**
 * Desenfoque de caja separable sobre píxeles ARGB (solo RGB; el alfa sale
 * siempre opaco). Varias pasadas se aproximan a un desenfoque gaussiano.
 *
 * Lo usa la capa de bloom del salpicadero: los elementos que "emiten luz" se
 * pintan sobre negro en un bitmap a 1/4 de resolución, se desenfocan con esto
 * y se suman (modo PLUS) encima del frame. El negro no suma nada, así que solo
 * brilla lo emisivo. Es O(ancho × alto) por pasada, sin importar el radio.
 */
object BoxBlur {

    /**
     * Desenfoca [px] (ancho [w], alto [h]) en el sitio. [tmp] es un búfer del
     * mismo tamaño que se reutiliza para no reservar memoria en cada frame.
     */
    fun blurRgb(px: IntArray, tmp: IntArray, w: Int, h: Int, radius: Int, passes: Int = 3) {
        require(px.size >= w * h && tmp.size >= w * h) { "búfer demasiado pequeño" }
        if (radius < 1 || w < 1 || h < 1) return
        repeat(passes) {
            pass(px, tmp, w, h, radius, horizontal = true)
            pass(tmp, px, w, h, radius, horizontal = false)
        }
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val div = 2 * r + 1
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[base + i.coerceIn(0, len - 1) * step]
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (i in 0 until len) {
                dst[base + i * step] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = src[base + (i - r).coerceIn(0, len - 1) * step]
                val inc = src[base + (i + r + 1).coerceIn(0, len - 1) * step]
                sr += ((inc shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((inc shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (inc and 0xFF) - (out and 0xFF)
            }
        }
    }
}
