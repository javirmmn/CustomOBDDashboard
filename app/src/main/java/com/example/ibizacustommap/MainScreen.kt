package com.example.ibizacustommap

import android.content.SharedPreferences
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

/**
 * Pantalla del salpicadero en Android Auto. Solo gestiona el ciclo de vida,
 * la superficie y la fuente de datos:
 * - Escáner real: [ObdPoller] (conexión, polling, reconexión y estados).
 * - Simulación: [DashboardSimulator], SOLO si se activa en la app del móvil
 *   (AppSettings). Nunca se activa sola; se ve con la franja "SIMULACIÓN".
 */
class MainScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    @Volatile
    private var surfaceContainer: SurfaceContainer? = null

    private val gForceSensor = GForceSensor(carContext)

    // Fuente de datos. Se cambian solo desde el hilo principal; el hilo de
    // render solo las lee (de ahí @Volatile).
    @Volatile
    private var simulation = false

    @Volatile
    private var poller: ObdPoller? = null

    private var simulationTick = 0f // solo hilo de render

    private val prefs = AppSettings.prefs(carContext)

    // Se guarda en un campo: SharedPreferences solo guarda referencias débiles.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppSettings.KEY_SIMULATION) applySource(AppSettings.isSimulation(getCarContext()))
    }

    private val renderThread = HandlerThread(RENDER_THREAD_NAME).apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val redrawRunnable = Runnable { performRedrawSurface() }

    private val refreshTickerRunnable = object : Runnable {
        override fun run() {
            if (surfaceContainer == null) return
            if (simulation) simulationTick += 0.05f
            scheduleRedraw()
            renderHandler.postDelayed(this, REFRESH_TICK_MS)
        }
    }

    /** Cambia entre escáner real y simulación. Hilo principal. */
    private fun applySource(sim: Boolean) {
        simulation = sim
        if (sim) {
            // Libera el adaptador: la simulación no lo necesita.
            poller?.stop()
            poller = null
            ObdLinkState.link.value = LinkState.SIMULATION
        } else if (poller == null) {
            poller = ObdPoller(
                connector = BluetoothElmConnector(carContext),
                onState = { ObdLinkState.link.value = it },
            ).also { it.start() }
        }
        scheduleRedraw()
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
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        applySource(AppSettings.isSimulation(carContext))
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

                    prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
                    gForceSensor.stop()
                    // Interrumpe el hilo OBD y cierra el socket; el hilo termina solo.
                    poller?.stop()
                    poller = null
                    renderHandler.removeCallbacksAndMessages(null)
                    renderThread.quitSafely()
                    ObdLinkState.link.value = null
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

    /** Lo que hay que pintar ahora. Hilo de render. */
    private fun currentSnapshot(): TelemetrySnapshot =
        if (simulation) {
            DashboardSimulator.at(simulationTick)
        } else {
            (poller?.snapshot ?: TelemetrySnapshot.empty(LinkState.SEARCHING))
                .copy(gForce = gForceSensor.currentG)
        }

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

        val snapshot = currentSnapshot()

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

    private companion object {
        private const val TAG = "MainScreen"
        private const val RENDER_THREAD_NAME = "IbizaCustomMapSurface"
        private const val REFRESH_TICK_MS = 33L
    }
}
