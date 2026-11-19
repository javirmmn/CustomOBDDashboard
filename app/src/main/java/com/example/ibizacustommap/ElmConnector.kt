package com.example.ibizacustommap

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Resultado de un intento de conexión con el adaptador. */
sealed interface ConnectResult {
    data class Connected(val link: ElmIo) : ConnectResult
    data object Failed : ConnectResult
    data object NoPermission : ConnectResult
    data object BluetoothOff : ConnectResult
}

/** Abre conexiones con el adaptador. En los tests se sustituye por un falso. */
interface ElmConnector {
    /** Bloqueante: puede tardar varios segundos si el adaptador no está. */
    fun connect(): ConnectResult

    /** Aborta un [connect] en curso o cierra la conexión abierta. Desde cualquier hilo. */
    fun abort()
}

/**
 * Conexión Bluetooth real con el Vgate/ELM327 a través de [ObdManager]
 * (sin cambios en cómo elige el dispositivo ni en cómo abre el socket).
 * Comprueba antes el permiso y que el Bluetooth esté encendido, para poder
 * mostrar el motivo en la pantalla del coche en vez de fallar sin más.
 */
class BluetoothElmConnector(context: Context) : ElmConnector {

    private val appContext = context.applicationContext

    @Volatile
    private var current: ObdManager? = null

    @SuppressLint("MissingPermission") // isEnabled no requiere BLUETOOTH_CONNECT; BLUETOOTH (≤ API 30) es normal
    override fun connect(): ConnectResult {
        if (!hasConnectPermission()) return ConnectResult.NoPermission
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return ConnectResult.BluetoothOff
        if (!adapter.isEnabled) return ConnectResult.BluetoothOff

        val manager = ObdManager(appContext)
        current = manager
        return try {
            if (manager.connect()) ConnectResult.Connected(manager) else ConnectResult.Failed
        } catch (e: SecurityException) {
            // Antes esto tumbaba el proceso: bondedDevices está fuera del try de connect().
            manager.closeConnection()
            ConnectResult.NoPermission
        }
    }

    override fun abort() {
        current?.closeConnection()
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
