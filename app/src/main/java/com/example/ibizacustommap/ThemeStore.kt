package com.example.ibizacustommap

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Tema visual activo. El renderer lee `theme.value` una vez por frame (un
 * DashTheme es inmutable, así que es seguro entre hilos). En release nadie lo
 * cambia: siempre son los valores por defecto de [DashTheme]. En debug lo
 * cambia en vivo la pantalla de ajuste del diseño, y se ve también en Android
 * Auto porque comparte proceso con la app del móvil.
 */
object ThemeStore {
    val theme = MutableStateFlow(DashTheme())

    fun reset() {
        theme.value = DashTheme()
    }
}

/** Herramientas de depuración del salpicadero. */
object DashDebug {
    /** Muestra las métricas de rendimiento encima del salpicadero de Android Auto. */
    val showMetricsOverlay = MutableStateFlow(false)

    /** Métricas del salpicadero de Android Auto (≈1 por segundo), para verlas en la pantalla de ajuste. */
    val carMetrics = MutableStateFlow<MetricsSummary?>(null)
}
