package com.example.ibizacustommap

import android.util.Log
import java.io.IOException
import java.util.Locale

/** Espera inyectable (en los tests no se duerme de verdad). */
fun interface Sleeper {
    @Throws(InterruptedException::class)
    fun sleep(ms: Long)
}

/**
 * Conexión y lectura del ELM327 en su propio hilo.
 *
 * Conexión (Fase 0): sin adaptador nunca simula; reintenta con espera
 * creciente. Si la conexión se corta o el adaptador se cuelga, valores a NaN
 * ("--") y reconexión sola. Si la ECU calla, "SIN DATOS DE LA ECU". [stop]
 * termina el hilo siempre.
 *
 * Lectura (Fase 1, basada en el diagnóstico real del coche):
 * - Al conectar se lee el mapa de PIDs soportados (0100, 0120…) y solo se
 *   pide lo que existe: se acabaron los ~240 ms por PID inexistente (5C, 10,
 *   34) que daban tirones.
 * - Sin esperas fijas: el prompt '>' del ELM327 ya es el control de flujo.
 * - Grupos por prioridad, cada uno en UNA petición multi-PID:
 *   rápido (RPM, velocidad, MAP) en cada ciclo; medio (pedal, carga, avance,
 *   lambda) ~2,5 Hz; temperaturas cada 5 s; presión barométrica cada minuto.
 *   En cada ciclo va el grupo rápido y, como mucho, otro grupo pendiente.
 * - El grupo rápido prueba al empezar con y sin el sufijo de nº de tramas y
 *   se queda con el más rápido que llegue completo. Si la ECU no contesta bien
 *   en grupo, se pasa a pedir los PIDs de uno en uno.
 * - Boost = MAP − presión barométrica (PID 33), no − 100 fijos.
 *
 * El último estado se publica en [snapshot] (inmutable, @Volatile).
 */
