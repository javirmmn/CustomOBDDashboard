package com.example.ibizacustommap

/**
 * Operaciones mínimas de envío/lectura contra un ELM327. La implementa
 * [ObdManager] (Bluetooth real) y, en los tests, un falso que responde con
 * tramas grabadas. Es el germen del `ElmTransport` de la Fase 3 del plan.
 */
interface ElmIo {
    val isConnected: Boolean

    fun sendCommand(command: String)

    /** Respuesta ya limpia (sin espacios, saltos ni '>'), "TIMEOUT" o "ERROR". */
    fun readResponse(timeoutMs: Long = ObdManager.DEFAULT_TIMEOUT_MS): String
}
