package com.example.ibizacustommap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import java.io.File

/**
 * Depuración: dibuja un frame con datos simulados en un bitmap de 2400×1080
 * (la pantalla 20:9 de referencia) y lo guarda en PNG, para comparar con
 * `docs/design/referencia.webp` sin pasar por el coche.
 *
 * Guarda en `getExternalFilesDir(null)`:
 * - `debug_frame.png`: el frame completo.
 * - `debug_bloom.png`: solo la capa de bloom (1/4 de resolución), para
 *   comprobar que los brillos caen donde deben.
 *
 * Para sacarlos del móvil o del emulador:
 * `adb pull /sdcard/Android/data/com.javirmmn.customobddashboard/files/debug_frame.png`
 */
object DebugFrameExporter {

    const val WIDTH = 2400
    const val HEIGHT = 1080

    private const val TAG = "DebugFrame"

    /**
     * Los mismos valores que la imagen de referencia, para compararlas 1:1.
     * 142 km/h en 4ª son ≈4230 rpm con las relaciones reales de la caja.
     */
    fun referenceSnapshot() = TelemetrySnapshot(
        speedKmh = 142f,
        gearDisplay = "4",
        rpm = 142f * 29.8f,
        oilTemp = 87f,
        oilTempFromCoolant = true,
        turboPressure = 0.8f,
        intakeTemp = 32f,
        throttlePercent = 98f,
        throttleIsPedal = true,
        lambda = 1.0f,
        engineLoad = 64f,
        gForce = 0.3f,
        ignitionTiming = 18f,
        debugRawResponse = "REF",
        debugGearRatio = Float.NaN,
        link = LinkState.LIVE,
        gForcePeak = 0.9f,
        ambientTemp = 22.5f,
        fuelConsumption = 6.8f,
        rangeKm = 410f,
    )

    /** Tick del simulador en 4ª a ~149 km/h (≈4400 rpm). */
    const val SIM_TICK = 2.72f

    /**
     * Devuelve el PNG del frame de referencia, o null si no hay almacenamiento.
     * Guarda también `debug_frame_sim.png` (modo simulación, con su franja).
     * No llamar desde el hilo principal.
     */
    fun export(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        val logo = DashboardRenderer.loadLogo(context.resources)

        // En vivo (sin la franja SIMULACIÓN) y sin chivato de debug, como se vería en el coche.
        val out = File(dir, "debug_frame.png")
        val reference = DashboardRenderer(logo, debugOverlay = false)
        save(render(reference, referenceSnapshot()), out)
        reference.lastBloom?.let { save(it, File(dir, "debug_bloom.png")) }

        save(render(DashboardRenderer(logo, debugOverlay = false), DashboardSimulator.at(SIM_TICK)), File(dir, "debug_frame_sim.png"))

        Log.i(TAG, "Frames de depuración guardados en ${dir.absolutePath}")
        return out
    }

    private fun render(renderer: DashboardRenderer, snapshot: TelemetrySnapshot): Bitmap {
        val frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(frame), WIDTH, HEIGHT, snapshot, nowMs = 0L)
        return frame
    }

    private fun save(bitmap: Bitmap, file: File) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
