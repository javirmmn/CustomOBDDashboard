package com.example.ibizacustommap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdPollerTest {

    /**
     * ELM327 falso. [responder] da, para cada comando, la lista de lo que
     * devolverán las lecturas sucesivas (la 2ª es la que recoge el drenaje tras
     * un TIMEOUT). Si se acaba, devuelve "TIMEOUT".
     */
    private class FakeLink(
        private val failAfterSends: Int = Int.MAX_VALUE,
        private val responder: (String) -> List<String>,
    ) : ElmIo {
        override var isConnected = true
        var closed = false
        val sent = mutableListOf<String>()
        private val pending = ArrayDeque<String>()

        override fun sendCommand(command: String) {
            if (closed || sent.size >= failAfterSends) throw ElmLinkException("Bluetooth caído")
            sent += command
            pending.clear()
            pending.addAll(responder(command))
        }

        override fun readResponse(timeoutMs: Long): String {
            if (closed) throw ElmLinkException("Bluetooth caído")
            return pending.removeFirstOrNull() ?: "TIMEOUT"
        }

        override fun close() {
            closed = true
            isConnected = false
        }

        fun count(cmd: String) = sent.count { it == cmd }
    }

    private class FakeConnector(private vararg val results: ConnectResult) : ElmConnector {
        var attempts = 0
        var aborts = 0
        override fun connect(): ConnectResult = results.getOrElse(attempts++) { ConnectResult.Failed }
        override fun abort() {
            aborts++
        }
    }

    /** No duerme: apunta la espera y para el bucle (InterruptedException) cuando [stop] lo dice. */
    private class FakeSleeper(private val stop: () -> Boolean) : Sleeper {
        val sleeps = mutableListOf<Long>()
        override fun sleep(ms: Long) {
            if (stop()) throw InterruptedException()
            sleeps += ms
        }
    }

    // 2055 rpm a 50 km/h = 41.1 rpm/(km/h): 3ª marcha.
    private val car: (String) -> List<String> = { cmd ->
        listOf(
            when (cmd) {
                "ATDPN" -> "A6"
                "01 0C" -> "410C201C" // 2055 rpm
                "01 0D" -> "410D32" // 50 km/h
                "01 0B" -> "410B96" // 150 kPa → 0.5 bar
                "01 11" -> "41117F" // 49 %
                "01 5C" -> "NODATA"
                "01 05" -> "41055A" // 50 °C
                "01 0F" -> "410F46" // 30 °C
                "01 04" -> "410480" // 50 %
                "01 10" -> "NODATA"
                "01 34" -> "NODATA"
                "01 44" -> "41448000" // lambda 1.0
                else -> "OK"
            },
        )
    }

    private fun poller(connector: ElmConnector, sleeper: Sleeper, states: MutableList<LinkState>) =
        ObdPoller(connector, sleeper, onState = { states += it }, log = {})

    @Test
    fun sinAdaptadorNoSimulaYReintentaConEsperaCreciente() {
        val connector = FakeConnector()
        val states = mutableListOf<LinkState>()
        lateinit var sleeper: FakeSleeper
        sleeper = FakeSleeper { sleeper.sleeps.size >= 6 }
        val p = poller(connector, sleeper, states)

        p.run()

        assertEquals(listOf(LinkState.SEARCHING, LinkState.NO_CONNECTION), states)
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L, 30000L, 30000L), sleeper.sleeps)
        assertEquals(LinkState.NO_CONNECTION, p.snapshot.link)
        assertEquals("--", p.snapshot.gearDisplay)
        assertTrue(p.snapshot.speedKmh.isNaN())
        assertEquals(0, p.snapshot.activeLeds)
    }

    @Test
    fun sinPermisoLoDiceEnVezDeRomperse() {
        val connector = FakeConnector(ConnectResult.NoPermission, ConnectResult.BluetoothOff)
        val states = mutableListOf<LinkState>()
        lateinit var sleeper: FakeSleeper
        sleeper = FakeSleeper { sleeper.sleeps.size >= 1 }
        poller(connector, sleeper, states).run()
        assertEquals(listOf(LinkState.SEARCHING, LinkState.NO_PERMISSION, LinkState.BLUETOOTH_OFF), states)
    }

    @Test
    fun enVivoConLosMismosComandosYValores() {
        val link = FakeLink(responder = car)
        val states = mutableListOf<LinkState>()
        val p = poller(FakeConnector(ConnectResult.Connected(link)), FakeSleeper { link.count("01 0C") >= 3 }, states)

        p.run()

        assertEquals(listOf("ATZ", "ATE0", "ATH0", "ATS0", "ATSP0", "ATDPN"), link.sent.take(6))
        assertEquals(
            listOf("01 0C", "01 0D", "01 0B", "01 11", "01 5C", "01 05", "01 0F", "01 04", "01 10", "01 34", "01 44"),
            link.sent.drop(6).take(11),
        )
        assertEquals(listOf(LinkState.SEARCHING, LinkState.CONNECTING, LinkState.LIVE), states)
        val s = p.snapshot
        assertEquals(LinkState.LIVE, s.link)
        assertEquals(50f, s.speedKmh, 0.01f)
        assertEquals(8, s.activeLeds)
        assertEquals(0.5f, s.turboPressure, 0.01f)
        assertEquals(49f, s.throttlePercent, 0.01f)
        assertEquals(50f, s.oilTemp, 0.01f)
        assertTrue(s.oilTempFromCoolant)
        assertEquals(30f, s.intakeTemp, 0.01f)
        assertEquals(50f, s.engineLoad, 0.01f)
        assertEquals(14.7f, s.afr, 0.01f)
        assertTrue(s.maf.isNaN())
        assertEquals("3", s.gearDisplay) // confirmada tras 2 lecturas
        // El hilo terminó por la interrupción: conexión cerrada, sin reconectar.
        assertTrue(link.closed)
    }

    @Test
    fun siSeCortaLaConexionVuelveADashYReconecta() {
        val dead = FakeLink(failAfterSends = 25, responder = car)
        val good = FakeLink(responder = car)
        val states = mutableListOf<LinkState>()
        val seenAfterDrop = mutableListOf<TelemetrySnapshot>()
        lateinit var p: ObdPoller
        val connector = FakeConnector(ConnectResult.Connected(dead), ConnectResult.Connected(good))
        p = ObdPoller(
            connector,
            FakeSleeper { good.count("01 0C") >= 2 },
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
        // Al perder la conexión los valores no se quedan congelados.
        val dropped = seenAfterDrop.single()
        assertTrue(dropped.speedKmh.isNaN())
        assertTrue(dropped.turboPressure.isNaN())
        assertEquals("--", dropped.gearDisplay)
        assertEquals(LinkState.LIVE, p.snapshot.link)
    }

    @Test
    fun trasUnTimeoutLaRespuestaTardiaNoSeLeeComoLaSiguiente() {
        val link = FakeLink { cmd ->
            if (cmd == "01 0D") listOf("TIMEOUT", "410D32") else car(cmd)
        }
        val p = poller(FakeConnector(ConnectResult.Connected(link)), FakeSleeper { link.count("01 0C") >= 2 }, mutableListOf())

        p.run()

        // Sin drenaje, "01 0B" habría leído "410D32" y el boost se perdería.
        assertEquals(0.5f, p.snapshot.turboPressure, 0.01f)
        assertTrue(p.snapshot.speedKmh.isNaN())
    }

    @Test
    fun adaptadorColgadoProvocaReconexion() {
        val hung = FakeLink { cmd -> if (cmd.startsWith("01")) emptyList() else car(cmd) }
        val states = mutableListOf<LinkState>()
        val connector = FakeConnector(ConnectResult.Connected(hung))
        val p = poller(connector, FakeSleeper { states.lastOrNull() == LinkState.RECONNECTING }, states)

        p.run()

        assertTrue(hung.closed)
        assertEquals(3, hung.sent.count { it.startsWith("01") }) // 3 TIMEOUT seguidos y fuera
        assertEquals(LinkState.RECONNECTING, states.last())
    }

    @Test
    fun siLaEcuDejaDeContestarLosValoresPasanADash() {
        // Dos ciclos con datos y luego la ECU calla (contacto quitado, bus dormido…).
        lateinit var link: FakeLink
        link = FakeLink { cmd -> if (link.count("01 0C") > 2 && cmd.startsWith("01")) listOf("NODATA") else car(cmd) }
        val states = mutableListOf<LinkState>()
        val p = poller(
            FakeConnector(ConnectResult.Connected(link)),
            FakeSleeper { states.lastOrNull() == LinkState.NO_ECU_DATA },
            states,
        )

        p.run()

        assertEquals(LinkState.LIVE, states[2])
        assertEquals(LinkState.NO_ECU_DATA, p.snapshot.link)
        assertTrue(p.snapshot.speedKmh.isNaN())
        assertTrue(p.snapshot.oilTemp.isNaN())
        assertEquals("--", p.snapshot.gearDisplay)
        assertEquals(0, p.snapshot.activeLeds)
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
