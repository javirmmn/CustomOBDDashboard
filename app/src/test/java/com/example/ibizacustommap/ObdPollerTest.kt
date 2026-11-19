package com.example.ibizacustommap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdPollerTest {

    private class FakeClock {
        var now = 0L
    }

    /**
     * ECU + ELM327 falsos que responden como el coche real (diagnóstico del
     * 2026-11-19): mapa de PIDs soportados, multi-PID, formato multi-trama
     * "0080:...1:..." y sufijo de nº de tramas.
     */
    private class FakeEcu(val values: MutableMap<Int, String>) {
        /** Si true, cualquier sufijo devuelve solo la primera trama. */
        var counterCutsToFirstFrame = false
        var multiPidSupported = true
        var silent = false

        fun respond(cmd: String): List<String> {
            if (cmd.startsWith("AT")) return listOf(if (cmd == "ATDPN") "A6" else "OK")
            if (silent) return listOf("NODATA")
            val body = cmd.removePrefix("01")
            val digit = if (body.length % 2 == 1) body.last().digitToInt() else null
            val pids = body.dropLast(if (digit != null) 1 else 0).chunked(2).map { it.toInt(16) }
            if (pids.size == 1 && pids[0] % 0x20 == 0) return listOf(bitmap(pids[0]))
            var present = pids.filter { it in values }
            if (!multiPidSupported) present = present.take(1)
            if (present.isEmpty()) return listOf("NODATA")
            val payload = "41" + present.joinToString("") { "%02X".format(it) + values.getValue(it) }
            val frames = frames(payload)
            val keep = when {
                digit == null -> frames.size
                counterCutsToFirstFrame -> 1
                else -> digit
            }
            return listOf(frames.take(keep).joinToString(""))
        }

        private fun bitmap(base: Int): String {
            var bits = 0L
            for (i in 1..32) {
                val on = if (i == 32) values.keys.any { it > base + 32 } else (base + i) in values
                if (on) bits = bits or (1L shl (32 - i))
            }
            return "41%02X%08X".format(base, bits)
        }

        private fun frames(payload: String): List<String> {
            val n = payload.length / 2
            if (n <= 7) return listOf(payload)
            val out = mutableListOf("%03X".format(n) + "0:" + payload.substring(0, 12))
            var rest = payload.substring(12)
            var idx = 1
            while (rest.isNotEmpty()) {
                out += "%X".format(idx % 16) + ":" + rest.take(14).padEnd(14, '0')
                rest = rest.drop(14)
                idx++
            }
            return out
        }
    }

    /** Enlace falso sobre [ecu]. Cada lectura avanza el reloj lo que tardaría en el coche. */
    private class FakeLink(
        private val ecu: FakeEcu,
        private val clock: FakeClock,
        private val failAfterSends: Int = Int.MAX_VALUE,
        private val override: (cmd: String, nth: Int) -> List<String>? = { _, _ -> null },
        private val stopWhen: () -> Boolean = { false },
    ) : ElmIo {
        override var isConnected = true
        var closed = false
        val sent = mutableListOf<String>()
        private val pending = ArrayDeque<String>()
        private var lastCmd = ""

        override fun sendCommand(command: String) {
            if (stopWhen()) throw InterruptedException()
            if (closed || sent.size >= failAfterSends) throw ElmLinkException("Bluetooth caído")
            sent += command
            lastCmd = command
            pending.clear()
            pending.addAll(override(command, sent.count { it == command }) ?: ecu.respond(command))
        }

        override fun readResponse(timeoutMs: Long): String {
            if (closed) throw ElmLinkException("Bluetooth caído")
            clock.now += when {
                lastCmd.startsWith("AT") -> 5L
                lastCmd.length % 2 == 1 -> 45L // con sufijo
                lastCmd.length > 4 -> 70L // multi-PID sin sufijo
                else -> 60L
            }
            return pending.removeFirstOrNull() ?: "TIMEOUT"
        }

        override fun close() {
            closed = true
            isConnected = false
        }

        fun fastCount() = sent.count { it.startsWith("010C0D0B") || it == "010C" }
        fun pidsSent(): Set<Int> = sent.filter { it.startsWith("01") }.flatMap { c ->
            val body = c.removePrefix("01")
            body.dropLast(body.length % 2).chunked(2).map { it.toInt(16) }
        }.toSet()
    }

    private class FakeConnector(private vararg val results: ConnectResult) : ElmConnector {
        var attempts = 0
        var aborts = 0
        override fun connect(): ConnectResult = results.getOrElse(attempts++) { ConnectResult.Failed }
        override fun abort() {
            aborts++
        }
    }

    private class FakeSleeper(private val stop: () -> Boolean = { false }) : Sleeper {
        val sleeps = mutableListOf<Long>()
        override fun sleep(ms: Long) {
            if (stop()) throw InterruptedException()
            sleeps += ms
        }
    }

    /** Valores del coche real (ralentí del diagnóstico) con 2055 rpm a 50 km/h = 3ª. */
    private fun car() = FakeEcu(
        mutableMapOf(
            0x0C to "201C", // 2055 rpm
            0x0D to "32", // 50 km/h
            0x0B to "96", // MAP 150 kPa
            0x33 to "65", // baro 101 kPa
            0x49 to "23", // pedal D en reposo (13 %)
            0x11 to "33",
            0x04 to "80", // carga 50 %
            0x0E to "56", // avance -21°
            0x44 to "8000", // lambda 1.0
            0x05 to "5A", // refrigerante 50 °C
            0x0F to "46", // admisión 30 °C
        ),
    )

    private fun poller(
        connector: ElmConnector,
        clock: FakeClock,
        sleeper: Sleeper = FakeSleeper(),
        states: MutableList<LinkState> = mutableListOf(),
        rates: MutableList<Float?> = mutableListOf(),
    ) = ObdPoller(connector, sleeper, { clock.now }, onState = { states += it }, onRate = { rates += it }, log = {})

    @Test
    fun enVivoSoloPideLoSoportadoEnGrupoYSinEsperas() {
        val clock = FakeClock()
        lateinit var link: FakeLink
        link = FakeLink(car(), clock, stopWhen = { link.fastCount() >= 40 })
        val states = mutableListOf<LinkState>()
        val rates = mutableListOf<Float?>()
        val sleeper = FakeSleeper()
        val p = poller(FakeConnector(ConnectResult.Connected(link)), clock, sleeper, states, rates)

        p.run()

        // Misma inicialización de siempre y luego el mapa de PIDs.
        assertEquals(listOf("ATZ", "ATE0", "ATH0", "ATS0", "ATSP0", "ATDPN", "0100", "0120", "0140"), link.sent.take(9))
        // Nunca pregunta por lo que la ECU no tiene (aceite, MAF, lambda medida).
        assertTrue(link.pidsSent().none { it in setOf(0x5C, 0x10, 0x34) })
        // Las únicas esperas son las de la inicialización: el polling no duerme.
        assertEquals(listOf(800L, 300L, 300L, 300L, 500L, 300L), sleeper.sleeps)
        // Calibra 5 con sufijo y 5 sin él, y se queda con el sufijo (más rápido).
        assertEquals(5, link.sent.count { it == "010C0D0B" })
        assertEquals("010C0D0B2", link.sent.last { it.startsWith("010C0D0B") })
        // El grupo medio va en una sola petición.
        assertTrue(link.sent.contains("0149040E44"))

        assertEquals(listOf(LinkState.SEARCHING, LinkState.CONNECTING, LinkState.LIVE), states)
        val s = p.snapshot
        assertEquals(50f, s.speedKmh, 0.01f)
        assertEquals(8, s.activeLeds)
        assertEquals(0.49f, s.turboPressure, 0.001f) // (150 − 101) / 100, no − 100 fijos
        assertTrue(s.throttleIsPedal)
        assertEquals(0f, s.throttlePercent, 0.01f) // pedal en reposo
        assertEquals(50f, s.engineLoad, 0.01f)
        assertEquals(-21f, s.ignitionTiming, 0.01f)
        assertEquals(1.0f, s.lambda, 0.001f)
        assertEquals(50f, s.oilTemp, 0.01f)
        assertTrue(s.oilTempFromCoolant)
        assertEquals(30f, s.intakeTemp, 0.01f)
        assertEquals("3", s.gearDisplay)
        assertTrue(link.closed)
        // Con las latencias del coche: muy por encima de los ~2 Hz de antes.
        val hz = rates.filterNotNull()
        assertTrue("rates=$rates", hz.isNotEmpty() && hz.all { it > 10f })
    }

    @Test
    fun siElSufijoCortaLaRespuestaSeUsaSinSufijo() {
        val clock = FakeClock()
        val ecu = car().apply { counterCutsToFirstFrame = true }
        lateinit var link: FakeLink
        link = FakeLink(ecu, clock, stopWhen = { link.fastCount() >= 20 })
        val p = poller(FakeConnector(ConnectResult.Connected(link)), clock)

        p.run()

        assertEquals(1, link.sent.count { it == "010C0D0B2" }) // una incompleta y se descarta
        assertEquals("010C0D0B", link.sent.last { it.startsWith("010C0D0B") })
        assertEquals(0.49f, p.snapshot.turboPressure, 0.001f)
        assertEquals(50f, p.snapshot.speedKmh, 0.01f)
    }

    @Test
    fun siLaEcuNoSabeResponderEnGrupoPidePidAPid() {
        val clock = FakeClock()
        val ecu = car().apply { multiPidSupported = false }
        lateinit var link: FakeLink
        link = FakeLink(ecu, clock, stopWhen = { link.sent.count { it == "010B" } >= 5 })
        val p = poller(FakeConnector(ConnectResult.Connected(link)), clock)

        p.run()

        assertTrue(link.sent.contains("010D"))
        assertEquals(50f, p.snapshot.speedKmh, 0.01f)
        assertEquals(0.49f, p.snapshot.turboPressure, 0.001f)
        assertEquals(50f, p.snapshot.engineLoad, 0.01f)
    }

    @Test
    fun trasUnTimeoutLaRespuestaTardiaNoSeLeeComoLaSiguiente() {
        val clock = FakeClock()
        lateinit var link: FakeLink
        link = FakeLink(
            car(),
            clock,
            override = { cmd, nth ->
                if (cmd == "010C0D0B2" && nth == 1) listOf("TIMEOUT", "0080:410C201C0D321:0B960000000000") else null
            },
            stopWhen = { link.fastCount() >= 2 },
        )
        val p = poller(FakeConnector(ConnectResult.Connected(link)), clock)

        p.run()

        // El grupo medio, justo después del TIMEOUT, lee SU respuesta.
        assertEquals(50f, p.snapshot.engineLoad, 0.01f)
        assertEquals(-21f, p.snapshot.ignitionTiming, 0.01f)
        assertEquals(1.0f, p.snapshot.lambda, 0.001f)
    }

    @Test
    fun siSeCortaLaConexionVuelveADashYReconecta() {
        val clock = FakeClock()
        val dead = FakeLink(car(), clock, failAfterSends = 30)
        lateinit var good: FakeLink
        good = FakeLink(car(), clock, stopWhen = { good.fastCount() >= 3 })
        val connector = FakeConnector(ConnectResult.Connected(dead), ConnectResult.Connected(good))
        val states = mutableListOf<LinkState>()
        val seenAfterDrop = mutableListOf<TelemetrySnapshot>()
        lateinit var p: ObdPoller
        p = ObdPoller(
            connector, FakeSleeper(), { clock.now },
            onState = { states += it; if (it == LinkState.RECONNECTING) seenAfterDrop += p.snapshot },
            log = {},
        )

        p.run()

        assertTrue(dead.closed)
        assertEquals(2, connector.attempts)
        assertEquals(
            listOf(
                LinkState.SEARCHING, LinkState.CONNECTING, LinkState.LIVE,
                LinkState.RECONNECTING, LinkState.CONNECTING, LinkState.LIVE,
            ),
            states,
        )
        val dropped = seenAfterDrop.single()
        assertTrue(dropped.speedKmh.isNaN())
        assertTrue(dropped.turboPressure.isNaN())
        assertEquals("--", dropped.gearDisplay)
        assertEquals(LinkState.LIVE, p.snapshot.link)
    }

    @Test
    fun adaptadorColgadoProvocaReconexion() {
        val clock = FakeClock()
        val states = mutableListOf<LinkState>()
        val hung = FakeLink(car(), clock, override = { cmd, _ -> if (cmd.startsWith("010C")) emptyList() else null })
        val p = poller(
            FakeConnector(ConnectResult.Connected(hung)),
            clock,
            FakeSleeper { states.lastOrNull() == LinkState.RECONNECTING },
            states,
        )

        p.run()

        assertTrue(hung.closed)
        assertEquals(3, hung.sent.count { it.startsWith("010C") }) // 3 TIMEOUT seguidos y fuera
        assertEquals(LinkState.RECONNECTING, states.last())
    }

    @Test
    fun siLaEcuDejaDeContestarLosValoresPasanADash() {
        val clock = FakeClock()
        val ecu = car()
        val states = mutableListOf<LinkState>()
        val rates = mutableListOf<Float?>()
        lateinit var link: FakeLink
        link = FakeLink(ecu, clock, stopWhen = {
            if (link.fastCount() >= 3) ecu.silent = true // contacto quitado
            states.lastOrNull() == LinkState.NO_ECU_DATA
        })
        val p = poller(FakeConnector(ConnectResult.Connected(link)), clock, states = states, rates = rates)

        p.run()

        assertEquals(LinkState.LIVE, states[2])
        assertEquals(LinkState.NO_ECU_DATA, p.snapshot.link)
        assertTrue(p.snapshot.speedKmh.isNaN())
        assertTrue(p.snapshot.oilTemp.isNaN())
        assertTrue(p.snapshot.turboPressure.isNaN())
        assertEquals("--", p.snapshot.gearDisplay)
        assertEquals(0, p.snapshot.activeLeds)
        assertEquals(null, rates.last())
    }

    @Test
    fun sinAdaptadorNoSimulaYReintentaConEsperaCreciente() {
        val states = mutableListOf<LinkState>()
        lateinit var sleeper: FakeSleeper
        sleeper = FakeSleeper { sleeper.sleeps.size >= 6 }
        val p = poller(FakeConnector(), FakeClock(), sleeper, states)

        p.run()

        assertEquals(listOf(LinkState.SEARCHING, LinkState.NO_CONNECTION), states)
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L, 30000L, 30000L), sleeper.sleeps)
        assertEquals("--", p.snapshot.gearDisplay)
        assertTrue(p.snapshot.speedKmh.isNaN())
        assertEquals(0, p.snapshot.activeLeds)
    }

    @Test
    fun sinPermisoLoDiceEnVezDeRomperse() {
        val states = mutableListOf<LinkState>()
        lateinit var sleeper: FakeSleeper
        sleeper = FakeSleeper { sleeper.sleeps.size >= 1 }
        poller(FakeConnector(ConnectResult.NoPermission, ConnectResult.BluetoothOff), FakeClock(), sleeper, states).run()
        assertEquals(listOf(LinkState.SEARCHING, LinkState.NO_PERMISSION, LinkState.BLUETOOTH_OFF), states)
    }

    @Test
    fun stopTerminaElHiloDeVerdad() {
        val connector = FakeConnector() // siempre falla: el hilo queda en la espera de 2 s
        val p = ObdPoller(connector, log = {})
        p.start()
        Thread.sleep(100)
        p.stop()
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline && pollerThreadAlive()) Thread.sleep(20)
        assertFalse("el hilo ObdPoller sigue vivo", pollerThreadAlive())
        assertTrue(connector.aborts >= 1)
    }

    private fun pollerThreadAlive() = Thread.getAllStackTraces().keys.any { it.name == "ObdPoller" && it.isAlive }

    @Test
    fun esperaCreciente() {
        assertEquals(2000L, ObdPoller.backoffMs(0))
        assertEquals(2000L, ObdPoller.backoffMs(1))
        assertEquals(4000L, ObdPoller.backoffMs(2))
        assertEquals(16000L, ObdPoller.backoffMs(4))
        assertEquals(30000L, ObdPoller.backoffMs(5))
        assertEquals(30000L, ObdPoller.backoffMs(50))
    }
}
