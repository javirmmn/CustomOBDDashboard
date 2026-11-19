package com.example.ibizacustommap

import android.util.Log
import java.io.IOException

/** Espera inyectable (en los tests no se duerme de verdad). */
fun interface Sleeper {
    @Throws(InterruptedException::class)
    fun sleep(ms: Long)
}

/**
 * Conexión y polling del ELM327 en su propio hilo, sacados de MainScreen
 * (Fase 0 del plan). Los comandos de inicialización, los PIDs y las esperas
 * del polling son LOS MISMOS que tenía MainScreen; lo que cambia es la
 * gestión de errores:
 *
 * - Sin adaptador nunca entra en simulación: estado BUSCANDO y luego SIN
 *   CONEXIÓN, reintentando con espera creciente (2, 4, 8, 16, 30 s).
 * - Si la conexión se corta (Bluetooth caído, adaptador colgado), los valores
 *   vuelven a NaN ("--"), estado RECONECTANDO, y se reconecta solo.
 * - Tras un TIMEOUT se recoge la respuesta tardía (ElmIo.query): ya no se
 *   lee como la del comando siguiente.
 * - Si la ECU deja de dar datos (adaptador vivo), los valores pasan a "--"
 *   con estado SIN DATOS DE LA ECU en vez de quedarse congelados.
 * - [stop] interrumpe el hilo y cierra el socket: el hilo termina siempre,
 *   sin zombis ni crash por InterruptedException.
 *
 * El último estado se publica en [snapshot] (inmutable, @Volatile): el hilo
 * de render lo lee sin locks.
 */
