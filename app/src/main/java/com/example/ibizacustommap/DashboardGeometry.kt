package com.example.ibizacustommap

import java.util.Locale

/**
 * Escala del cuentarrevoluciones: arco de 270° que empieza abajo a la
 * izquierda (135°, sentido horario como en Canvas) y termina abajo a la
 * derecha. 0 a 7 (x1000 rpm), zona roja desde [RED_RPM].
 *
 * 1.0 TSI 95 CV: potencia máxima entre 5000 y 5500 rpm.
 */
object RpmScale {
    const val MAX_RPM = 7000f
    const val RED_RPM = 5500f
    /** Desde aquí todo el arco parpadea en rojo: hay que cambiar ya. */
    const val LIMIT_RPM = 6000f
    const val START_DEG = 135f
    const val SWEEP_DEG = 270f

    /** Números de la escala (x1000 rpm). */
    val labels: List<Int> = (0..(MAX_RPM / 1000f).toInt()).toList()

    /** Grados recorridos desde el 0 hasta [rpm] (acotado a la escala). */
    fun sweepFor(rpm: Float): Float =
        if (rpm.isNaN()) 0f else SWEEP_DEG * (rpm.coerceIn(0f, MAX_RPM) / MAX_RPM)

    /** Ángulo de Canvas (grados, horario desde las 3 en punto) de [rpm]. */
    fun angleFor(rpm: Float): Float = START_DEG + sweepFor(rpm)

    fun isRed(rpm: Float) = !rpm.isNaN() && rpm >= RED_RPM
    fun isAtLimit(rpm: Float) = !rpm.isNaN() && rpm >= LIMIT_RPM
}

/** Formato de los números del salpicadero: siempre punto decimal (estilo MoTeC), nunca coma. */
object DashFormat {
    fun int(v: Float): String = if (v.isNaN()) NO_DATA else Math.round(v).toString()
    fun oneDecimal(v: Float): String = if (v.isNaN()) NO_DATA else String.format(Locale.ROOT, "%.1f", v)
    fun twoDecimals(v: Float): String = if (v.isNaN()) NO_DATA else String.format(Locale.ROOT, "%.2f", v)

    // Variantes sin crear Strings (para dibujar en cada frame): escriben en [out].
    fun int(v: Float, out: TextBuf): TextBuf =
        if (v.isNaN()) out.clear().append(NO_DATA) else out.clear().appendInt(Math.round(v).toLong())

    fun oneDecimal(v: Float, out: TextBuf): TextBuf =
        if (v.isNaN()) out.clear().append(NO_DATA) else out.clear().appendFixed(v, 1)

    fun twoDecimals(v: Float, out: TextBuf): TextBuf =
        if (v.isNaN()) out.clear().append(NO_DATA) else out.clear().appendFixed(v, 2)

    /** Parte de la barra que se rellena (0..1); sin dato, vacía. */
    fun fraction(v: Float, min: Float, max: Float): Float =
        if (v.isNaN() || max <= min) 0f else ((v - min) / (max - min)).coerceIn(0f, 1f)

    const val NO_DATA = "--"
}
