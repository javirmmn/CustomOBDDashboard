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
    // TELEMETRÍA TEMPORAL (PLACEHOLDER)
    // =========================================================

    private var speedKmh: Float = 114f
    private var gear: Int = 3

    private var oilTemp: Float = 98f
    private var turboPressure: Float = 1.4f
    private var intakeTemp: Float = 34f
    private var throttlePercent: Float = 82f

    private var afr: Float = 14.7f
    private var engineLoad: Float = 65f
    private var gForce: Float = 1.1f
    private var maf: Float = 45f

    private val telemetryLock = Any()

    // =========================================================
    // RENDER
    // =========================================================

    private val renderThread =
        HandlerThread(RENDER_THREAD_NAME).apply { start() }

    private val renderHandler =
        Handler(renderThread.looper)

    private val redrawRunnable =
        Runnable { performRedrawSurface() }

    private val refreshTickerRunnable =
        object : Runnable {

            override fun run() {

                if (surfaceContainer == null) return

                scheduleRedraw()

                renderHandler.postDelayed(
                    this,
                    REFRESH_TICK_MS
                )
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

                        getCarContext()
                            .getCarService(
                                NavigationManager::class.java
                            )
                            .navigationEnded()

                        getCarContext()
                            .getCarService(
                                NavigationManager::class.java
                            )
                            .clearNavigationManagerCallback()

                    } catch (_: Exception) {
                    }

                    renderHandler.removeCallbacksAndMessages(null)

                    renderThread.quitSafely()
                }
            },
        )
    }

