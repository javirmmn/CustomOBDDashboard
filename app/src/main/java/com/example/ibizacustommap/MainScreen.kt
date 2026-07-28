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
import com.example.ibizacustommap.BuildConfig

class MainScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private var modoSimulacion = false
    private var obdManager: ObdManager? = null
    private var obdThread: Thread? = null

    @Volatile
    private var surfaceContainer: SurfaceContainer? = null

    private var speedKmh: Float = 0f
    private var gearDisplay: String = "N"
    private var activeLeds: Int = 0

    private var oilTemp: Float = 90f
    private var turboPressure: Float = 0f
    private var intakeTemp: Float = 30f
    private var throttlePercent: Float = 0f

    private var afr: Float = 14.7f
    private var engineLoad: Float = 0f
    private var gForce: Float = 0f
    private var maf: Float = 10f

    // VARIABLES CHIVATO DEBUG
    private var debugRawResponse: String = "ESPERANDO..."
    private var debugMafRaw: String = "-"
    private var debugAfrRaw: String = "-"

    private val telemetryLock = Any()
    private var simulationTick = 0f

    private val renderThread = HandlerThread(RENDER_THREAD_NAME).apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val redrawRunnable = Runnable { performRedrawSurface() }

    // Snapshot inmutable de telemetría para el hilo de render.
    // Evita leer directamente las vars mutadas por el hilo OBD sin lock.
    private data class TelemetrySnapshot(
        val speedKmh: Float,
        val gearDisplay: String,
        val activeLeds: Int,
        val oilTemp: Float,
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
        val modoSimulacion: Boolean,
    )

    private val refreshTickerRunnable = object : Runnable {
        override fun run() {
            if (surfaceContainer == null) return

            if (modoSimulacion) {
                simulationTick += 0.05f

                val waveSlow = ((Math.sin(simulationTick.toDouble() * 0.5) + 1.0) / 2.0).toFloat()
                val waveFast = ((Math.sin(simulationTick.toDouble() * 1.5) + 1.0) / 2.0).toFloat()
                val waveAFR = ((Math.cos(simulationTick.toDouble() * 0.7) + 1.0) / 2.0).toFloat()

                synchronized(telemetryLock) {
                    activeLeds = (waveFast * 24f).toInt()
                    speedKmh = 60f + (waveSlow * 90f)

                    gearDisplay = when {
                        speedKmh < 3 -> "N"
                        speedKmh < 30 -> "1"
                        speedKmh < 60 -> "2"
                        speedKmh < 90 -> "3"
                        speedKmh < 120 -> "4"
                        else -> "5"
                    }

                    oilTemp = 60f + (waveSlow * 70f)
                    turboPressure = -0.2f + (waveFast * 1.8f)
                    intakeTemp = 25f + (waveSlow * 40f)
                    throttlePercent = waveFast * 100f
                    afr = 10.0f + (waveAFR * 6.5f)
                    engineLoad = waveFast * 100f
                    gForce = waveFast * 1.2f
                    maf = 10f + (waveFast * 130f)
                }
            }

            scheduleRedraw()
            renderHandler.postDelayed(this, REFRESH_TICK_MS)
        }
    }

    private fun startObdPolling() {
        obdThread = Thread {
            if (obdManager == null) {
                obdManager = ObdManager(carContext)
            }

            Log.d(TAG, "Iniciando búsqueda de adaptador OBD2...")
            var attempts = 0
            var connected = false

            while (!connected && attempts < 5 && !Thread.currentThread().isInterrupted) {
                connected = obdManager?.connect() == true
                if (!connected) {
                    attempts++
                    Thread.sleep(1000)
                }
            }

            modoSimulacion = !connected

            if (modoSimulacion) {
                Log.d(TAG, "Activando MODO SIMULACIÓN automático.")
            } else {
                Log.d(TAG, "¡OBD2 Conectado! Ejecutando rutina de inicialización estricta.")
                try {
                    obdManager?.sendCommand("ATZ")
                    Thread.sleep(800)
                    obdManager?.readResponse()

                    obdManager?.sendCommand("ATE0")
                    Thread.sleep(300)
                    obdManager?.readResponse()

                    obdManager?.sendCommand("ATH0")
                    Thread.sleep(300)
                    obdManager?.readResponse()

                    obdManager?.sendCommand("ATS0")
                    Thread.sleep(300)
                    obdManager?.readResponse()

                    obdManager?.sendCommand("ATSP0")
                    Thread.sleep(500)
                    obdManager?.readResponse()

                    // Sin ATSH7E0: dejamos que ATSP0 autodetecte protocolo
                    // contra la dirección funcional por defecto.

                    obdManager?.sendCommand("ATDPN")
                    Thread.sleep(300)
                    val protocoloDetectado = obdManager?.readResponse() ?: "?"
                    Log.d(TAG, "Protocolo autodetectado (ATDPN): $protocoloDetectado")

                    Log.d(TAG, "Inicialización completada. Empezando a pedir datos...")
                } catch (e: Exception) {
                    Log.e(TAG, "Error iniciando el chip ELM327: ${e.message}")
                    modoSimulacion = true
                }
            }

            var loopCounter = 0

            while (!Thread.currentThread().isInterrupted) {
                if (modoSimulacion) {
                    Thread.sleep(1000)
                    continue
                }

                try {
                    // --- CARRIL RÁPIDO ---

                    obdManager?.sendCommand("01 0C")
                    val rawRpm = obdManager?.readResponse() ?: "VACIO"
                    val rpmCalc = ObdDecoder.parseRPM(rawRpm)

                    synchronized(telemetryLock) {
                        debugRawResponse = rawRpm
                    }
                    Thread.sleep(40)

                    obdManager?.sendCommand("01 0D")
                    val speedCalc = ObdDecoder.parseSpeed(obdManager?.readResponse() ?: "").toFloat()
                    Thread.sleep(40)

                    obdManager?.sendCommand("01 0B")
                    val boostCalc = ObdDecoder.parseBoost(obdManager?.readResponse() ?: "").toFloat()
                    Thread.sleep(40)

                    var currentGear = "N"
                    if (speedCalc >= 0f && rpmCalc >= 0) {
                        if (speedCalc < 3f) {
                            currentGear = "N"
                        } else {
                            val ratio = rpmCalc / speedCalc
                            currentGear = when {
                                ratio < 20f -> "N"
                                ratio > 110f -> "1"
                                ratio in 70f..110f -> "2"
                                ratio in 48f..70f -> "3"
                                ratio in 35f..48f -> "4"
                                ratio in 26f..35f -> "5"
                                ratio in 20f..26f -> "6"
                                else -> "N"
                            }
                        }
                    }

                    // --- CARRIL LENTO ---
                    var newOil = oilTemp
                    var newIntake = intakeTemp
                    var newLoad = engineLoad
                    var newMaf = maf
                    var newAfr = afr
                    var newThrottle = throttlePercent

                    if (loopCounter % 10 == 0) {
                        obdManager?.sendCommand("01 11")
                        val throttleCalc = ObdDecoder.parsePercentage(obdManager?.readResponse() ?: "", "11").toFloat()
                        if (throttleCalc >= 0f) newThrottle = throttleCalc
                        Thread.sleep(40)

                        obdManager?.sendCommand("01 5C")
                        var tRaw = obdManager?.readResponse() ?: ""
                        var tCalc = ObdDecoder.parseTemp(tRaw, "5C").toFloat()
                        if (tCalc <= -100f) {
                            obdManager?.sendCommand("01 05")
                            tRaw = obdManager?.readResponse() ?: ""
                            tCalc = ObdDecoder.parseTemp(tRaw, "05").toFloat()
                        }
                        if (tCalc > -100f) newOil = tCalc
                        Thread.sleep(40)

                        obdManager?.sendCommand("01 0F")
                        val intakeC = ObdDecoder.parseTemp(obdManager?.readResponse() ?: "", "0F").toFloat()
                        if (intakeC > -100f) newIntake = intakeC
                        Thread.sleep(40)

                        obdManager?.sendCommand("01 04")
                        val loadC = ObdDecoder.parsePercentage(obdManager?.readResponse() ?: "", "04").toFloat()
                        if (loadC >= 0f) newLoad = loadC
                        Thread.sleep(40)

                        // MAF: timeout corto (FAST_TIMEOUT_MS). Si tu ECU no lo
                        // soporta, no queremos que arrastre 1.5s el bucle entero.
                        obdManager?.sendCommand("01 10")
                        val rawMaf = obdManager?.readResponse(ObdManager.FAST_TIMEOUT_MS) ?: "VACIO"
                        val mafC = ObdDecoder.parseMAF(rawMaf).toFloat()
                        synchronized(telemetryLock) { debugMafRaw = rawMaf }
                        if (mafC >= 0f) newMaf = mafC
                        Thread.sleep(40)

                        // AFR: primero PID 44 (Commanded Equivalence Ratio).
                        // Si no responde, fallback a PID 34 (O2 Sensor Equivalence Ratio).
                        obdManager?.sendCommand("01 44")
                        var rawAfr = obdManager?.readResponse(ObdManager.FAST_TIMEOUT_MS) ?: "VACIO"
                        var afrC = ObdDecoder.parseAFR(rawAfr, "44")
                        if (afrC <= 0.0) {
                            Thread.sleep(40)
                            obdManager?.sendCommand("01 34")
                            rawAfr = obdManager?.readResponse(ObdManager.FAST_TIMEOUT_MS) ?: "VACIO"
                            afrC = ObdDecoder.parseAFR(rawAfr, "34")
                        }
                        synchronized(telemetryLock) { debugAfrRaw = rawAfr }
                        if (afrC > 0.0) newAfr = afrC.toFloat()
                        Thread.sleep(40)
                    }

                    synchronized(telemetryLock) {
                        if (rpmCalc >= 0) activeLeds = (rpmCalc / 300).coerceIn(0, 24)
                        if (speedCalc >= 0f) speedKmh = speedCalc
                        if (boostCalc > -100f) turboPressure = boostCalc
                        gearDisplay = currentGear

                        if (loopCounter % 10 == 0) {
                            throttlePercent = newThrottle
                            oilTemp = newOil
                            intakeTemp = newIntake
                            engineLoad = newLoad
                            maf = newMaf
                            afr = newAfr
                        }
                    }

                    loopCounter++
                    if (loopCounter > 1000) loopCounter = 0

                    Thread.sleep(50)

                } catch (e: Exception) {
                    Log.e(TAG, "Conexión OBD2 perdida en marcha: ${e.message}")
                    obdManager?.closeConnection()
                    modoSimulacion = true
                }
            }
        }
        obdThread?.start()
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
        startObdPolling()

        carContext
            .getCarService(NavigationManager::class.java)
            .setNavigationManagerCallback(navigationManagerCallback)

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

                    obdThread?.interrupt()
                    obdManager?.closeConnection()
                    renderHandler.removeCallbacksAndMessages(null)
                    renderThread.quitSafely()
                }
            },
        )
    }

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

        val snapshot: TelemetrySnapshot
        synchronized(telemetryLock) {
            snapshot = TelemetrySnapshot(
                speedKmh = speedKmh,
                gearDisplay = gearDisplay,
                activeLeds = activeLeds,
                oilTemp = oilTemp,
                turboPressure = turboPressure,
                intakeTemp = intakeTemp,
                throttlePercent = throttlePercent,
                afr = afr,
                engineLoad = engineLoad,
                gForce = gForce,
                maf = maf,
                debugRawResponse = debugRawResponse,
                debugMafRaw = debugMafRaw,
                debugAfrRaw = debugAfrRaw,
                modoSimulacion = modoSimulacion,
            )
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
            drawGt3Dashboard(canvas, w, h, snapshot)
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

    private data class TelemetryItem(val label: String, val value: String, val unit: String, val dynamicColor: Int)

    private fun drawGt3Dashboard(
        canvas: Canvas,
        width: Int,
        height: Int,
        s: TelemetrySnapshot,
    ) {
        canvas.drawColor(BG_BLACK)

        val density = getCarContext().resources.displayMetrics.density

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

        drawTextCentered(canvas, s.speedKmh.toInt().toString(), (cxL + cxR) / 2f, speedY, speedPaint)

        val gearY = speedDividerY + ((centerEndY - speedDividerY) * 0.60f)
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
            s.intakeTemp >= 55f -> ALERT_RED
            s.intakeTemp >= 45f -> ACCENT_ORANGE
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

        val loadColor = when {
            s.engineLoad >= 95f -> ALERT_RED
            s.engineLoad >= 85f -> ACCENT_ORANGE
            else -> TEXT_WHITE
        }

        val gForceColor = if (s.gForce >= 1.0f) ACCENT_ORANGE else TEXT_WHITE

        val mafColor = TEXT_WHITE

        val leftData = listOf(
            TelemetryItem("OIL TEMP", "${s.oilTemp.toInt()}", "°", oilColor),
            TelemetryItem("BOOST BAR", String.format("%.1f", s.turboPressure), "", boostColor),
            TelemetryItem("INTAKE TEMP", "${s.intakeTemp.toInt()}", "°", intakeColor),
            TelemetryItem("THROTTLE", "${s.throttlePercent.toInt()}", "%", throttleColor)
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

        val rightData = listOf(
            TelemetryItem("AFR", String.format("%.1f", s.afr), "", afrColor),
            TelemetryItem("ENG LOAD", "${s.engineLoad.toInt()}", "%", loadColor),
            TelemetryItem("G-FORCE", String.format("%.1f", s.gForce), "", gForceColor),
            TelemetryItem("MAF", "${s.maf.toInt()}", "G/S", mafColor)
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
        // Ahora incluye MAF y AFR crudos para diagnosticar PIDs
        // no soportados.
        // =====================================================
        if (BuildConfig.DEBUG) {
            val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.YELLOW
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = height * 0.022f
                textAlign = Paint.Align.LEFT
            }

            val displayText = if (s.modoSimulacion) {
                "SIM_OK"
            } else {
                "RPM:${s.debugRawResponse} MAF:${s.debugMafRaw} AFR:${s.debugAfrRaw}"
            }

            canvas.drawText(displayText, width * 0.02f, height * 0.045f, debugPaint)
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
        private const val REFRESH_TICK_MS = 33L

        private val BG_BLACK = Color.argb(255, 4, 4, 4)
        private val SOFT_RED = Color.argb(255, 90, 0, 0)
        private val DARK_RED = Color.argb(255, 45, 0, 0)
        private val TEXT_WHITE = Color.argb(255, 240, 240, 240)
        private val TEXT_GRAY = Color.argb(255, 120, 120, 120)
        private val DIVIDER = Color.argb(255, 45, 45, 45)

        private val ACCENT_ORANGE = Color.rgb(255, 120, 0)
        private val ALERT_RED = Color.rgb(255, 50, 50)
    }
}