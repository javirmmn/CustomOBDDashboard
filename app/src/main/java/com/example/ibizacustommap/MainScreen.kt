package com.example.ibizacustommap

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    @Volatile
    private var surfaceContainer: SurfaceContainer? = null

    // =========================================================
    // TELEMETRÍA TEMPORAL (SIMULADOR)
    // =========================================================

    private var speedKmh: Float = 0f
    private var gear: Int = 1
    private var activeLeds: Int = 0

    private var oilTemp: Float = 90f
    private var turboPressure: Float = 0f
    private var intakeTemp: Float = 30f
    private var throttlePercent: Float = 0f

    private var afr: Float = 14.7f
    private var engineLoad: Float = 0f
    private var gForce: Float = 0f
    private var maf: Float = 10f

    private val telemetryLock = Any()

    // Reloj interno del simulador
    private var simulationTick = 0f

    // =========================================================
    // RENDER
    // =========================================================

    private val renderThread = HandlerThread(RENDER_THREAD_NAME).apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val redrawRunnable = Runnable { performRedrawSurface() }

    private val refreshTickerRunnable = object : Runnable {
        override fun run() {
            if (surfaceContainer == null) return

            // --- INICIO DEL SIMULADOR MATEMÁTICO ---
            simulationTick += 0.05f

            // Creamos tres "ondas" para que los valores no suban y bajen todos exactamente a la vez
            val waveSlow = ((Math.sin(simulationTick.toDouble() * 0.5) + 1.0) / 2.0).toFloat()
            val waveFast = ((Math.sin(simulationTick.toDouble() * 1.5) + 1.0) / 2.0).toFloat()
            val waveAFR = ((Math.cos(simulationTick.toDouble() * 0.7) + 1.0) / 2.0).toFloat()

            synchronized(telemetryLock) {
                // RPM LEDs (De 0 a 24)
                activeLeds = (waveFast * 24f).toInt()

                // Velocidad y marchas
                speedKmh = 60f + (waveSlow * 90f)
                gear = when {
                    speedKmh < 80 -> 3
                    speedKmh < 120 -> 4
                    else -> 5
                }

                // Aceite (Ampliamos rango de 60º a 130º para ver frío y calor extremo)
                oilTemp = 60f + (waveSlow * 70f)

                // Presión de Turbo (De -0.2 bar en retención a 1.6 bar a fondo)
                turboPressure = -0.2f + (waveFast * 1.8f)

                // Temperatura de admisión (De 25º a 65º)
                intakeTemp = 25f + (waveSlow * 40f)

                // Acelerador
                throttlePercent = waveFast * 100f

                // Mezcla AFR (Barrido completo de 10.0 a 16.5 para ver todos los colores)
                afr = 10.0f + (waveAFR * 6.5f)

                // Carga del motor y Fuerzas G
                engineLoad = waveFast * 100f
                gForce = waveFast * 1.2f

                // Caudalímetro
                maf = 10f + (waveFast * 130f)
            }
            // --- FIN DEL SIMULADOR ---

            scheduleRedraw()
            // Refresco ultra rápido para 30 FPS
            renderHandler.postDelayed(this, REFRESH_TICK_MS)
        }
    }

    private var redrawInProgress = false
    private var redrawQueued = false

    private var lastVisible: Rect? = null
    private var lastStable: Rect? = null

    private val navigationManagerCallback =
        object : NavigationManagerCallback {
            override fun onStopNavigation() {
                Log.d(TAG, "Navigation stopped")
            }
        }

    init {

        carContext
            .getCarService(NavigationManager::class.java)
            .setNavigationManagerCallback(
                navigationManagerCallback
            )

        carContext
            .getCarService(AppManager::class.java)
            .setSurfaceCallback(this)

        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->

                if (event == Lifecycle.Event.ON_DESTROY) {

                    try {
                        getCarContext().getCarService(NavigationManager::class.java).navigationEnded()
                        getCarContext().getCarService(NavigationManager::class.java).clearNavigationManagerCallback()
                    } catch (_: Exception) {}

                    renderHandler.removeCallbacksAndMessages(null)
                    renderThread.quitSafely()
                }
            },
        )
    }

    // =========================================================
    // TEMPLATE (Con el botón ActionStrip restaurado)
    // =========================================================

    private fun buildActionStrip(): ActionStrip =
        ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(androidx.car.app.model.CarIcon.APP_ICON)
                    .setOnClickListener { finish() }
                    .build(),
            )
            .build()

    override fun onGetTemplate(): Template {
        return NavigationTemplate.Builder()
            .setActionStrip(buildActionStrip())
            .build()
    }

    // =========================================================
    // SURFACE
    // =========================================================

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        getCarContext().getCarService(NavigationManager::class.java).navigationStarted()
        this.surfaceContainer = surfaceContainer
        renderHandler.removeCallbacks(redrawRunnable)
        renderHandler.removeCallbacks(refreshTickerRunnable)
        invalidate()
        renderHandler.post(redrawRunnable)
        renderHandler.postDelayed(refreshTickerRunnable, REFRESH_TICK_MS)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        if (lastVisible?.equals(visibleArea) == true) return
        lastVisible = Rect(visibleArea)
        scheduleRedraw()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (lastStable?.equals(stableArea) == true) return
        lastStable = Rect(stableArea)
        scheduleRedraw()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        renderHandler.removeCallbacks(redrawRunnable)
        renderHandler.removeCallbacks(refreshTickerRunnable)
        if (this.surfaceContainer === surfaceContainer) {
            this.surfaceContainer = null
        }
        lastVisible = null
        lastStable = null
        synchronized(renderStateLock) {
            redrawInProgress = false
            redrawQueued = false
        }
    }

    // =========================================================
    // RENDER LOOP
    // =========================================================

    private fun scheduleRedraw() {
        if (surfaceContainer == null) return
        renderHandler.removeCallbacks(redrawRunnable)
        renderHandler.post(redrawRunnable)
    }

    private val renderStateLock = Any()

    private fun performRedrawSurface() {
        synchronized(renderStateLock) {
            if (redrawInProgress) {
                redrawQueued = true
                return
            }
            redrawInProgress = true
        }

        val container = surfaceContainer
        if (container == null) {
            endRedrawOrRequeue()
            return
        }

        val surface = container.surface
        if (surface == null || !surface.isValid) {
            endRedrawOrRequeue()
            return
        }

        val w = container.width
        val h = container.height

        if (w <= 0 || h <= 0) {
            endRedrawOrRequeue()
            return
        }

        val canvas: Canvas? =
            try {
                surface.lockHardwareCanvas()
            } catch (e: Exception) {
                null
            }

        if (canvas == null) {
            endRedrawOrRequeue()
            return
        }

        try {
            drawGt3Dashboard(canvas, w, h)
        } catch (e: Exception) {
            Log.e(TAG, "Draw error", e)
        } finally {
            try {
                surface.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                Log.e(TAG, "unlock fail", e)
            }
            endRedrawOrRequeue()
        }
    }

    private fun endRedrawOrRequeue() {
        val runAgain = synchronized(renderStateLock) {
            redrawInProgress = false
            if (redrawQueued) {
                redrawQueued = false
                true
            } else {
                false
            }
        }
        if (runAgain && surfaceContainer != null) {
            renderHandler.post(redrawRunnable)
        }
    }

    // =========================================================
    // DASHBOARD
    // =========================================================

    // Pequeña clase para estructurar los datos y su color dinámico
    private data class TelemetryItem(val label: String, val value: String, val unit: String, val dynamicColor: Int)

    private fun drawGt3Dashboard(
        canvas: Canvas,
        width: Int,
        height: Int,
    ) {

        canvas.drawColor(BG_BLACK)

        val density = getCarContext().resources.displayMetrics.density

        // =====================================================
        // PAINTS
        // =====================================================

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

        // =====================================================
        // RPM BAR
        // =====================================================

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
            val active = i < activeLeds

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

        // =====================================================
        // LAYOUT
        // =====================================================

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

        // =====================================================
        // GUIDE LINES
        // =====================================================

        canvas.drawLine(lxL + width * 0.012f, startY, lxL + width * 0.012f, endY, guidePaint)
        canvas.drawLine(rxR - width * 0.012f, startY, rxR - width * 0.012f, endY, guidePaint)
        canvas.drawLine(cxL, startY, cxR, startY, softRedPaint)
        canvas.drawLine(cxL, startY, cxL, centerEndY, softRedPaint)
        canvas.drawLine(cxR, startY, cxR, centerEndY, softRedPaint)

        // =====================================================
        // SPEED / GEAR
        // =====================================================

        val speedDividerY = startY + (centerEndY - startY) * 0.16f
        canvas.drawLine(cxL, speedDividerY, cxR, speedDividerY, dividerPaint)
        val speedY = startY + (speedDividerY - startY) * 0.48f

        drawTextCentered(canvas, speedKmh.toInt().toString(), (cxL + cxR) / 2f, speedY, speedPaint)

        val gearY = speedDividerY + ((centerEndY - speedDividerY) * 0.60f)
        drawTextCentered(canvas, gear.toString(), (cxL + cxR) / 2f, gearY, gearPaint)

        // =====================================================
        // ROW DRAWING FUNCTION (Soporta colores dinámicos)
        // =====================================================

        fun drawDataRow(slotRect: RectF, item: TelemetryItem) {
            val paddingX = width * 0.020f

            // Dibujar Etiqueta
            canvas.drawText(item.label, slotRect.left + paddingX, slotRect.centerY(), labelPaint)

            // Pincel para el número con su color dinámico
            val numberPaint = Paint(valuePaint).apply { color = item.dynamicColor }

            // Pincel para la unidad
            val isDegree = item.unit.contains("°")
            val unitPaint = Paint(numberPaint).apply {
                textSize = if (isDegree) numberPaint.textSize else numberPaint.textSize * 0.55f
            }

            val unitWidth = unitPaint.measureText(item.unit)
            val spaceBetween = if (item.unit.isNotEmpty() && !isDegree) width * 0.005f else 0f
            val rightMargin = slotRect.right - paddingX

            // Dibujar Unidad
            canvas.drawText(item.unit, rightMargin, slotRect.centerY() + height * 0.010f, unitPaint)

            // Dibujar Número
            canvas.drawText(item.value, rightMargin - unitWidth - spaceBetween, slotRect.centerY() + height * 0.010f, numberPaint)
        }

        // =====================================================
        // LÓGICA DE COLORES DE ALERTAS (MOTOR)
        // =====================================================

        val oilColor = when {
            oilTemp >= 120f -> ALERT_RED         // Sobrecalentamiento crítico
            oilTemp >= 110f -> ACCENT_ORANGE     // Muy caliente
            oilTemp >= 85f -> TEXT_WHITE         // TEMPERATURA ÓPTIMA
            oilTemp >= 70f -> ACCENT_ORANGE      // Calentando (no exigir)
            else -> ALERT_RED                    // Aceite muy frío (peligro)
        }

        val boostColor = when {
            turboPressure >= 1.4f -> ALERT_RED
            turboPressure >= 1.2f -> ACCENT_ORANGE
            else -> TEXT_WHITE
        }

        val intakeColor = when {
            intakeTemp >= 55f -> ALERT_RED
            intakeTemp >= 45f -> ACCENT_ORANGE
            else -> TEXT_WHITE
        }

        val throttleColor = if (throttlePercent >= 95f) ACCENT_ORANGE else TEXT_WHITE

        // AFR: Sistema simétrico (Peligro tanto por exceso como por defecto)
        val afrColor = when {
            afr >= 16.0f -> ALERT_RED            // Peligro: Extremadamente pobre
            afr >= 15.2f -> ACCENT_ORANGE        // Aviso: Ligeramente pobre
            afr > 11.5f -> TEXT_WHITE            // ZONA ÓPTIMA (Crucero y carga segura)
            afr > 10.5f -> ACCENT_ORANGE         // Aviso: Ligeramente rico
            else -> ALERT_RED                    // Peligro: Extremadamente rico (ahogándose)
        }

        val loadColor = when {
            engineLoad >= 95f -> ALERT_RED
            engineLoad >= 85f -> ACCENT_ORANGE
            else -> TEXT_WHITE
        }

        val gForceColor = if (gForce >= 1.0f) ACCENT_ORANGE else TEXT_WHITE

        val mafColor = TEXT_WHITE

        // =====================================================
        // LEFT PANEL
        // =====================================================

        val leftData = listOf(
            TelemetryItem("OIL TEMP", "${oilTemp.toInt()}", "°", oilColor),
            TelemetryItem("BOOST PSI", String.format("%.1f", turboPressure), "", boostColor),
            TelemetryItem("INTAKE TEMP", "${intakeTemp.toInt()}", "°", intakeColor),
            TelemetryItem("THROTTLE", "${throttlePercent.toInt()}", "%", throttleColor)
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

        // =====================================================
        // RIGHT PANEL
        // =====================================================

        val rightData = listOf(
            TelemetryItem("AFR", String.format("%.1f", afr), "", afrColor),
            TelemetryItem("ENG LOAD", "${engineLoad.toInt()}", "%", loadColor),
            TelemetryItem("G-FORCE", String.format("%.1f", gForce), "", gForceColor),
            TelemetryItem("MAF", "${maf.toInt()}", "G/S", mafColor)
        )

        rightData.forEachIndexed { index, item ->
            val top = startY + rowHeight * index
            val slotRect = RectF(rxL, top, rxR, top + rowHeight)

            drawDataRow(slotRect, item)

            if (index < rightData.size - 1) {
                canvas.drawLine(rxL + width * 0.010f, slotRect.bottom, rxR - width * 0.010f, slotRect.bottom, dividerPaint)
            }
        }
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

    private companion object {
        private const val TAG = "MainScreen"
        private const val RENDER_THREAD_NAME = "IbizaCustomMapSurface"

        // Ahora a ~30 FPS para que la animación sea súper fluida
        private const val REFRESH_TICK_MS = 33L

        private val BG_BLACK = Color.argb(255, 4, 4, 4)
        private val RACING_RED = Color.argb(255, 180, 0, 0)
        private val SOFT_RED = Color.argb(255, 90, 0, 0)
        private val DARK_RED = Color.argb(255, 45, 0, 0)
        private val TEXT_WHITE = Color.argb(255, 240, 240, 240)
        private val TEXT_GRAY = Color.argb(255, 120, 120, 120)
        private val DIVIDER = Color.argb(255, 45, 45, 45)

        // Colores de Alerta
        private val ACCENT_ORANGE = Color.rgb(255, 120, 0)
        private val ALERT_RED = Color.rgb(255, 50, 50)
    }
}