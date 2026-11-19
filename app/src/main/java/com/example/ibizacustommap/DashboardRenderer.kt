package com.example.ibizacustommap

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Snapshot inmutable de telemetría lista para dibujar. Lo publica ObdPoller
 * (o DashboardSimulator) de golpe y se pinta sin volver a tocar variables
 * mutables compartidas con otro hilo. [link] es el estado de la fuente de
 * datos, que se pinta en la pantalla del coche.
 */
data class TelemetrySnapshot(
    val speedKmh: Float,
    val gearDisplay: String,
    val activeLeds: Int,
    val oilTemp: Float,
    val oilTempFromCoolant: Boolean,
    val turboPressure: Float,
    val intakeTemp: Float,
    val throttlePercent: Float,
    val afr: Float,
    val engineLoad: Float,
    val gForce: Float,
    val maf: Float,
    val debugRawResponse: String,
    val debugMafRaw: String,
    val debugAfrRaw: String,
    val debugGearRatio: Float,
    val link: LinkState,
) {
    companion object {
        /** Sin ningún dato: todo "--", marcha "--" y LEDs apagados. */
        fun empty(link: LinkState) = TelemetrySnapshot(
            speedKmh = Float.NaN,
            gearDisplay = "--",
            activeLeds = 0,
            oilTemp = Float.NaN,
            oilTempFromCoolant = false,
            turboPressure = Float.NaN,
            intakeTemp = Float.NaN,
            throttlePercent = Float.NaN,
            afr = Float.NaN,
            engineLoad = Float.NaN,
            gForce = Float.NaN,
            maf = Float.NaN,
            debugRawResponse = "-",
            debugMafRaw = "-",
            debugAfrRaw = "-",
            debugGearRatio = Float.NaN,
            link = link,
        )
    }
}

private data class TelemetryItem(val label: String, val value: String, val unit: String, val dynamicColor: Int)

/**
 * Dibuja el salpicadero "GT3" sobre un Canvas de Android. Es un componente
 * puro (no sabe nada de Bluetooth, OBD ni Android Auto): recibe un
 * [TelemetrySnapshot] ya calculado y lo pinta.
 *
 * Lo usan tanto MainScreen (pantalla real en Android Auto) como
 * DashboardPreviewActivity (vista previa temporal en el móvil).
 * Al compartir el mismo código, ambas pantallas muestran EXACTAMENTE lo mismo.
 */
object DashboardRenderer {

    // 24 LEDs × 250 = 6000 rpm (1.0 TSI 95 CV: potencia máx. 5000-5500 rpm).
    // Naranja desde 4500 rpm, rojo desde 5500 (fin de la zona de potencia máxima).
    const val RPM_PER_LED = 250

    private const val AFR_FUEL_CUT = 22.05f // lambda 1.5

    private val BG_BLACK = Color.argb(255, 4, 4, 4)
    private val SOFT_RED = Color.argb(255, 90, 0, 0)
    private val DARK_RED = Color.argb(255, 45, 0, 0)
    private val TEXT_WHITE = Color.argb(255, 240, 240, 240)
    private val TEXT_GRAY = Color.argb(255, 120, 120, 120)
    private val DIVIDER = Color.argb(255, 45, 45, 45)