class ObdPoller(
    private val connector: ElmConnector,
    private val sleeper: Sleeper = Sleeper { Thread.sleep(it) },
    private val onState: (LinkState) -> Unit = {},
    private val log: (String) -> Unit = { Log.d(TAG, it) },
) {

    @Volatile
    var snapshot: TelemetrySnapshot = TelemetrySnapshot.empty(LinkState.SEARCHING)
        private set

    @Volatile
    private var stopped = false
    private var thread: Thread? = null
    private var lastState: LinkState? = null

    fun start() {
        check(thread == null) { "ObdPoller ya arrancado" }
        thread = Thread({ run() }, THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    /** Para el polling. No bloquea: el hilo termina en cuanto sale de la espera o de la E/S. */
    fun stop() {
        stopped = true
        thread?.interrupt()
        connector.abort()
    }

    /** Bucle principal. Público para los tests (se llama en el hilo del test). */
    internal fun run() {
        var failures = 0
        var everConnected = false
        try {
            publish(TelemetrySnapshot.empty(LinkState.SEARCHING))
            while (!stopped && !Thread.currentThread().isInterrupted) {
                when (val result = connector.connect()) {
                    is ConnectResult.Connected -> {
                        everConnected = true
                        failures = 0
                        try {
                            session(result.link)
                        } catch (e: IOException) {
                            log("Conexión OBD perdida: ${e.message}")
                        } catch (e: RuntimeException) {
                            log("Error inesperado en el polling: $e")
                        } finally {
                            result.link.close()
                        }
                        publish(TelemetrySnapshot.empty(LinkState.RECONNECTING))
                        sleeper.sleep(RECONNECT_DELAY_MS)
                        continue
                    }
                    ConnectResult.NoPermission -> publish(TelemetrySnapshot.empty(LinkState.NO_PERMISSION))
                    ConnectResult.BluetoothOff -> publish(TelemetrySnapshot.empty(LinkState.BLUETOOTH_OFF))
                    ConnectResult.Failed -> {
                        failures++
                        val state = when {
                            failures >= ATTEMPTS_BEFORE_NO_CONNECTION -> LinkState.NO_CONNECTION
                            everConnected -> LinkState.RECONNECTING
                            else -> LinkState.SEARCHING
                        }
                        publish(TelemetrySnapshot.empty(state))
                    }
                }
                sleeper.sleep(backoffMs(failures))
            }
        } catch (e: InterruptedException) {
            // stop(): salida normal del hilo.
        } finally {
            log("Hilo OBD terminado")
        }
    }

    /**
     * Inicialización + polling sobre una conexión abierta. Sale lanzando
     * IOException (conexión perdida) o InterruptedException (stop).
     */
    private fun session(link: ElmIo) {
        publish(TelemetrySnapshot.empty(LinkState.CONNECTING))
        initElm(link)

        val gearEstimator = GearEstimator()
        var r = Readings()
        var loopCounter = 0
        var fastTimeouts = 0
        var silentCycles = 0
        var debugRaw = "ESPERANDO..."
        var debugMafRaw = "-"
        var debugAfrRaw = "-"
        var gearRatio = Float.NaN

        // Un TIMEOUT suelto puede pasar; varios seguidos en PIDs que siempre
        // responden (0C/0D/0B) significan adaptador colgado → reconectar.
        fun fast(cmd: String): ElmReply {
            val reply = link.query(cmd)
            fastTimeouts = if (reply is ElmReply.Timeout) fastTimeouts + 1 else 0
            if (fastTimeouts >= MAX_FAST_TIMEOUTS) throw ElmLinkException("el adaptador no responde")
            return reply
        }

        while (true) {
            if (stopped || Thread.currentThread().isInterrupted) throw InterruptedException()

            // --- CARRIL RÁPIDO ---
            val rpmReply = fast("01 0C")
            val rpmCalc = ObdDecoder.parseRPM(rpmReply.data)
            debugRaw = rpmReply.raw
            sleeper.sleep(40)

            val speedCalc = ObdDecoder.parseSpeed(fast("01 0D").data).toFloat()
            sleeper.sleep(40)

            val boostCalc = ObdDecoder.parseBoost(fast("01 0B").data).toFloat()
            sleeper.sleep(40)

            val currentGear = gearEstimator.update(rpmCalc, speedCalc)
            val ratio = if (rpmCalc >= 0 && speedCalc >= 3f) rpmCalc / speedCalc else Float.NaN

            // --- CARRIL LENTO ---
            val slow = loopCounter % 10 == 0
            if (slow) {
                val throttleCalc = ObdDecoder.parsePercentage(link.query("01 11").data, "11").toFloat()
                if (throttleCalc >= 0f) r.throttle = throttleCalc
                sleeper.sleep(40)

                var tCalc = ObdDecoder.parseTemp(link.query("01 5C").data, "5C").toFloat()
                var tFromCoolant = false
                if (tCalc <= -100f) {
                    tCalc = ObdDecoder.parseTemp(link.query("01 05").data, "05").toFloat()
                    tFromCoolant = true
                }
                if (tCalc > -100f) {
                    r.oil = tCalc
                    r.oilFromCoolant = tFromCoolant
                }
                sleeper.sleep(40)

                val intakeC = ObdDecoder.parseTemp(link.query("01 0F").data, "0F").toFloat()
                if (intakeC > -100f) r.intake = intakeC
                sleeper.sleep(40)

                val loadC = ObdDecoder.parsePercentage(link.query("01 04").data, "04").toFloat()
                if (loadC >= 0f) r.load = loadC
                sleeper.sleep(40)

                // MAF: timeout corto. Si la ECU no lo soporta, no queremos que
                // arrastre 1.5 s el bucle entero.
                val mafReply = link.query("01 10", ObdManager.FAST_TIMEOUT_MS)
                debugMafRaw = mafReply.raw
                val mafC = ObdDecoder.parseMAF(mafReply.data).toFloat()
                if (mafC >= 0f) r.maf = mafC
                sleeper.sleep(40)

                // AFR: primero PID 34 (lambda medida B1S1); si no, PID 44 (lambda pedida).
                var afrReply = link.query("01 34", ObdManager.FAST_TIMEOUT_MS)
                var afrC = ObdDecoder.parseAFR(afrReply.data, "34")
                if (afrC <= 0.0) {
                    sleeper.sleep(40)
                    afrReply = link.query("01 44", ObdManager.FAST_TIMEOUT_MS)
                    afrC = ObdDecoder.parseAFR(afrReply.data, "44")
                }
                debugAfrRaw = afrReply.raw
                if (afrC > 0.0) r.afr = afrC.toFloat()
                sleeper.sleep(40)
            }

            val gotFastData = rpmCalc >= 0 || speedCalc >= 0f || boostCalc > -100f
            val state: LinkState
            if (gotFastData) {
                silentCycles = 0
                if (rpmCalc >= 0) r.leds = (rpmCalc / DashboardRenderer.RPM_PER_LED).coerceIn(0, 24)
                if (speedCalc >= 0f) r.speed = speedCalc
                if (boostCalc > -100f) r.boost = boostCalc
                if (rpmCalc >= 0 && speedCalc >= 0f) r.gearKnown = true
                gearRatio = ratio
                state = LinkState.LIVE
            } else {
                silentCycles++
                if (silentCycles >= SILENT_CYCLES_BEFORE_NO_DATA) {
                    // La ECU no contesta: nada de valores congelados.
                    r = Readings()
                    gearRatio = Float.NaN
                    state = LinkState.NO_ECU_DATA
                } else {
                    state = lastState ?: LinkState.LIVE
                }
            }

            publish(
                TelemetrySnapshot(
                    speedKmh = r.speed,
                    gearDisplay = if (r.gearKnown) currentGear else "--",
                    activeLeds = r.leds,
                    oilTemp = r.oil,
                    oilTempFromCoolant = r.oilFromCoolant,
                    turboPressure = r.boost,
                    intakeTemp = r.intake,
                    throttlePercent = r.throttle,
                    afr = r.afr,
                    engineLoad = r.load,
                    gForce = Float.NaN, // la pone MainScreen desde GForceSensor
                    maf = r.maf,
                    debugRawResponse = debugRaw,
                    debugMafRaw = debugMafRaw,
                    debugAfrRaw = debugAfrRaw,
                    debugGearRatio = gearRatio,
                    link = state,
                ),
            )

            loopCounter++
            if (loopCounter > 1000) loopCounter = 0

            sleeper.sleep(50)
        }
    }

    /** Misma secuencia y esperas que tenía MainScreen (no tocar sin probar en el coche). */
    private fun initElm(link: ElmIo) {
        INIT_SEQUENCE.forEach { (cmd, waitMs) ->
            link.sendCommand(cmd)
            sleeper.sleep(waitMs)
            link.readResponse()
        }
        // Sin ATSH7E0: dejamos que ATSP0 autodetecte protocolo contra la
        // dirección funcional por defecto.
        link.sendCommand("ATDPN")
        sleeper.sleep(300)
        log("Protocolo autodetectado (ATDPN): ${link.readResponse()}")
    }

    private fun publish(s: TelemetrySnapshot) {
        if (stopped) return
        snapshot = s
        if (s.link != lastState) {
            lastState = s.link
            log("Estado: ${s.link}")
            onState(s.link)
        }
    }

    /** Lecturas de la sesión actual; NaN = sin dato válido todavía. */
    private class Readings {
        var speed = Float.NaN
        var leds = 0
        var boost = Float.NaN
        var oil = Float.NaN
        var oilFromCoolant = false
        var intake = Float.NaN
        var throttle = Float.NaN
        var load = Float.NaN
        var maf = Float.NaN
        var afr = Float.NaN
        var gearKnown = false
    }

    companion object {
        private const val TAG = "ObdPoller"
        private const val THREAD_NAME = "ObdPoller"
        const val ATTEMPTS_BEFORE_NO_CONNECTION = 3
        const val MAX_FAST_TIMEOUTS = 3
        const val SILENT_CYCLES_BEFORE_NO_DATA = 8
        const val RECONNECT_DELAY_MS = 1000L

        private val INIT_SEQUENCE = listOf(
            "ATZ" to 800L,
            "ATE0" to 300L,
            "ATH0" to 300L,
            "ATS0" to 300L,
            "ATSP0" to 500L,
        )

        /** 2, 2, 4, 8, 16, 30, 30… segundos. */
        fun backoffMs(failures: Int): Long {
            val exp = (failures - 1).coerceIn(0, 4)
            return minOf(2000L shl exp, 30_000L)
        }
    }
}
