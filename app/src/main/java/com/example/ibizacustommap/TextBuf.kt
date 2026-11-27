package com.example.ibizacustommap

import kotlin.math.abs

/**
 * Texto reutilizable sobre un CharArray, para formatear números en cada frame
 * sin crear Strings. Se dibuja con `Canvas.drawText(chars, 0, length, ...)`.
 */
class TextBuf(capacity: Int = 48) {
    val chars = CharArray(capacity)
    var length = 0
        private set

    fun clear(): TextBuf {
        length = 0
        return this
    }

    fun append(c: Char): TextBuf {
        if (length < chars.size) chars[length++] = c
        return this
    }

    fun append(s: String): TextBuf {
        for (i in s.indices) append(s[i])
        return this
    }

    fun append(other: TextBuf): TextBuf {
        for (i in 0 until other.length) append(other.chars[i])
        return this
    }

    fun appendInt(v: Long): TextBuf {
        if (v < 0) append('-')
        var n = abs(v)
        val start = length
        do {
            append('0' + (n % 10).toInt())
            n /= 10
        } while (n > 0)
        // Los dígitos han salido al revés.
        var i = start
        var j = length - 1
        while (i < j) {
            val tmp = chars[i]; chars[i] = chars[j]; chars[j] = tmp
            i++; j--
        }
        return this
    }

    /**
     * [v] con [decimals] decimales y punto (redondeo al más cercano, mitades
     * hacia arriba). Igual que `String.format(Locale.ROOT, "%.Nf")`, incluido
     * el "-0.0" de los negativos muy pequeños.
     */
    fun appendFixed(v: Float, decimals: Int): TextBuf {
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val scaled = Math.round(abs(v.toDouble()) * scale)
        if (v < 0) append('-')
        appendInt(scaled / scale)
        if (decimals > 0) {
            append('.')
            val frac = scaled % scale
            var div = scale / 10
            while (div > 0) {
                append('0' + ((frac / div) % 10).toInt())
                div /= 10
            }
        }
        return this
    }

    fun contentEquals(s: String): Boolean {
        if (s.length != length) return false
        for (i in 0 until length) if (chars[i] != s[i]) return false
        return true
    }

    override fun toString() = String(chars, 0, length)
}
