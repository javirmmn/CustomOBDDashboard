package com.example.ibizacustommap

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sonda de diagnóstico OBD (Fase 0). Solo se lanza en builds de debug, una vez
 * tras la inicialización del ELM327. No toca el estado del dashboard: solo
 * pregunta al adaptador/ECU y deja un informe en logcat (tag OBD_DIAG) y en
 * Android/data/<applicationId>/files/diagnostics/obd_diag_<fecha>.txt
 *
 * Responde a las incertidumbres de la auditoría: modelo del adaptador,
 * protocolo real, PIDs soportados por la ECU, latencia real por PID y si el
 * adaptador acepta el sufijo de nº de respuestas ("010C1") y multi-PID.
 */
class ObdDiagnostics(private val context: Context, private val obd: ObdManager) {

    private data class Result(val response: String, val ms: Long)

    private val report = StringBuilder()

    fun run() {
        val start = System.currentTimeMillis()
        try {
            line("==== DIAGNÓSTICO OBD ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ====")
            adapterInfo()
            // 0100 debe ir antes de ATDPN: es la primera petición OBD y la que
            // dispara la búsqueda de protocolo con ATSP0.
            val supported = supportedPids()
            protocolInfo()
            timePids(supported)
            responseCountTest(supported)
            multiPidTest(supported)
        } catch (e: InterruptedException) {
            line("ABORTADO: hilo interrumpido")
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            line("ABORTADO: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            line("==== FIN DIAGNÓSTICO (${System.currentTimeMillis() - start} ms) ====")
            saveReport()
        }
    }

    private fun adapterInfo() {
        line("-- Adaptador --")
        line("  ATI  (versión)     : ${exec("ATI").response}")
        line("  AT@1 (descripción) : ${exec("AT@1").response}")
        line("  ATRV (tensión)     : ${exec("ATRV").response}")
    }

    private fun supportedPids(): Set<Int> {
        line("-- PIDs soportados (modo 01) --")
        val all = sortedSetOf<Int>()
        var base = 0x00
        while (base <= 0xC0) {
            val cmd = "01%02X".format(base)
            var perEcu = parseSupportedPids(exec(cmd, if (base == 0) SEARCH_TIMEOUT_MS else CMD_TIMEOUT_MS).response, base)
            if (perEcu.isEmpty() && base == 0) {
                line("  0100 sin respuesta válida, reintentando...")
                perEcu = parseSupportedPids(exec(cmd, SEARCH_TIMEOUT_MS).response, base)
            }
            if (perEcu.isEmpty()) {
                line("  $cmd: sin mapa válido")
                break
            }
            line("  $cmd: ${perEcu.size} ECU(s) respondieron")
            perEcu.forEachIndexed { i, pids -> line("    ECU#${i + 1}: ${formatPids(pids)}") }
            val merged = perEcu.flatten()
            all.addAll(merged)
            // El último bit de cada mapa indica si existe el siguiente rango.
            if ((base + 0x20) !in merged) break
            base += 0x20
        }
        line("  TOTAL: ${formatPids(all)}")

        line("-- PIDs que usa hoy el dashboard --")
        USED_PIDS.forEach { (pid, name) ->
            line("  %02X %-22s %s".format(pid, name, if (pid in all) "SOPORTADO" else "NO soportado"))
        }
        line("-- PIDs candidatos --")
        CANDIDATE_PIDS.forEach { (pid, name) ->
            line("  %02X %-22s %s".format(pid, name, if (pid in all) "SOPORTADO" else "NO soportado"))
        }
        return all
    }

    private fun protocolInfo() {
        line("-- Protocolo --")
        val dpn = exec("ATDPN").response
        line("  ATDPN: $dpn -> ${describeProtocol(dpn)}")
        line("  ATDP : ${exec("ATDP").response}")
    }

    private fun timePids(supported: Set<Int>) {
        line("-- Latencia por PID (ms mín/media/máx, válidas/intentos) --")
        // Los PIDs en uso se miden aunque no estén soportados: así se ve cuánto
        // cuesta en cada ciclo preguntar por algo que la ECU no tiene.
        USED_PIDS.forEach { (pid, name) -> measurePid(pid, name, if (pid in supported) 5 else 2, "EN USO") }
        CANDIDATE_PIDS.filter { it.first in supported }.forEach { (pid, name) -> measurePid(pid, name, 3, "CANDIDATO") }
    }

    private fun measurePid(pid: Int, name: String, reps: Int, tag: String) {
        val cmd = "01%02X".format(pid)
        val prefix = "41%02X".format(pid)
        val results = (1..reps).map { exec(cmd) }
        val valid = results.count { it.response.startsWith(prefix) }
        line("  %02X %-22s [%s] %s, %d/%d válidas, ej: %s".format(
            pid, name, tag, stats(results), valid, reps, results.last().response))
    }

    private fun responseCountTest(supported: Set<Int>) {
        line("-- Sufijo nº de respuestas (010C vs 010C1) --")
        if (0x0C !in supported) {
            line("  omitido: 0C no soportado")
            return
        }
        val plain = (1..10).map { exec("010C") }
        val counted = (1..10).map { exec("010C1") }
        val validCounted = counted.count { it.response.startsWith("410C") }
        line("  010C : ${stats(plain)}")
        line("  010C1: ${stats(counted)}, $validCounted/10 válidas, ej: ${counted.last().response}")
        val saved = plain.map { it.ms }.average() - counted.map { it.ms }.average()
        line("  Ahorro medio por PID: %.0f ms".format(saved))
    }

    private fun multiPidTest(supported: Set<Int>) {
        line("-- Multi-PID en una petición --")
        if (0x0C !in supported || 0x0D !in supported) {
            line("  omitido: 0C/0D no soportados")
            return
        }
        // 2 PIDs caben en una trama CAN; 3 PIDs ya van en varias tramas (formato "0:...1:...").
        val requests = mutableListOf("010C0D")
        if (0x0B in supported) requests += "010C0D0B"
        requests.forEach { cmd ->
            val results = (1..3).map { exec(cmd) }
            val withRpm = results.count { it.response.contains("410C") }
            line("  $cmd: ${stats(results)}, $withRpm/3 contienen 410C, ej: ${results.last().response}")
        }
    }

    private fun exec(cmd: String, timeoutMs: Long = CMD_TIMEOUT_MS): Result {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        check(obd.isConnected) { "conexión perdida" }
        val t0 = System.nanoTime()
        obd.sendCommand(cmd)
        val response = obd.readResponse(timeoutMs)
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (response == "TIMEOUT") {
            // Si no se recoge, la respuesta tardía se leería como la del siguiente comando.
            val late = obd.readResponse(DRAIN_TIMEOUT_MS)
            line("    [$cmd] TIMEOUT tras $ms ms; recibido después: $late")
        }
        return Result(response, ms)
    }

    private fun stats(results: List<Result>): String {
        val times = results.map { it.ms }
        return "%d/%.0f/%d ms".format(times.min(), times.average(), times.max())
    }

    private fun line(text: String) {
        report.appendLine(text)
        Log.i(TAG, text)
    }

    private fun saveReport() {
        try {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics").apply { mkdirs() }
            val file = File(dir, "obd_diag_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt")
            file.writeText(report.toString())
            Log.i(TAG, "Informe guardado en ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo guardar el informe: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "OBD_DIAG"
        private const val SEARCH_TIMEOUT_MS = 8000L // búsqueda de protocolo (ATSP0)
        private const val CMD_TIMEOUT_MS = 2000L
        private const val DRAIN_TIMEOUT_MS = 1500L

        val USED_PIDS = listOf(
            0x0C to "RPM",
            0x0D to "Velocidad",
            0x0B to "MAP (boost)",
            0x11 to "Mariposa abs. (THROTTLE)",
            0x5C to "Temp. aceite",
            0x05 to "Temp. refrigerante",
            0x0F to "Temp. admisión",
            0x04 to "Carga calculada",
            0x10 to "MAF",
            0x44 to "Lambda pedido (AFR)",
            0x34 to "Lambda O2 B1S1 (AFR)",
        )

        val CANDIDATE_PIDS = listOf(
            0x33 to "Presión barométrica",
            0x49 to "Pedal acelerador D",
            0x4A to "Pedal acelerador E",
            0x4C to "Mariposa pedida",
            0x45 to "Mariposa relativa",
            0x43 to "Carga absoluta",
            0x2F to "Nivel combustible",
            0x0E to "Avance encendido",
            0x46 to "Temp. ambiente",
            0x1F to "Tiempo motor en marcha",
        )

        private val PROTOCOLS = mapOf(
            '1' to "SAE J1850 PWM",
            '2' to "SAE J1850 VPW",
            '3' to "ISO 9141-2",
            '4' to "ISO 14230-4 KWP (5 baud init)",
            '5' to "ISO 14230-4 KWP (fast init)",
            '6' to "ISO 15765-4 CAN (11 bit, 500 kbaud)",
            '7' to "ISO 15765-4 CAN (29 bit, 500 kbaud)",
            '8' to "ISO 15765-4 CAN (11 bit, 250 kbaud)",
            '9' to "ISO 15765-4 CAN (29 bit, 250 kbaud)",
            'A' to "SAE J1939 CAN",
            'B' to "USER1 CAN",
            'C' to "USER2 CAN",
        )

        /**
         * Decodifica la respuesta (ya limpia, sin espacios) a 01<base> en el
         * conjunto de PIDs soportados, uno por cada ECU que respondió.
         * El bit más significativo del primer byte corresponde a base+1.
         */
        fun parseSupportedPids(cleanResponse: String, base: Int): List<Set<Int>> {
            val regex = Regex("41%02X([0-9A-F]{8})".format(base))
            return regex.findAll(cleanResponse.uppercase()).map { match ->
                val bits = match.groupValues[1].toLong(16)
                (0 until 32).filter { i -> (bits shr (31 - i)) and 1L == 1L }
                    .map { i -> base + i + 1 }
                    .toSortedSet()
            }.toList()
        }

        /** "A6" -> "automático: ISO 15765-4 CAN (11 bit, 500 kbaud)". */
        fun describeProtocol(dpn: String): String {
            val auto = dpn.length == 2 && dpn[0] == 'A'
            val code = if (auto) dpn[1] else dpn.singleOrNull() ?: return "desconocido"
            val name = PROTOCOLS[code] ?: return "desconocido"
            return if (auto) "automático: $name" else name
        }

        fun formatPids(pids: Collection<Int>): String =
            if (pids.isEmpty()) "(ninguno)" else pids.joinToString(" ") { "%02X".format(it) }
    }
}
