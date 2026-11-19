package com.example.ibizacustommap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Date

class ObdDiagnosticsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * ELM327 falso: responde según [responses] (comando → respuesta limpia, como
     * la deja ObdManager.readResponse). Lo que no está en el mapa responde
     * "NODATA". Una respuesta "TIMEOUT" deja [late] pendiente para la siguiente
     * lectura, como haría un adaptador real que contesta tarde.
     */
    private class FakeElmIo(
        private val responses: Map<String, String>,
        private val late: String = "",
        private val interruptOn: String? = null,
    ) : ElmIo {
        override var isConnected = true
        val sent = mutableListOf<String>()
        private var pending: String? = null

        override fun sendCommand(command: String) {
            sent += command
            if (command == interruptOn) Thread.currentThread().interrupt()
        }

        override fun readResponse(timeoutMs: Long): String {
            pending?.let { pending = null; return it }
            val r = responses[sent.last()] ?: "NODATA"
            if (r == "TIMEOUT") pending = late
            return r
        }
    }

    // 0100 = 18 3F 80 01 → 04 05 0B 0C 0D 0E 0F 10 11 y el bit 20 (existe el rango 0120).
    private val car = mapOf(
        "ATI" to "ELM327v1.5",
        "AT@1" to "OBDIItoRS232Interpreter",
        "ATRV" to "12.6V",
        "0100" to "4100183F8001",
        "0120" to "412000002000", // 0x33 soportado
        "ATDPN" to "A6",
        "ATDP" to "AUTO,ISO15765-4(CAN11/500)",
        "010C" to "410C1AF8",
        "010C1" to "410C1AF8",
        "010D" to "410D32",
        "010B" to "410B65",
        "010C0D" to "410C1AF80D32",
        "010C0D1" to "410C1AF80D32",
        "010C0D0B" to "0080:410C1AF80D321:0B65AAAAAAAAAA",
        "010C0D0B1" to "0080:410C1AF80D321:0B65AAAAAAAAAA",
        "ATAT1" to "OK",
        "ATAT2" to "OK",
        "ATST19" to "OK",
        "ATST32" to "OK",
    )

    @Test
    fun informeCompletoConAdaptadorFalso() {
        val elm = FakeElmIo(car)
        val lines = mutableListOf<String>()
        val report = ObdDiagnostics(elm, sink = { lines += it }).run(listOf("App: test"))

        assertTrue(report.contains("App: test"))
        assertTrue(report.contains("ATI  (versión)     : ELM327v1.5"))
        assertTrue(report.contains("TOTAL: 04 05 0B 0C 0D 0E 0F 10 11 20 33"))
        assertTrue(report.contains("automático: ISO 15765-4 CAN (11 bit, 500 kbaud)"))
        // 010C0D, 010C0D1, 010C0D0B y 010C0D0B1: todas completas.
        assertEquals(report, 4, Regex("5/5 completas").findAll(report).count())
        assertTrue(report.contains("ATAT2 + ATST19"))
        assertTrue(report.contains("==== FIN DIAGNÓSTICO"))
        assertFalse(report.contains("ABORTADO"))
        // Lo que va al sink es exactamente el informe.
        assertEquals(report, lines.joinToString("") { it + "\n" })
        // Tras probar la temporización, se restaura la de por defecto.
        assertEquals(listOf("ATAT1", "ATST32"), elm.sent.takeLast(2))
        // Todo lo enviado es de solo lectura.
        assertTrue(elm.sent.all { ObdDiagnostics.isReadOnly(it) })
    }

    @Test
    fun trasUnTimeoutSeRecogeLaRespuestaTardia() {
        val elm = FakeElmIo(car + ("ATRV" to "TIMEOUT"), late = "12.4V")
        val report = ObdDiagnostics(elm, sink = {}).run()
        assertTrue(report.contains("[ATRV] TIMEOUT"))
        assertTrue(report.contains("recibido después: 12.4V"))
        // La respuesta tardía no se confunde con la del siguiente comando (0100).
        assertTrue(report.contains("TOTAL: 04 05 0B 0C 0D 0E 0F 10 11 20 33"))
    }

    @Test
    fun interrupcionAbortaYConservaElFlag() {
        val elm = FakeElmIo(car, interruptOn = "0100")
        val report = ObdDiagnostics(elm, sink = {}).run()
        val interrupted = Thread.interrupted() // lo lee y lo limpia para no afectar a otros tests
        assertTrue(interrupted)
        assertTrue(report.contains("ABORTADO: hilo interrumpido"))
        assertTrue(report.contains("==== FIN DIAGNÓSTICO"))
        assertFalse(elm.sent.contains("ATDPN"))
    }

    @Test
    fun conexionPerdidaAborta() {
        val elm = FakeElmIo(car)
        elm.isConnected = false
        val report = ObdDiagnostics(elm, sink = {}).run()
        assertTrue(report.contains("ABORTADO: IllegalStateException: conexión perdida"))
        assertTrue(elm.sent.isEmpty())
    }

    @Test
    fun sinSoporteDe0cSeOmitenLasPruebasDeVelocidad() {
        val elm = FakeElmIo(car + ("0100" to "4100180F8000")) // 04 05 0D 0E 0F 10 11, sin 0C
        val report = ObdDiagnostics(elm, sink = {}).run()
        assertFalse(report.contains("ABORTADO"))
        assertEquals(report, 3, Regex("omitido: 0C").findAll(report).count())
        assertFalse(elm.sent.contains("010C1"))
    }

    @Test
    fun soloLectura() {
        listOf("ATZ", "ATE0", "ATSP0", "ATI", "AT@1", "ATRV", "ATDPN", "ATAT2", "ATST19",
            "0100", "010C", "010C1", "01 0C", "010C0D0B", "010C0D0B1").forEach {
            assertTrue(it, ObdDiagnostics.isReadOnly(it))
        }
        listOf("04", "0300", "2EF190", "3101FF00", "1003", "ATPP0CSV00", "AT@3ABC", "ATMA",
            "ATSH7E0", "ATCRA7E8", "01", "010C0D0B05060708", "01ZZ", "").forEach {
            assertFalse(it, ObdDiagnostics.isReadOnly(it))
        }
    }

    @Test
    fun unirTramas() {
        assertEquals("410C1AF80D320B65", ObdDiagnostics.joinFrames("0080:410C1AF80D321:0B65AAAAAAAAAA"))
        assertEquals("410C1AF80D32", ObdDiagnostics.joinFrames("410C1AF80D32"))
    }

    @Test
    fun repartirMultiPid() {
        val sizes = mapOf(0x0C to 2, 0x0D to 1, 0x0B to 1)
        assertEquals(
            mapOf(0x0C to "1AF8", 0x0D to "32", 0x0B to "65"),
            ObdDiagnostics.splitMultiPid("410C1AF80D320B65", sizes),
        )
        // Algunas ECU repiten "41" delante de cada PID.
        assertEquals(mapOf(0x0C to "1AF8", 0x0D to "32"), ObdDiagnostics.splitMultiPid("410C1AF8410D32", sizes))
        assertNull(ObdDiagnostics.splitMultiPid("NODATA", sizes))
        assertNull(ObdDiagnostics.splitMultiPid("410C1A", sizes)) // truncada
        assertNull(ObdDiagnostics.splitMultiPid("41FF00", sizes)) // PID desconocido
    }

    @Test
    fun guardarYRecuperarInformes() {
        val dir = tmp.newFolder("diagnostics")
        assertNull(ObdDiagnostics.latestReport(dir))
        repeat(12) { i ->
            ObdDiagnostics.saveReport(dir, "informe $i", Date(1_700_000_000_000L + i * 1000L))
        }
        assertEquals(10, dir.listFiles()!!.size)
        assertEquals("informe 11", ObdDiagnostics.latestReport(dir)!!.readText())
    }

    @Test
    fun mapaDePidsSoportados() {
        // Ejemplo clásico: BE 3F A8 13
        val perEcu = ObdDiagnostics.parseSupportedPids("4100BE3FA813", 0x00)
        assertEquals(1, perEcu.size)
        assertEquals(
            setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
                0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20),
            perEcu[0],
        )
    }

    @Test
    fun mapaConVariasEcusYBaseDistinta() {
        val perEcu = ObdDiagnostics.parseSupportedPids("SEARCHING...412080000001412000000000", 0x20)
        assertEquals(2, perEcu.size)
        assertEquals(setOf(0x21, 0x40), perEcu[0])
        assertTrue(perEcu[1].isEmpty())
    }

    @Test
    fun mapaInvalido() {
        assertTrue(ObdDiagnostics.parseSupportedPids("NODATA", 0x00).isEmpty())
        assertTrue(ObdDiagnostics.parseSupportedPids("UNABLETOCONNECT", 0x00).isEmpty())
        assertTrue(ObdDiagnostics.parseSupportedPids("4100BE3F", 0x00).isEmpty())
    }

    @Test
    fun protocolo() {
        assertEquals("automático: ISO 15765-4 CAN (11 bit, 500 kbaud)", ObdDiagnostics.describeProtocol("A6"))
        assertEquals("ISO 14230-4 KWP (fast init)", ObdDiagnostics.describeProtocol("5"))
        assertEquals("automático: SAE J1939 CAN", ObdDiagnostics.describeProtocol("AA"))
        assertEquals("desconocido", ObdDiagnostics.describeProtocol("?"))
        assertEquals("desconocido", ObdDiagnostics.describeProtocol("TIMEOUT"))
    }
}
