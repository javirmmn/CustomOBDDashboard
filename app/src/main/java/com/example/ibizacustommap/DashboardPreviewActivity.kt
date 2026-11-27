package com.example.ibizacustommap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/**
 * Vista previa TEMPORAL del salpicadero, directamente en la pantalla del
 * móvil, sin pasar por Android Auto ni por el Desktop Head Unit. Solo existe
 * para poder comprobar visualmente los cambios de datos/colores mientras no
 * se pueda probar en el coche o en un head unit real.
 *
 * Usa el MISMO DashboardRenderer que la pantalla real de Android Auto
 * (MainScreen), así que lo que se ve aquí es exactamente lo que se vería en
 * el coche. Los datos son siempre de DashboardSimulator (modo simulación):
 * esta pantalla no toca el Bluetooth ni el ObdManager.
 *
 * Se lanza desde un botón en MainActivity, visible en todas las compilaciones
 * (incluida release/Play Store). Pendiente de sustituir por la app del
 * móvil definitiva (Fase 5 del plan).
 */
class DashboardPreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pantalla completa, sin barras de sistema, para acercarse al aspecto
        // de la pantalla del coche. Si algo fallara aquí no debe impedir ver
        // la vista previa, así que se ignora cualquier error.
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } catch (_: Exception) {
        }

        // Solo en debug: guarda un frame 2400×1080 en PNG para compararlo con la referencia.
        if (BuildConfig.DEBUG) {
            Thread({
                try {
                    DebugFrameExporter.export(applicationContext)
                } catch (e: Exception) {
                    android.util.Log.e("DebugFrame", "No se pudo exportar el frame", e)
                }
            }, "DebugFrameExport").start()
        }

        setContent {
            var tick by remember { mutableFloatStateOf(0f) }

            LaunchedEffect(Unit) {
                while (true) {
                    tick += 0.05f
                    delay(FRAME_MS)
                }
            }

            val snapshot = remember(tick) { DashboardSimulator.at(tick) }
            val renderer = remember { DashboardRenderer(DashboardRenderer.loadLogo(resources)) }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                drawIntoCanvas { canvas ->
                    renderer.draw(
                        canvas = canvas.nativeCanvas,
                        width = size.width.toInt(),
                        height = size.height.toInt(),
                        s = snapshot,
                    )
                }
            }
        }
    }

    private companion object {
        const val FRAME_MS = 33L // ~30 fps, igual que el ticker real de MainScreen
    }
}
