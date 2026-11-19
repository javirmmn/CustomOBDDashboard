package com.example.ibizacustommap

import android.graphics.Canvas
import android.graphics.Rect
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

    private var modoSimulacion = false
    private var obdManager: ObdManager? = null
    private var obdThread: Thread? = null

    @Volatile
    private var surfaceContainer: SurfaceContainer? = null

    // NaN = todavía no ha llegado ninguna lectura válida de la ECU → se pinta "--".
    // (Antes arrancaban con valores inventados, p. ej. MAF=10, que se quedaban
    // fijos para siempre si el PID no estaba soportado.)
    private var speedKmh: Float = Float.NaN
    private var gearDisplay: String = "N"
    private var activeLeds: Int = 0

    private var oilTemp: Float = Float.NaN
    private var oilTempFromCoolant: Boolean = false // true si el valor viene del PID 05 (refrigerante)
    private var turboPressure: Float = Float.NaN
    private var intakeTemp: Float = Float.NaN
    private var throttlePercent: Float = Float.NaN

    private var afr: Float = Float.NaN
    private var engineLoad: Float = Float.NaN
    private var gForce: Float = 0f // solo simulación; en real sale de gForceSensor
    private var maf: Float = Float.NaN

    private val gearEstimator = GearEstimator()
    private val gForceSensor = GForceSensor(carContext)

    // VARIABLES CHIVATO DEBUG
    private var debugRawResponse: String = "ESPERANDO..."
    private var debugMafRaw: String = "-"
    private var debugAfrRaw: String = "-"
    private var debugGearRatio: Float = Float.NaN

    private val telemetryLock = Any()
    private var simulationTick = 0f

    private val renderThread = HandlerThread(RENDER_THREAD_NAME).apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val redrawRunnable = Runnable { performRedrawSurface() }

    // TelemetrySnapshot vive en DashboardRenderer.kt: es el mismo tipo que usa
    // la vista previa del móvil (DashboardPreviewActivity), para garantizar
    // que ambas pantallas dibujen exactamente lo mismo.

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

                    val currentGear = gearEstimator.update(rpmCalc, speedCalc)
                    val gearRatio = if (rpmCalc >= 0 && speedCalc >= 3f) rpmCalc / speedCalc else Float.NaN

                    // --- CARRIL LENTO ---
                    var newOil = oilTemp
                    var newOilFromCoolant = oilTempFromCoolant
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
                        var tFromCoolant = false
                        if (tCalc <= -100f) {
                            obdManager?.sendCommand("01 05")
                            tRaw = obdManager?.readResponse() ?: ""
                            tCalc = ObdDecoder.parseTemp(tRaw, "05").toFloat()
                            tFromCoolant = true
                        }
                        if (tCalc > -100f) {
                            newOil = tCalc
                            newOilFromCoolant = tFromCoolant
                        }
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

                        // AFR: primero PID 34 (sonda lambda B1S1 = mezcla MEDIDA).
                        // Si no responde, fallback a PID 44 (mezcla que PIDE la ECU:
                        // es un objetivo, a crucero vale siempre 14.7).
                        obdManager?.sendCommand("01 34")
                        var rawAfr = obdManager?.readResponse(ObdManager.FAST_TIMEOUT_MS) ?: "VACIO"
                        var afrC = ObdDecoder.parseAFR(rawAfr, "34")
                        if (afrC <= 0.0) {
                            Thread.sleep(40)
                            obdManager?.sendCommand("01 44")
                            rawAfr = obdManager?.readResponse(ObdManager.FAST_TIMEOUT_MS) ?: "VACIO"
                            afrC = ObdDecoder.parseAFR(rawAfr, "44")
                        }
                        synchronized(telemetryLock) { debugAfrRaw = rawAfr }
                        if (afrC > 0.0) newAfr = afrC.toFloat()
                        Thread.sleep(40)
                    }

                    synchronized(telemetryLock) {
                        if (rpmCalc >= 0) activeLeds = (rpmCalc / DashboardRenderer.RPM_PER_LED).coerceIn(0, 24)
                        if (speedCalc >= 0f) speedKmh = speedCalc
                        if (boostCalc > -100f) turboPressure = boostCalc
                        gearDisplay = currentGear
                        debugGearRatio = gearRatio

                        if (loopCounter % 10 == 0) {
                            throttlePercent = newThrottle
                            oilTemp = newOil
                            oilTempFromCoolant = newOilFromCoolant
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
        // Solo informativo: el diagnóstico del móvil lo usa para explicar por
        // qué el adaptador puede estar ocupado. No cambia la conexión.
        ObdLinkState.dashboardActive = true
        startObdPolling()
        gForceSensor.start()

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

                    gForceSensor.stop()
                    obdThread?.interrupt()
                    obdManager?.closeConnection()
                    renderHandler.removeCallbacksAndMessages(null)
                    renderThread.quitSafely()
                    ObdLinkState.dashboardActive = false
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
                oilTempFromCoolant = oilTempFromCoolant,
                turboPressure = turboPressure,
                intakeTemp = intakeTemp,
                throttlePercent = throttlePercent,
                afr = afr,
                engineLoad = engineLoad,
                gForce = if (modoSimulacion) gForce else gForceSensor.currentG,
                maf = maf,
                debugRawResponse = debugRawResponse,
                debugMafRaw = debugMafRaw,
                debugAfrRaw = debugAfrRaw,
                debugGearRatio = debugGearRatio,
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
            DashboardRenderer.draw(canvas, w, h, getCarContext().resources.displayMetrics.density, snapshot)
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

    // El dibujo del salpicadero vive en DashboardRenderer.kt (compartido con
    // DashboardPreviewActivity). Aquí solo se llama a DashboardRenderer.draw(...)
    // desde performRedrawSurface().

    private companion object {
        private const val TAG = "MainScreen"
        private const val RENDER_THREAD_NAME = "IbizaCustomMapSurface"
        private const val REFRESH_TICK_MS = 33L
    }
}