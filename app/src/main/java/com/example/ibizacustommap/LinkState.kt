package com.example.ibizacustommap

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Estado de la fuente de datos del salpicadero. Se pinta en la pantalla del
 * coche (también en release) y se muestra en la app del móvil.
 */
enum class LinkState(val label: String) {
    /** Buscando el adaptador emparejado / abriendo el Bluetooth. */
    SEARCHING("BUSCANDO ADAPTADOR…"),

    /** Socket abierto, inicializando el ELM327. */
    CONNECTING("CONECTANDO…"),

    /** Datos reales de la ECU. */
    LIVE("EN VIVO"),

    /** El adaptador responde, pero la ECU no da datos (contacto quitado, bus dormido…). */
    NO_ECU_DATA("SIN DATOS DE LA ECU"),

    /** Se perdió la conexión y se está intentando recuperar. */
    RECONNECTING("RECONECTANDO…"),

    /** Varios intentos fallidos; se sigue reintentando con espera creciente. */
    NO_CONNECTION("SIN CONEXIÓN · REINTENTANDO"),

    BLUETOOTH_OFF("BLUETOOTH APAGADO"),

    NO_PERMISSION("FALTAN PERMISOS · ABRE LA APP EN EL MÓVIL"),

    /** Datos de DashboardSimulator, elegidos explícitamente en la app del móvil. */
    SIMULATION("SIMULACIÓN"),
}

/** Estado compartido en el proceso entre el salpicadero (coche) y la app del móvil. */
object ObdLinkState {
    /**
     * Estado actual del salpicadero de Android Auto, o null si no está abierto.
     * Si no es null, su hilo OBD puede tener el adaptador (que solo admite una
     * conexión Bluetooth a la vez).
     */
    val link = MutableStateFlow<LinkState?>(null)

    /** Lecturas por segundo de RPM/velocidad/MAP en vivo, o null. */
    val rateHz = MutableStateFlow<Float?>(null)
}
