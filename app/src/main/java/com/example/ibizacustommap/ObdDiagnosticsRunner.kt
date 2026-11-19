package com.example.ibizacustommap

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lanza [ObdDiagnostics] bajo demanda desde la app del móvil (también en
 * release), con su PROPIA conexión al adaptador: no toca la conexión ni el
 * bucle de polling del salpicadero. Bloqueante: llamar desde un hilo de fondo
 * (DiagnosticsViewModel usa runInterruptible en Dispatchers.IO).
 */
class ObdDiagnosticsRunner(context: Context) {

    sealed interface Outcome {
        /** El diagnóstico se ejecutó (completo o abortado a medias: ver el informe). */
        data class Done(val report: String, val file: File?) : Outcome
        data class Failed(val message: String) : Outcome
    }

    private val appContext = context.applicationContext
    private val connector = BluetoothElmConnector(appContext)

    fun run(onStatus: (String) -> Unit, onLine: (String) -> Unit): Outcome {
        if (!running.compareAndSet(false, true)) return Outcome.Failed("Ya hay un diagnóstico en marcha.")
        var link: ElmIo? = null
        try {
            onStatus("Conectando con el adaptador…")
            val result = connector.connect()
            if (Thread.currentThread().isInterrupted) return Outcome.Failed(CANCELLED)
            val manager = when (result) {
                is ConnectResult.Connected -> result.link
                ConnectResult.NoPermission ->
                    return Outcome.Failed("Falta el permiso de Bluetooth. Concédelo en la pantalla principal de la app.")
                ConnectResult.BluetoothOff -> return Outcome.Failed("El Bluetooth del móvil está apagado.")
                ConnectResult.Failed -> return Outcome.Failed(noConnectionMessage())
            }
            link = manager

            onStatus("Inicializando el ELM327…")
            val init = initElm(manager)

            onStatus("Midiendo… (puede tardar 1–2 minutos)")
            val report = ObdDiagnostics(manager) { text ->
                Log.i(TAG, text)
                onLine(text)
            }.run(preamble() + init)

            val file = try {
                ObdDiagnostics.saveReport(reportsDir(appContext), report)
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo guardar el informe: ${e.message}")
                null
            }
            return Outcome.Done(report, file)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Outcome.Failed(CANCELLED)
        } catch (e: IOException) {
            return Outcome.Failed("Se perdió la conexión con el adaptador: ${e.message}")
        } catch (e: Exception) {
            return Outcome.Failed("Error inesperado: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            link?.close()
            connector.abort()
            running.set(false)
        }
    }

    /**
     * Aborta lo que esté haciendo [run] cerrando el socket: desbloquea un
     * connect() o una lectura en curso. Se puede llamar desde cualquier hilo.
     */
    fun cancel() {
        connector.abort()
    }

    private fun preamble(): List<String> = listOf(
        "App: ${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE}), " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    )

    /**
     * COPIA intencionada de la inicialización de ObdPoller (mismos comandos y
     * esperas), para que el diagnóstico mida el adaptador en el mismo estado
     * que el salpicadero. Se unificarán en la Fase 3 del plan. ATDPN no va
     * aquí: el diagnóstico lo pide después de 0100, cuando ya hay protocolo.
     */
    private fun initElm(obd: ElmIo): List<String> {
        val out = mutableListOf("-- Inicialización (misma secuencia que el salpicadero) --")
        INIT_SEQUENCE.forEach { (cmd, waitMs) ->
            check(ObdDiagnostics.isReadOnly(cmd))
            obd.sendCommand(cmd)
            Thread.sleep(waitMs)
            out += "  $cmd -> ${obd.readResponse()}"
        }
        return out
    }

    private fun noConnectionMessage(): String = buildString {
        append("No se pudo conectar con el adaptador OBD.")
        val dashboard = ObdLinkState.link.value
        if (dashboard != null && dashboard != LinkState.SIMULATION) {
            append(
                "\n\nEl salpicadero está abierto en Android Auto y seguramente tiene el adaptador " +
                    "ocupado (solo admite una conexión). Desconecta Android Auto o cierra la app " +
                    "en la pantalla del coche y vuelve a intentarlo.",
            )
        }
        append("\n\nComprueba: Bluetooth encendido, adaptador emparejado y enchufado, y contacto puesto.")
    }

    companion object {
        private const val TAG = "OBD_DIAG"
        private const val CANCELLED = "Diagnóstico cancelado."
        private val running = AtomicBoolean(false)

        private val INIT_SEQUENCE = listOf(
            "ATZ" to 800L,
            "ATE0" to 300L,
            "ATH0" to 300L,
            "ATS0" to 300L,
            "ATSP0" to 500L,
        )

        /** Carpeta privada de la app (no accesible a otras apps) con los informes. */
        fun reportsDir(context: Context): File = File(context.filesDir, "diagnostics")
    }
}
