package com.example.ibizacustommap

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sonda de diagnóstico OBD (Paso previo del plan). Se lanza bajo demanda desde
 * la app del móvil (DiagnosticsActivity → ObdDiagnosticsRunner), también en
 * release. No toca el estado del dashboard: solo pregunta al adaptador/ECU y
 * devuelve un informe de texto que se puede compartir o copiar.
 *
 * Responde a las incertidumbres de la auditoría: modelo del adaptador,
 * protocolo real, PIDs soportados por la ECU, latencia real por PID, si el
 * adaptador acepta el sufijo de nº de respuestas ("010C1"), multi-PID y la
 * temporización adaptativa (ATAT/ATST).
 *
 * SOLO LECTURA: cada comando pasa por [isReadOnly] antes de enviarse. Nada de
 * modo 04 (borrar DTCs), servicios de escritura ni comandos AT que graben en
 * el adaptador. El informe no incluye datos identificativos (no se lee el VIN).
 *
 * Las latencias medidas incluyen hasta ~10 ms de sondeo de
 * ObdManager.readResponse (sleep(10) mientras no hay datos): son una cota
 * ligeramente pesimista.
 */
class ObdDiagnostics(
    private val obd: ElmIo,
    private val sink: (String) -> Unit = { Log.i(TAG, it) },
) {

    private data class Result(val response: String, val ms: Long)

    private val report = StringBuilder()

    /** Media de latencia por PID (ms), para comparar con multi-PID. */
    private val avgMsByPid = mutableMapOf<Int, Double>()

    /**
     * Ejecuta la sonda completa y devuelve el informe. Bloqueante: llamar desde
     * un hilo de fondo. Si el hilo se interrumpe, aborta, lo anota en el
     * informe y conserva el flag de interrupción.
     */
    fun run(preamble: List<String> = emptyList()): String {
        val start = System.currentTimeMillis()
        try {
            line("==== DIAGNÓSTICO OBD ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ====")
            preamble.forEach { line(it) }
            adapterInfo()
            // 0100 debe ir antes de ATDPN: es la primera petición OBD y la que
            // dispara la búsqueda de protocolo con ATSP0.
            val supported = supportedPids()
            protocolInfo()
            timePids(supported)
            responseCountTest(supported)
            multiPidTest(supported)
            adaptiveTimingTest(supported)
        } catch (e: InterruptedException) {
            line("ABORTADO: hilo interrumpido")
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            line("ABORTADO: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            line("==== FIN DIAGNÓSTICO (${System.currentTimeMillis() - start} ms) ====")
        }
        return report.toString()
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
            line("  %02X %-24s %s".format(pid, name, if (pid in all) "SOPORTADO" else "NO soportado"))
        }
        line("-- PIDs candidatos --")
        CANDIDATE_PIDS.forEach { (pid, name) ->
            line("  %02X %-24s %s".format(pid, name, if (pid in all) "SOPORTADO" else "NO soportado"))
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
        avgMsByPid[pid] = results.map { it.ms }.average()
        line("  %02X %-24s [%s] %s, %d/%d válidas, ej: %s".format(
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
        line("  Ahorro medio por PID: %.0f ms".format(Locale.ROOT, saved))
    }

    private fun multiPidTest(supported: Set<Int>) {
        line("-- Multi-PID en una petición --")
        if (0x0C !in supported || 0x0D !in supported) {
            line("  omitido: 0C/0D no soportados")
            return
        }
        // 2 PIDs caben en una trama CAN; 3 PIDs ya van en varias tramas (formato "0:...1:...").
        // El sufijo cuenta tramas: se prueba "1" y el nº real de tramas (el que usa ObdPoller).
        val groups = mutableListOf(listOf(0x0C, 0x0D))
        if (0x0B in supported) groups += listOf(0x0C, 0x0D, 0x0B)
        val medium = listOf(0x49, 0x04, 0x0E, 0x44).filter { it in supported }
        if (medium.size >= 2) groups += medium
        groups.forEach { pids ->
            val base = PidSpec.command(pids, counted = false)
            val separate = pids.sumOf { avgMsByPid[it] ?: 0.0 }
            val frames = PidSpec.responseFrames(pids)
            val variants = listOf(base, base + "1") + if (frames > 1) listOf(base + frames) else emptyList()
            variants.forEach { cmd ->
                val results = (1..5).map { exec(cmd) }
                val complete = results.count { r ->
                    splitMultiPid(joinFrames(r.response), PidSpec.DATA_BYTES)?.keys?.containsAll(pids) == true
                }
                line("  %-10s %s, %d/5 completas, ej: %s".format(cmd, stats(results), complete, results.last().response))
            }
            line("  (por separado: ~%.0f ms en total)".format(Locale.ROOT, separate))
        }
    }

    private fun adaptiveTimingTest(supported: Set<Int>) {
        line("-- Temporización adaptativa (ATAT / ATST), 010C x10 --")
        if (0x0C !in supported) {
            line("  omitido: 0C no soportado")
            return
        }
        val variants = listOf(
            "ATAT1 (por defecto)" to listOf("ATAT1"),
            "ATAT2" to listOf("ATAT2"),
            "ATAT2 + ATST19 (100 ms)" to listOf("ATAT2", "ATST19"),
        )
        try {
            variants.forEach { (name, setup) ->
                val acks = setup.joinToString(" ") { "$it:${exec(it).response}" }
                val results = (1..10).map { exec("010C") }
                val valid = results.count { it.response.startsWith("410C") }
                line("  %-24s %s, %d/10 válidas [%s]".format(name, stats(results), valid, acks))
            }
        } finally {
            restoreTiming()
        }
    }

    /** Deja el adaptador con la temporización por defecto (también la restablece ATZ). */
    private fun restoreTiming() {
        if (Thread.currentThread().isInterrupted || !obd.isConnected) return
        try {
            exec("ATAT1")
            exec("ATST32")
        } catch (e: Exception) {
            line("  (no se pudo restaurar ATAT1/ATST32: ${e.message})")
        }
    }

    private fun exec(cmd: String, timeoutMs: Long = CMD_TIMEOUT_MS): Result {
        require(isReadOnly(cmd)) { "comando no permitido (solo lectura): $cmd" }
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
        return "%d/%.0f/%d ms".format(Locale.ROOT, times.min(), times.average(), times.max())
    }

    private fun line(text: String) {
        report.appendLine(text)
        sink(text)
    }

    companion object {
        private const val TAG = "OBD_DIAG"
        private const val SEARCH_TIMEOUT_MS = 8000L // búsqueda de protocolo (ATSP0)
        private const val CMD_TIMEOUT_MS = 2000L
        private const val DRAIN_TIMEOUT_MS = 1500L
        private const val REPORT_PREFIX = "obd_diag_"
        private const val REPORTS_KEPT = 10

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
            0x34 to "Lambda O2 B1S1 (AFR)",
            0x44 to "Lambda pedido (AFR)",
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

        // Lista blanca: inicialización (la misma que MainScreen), consultas
        // informativas del adaptador y temporización (volátil: ATZ la resetea).
        // Nada que escriba en la EEPROM del adaptador (ATPP, AT@3...).
        private val READ_ONLY_AT = Regex("AT(Z|E0|H0|S0|SP0|I|@1|RV|DPN|DP|AT[0-2]|ST[0-9A-F]{2})")

        // Modo 01 (datos actuales): de 1 a 6 PIDs y, opcionalmente, el dígito
        // de nº de respuestas.
        private val MODE_01 = Regex("01([0-9A-F]{2}){1,6}[1-9]?")

        /** true si el comando es de solo lectura y está permitido en el diagnóstico. */
        fun isReadOnly(command: String): Boolean {
            val c = command.uppercase(Locale.ROOT).replace(" ", "")
            return READ_ONLY_AT.matches(c) || MODE_01.matches(c)
        }

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

        /**
         * Une una respuesta multi-trama del ELM327 ya limpia
         * ("0080:410C1AF80D321:0B65AAAAAAAAAA": longitud en hex, y cada trama
         * precedida de su índice y ':') en la carga útil "410C1AF80D320B65",
         * sin el relleno. Si no hay formato multi-trama la devuelve tal cual.
         */
        fun joinFrames(clean: String): String {
            if (!clean.contains(':')) return clean
            val parts = clean.split(':')
            val length = parts[0].dropLast(1).toIntOrNull(16) ?: return clean
            val data = StringBuilder()
            for (i in 1 until parts.size) {
                // Cada trama salvo la última termina con el índice de la siguiente.
                data.append(if (i < parts.size - 1) parts[i].dropLast(1) else parts[i])
            }
            return if (data.length >= length * 2) data.substring(0, length * 2) else data.toString()
        }

        /**
         * Reparte una respuesta multi-PID ("410C1AF80D32") en sus PIDs, según el
         * nº de bytes de cada uno. Tolera que la ECU repita el "41" antes de
         * cada PID. Devuelve null si la respuesta no cuadra.
         */
        fun splitMultiPid(payload: String, sizes: Map<Int, Int>): Map<Int, String>? {
            if (!payload.startsWith("41")) return null
            val out = linkedMapOf<Int, String>()
            var i = 2
            while (i + 2 <= payload.length) {
                val pid = payload.substring(i, i + 2).toIntOrNull(16) ?: return null
                if (pid == 0x41 && pid !in sizes) {
                    i += 2
                    continue
                }
                val n = sizes[pid] ?: return null
                val end = i + 2 + n * 2
                if (end > payload.length) return null
                out[pid] = payload.substring(i + 2, end)
                i = end
            }
            return if (out.isEmpty() || i != payload.length) null else out
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

        /** Guarda el informe en [dir] y borra los más antiguos (se conservan [REPORTS_KEPT]). */
        fun saveReport(dir: File, report: String, now: Date = Date()): File {
            dir.mkdirs()
            val file = File(dir, "$REPORT_PREFIX${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)}.txt")
            file.writeText(report)
            reportFiles(dir).drop(REPORTS_KEPT).forEach { it.delete() }
            return file
        }

        /** Informe más reciente guardado en [dir], o null. */
        fun latestReport(dir: File): File? = reportFiles(dir).firstOrNull()

        private fun reportFiles(dir: File): List<File> =
            dir.listFiles { f -> f.isFile && f.name.startsWith(REPORT_PREFIX) }
                ?.sortedByDescending { it.name }
                ?: emptyList()
    }
}
