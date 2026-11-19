package com.example.ibizacustommap

import java.io.IOException

/**
 * Operaciones mínimas de envío/lectura contra un ELM327. La implementa
 * [ObdManager] (Bluetooth real) y, en los tests, un falso que responde con
 * tramas grabadas. Es el germen del `ElmTransport` de la Fase 3 del plan.
 *
 * Los fallos de transporte (Bluetooth cortado, socket cerrado) se lanzan como
 * [ElmLinkException]; una interrupción del hilo se propaga como
 * InterruptedException. Nunca se tragan.
 */
interface ElmIo {
    val isConnected: Boolean

    /** @throws ElmLinkException si no hay conexión o falla el envío. */
    fun sendCommand(command: String)

    /**
     * Respuesta ya limpia (sin espacios, saltos ni '>'), o "TIMEOUT" si el
     * adaptador no devolvió el prompt a tiempo.
     * @throws ElmLinkException si no hay conexión o falla la lectura.
     */
    fun readResponse(timeoutMs: Long = ObdManager.DEFAULT_TIMEOUT_MS): String

    /** Cierra la conexión. Se puede llamar desde cualquier hilo y varias veces. */
    fun close() {}
}

/** La conexión con el adaptador se ha perdido o no existe. */
class ElmLinkException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Respuesta del ELM327 clasificada. [raw] es la respuesta limpia tal cual. */
sealed interface ElmReply {
    val raw: String

    /** Datos en hexadecimal (p. ej. "410C1AF8" o multi-trama "0080:410C...1:..."). */
    data class Data(override val raw: String) : ElmReply

    /** "NO DATA": la ECU no contesta a ese PID (no soportado o sin dato ahora). */
    data class NoData(override val raw: String) : ElmReply

    /** Respuesta negativa "7F <modo> <NRC>". */
    data class Negative(override val raw: String, val nrc: Int?) : ElmReply

    /** Error de bus o de enlace con la ECU: CAN ERROR, UNABLE TO CONNECT, BUS BUSY… */
    data class BusError(override val raw: String) : ElmReply

    /** "?": el adaptador no entendió el comando. */
    data class NotUnderstood(override val raw: String) : ElmReply

    /** El adaptador no devolvió el prompt a tiempo. [late] es lo que llegó después (ya descartado). */
    data class Timeout(val late: String) : ElmReply {
        override val raw: String get() = "TIMEOUT"
    }

    /** Cualquier otra cosa (texto de comandos AT, vacío…). */
    data class Other(override val raw: String) : ElmReply

    companion object {
        private val HEX = Regex("[0-9A-F:]+")
        private val BUS_ERRORS = listOf(
            "UNABLETOCONNECT", "CANERROR", "BUSERROR", "BUSBUSY", "BUFFERFULL",
            "FBERROR", "DATAERROR", "RXERROR", "LVRESET", "ACTALERT", "ERR",
        )

        fun classify(clean: String): ElmReply {
            val c = clean.uppercase()
            return when {
                c == "TIMEOUT" -> Timeout("")
                c.contains("NODATA") -> NoData(clean)
                c == "?" -> NotUnderstood(clean)
                c.startsWith("7F") && HEX.matches(c) -> Negative(clean, c.substring(4).take(2).toIntOrNull(16))
                HEX.matches(c) -> Data(clean)
                BUS_ERRORS.any { c.contains(it) } -> BusError(clean)
                else -> Other(clean)
            }
        }
    }
}

/** Respuesta en hexadecimal si es de datos; "" en cualquier otro caso (los parsers devuelven su centinela). */
val ElmReply.data: String
    get() = if (this is ElmReply.Data) raw else ""

/** Tiempo para recoger una respuesta que llega tarde tras un TIMEOUT. */
const val ELM_DRAIN_TIMEOUT_MS = 1000L

/**
 * Envía [command] y clasifica la respuesta. Si hay TIMEOUT, recoge y descarta
 * la respuesta tardía: si no, se leería como la del siguiente comando y los
 * datos quedarían desincronizados.
 */
fun ElmIo.query(command: String, timeoutMs: Long = ObdManager.DEFAULT_TIMEOUT_MS): ElmReply {
    sendCommand(command)
    val response = readResponse(timeoutMs)
    if (response == "TIMEOUT") {
        return ElmReply.Timeout(late = readResponse(ELM_DRAIN_TIMEOUT_MS))
    }
    return ElmReply.classify(response)
}
