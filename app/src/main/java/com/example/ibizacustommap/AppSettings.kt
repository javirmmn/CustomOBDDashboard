package com.example.ibizacustommap

import android.content.Context
import android.content.SharedPreferences

/** Ajustes de la app del móvil, compartidos con la pantalla del coche (mismo proceso). */
object AppSettings {
    private const val PREFS_NAME = "settings"
    const val KEY_SIMULATION = "simulation"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Simulación en la pantalla del coche, elegida explícitamente en el móvil. Por defecto, no. */
    fun isSimulation(context: Context): Boolean = prefs(context).getBoolean(KEY_SIMULATION, false)

    fun setSimulation(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SIMULATION, enabled).apply()
    }
}