    private val ACCENT_ORANGE = Color.rgb(255, 120, 0)
    private val ALERT_RED = Color.rgb(255, 50, 50)

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        density: Float,
        s: TelemetrySnapshot,
    ) {
        canvas.drawColor(BG_BLACK)

        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DIVIDER
            strokeWidth = 1.5f * density
        }

        val softRedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SOFT_RED
            strokeWidth = 1.3f * density
        }

        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DARK_RED
            strokeWidth = 2f * density
        }

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_GRAY
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
            textSize = height * 0.033f
            letterSpacing = 0.08f
        }

        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
            textSize = height * 0.058f
        }

        val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 255, 255)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = height * 0.068f
        }

        val gearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = height * 0.54f
        }

        val rpmTop = height * 0.06f
        val rpmBottom = height * 0.088f
        val rpmLeft = width * 0.08f
        val rpmRight = width * 0.92f
        val ledCount = 24
        val ledGap = width * 0.004f
        val ledWidth = ((rpmRight - rpmLeft) - (ledGap * (ledCount - 1))) / ledCount

        for (i in 0 until ledCount) {
            val left = rpmLeft + i * (ledWidth + ledGap)
            val right = left + ledWidth
            val active = i < s.activeLeds

            val color = when {
                i > 20 -> ALERT_RED
                i > 16 -> ACCENT_ORANGE
                else -> Color.rgb(255, 255 - (i * 4), 255 - (i * 6))
            }

            val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = if (active) color else DARK_RED
            }

            canvas.drawRoundRect(RectF(left, rpmTop, right, rpmBottom), 2f, 2f, ledPaint)
        }

        val startY = rpmBottom + height * 0.10f
        val endY = height * 0.86f
        val gap = width * 0.02f
        val centerWidth = width * 0.31f
        val sideWidth = width * 0.25f

        val cxL = (width / 2f) - (centerWidth / 2f)
        val cxR = (width / 2f) + (centerWidth / 2f)
        val lxR = cxL - gap
        val lxL = lxR - sideWidth
        val rxL = cxR + gap
        val rxR = rxL + sideWidth
        val centerEndY = startY + (endY - startY) * 0.72f

        canvas.drawLine(lxL + width * 0.012f, startY, lxL + width * 0.012f, endY, guidePaint)
        canvas.drawLine(rxR - width * 0.012f, startY, rxR - width * 0.012f, endY, guidePaint)
        canvas.drawLine(cxL, startY, cxR, startY, softRedPaint)
        canvas.drawLine(cxL, startY, cxL, centerEndY, softRedPaint)
        canvas.drawLine(cxR, startY, cxR, centerEndY, softRedPaint)

        val speedDividerY = startY + (centerEndY - startY) * 0.16f
        canvas.drawLine(cxL, speedDividerY, cxR, speedDividerY, dividerPaint)
        val speedY = startY + (speedDividerY - startY) * 0.48f

        val speedText = if (s.speedKmh.isNaN()) "--" else s.speedKmh.toInt().toString()
        drawTextCentered(canvas, speedText, (cxL + cxR) / 2f, speedY, speedPaint)

        val gearY = speedDividerY + ((centerEndY - speedDividerY) * 0.60f)
        // Sin datos la marcha es "--" en gris, no "N".
        if (s.gearDisplay == "--") gearPaint.color = TEXT_GRAY
        drawTextCentered(canvas, s.gearDisplay, (cxL + cxR) / 2f, gearY, gearPaint)

        fun drawDataRow(slotRect: RectF, item: TelemetryItem) {
            val paddingX = width * 0.020f
            canvas.drawText(item.label, slotRect.left + paddingX, slotRect.centerY(), labelPaint)

            val numberPaint = Paint(valuePaint).apply { color = item.dynamicColor }

            val isDegree = item.unit.contains("°")
            val unitPaint = Paint(numberPaint).apply {
                textSize = if (isDegree) numberPaint.textSize else numberPaint.textSize * 0.55f
            }

            val unitWidth = unitPaint.measureText(item.unit)
            val spaceBetween = if (item.unit.isNotEmpty() && !isDegree) width * 0.005f else 0f
            val rightMargin = slotRect.right - paddingX

            canvas.drawText(item.unit, rightMargin, slotRect.centerY() + height * 0.010f, unitPaint)
            canvas.drawText(item.value, rightMargin - unitWidth - spaceBetween, slotRect.centerY() + height * 0.010f, numberPaint)
        }

        // FIX: la temperatura de aceite ya no marca rojo por defecto cuando
        // el motor está frío/templado. Rojo solo en sobrecalentamiento real
        // o si el sensor da un valor claramente erróneo.
        val oilColor = when {
            s.oilTemp >= 120f -> ALERT_RED
            s.oilTemp >= 110f -> ACCENT_ORANGE
            s.oilTemp in 70f..110f -> TEXT_WHITE
            s.oilTemp in 20f..70f -> ACCENT_ORANGE // motor calentando, aviso suave
            else -> TEXT_GRAY // valor fuera de rango físico razonable
        }

        val boostColor = when {
            s.turboPressure >= 1.4f -> ALERT_RED
            s.turboPressure >= 1.2f -> ACCENT_ORANGE
            else -> TEXT_WHITE
        }

        val intakeColor = when {
            // 50-55 °C es normal en un TSI con calor/tráfico (aire tras el intercooler).
            s.intakeTemp >= 70f -> ALERT_RED
            s.intakeTemp >= 60f -> ACCENT_ORANGE
            else -> TEXT_WHITE
        }

        val throttleColor = if (s.throttlePercent >= 95f) ACCENT_ORANGE else TEXT_WHITE

        val afrColor = when {
            s.afr >= 16.0f -> ALERT_RED
            s.afr >= 15.2f -> ACCENT_ORANGE
            s.afr > 11.5f -> TEXT_WHITE
            s.afr > 10.5f -> ACCENT_ORANGE
            else -> ALERT_RED
        }

        // En un turbo la carga calculada llega al 100 % en cada aceleración a fondo:
        // es normal, no una alarma. Naranja solo como indicador de plena carga.
        val loadColor = if (s.engineLoad >= 95f) ACCENT_ORANGE else TEXT_WHITE

        val gForceColor = if (s.gForce >= 1.0f) ACCENT_ORANGE else TEXT_WHITE

        val mafColor = TEXT_WHITE

        // Sin lectura válida (NaN) → "--" en gris, nunca un número inventado.
        fun item(label: String, v: Float, text: String, unit: String, color: Int) =
            if (v.isNaN()) TelemetryItem(label, "--", "", TEXT_GRAY) else TelemetryItem(label, text, unit, color)

        // Si la ECU no da la temperatura de aceite (PID 5C) se muestra la del
        // refrigerante (PID 05), pero con su propia etiqueta.
        val oilLabel = if (s.oilTempFromCoolant) "WATER TEMP" else "OIL TEMP"

        val leftData = listOf(
            item(oilLabel, s.oilTemp, "${s.oilTemp.toInt()}", "°", oilColor),
            item("BOOST BAR", s.turboPressure, String.format("%.1f", s.turboPressure), "", boostColor),
            item("INTAKE TEMP", s.intakeTemp, "${s.intakeTemp.toInt()}", "°", intakeColor),
            item("THROTTLE", s.throttlePercent, "${s.throttlePercent.toInt()}", "%", throttleColor)
        )

        val rowHeight = (endY - startY) / leftData.size

        leftData.forEachIndexed { index, item ->
            val top = startY + rowHeight * index
            val slotRect = RectF(lxL, top, lxR, top + rowHeight)

            drawDataRow(slotRect, item)

            if (index < leftData.size - 1) {
                canvas.drawLine(lxL + width * 0.010f, slotRect.bottom, lxR - width * 0.010f, slotRect.bottom, dividerPaint)
            }
        }

        // Lambda ≥ 1.5 no se da en combustión de gasolina: es corte de inyección
        // (al soltar el acelerador) o la sonda saturada. La ECU manda 0xFFFF → 29.4.
        val afrItem = if (!s.afr.isNaN() && s.afr >= AFR_FUEL_CUT) {
            TelemetryItem("AFR", "CUT", "", TEXT_GRAY)
        } else {
            item("AFR", s.afr, String.format("%.1f", s.afr), "", afrColor)
        }

        val mafText = if (s.maf < 100f) String.format("%.1f", s.maf) else "${s.maf.toInt()}"

        val rightData = listOf(
            afrItem,
            item("ENG LOAD", s.engineLoad, "${s.engineLoad.toInt()}", "%", loadColor),
            item("G-FORCE", s.gForce, String.format("%.1f", s.gForce), "", gForceColor),
            item("MAF", s.maf, mafText, "G/S", mafColor)
        )

        rightData.forEachIndexed { index, item ->
            val top = startY + rowHeight * index
            val slotRect = RectF(rxL, top, rxR, top + rowHeight)

            drawDataRow(slotRect, item)

            if (index < rightData.size - 1) {
                canvas.drawLine(rxL + width * 0.010f, slotRect.bottom, rxR - width * 0.010f, slotRect.bottom, dividerPaint)
            }
        }

        // =====================================================
        // CHIVATO DEBUG — esquina superior izquierda, pequeño,
        // fuera de zona de datos. Solo visible en builds de debug.
        // Incluye MAF y AFR crudos para diagnosticar PIDs no soportados,
        // y R: rpm/velocidad de la marcha actual para calibrar GearEstimator.
        // =====================================================
        if (BuildConfig.DEBUG) {
            val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.YELLOW
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = height * 0.022f
                textAlign = Paint.Align.LEFT
            }

            val displayText = if (s.link == LinkState.SIMULATION) {
                "SIM_OK"
            } else {
                val ratioText = if (s.debugGearRatio.isNaN()) "-" else String.format("%.1f", s.debugGearRatio)
                "R:$ratioText RPM:${s.debugRawResponse} MAF:${s.debugMafRaw} AFR:${s.debugAfrRaw}"
            }

            canvas.drawText(displayText, width * 0.02f, height * 0.045f, debugPaint)
        }

        // Estado de la conexión, en todas las compilaciones (release incluida),
        // en el hueco entre los LEDs y los recuadros de datos.
        drawLinkBanner(canvas, width, height, s.link, (rpmBottom + startY) / 2f)
    }

    private val LIVE_GREEN = Color.rgb(70, 190, 90)

    private fun drawLinkBanner(canvas: Canvas, width: Int, height: Int, link: LinkState, cy: Float) {
        // EN VIVO: discreto. El resto: franja visible, roja si requiere acción.
        val (bg, fg) = when (link) {
            LinkState.LIVE -> Color.TRANSPARENT to LIVE_GREEN
            LinkState.SIMULATION -> ACCENT_ORANGE to Color.BLACK
            LinkState.SEARCHING, LinkState.CONNECTING -> Color.TRANSPARENT to TEXT_WHITE
            LinkState.RECONNECTING, LinkState.NO_ECU_DATA -> Color.TRANSPARENT to ACCENT_ORANGE
            LinkState.NO_CONNECTION, LinkState.BLUETOOTH_OFF, LinkState.NO_PERMISSION -> ALERT_RED to Color.WHITE
        }
        val text = if (link == LinkState.LIVE) "● ${link.label}" else link.label

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.12f
            textSize = height * if (link == LinkState.LIVE) 0.026f else 0.034f
        }
        // Que quepa siempre (pantallas estrechas).
        val maxWidth = width * 0.84f
        val measured = paint.measureText(text)
        if (measured > maxWidth) paint.textSize *= maxWidth / measured

        val cx = width / 2f
        if (bg != Color.TRANSPARENT) {
            val padX = height * 0.03f
            val padY = height * 0.012f
            val half = paint.measureText(text) / 2f
            val fm = paint.fontMetrics
            val textH = fm.descent - fm.ascent
            val rect = RectF(cx - half - padX, cy - textH / 2f - padY, cx + half + padX, cy + textH / 2f + padY)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, bgPaint)
        }
        drawTextCentered(canvas, text, cx, cy, paint)
    }

    private fun drawTextCentered(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        paint: Paint,
    ) {
        val fm = paint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, cx, baseline, paint)
    }
}