// =========================================================
    // TEMPLATE
    // =========================================================

    private fun buildActionStrip(): ActionStrip =
        ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(
                        androidx.car.app.model.CarIcon.APP_ICON
                    )
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

    override fun onSurfaceAvailable(
        surfaceContainer: SurfaceContainer
    ) {

        getCarContext()
            .getCarService(NavigationManager::class.java)
            .navigationStarted()

        this.surfaceContainer = surfaceContainer

        renderHandler.removeCallbacks(redrawRunnable)
        renderHandler.removeCallbacks(refreshTickerRunnable)

        invalidate()

        renderHandler.post(redrawRunnable)

        renderHandler.postDelayed(
            refreshTickerRunnable,
            REFRESH_TICK_MS
        )
    }

    override fun onVisibleAreaChanged(
        visibleArea: Rect
    ) {

        if (lastVisible?.equals(visibleArea) == true) return

        lastVisible = Rect(visibleArea)

        scheduleRedraw()
    }

    override fun onStableAreaChanged(
        stableArea: Rect
    ) {

        if (lastStable?.equals(stableArea) == true) return

        lastStable = Rect(stableArea)

        scheduleRedraw()
    }

    override fun onSurfaceDestroyed(
        surfaceContainer: SurfaceContainer
    ) {

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

            drawGt3Dashboard(
                canvas,
                w,
                h
            )

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

        val runAgain =
            synchronized(renderStateLock) {

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

    private fun drawGt3Dashboard(
        canvas: Canvas,
        width: Int,
        height: Int,
    ) {

        canvas.drawColor(BG_BLACK)

        val density =
            getCarContext()
                .resources
                .displayMetrics
                .density

        // =====================================================
        // PAINTS
        // =====================================================

        val dividerPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = DIVIDER
                strokeWidth = 1.5f * density
            }

        val softRedPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = SOFT_RED
                strokeWidth = 1.3f * density
            }

        val guidePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = DARK_RED
                strokeWidth = 2f * density
            }

        val labelPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = TEXT_GRAY

                typeface =
                    Typeface.create(
                        "sans-serif",
                        Typeface.NORMAL
                    )

                textAlign = Paint.Align.LEFT

                textSize = height * 0.026f

                letterSpacing = 0.08f
            }

        val valuePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = TEXT_WHITE

                typeface =
                    Typeface.create(
                        "sans-serif-medium",
                        Typeface.BOLD
                    )

                textAlign = Paint.Align.RIGHT

                textSize = height * 0.058f
            }

        val speedPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = Color.argb(
                    200,
                    255,
                    255,
                    255
                )

                typeface =
                    Typeface.create(
                        "sans-serif-medium",
                        Typeface.BOLD
                    )

                textAlign = Paint.Align.CENTER

                textSize = height * 0.068f
            }

        val gearPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = TEXT_WHITE

                typeface =
                    Typeface.create(
                        "sans-serif-medium",
                        Typeface.BOLD
                    )

                textAlign = Paint.Align.CENTER

                textSize = height * 0.54f
            }

        val accentPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = ACCENT_ORANGE

                typeface =
                    Typeface.create(
                        "sans-serif-medium",
                        Typeface.BOLD
                    )

                textAlign = Paint.Align.RIGHT

                textSize = height * 0.070f
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

        val ledWidth =
            ((rpmRight - rpmLeft)
                    - (ledGap * (ledCount - 1))) / ledCount

        val activeLeds = 18

        for (i in 0 until ledCount) {

            val left =
                rpmLeft + i * (ledWidth + ledGap)

            val right =
                left + ledWidth

            val active =
                i < activeLeds

            val color =
                when {

                    i > 20 -> Color.rgb(
                        255,
                        40,
                        40
                    )

                    i > 16 -> Color.rgb(
                        255,
                        120,
                        0
                    )

                    else -> Color.rgb(
                        255,
                        255 - (i * 4),
                        255 - (i * 6)
                    )
                }

            val ledPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {

                    this.color =
                        if (active) {
                            color
                        } else {
                            DARK_RED
                        }
                }

            canvas.drawRoundRect(
                RectF(
                    left,
                    rpmTop,
                    right,
                    rpmBottom
                ),
                2f,
                2f,
                ledPaint
            )
        }

        // =====================================================
        // LAYOUT
        // =====================================================

        val startY =
            rpmBottom + height * 0.10f

        val endY =
            height * 0.86f

        val gap =
            width * 0.02f

        val centerWidth =
            width * 0.31f

        val sideWidth =
            width * 0.25f

        val cxL =
            (width / 2f) - (centerWidth / 2f)

        val cxR =
            (width / 2f) + (centerWidth / 2f)

        val lxR =
            cxL - gap

        val lxL =
            lxR - sideWidth

        val rxL =
            cxR + gap

        val rxR =
            rxL + sideWidth

        val centerEndY =
            startY + (endY - startY) * 0.72f

        // =====================================================
        // GUIDE LINES
        // =====================================================

        // Línea roja izquierda
        canvas.drawLine(
            lxL + width * 0.012f,
            startY,
            lxL + width * 0.012f,
            endY,
            guidePaint
        )

        // Línea roja derecha
        canvas.drawLine(
            rxR - width * 0.012f,
            startY,
            rxR - width * 0.012f,
            endY,
            guidePaint
        )

        canvas.drawLine(
            cxL,
            startY,
            cxR,
            startY,
            softRedPaint
        )

        canvas.drawLine(
            cxL,
            startY,
            cxL,
            centerEndY,
            softRedPaint
        )

        canvas.drawLine(
            cxR,
            startY,
            cxR,
            centerEndY,
            softRedPaint
        )

        // =====================================================
        // SPEED / GEAR
        // =====================================================

        val speedDividerY =
            startY + (centerEndY - startY) * 0.16f

        canvas.drawLine(
            cxL,
            speedDividerY,
            cxR,
            speedDividerY,
            dividerPaint
        )

        val speedY =
            startY + (speedDividerY - startY) * 0.48f

        drawTextCentered(
            canvas,
            speedKmh.toInt().toString(),
            (cxL + cxR) / 2f,
            speedY,
            speedPaint
        )

        val gearY =
            speedDividerY +
                    ((centerEndY - speedDividerY) * 0.60f)

        drawTextCentered(
            canvas,
            gear.toString(),
            (cxL + cxR) / 2f,
            gearY,
            gearPaint
        )

        // =====================================================
        // ROW DRAWING FUNCTION (Con lógica de grados)
        // =====================================================

        fun drawDataRow(
            slotRect: RectF,
            label: String,
            value: String,
            unit: String,
            accent: Boolean = false
        ) {

            val paddingX = width * 0.020f

            canvas.drawText(
                label,
                slotRect.left + paddingX,
                slotRect.centerY(),
                labelPaint
            )

            val numberPaint = if (accent) accentPaint else valuePaint

            // Si la unidad es el símbolo de grado, no lo reducimos. Si es otra cosa, lo encogemos.
            val isDegree = unit.contains("°")
            val unitPaint = Paint(numberPaint).apply {
                textSize = if (isDegree) numberPaint.textSize else numberPaint.textSize * 0.55f
            }

            val unitWidth = unitPaint.measureText(unit)
            // Quitamos el margen extra si es un grado para que quede pegado al número
            val spaceBetween = if (unit.isNotEmpty() && !isDegree) width * 0.005f else 0f
            val rightMargin = slotRect.right - paddingX

            canvas.drawText(
                unit,
                rightMargin,
                slotRect.centerY() + height * 0.010f,
                unitPaint
            )

            canvas.drawText(
                value,
                rightMargin - unitWidth - spaceBetween,
                slotRect.centerY() + height * 0.010f,
                numberPaint
            )
        }

        // =====================================================
        // LEFT PANEL
        // =====================================================

        val leftData = listOf(
            Triple("OIL TEMP", "${oilTemp.toInt()}", "°"),
            Triple("BOOST PSI", String.format("%.1f", turboPressure), ""),
            Triple("INTAKE TEMP", "${intakeTemp.toInt()}", "°"),
            Triple("THROTTLE", "${throttlePercent.toInt()}", "%")
        )

        val rowHeight =
            (endY - startY) / leftData.size

        leftData.forEachIndexed { index, item ->

            val top =
                startY + rowHeight * index

            val slotRect =
                RectF(
                    lxL,
                    top,
                    lxR,
                    top + rowHeight
                )

            drawDataRow(
                slotRect,
                item.first,
                item.second,
                item.third,
                index == 1
            )

            if (index < leftData.size - 1) {

                canvas.drawLine(
                    lxL + width * 0.010f,
                    slotRect.bottom,
                    lxR - width * 0.010f,
                    slotRect.bottom,
                    dividerPaint
                )
            }
        }

        // =====================================================
        // RIGHT PANEL
        // =====================================================

        val rightData = listOf(
            Triple("AFR", String.format("%.1f", afr), ""),
            Triple("ENG LOAD", "${engineLoad.toInt()}", "%"),
            Triple("G-FORCE", String.format("%.1f", gForce), ""),
            Triple("MAF", "${maf.toInt()}", "G/S")
        )

        rightData.forEachIndexed { index, item ->

            val top =
                startY + rowHeight * index

            val slotRect =
                RectF(
                    rxL,
                    top,
                    rxR,
                    top + rowHeight
                )

            drawDataRow(
                slotRect,
                item.first,
                item.second,
                item.third,
                index == 2
            )

            if (index < rightData.size - 1) {

                canvas.drawLine(
                    rxL + width * 0.010f,
                    slotRect.bottom,
                    rxR - width * 0.010f,
                    slotRect.bottom,
                    dividerPaint
                )
            }
        }
    }

    // =========================================================
    // TEXT CENTER
    // =========================================================

    private fun drawTextCentered(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        paint: Paint,
    ) {

        val fm = paint.fontMetrics

        val baseline =
            cy - (fm.ascent + fm.descent) / 2f

        canvas.drawText(
            text,
            cx,
            baseline,
            paint
        )
    }

    // =========================================================
    // CONSTANTS
    // =========================================================

    private companion object {

        private const val TAG =
            "MainScreen"

        private const val RENDER_THREAD_NAME =
            "IbizaCustomMapSurface"

        private const val REFRESH_TICK_MS =
            500L

        // COLORS

        private val BG_BLACK =
            Color.argb(255, 4, 4, 4)

        private val RACING_RED =
            Color.argb(255, 180, 0, 0)

        private val SOFT_RED =
            Color.argb(255, 90, 0, 0)

        private val DARK_RED =
            Color.argb(255, 45, 0, 0)

        private val TEXT_WHITE =
            Color.argb(255, 240, 240, 240)

        private val TEXT_GRAY =
            Color.argb(255, 120, 120, 120)

        private val DIVIDER =
            Color.argb(255, 45, 45, 45)

        private val ACCENT_ORANGE =
            Color.rgb(255, 120, 0)
    }
}