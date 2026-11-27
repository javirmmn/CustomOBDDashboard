package com.example.ibizacustommap

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Iconos del salpicadero dibujados con trazos (sin ficheros de imagen ni
 * licencias de terceros). Cada uno ocupa una caja cuadrada de lado [size]
 * con esquina superior izquierda en (x, y). Se pintan una sola vez en el
 * fondo cacheado de [DashboardRenderer], así que el halo no cuesta nada por frame.
 */
internal object DashboardIcons {

    enum class Icon { COOLANT, INTAKE, LOAD, BOOST, PEDAL, TARGET, SPARK_PLUG, FUEL_DROP, FUEL_PUMP }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val oval = RectF()

    /** [weight] es el grosor del trazo en proporción al tamaño del icono. */
    fun draw(c: Canvas, icon: Icon, x: Float, y: Float, size: Float, color: Int, glow: Boolean = true, weight: Float = 0.07f) {
        val w = size * weight
        stroke.color = color
        stroke.strokeWidth = w
        fill.color = color
        if (glow) {
            val halo = Color.argb(170, Color.red(color), Color.green(color), Color.blue(color))
            stroke.setShadowLayer(size * 0.12f, 0f, 0f, halo)
            fill.setShadowLayer(size * 0.12f, 0f, 0f, halo)
        } else {
            stroke.clearShadowLayer()
            fill.clearShadowLayer()
        }
        // Coordenadas normalizadas 0..1 dentro de la caja.
        fun px(v: Float) = x + v * size
        fun py(v: Float) = y + v * size

        when (icon) {
            Icon.COOLANT -> {
                c.drawLine(px(0.5f), py(0.06f), px(0.5f), py(0.5f), stroke)
                for (t in listOf(0.16f, 0.28f, 0.40f)) c.drawLine(px(0.5f), py(t), px(0.66f), py(t), stroke)
                c.drawCircle(px(0.5f), py(0.58f), size * 0.09f, fill)
                for (wy in listOf(0.78f, 0.93f)) wave(c, px(0.08f), px(0.92f), py(wy), size * 0.05f)
            }
            Icon.INTAKE -> {
                c.drawLine(px(0.5f), py(0.04f), px(0.5f), py(0.32f), stroke)
                c.drawLine(px(0.5f), py(0.12f), px(0.62f), py(0.12f), stroke)
                c.drawLine(px(0.5f), py(0.22f), px(0.62f), py(0.22f), stroke)
                c.drawCircle(px(0.5f), py(0.38f), size * 0.07f, fill)
                oval.set(px(0.22f), py(0.52f), px(0.78f), py(0.9f))
                c.drawRoundRect(oval, size * 0.06f, size * 0.06f, stroke)
                c.drawLine(px(0.08f), py(0.62f), px(0.22f), py(0.62f), stroke)
                c.drawLine(px(0.08f), py(0.8f), px(0.22f), py(0.8f), stroke)
                c.drawLine(px(0.78f), py(0.62f), px(0.92f), py(0.62f), stroke)
                c.drawLine(px(0.78f), py(0.8f), px(0.92f), py(0.8f), stroke)
                c.drawLine(px(0.36f), py(0.9f), px(0.36f), py(0.98f), stroke)
                c.drawLine(px(0.64f), py(0.9f), px(0.64f), py(0.98f), stroke)
            }
            Icon.LOAD -> {
                oval.set(px(0.08f), py(0.2f), px(0.92f), py(1.04f))
                c.drawArc(oval, 180f, 180f, false, stroke)
                for (i in 0..4) {
                    val a = Math.toRadians(180.0 + i * 45.0)
                    val cx = px(0.5f); val cy = py(0.62f)
                    val r1 = size * 0.42f; val r2 = size * 0.32f
                    c.drawLine(
                        cx + (r1 * Math.cos(a)).toFloat(), cy + (r1 * Math.sin(a)).toFloat(),
                        cx + (r2 * Math.cos(a)).toFloat(), cy + (r2 * Math.sin(a)).toFloat(), stroke,
                    )
                }
                c.drawLine(px(0.5f), py(0.62f), px(0.72f), py(0.36f), stroke)
                c.drawCircle(px(0.5f), py(0.62f), size * 0.07f, fill)
            }
            Icon.BOOST -> {
                c.drawCircle(px(0.42f), py(0.58f), size * 0.32f, stroke)
                c.drawCircle(px(0.42f), py(0.58f), size * 0.15f, stroke)
                for (i in 0 until 6) {
                    val a = Math.toRadians(i * 60.0 + 15.0)
                    c.drawLine(
                        px(0.42f), py(0.58f),
                        px(0.42f) + (size * 0.15f * Math.cos(a)).toFloat(),
                        py(0.58f) + (size * 0.15f * Math.sin(a)).toFloat(), stroke,
                    )
                }
                path.reset()
                path.moveTo(px(0.42f), py(0.26f))
                path.lineTo(px(0.9f), py(0.26f))
                path.lineTo(px(0.9f), py(0.42f))
                path.lineTo(px(0.7f), py(0.42f))
                c.drawPath(path, stroke)
                c.drawLine(px(0.72f), py(0.08f), px(0.88f), py(0.08f), stroke)
                c.drawLine(px(0.82f), py(0.02f), px(0.88f), py(0.08f), stroke)
                c.drawLine(px(0.82f), py(0.14f), px(0.88f), py(0.08f), stroke)
            }
            Icon.PEDAL -> {
                val red = Color.rgb(255, 60, 60)
                stroke.color = red
                oval.set(px(0.04f), py(0.1f), px(0.74f), py(0.8f))
                c.drawArc(oval, 190f, 100f, false, stroke)
                stroke.color = color
                stroke.strokeWidth = w * 1.6f
                c.drawLine(px(0.5f), py(0.92f), px(0.8f), py(0.22f), stroke)
                stroke.strokeWidth = w
                c.drawLine(px(0.42f), py(0.92f), px(0.62f), py(0.92f), stroke)
                c.drawLine(px(0.39f), py(0.5f), px(0.6f), py(0.74f), stroke)
            }
            Icon.TARGET -> {
                c.drawCircle(px(0.5f), py(0.5f), size * 0.42f, stroke)
                c.drawCircle(px(0.5f), py(0.5f), size * 0.21f, stroke)
                c.drawLine(px(0.0f), py(0.5f), px(1.0f), py(0.5f), stroke)
                c.drawLine(px(0.5f), py(0.0f), px(0.5f), py(1.0f), stroke)
                for ((dx, dy) in listOf(0f to -0.42f, 0f to 0.42f, -0.42f to 0f, 0.42f to 0f)) {
                    c.drawCircle(px(0.5f + dx), py(0.5f + dy), size * 0.045f, fill)
                }
            }
            Icon.SPARK_PLUG -> {
                c.save()
                c.rotate(35f, px(0.5f), py(0.5f))
                c.drawLine(px(0.5f), py(0.0f), px(0.5f), py(0.12f), stroke)
                oval.set(px(0.42f), py(0.12f), px(0.58f), py(0.46f))
                c.drawRoundRect(oval, size * 0.04f, size * 0.04f, stroke)
                for (t in listOf(0.2f, 0.28f, 0.36f)) c.drawLine(px(0.42f), py(t), px(0.58f), py(t), stroke)
                oval.set(px(0.36f), py(0.46f), px(0.64f), py(0.6f))
                c.drawRect(oval, fill)
                oval.set(px(0.44f), py(0.6f), px(0.56f), py(0.84f))
                c.drawRect(oval, stroke)
                c.drawLine(px(0.5f), py(0.84f), px(0.5f), py(0.94f), stroke)
                c.drawLine(px(0.42f), py(0.94f), px(0.58f), py(0.94f), stroke)
                c.restore()
            }
            Icon.FUEL_DROP -> {
                path.reset()
                path.moveTo(px(0.3f), py(0.1f))
                path.cubicTo(px(0.12f), py(0.4f), px(0.04f), py(0.58f), px(0.12f), py(0.74f))
                path.cubicTo(px(0.2f), py(0.92f), px(0.4f), py(0.92f), px(0.48f), py(0.74f))
                path.cubicTo(px(0.56f), py(0.58f), px(0.48f), py(0.4f), px(0.3f), py(0.1f))
                path.close()
                c.drawPath(path, fill)
                c.drawLine(px(0.6f), py(0.5f), px(0.95f), py(0.5f), stroke)
                c.drawLine(px(0.83f), py(0.38f), px(0.95f), py(0.5f), stroke)
                c.drawLine(px(0.83f), py(0.62f), px(0.95f), py(0.5f), stroke)
            }
            Icon.FUEL_PUMP -> {
                oval.set(px(0.14f), py(0.12f), px(0.58f), py(0.92f))
                c.drawRoundRect(oval, size * 0.05f, size * 0.05f, stroke)
                oval.set(px(0.24f), py(0.24f), px(0.48f), py(0.44f))
                c.drawRect(oval, fill)
                c.drawLine(px(0.06f), py(0.92f), px(0.66f), py(0.92f), stroke)
                path.reset()
                path.moveTo(px(0.58f), py(0.36f))
                path.lineTo(px(0.74f), py(0.36f))
                path.lineTo(px(0.82f), py(0.48f))
                path.lineTo(px(0.82f), py(0.76f))
                path.quadTo(px(0.82f), py(0.84f), px(0.74f), py(0.82f))
                path.lineTo(px(0.7f), py(0.64f))
                c.drawPath(path, stroke)
                c.drawLine(px(0.74f), py(0.2f), px(0.86f), py(0.32f), stroke)
            }
        }
    }

    private fun wave(c: Canvas, x0: Float, x1: Float, y: Float, amp: Float) {
        path.reset()
        path.moveTo(x0, y)
        val step = (x1 - x0) / 4f
        for (i in 0 until 4) {
            val sx = x0 + i * step
            path.quadTo(sx + step / 2f, if (i % 2 == 0) y - amp else y + amp, sx + step, y)
        }
        c.drawPath(path, stroke)
    }
}