class ObdPoller(
    private val connector: ElmConnector,
    private val sleeper: Sleeper = Sleeper { Thread.sleep(it) },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onState: (LinkState) -> Unit = {},
    /** Lecturas por segundo del grupo rápido (null si no hay datos). Cada ~1 s. */
    private val onRate: (Float?) -> Unit = {},
    private val log: (String) -> Unit = { Log.d(TAG, it) },
) {

    @Volatile
    var snapshot: TelemetrySnapshot = TelemetrySnapshot.empty(LinkState.SEARCHING)
        private set

    @Volatile
    private var stopped = false
    private var thread: Thread? = null
    private var lastState: LinkState? = null

    // Se conserva entre reconexiones: lo aprendido del pedal sigue valiendo.
    private val pedalScaler = PedalScaler()

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
                            if (!stopped) onRate(null)
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

    // ------------------------------------------------------------------
    // Sesión: inicialización + lectura sobre una conexión abierta
    // ------------------------------------------------------------------

    /** Sale lanzando IOException (conexión perdida) o InterruptedException (stop). */
    private fun session(link: ElmIo) {
        publish(TelemetrySnapshot.empty(LinkState.CONNECTING))
        initElm(link)

        val supported = discoverSupported(link)
        val groups = buildGroups(supported)
        val fast = groups.first()
        val others = groups.drop(1)
        log("Grupos: " + groups.joinToString { "${it.name}=${ObdDiagnostics.formatPids(it.pids)}" })

        val r = Readings(oilSupported = PidSpec.OIL in supported, pedal = groups.any { g ->
            g.pids.any { it == PidSpec.PEDAL_D || it == PidSpec.PEDAL_E }
        })
        var gearEstimator = GearEstimator()
        var gearKnown = false
        var gearRatio = Float.NaN
        var debugRaw = "-"
        var fastTimeouts = 0
        var lastFastData = clock()
        var rateStart = clock()
        var rateCycles = 0
        var state = LinkState.CONNECTING

        // Varios TIMEOUT seguidos en el grupo rápido (que siempre responde) =
        // adaptador colgado → reconectar.
        val onFastReply: (ElmReply) -> Unit = { reply ->
            fastTimeouts = if (reply is ElmReply.Timeout) fastTimeouts + 1 else 0
            if (fastTimeouts >= MAX_FAST_TIMEOUTS) throw ElmLinkException("el adaptador no responde")
            debugRaw = reply.raw
        }

        while (true) {
            if (stopped || Thread.currentThread().isInterrupted) throw InterruptedException()

            // --- Grupo rápido: en cada ciclo ---
            val fastValues = readGroup(link, fast, onFastReply)
            val rpm = fastValues[PidSpec.RPM]?.let { ObdDecoder.parseRPM(raw(PidSpec.RPM, it)) } ?: -1
            val speed = fastValues[PidSpec.SPEED]?.let { ObdDecoder.parseSpeed(raw(PidSpec.SPEED, it)) } ?: -1
            r.apply(fastValues)

            // --- Como mucho, otro grupo pendiente (el más atrasado) ---
            val now = clock()
            others.filter { it.nextDueMs <= now }.minByOrNull { it.nextDueMs }?.let { g ->
                g.nextDueMs = now + g.intervalMs
                r.apply(readGroup(link, g) {})
            }

            val gear = gearEstimator.update(rpm, speed.toFloat())
            if (fastValues.isNotEmpty()) {
                lastFastData = clock()
                if (rpm >= 0 && speed >= 0) {
                    gearKnown = true
                    gearRatio = if (speed >= 3) rpm.toFloat() / speed else Float.NaN
                }
                state = LinkState.LIVE
                rateCycles++
            } else if (clock() - lastFastData >= NO_ECU_DATA_AFTER_MS) {
                // La ECU no contesta: nada de valores congelados.
                if (state != LinkState.NO_ECU_DATA) onRate(null)
                r.clear()
                gearEstimator = GearEstimator()
                gearKnown = false
                gearRatio = Float.NaN
                state = LinkState.NO_ECU_DATA
            }

            publish(r.toSnapshot(if (gearKnown) gear else "--", debugRaw, gearRatio, state))

            val elapsed = clock() - rateStart
            if (elapsed >= RATE_WINDOW_MS) {
                if (state == LinkState.LIVE) onRate(rateCycles * 1000f / elapsed)
                rateStart = clock()
                rateCycles = 0
            }
        }
    }

    /** Misma secuencia y esperas que la versión que ya funcionaba en el coche (no tocar sin probar allí). */
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

    /** Mapa de PIDs soportados (0100, 0120…). Si no se puede leer, los que se usaban antes. */
    private fun discoverSupported(link: ElmIo): Set<Int> {
        val all = sortedSetOf<Int>()
        var base = 0x00
        while (base <= 0xC0) {
            val cmd = "01%02X".format(Locale.ROOT, base)
            val timeout = if (base == 0) SEARCH_TIMEOUT_MS else ObdManager.DEFAULT_TIMEOUT_MS
            var maps = ObdDiagnostics.parseSupportedPids(link.query(cmd, timeout).data, base)
            // 0100 es la primera petición OBD: puede coincidir con la búsqueda de protocolo.
            if (maps.isEmpty() && base == 0) maps = ObdDiagnostics.parseSupportedPids(link.query(cmd, timeout).data, base)
            if (maps.isEmpty()) break
            val merged = maps.flatten()
            all.addAll(merged)
            if ((base + 0x20) !in merged) break
            base += 0x20
        }
        if (all.isEmpty()) {
            log("No se pudo leer el mapa de PIDs; se usan los de siempre")
            return FALLBACK_SUPPORTED
        }
        log("PIDs soportados: ${ObdDiagnostics.formatPids(all)}")
        return all
    }

    // ------------------------------------------------------------------
    // Grupos de PIDs
    // ------------------------------------------------------------------

    private enum class Mode { COUNTED, PLAIN, SINGLE }

    private class PidGroup(val name: String, val pids: List<Int>, val intervalMs: Long, fast: Boolean) {
        var mode: Mode = when {
            pids.size == 1 -> Mode.SINGLE
            fast -> Mode.COUNTED
            else -> Mode.PLAIN
        }
        var nextDueMs = 0L
        var badReplies = 0

        // Calibración (solo grupo rápido): mide con y sin sufijo y elige.
        var calibrating = fast && pids.size > 1
        val countedMs = mutableListOf<Long>()
        val plainMs = mutableListOf<Long>()
    }

    private fun buildGroups(s: Set<Int>): List<PidGroup> {
        fun pick(vararg pids: Int) = pids.filter { it in s }
        val pedal = when {
            PidSpec.PEDAL_D in s -> PidSpec.PEDAL_D
            PidSpec.PEDAL_E in s -> PidSpec.PEDAL_E
            PidSpec.THROTTLE in s -> PidSpec.THROTTLE
            else -> null
        }
        val temp = if (PidSpec.OIL in s) PidSpec.OIL else PidSpec.COOLANT
        return listOf(
            PidGroup("rápido", pick(PidSpec.RPM, PidSpec.SPEED, PidSpec.MAP), 0, fast = true),
            PidGroup("medio", listOfNotNull(pedal) + pick(PidSpec.LOAD, PidSpec.TIMING, PidSpec.LAMBDA_CMD), MEDIUM_INTERVAL_MS, fast = false),
            PidGroup("lento", pick(temp, PidSpec.INTAKE), SLOW_INTERVAL_MS, fast = false),
            PidGroup("baro", pick(PidSpec.BARO), BARO_INTERVAL_MS, fast = false),
        ).filter { it.pids.isNotEmpty() || it.name == "rápido" }
    }

    /** Pide un grupo y devuelve PID → datos (hex) de lo que llegó bien. */
    private fun readGroup(link: ElmIo, g: PidGroup, onReply: (ElmReply) -> Unit): Map<Int, String> {
        if (g.pids.isEmpty()) return emptyMap()
        if (g.mode == Mode.SINGLE) {
            val out = mutableMapOf<Int, String>()
            g.pids.forEach { pid ->
                val reply = link.query("01%02X".format(Locale.ROOT, pid))
                onReply(reply)
                parse(reply)?.get(pid)?.let { out[pid] = it }
            }
            return out
        }

        val t0 = clock()
        val reply = link.query(PidSpec.command(g.pids, g.mode == Mode.COUNTED))
        val ms = clock() - t0
        onReply(reply)
        val values = parse(reply) ?: emptyMap()
        // NO DATA / errores de bus son de la ECU (p. ej. contacto quitado), no
        // del formato: no cuentan como "la ECU no sabe responder en grupo".
        if (reply is ElmReply.Data || reply is ElmReply.NotUnderstood) {
            if (values.keys.containsAll(g.pids)) {
                g.badReplies = 0
                if (g.calibrating) recordCalibration(g, ms)
            } else {
                onIncomplete(g)
            }
        }
        return values
    }

    private fun parse(reply: ElmReply): Map<Int, String>? =
        if (reply is ElmReply.Data) {
            ObdDiagnostics.splitMultiPid(ObdDiagnostics.joinFrames(reply.raw), PidSpec.DATA_BYTES)
        } else {
            null
        }

    private fun recordCalibration(g: PidGroup, ms: Long) {
        if (g.mode == Mode.COUNTED) {
            g.countedMs += ms
            if (g.countedMs.size >= CALIBRATION_SAMPLES) g.mode = Mode.PLAIN
        } else if (g.mode == Mode.PLAIN) {
            g.plainMs += ms
            if (g.plainMs.size >= CALIBRATION_SAMPLES) {
                g.calibrating = false
                val counted = g.countedMs.size >= CALIBRATION_SAMPLES &&
                    g.countedMs.average() <= g.plainMs.average()
                g.mode = if (counted) Mode.COUNTED else Mode.PLAIN
                log("Grupo ${g.name}: con sufijo %.0f ms, sin sufijo %.0f ms → ${g.mode}".format(
                    Locale.ROOT,
                    if (g.countedMs.isEmpty()) Double.NaN else g.countedMs.average(),
                    g.plainMs.average(),
                ))
            }
        }
    }

    private fun onIncomplete(g: PidGroup) {
        if (g.calibrating && g.mode == Mode.COUNTED) {
            // El sufijo corta la respuesta: se descarta y se sigue calibrando sin él.
            log("Grupo ${g.name}: respuesta incompleta con sufijo, se descarta")
            g.countedMs.clear()
            g.mode = Mode.PLAIN
            return
        }
        g.badReplies++
        if (g.badReplies >= MAX_BAD_REPLIES) {
            g.badReplies = 0
            g.calibrating = false
            g.mode = if (g.mode == Mode.COUNTED) Mode.PLAIN else Mode.SINGLE
            log("Grupo ${g.name}: respuestas incompletas → ${g.mode}")
        }
    }

    // ------------------------------------------------------------------
    // Valores
    // ------------------------------------------------------------------

    private inner class Readings(private val oilSupported: Boolean, private val pedal: Boolean) {
        var speed = Float.NaN
        var rpm = Float.NaN
        var mapKpa = Float.NaN
        var baroKpa = DEFAULT_BARO_KPA
        var temp = Float.NaN
        var intake = Float.NaN
        var throttle = Float.NaN
        var load = Float.NaN
        var timing = Float.NaN
        var lambda = Float.NaN

        fun apply(values: Map<Int, String>) {
            values.forEach { (pid, data) ->
                val raw = raw(pid, data)
                when (pid) {
                    PidSpec.RPM -> ObdDecoder.parseRPM(raw).takeIf { it >= 0 }?.let {
                        rpm = it.toFloat()
                    }
                    PidSpec.SPEED -> ObdDecoder.parseSpeed(raw).takeIf { it >= 0 }?.let { speed = it.toFloat() }
                    PidSpec.MAP -> ObdDecoder.parseMap(raw).takeIf { it >= 0 }?.let { mapKpa = it.toFloat() }
                    PidSpec.BARO -> ObdDecoder.parseBarometricPressure(raw).takeIf { it in 60..115 }?.let { baroKpa = it.toFloat() }
                    PidSpec.PEDAL_D, PidSpec.PEDAL_E, PidSpec.THROTTLE ->
                        ObdDecoder.parsePercentage(raw, "%02X".format(Locale.ROOT, pid)).takeIf { it >= 0 }?.let {
                            throttle = if (pedal) pedalScaler.scale(it.toFloat()) else it.toFloat()
                        }
                    PidSpec.LOAD -> ObdDecoder.parsePercentage(raw, "04").takeIf { it >= 0 }?.let { load = it.toFloat() }
                    PidSpec.TIMING -> ObdDecoder.parseTimingAdvance(raw).takeIf { it > -100 }?.let { timing = it.toFloat() }
                    PidSpec.LAMBDA_CMD -> ObdDecoder.parseLambda(raw, "44").takeIf { it > 0 }?.let { lambda = it.toFloat() }
                    PidSpec.COOLANT, PidSpec.OIL, PidSpec.INTAKE ->
                        ObdDecoder.parseTemp(raw, "%02X".format(Locale.ROOT, pid)).takeIf { it > -100 }?.let {
                            if (pid == PidSpec.INTAKE) intake = it.toFloat() else temp = it.toFloat()
                        }
                }
            }
        }

        /** Sin datos de la ECU: todo a NaN (la presión barométrica se conserva). */
        fun clear() {
            speed = Float.NaN; rpm = Float.NaN; mapKpa = Float.NaN; temp = Float.NaN; intake = Float.NaN
            throttle = Float.NaN; load = Float.NaN; timing = Float.NaN; lambda = Float.NaN
        }

        fun toSnapshot(gear: String, debugRaw: String, gearRatio: Float, link: LinkState) = TelemetrySnapshot(
            speedKmh = speed,
            gearDisplay = gear,
            rpm = rpm,
            oilTemp = temp,
            oilTempFromCoolant = !oilSupported,
            turboPressure = if (mapKpa.isNaN()) Float.NaN else (mapKpa - baroKpa) / 100f,
            intakeTemp = intake,
            throttlePercent = throttle,
            throttleIsPedal = pedal,
            lambda = lambda,
            engineLoad = load,
            gForce = Float.NaN, // la pone MainScreen desde GForceSensor
            ignitionTiming = timing,
            debugRawResponse = debugRaw,
            debugGearRatio = gearRatio,
            link = link,
        )
    }

    private fun raw(pid: Int, data: String) = "41%02X".format(Locale.ROOT, pid) + data

    private fun publish(s: TelemetrySnapshot) {
        if (stopped) return
        snapshot = s
        if (s.link != lastState) {
            lastState = s.link
            log("Estado: ${s.link}")
            onState(s.link)
        }
    }

    companion object {
        private const val TAG = "ObdPoller"
        private const val THREAD_NAME = "ObdPoller"
        const val ATTEMPTS_BEFORE_NO_CONNECTION = 3
        const val MAX_FAST_TIMEOUTS = 3
        const val NO_ECU_DATA_AFTER_MS = 3000L
        const val RECONNECT_DELAY_MS = 1000L
        const val MEDIUM_INTERVAL_MS = 400L
        const val SLOW_INTERVAL_MS = 5000L
        const val BARO_INTERVAL_MS = 60_000L
        const val RATE_WINDOW_MS = 1000L
        private const val SEARCH_TIMEOUT_MS = 5000L
        private const val CALIBRATION_SAMPLES = 5
        private const val MAX_BAD_REPLIES = 2
        /** Hasta leer el PID 33, la referencia de antes (100 kPa). */
        private const val DEFAULT_BARO_KPA = 100f

        /** Si no se puede leer el mapa: los PIDs que la app ya pedía y existen en casi todas las ECU. */
        private val FALLBACK_SUPPORTED = setOf(
            PidSpec.RPM, PidSpec.SPEED, PidSpec.MAP, PidSpec.THROTTLE, PidSpec.LOAD,
            PidSpec.COOLANT, PidSpec.INTAKE, PidSpec.LAMBDA_CMD,
        )

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
