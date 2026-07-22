package com.example.ibizacustommap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class ObdManager(private val context: Context) {

    companion object {
        private const val TAG = "ObdManager"
        private val OBD_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    var isConnected = false
        private set

    init {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }

    @SuppressLint("MissingPermission")
    fun connect(): Boolean {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth apagado o no disponible.")
            return false
        }

        val pairedDevices = bluetoothAdapter!!.bondedDevices
        val obdDevice = pairedDevices.find { device ->
            val name = device.name ?: ""
            name.contains("OBD", ignoreCase = true) ||
                    name.contains("V-LINK", ignoreCase = true) ||
                    name.contains("ELM327", ignoreCase = true) ||
                    name.contains("Vgate", ignoreCase = true)
        }

        if (obdDevice == null) return false

        return try {
            Log.d(TAG, "Conectando a: ${obdDevice.name}...")
            socket = obdDevice.createRfcommSocketToServiceRecord(OBD_UUID)
            bluetoothAdapter?.cancelDiscovery()
            socket?.connect()

            inputStream = socket?.inputStream
            outputStream = socket?.outputStream
            isConnected = true

            Log.d(TAG, "Túnel abierto. No inicializamos aquí, lo hará MainScreen.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al conectar: ${e.message}")
            closeConnection()
            false
        }
    }

    fun closeConnection() {
        try {
            socket?.close()
            inputStream?.close()
            outputStream?.close()
            isConnected = false
            Log.d(TAG, "Conexión cerrada.")
        } catch (e: Exception) {
            Log.e(TAG, "Error cerrando: ${e.message}")
        }
    }

    fun sendCommand(command: String) {
        if (!isConnected || outputStream == null) return
        try {
            Log.d(TAG, "TX (Enviando): $command")
            val cmdWithReturn = "$command\r"
            outputStream?.write(cmdWithReturn.toByteArray())
            outputStream?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando comando: ${e.message}")
            closeConnection()
        }
    }

    fun readResponse(): String {
        if (!isConnected || inputStream == null) return ""
        try {
            val buffer = ByteArray(1024)
            val responseBuilder = java.lang.StringBuilder()

            // SISTEMA ANTI-BLOQUEO (TIMEOUT DE 1.5 SEGUNDOS)
            val startTime = System.currentTimeMillis()
            val timeoutMs = 1500L

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (inputStream!!.available() > 0) {
                    val bytesRead = inputStream!!.read(buffer)
                    if (bytesRead == -1) break

                    val chunk = String(buffer, 0, bytesRead)
                    responseBuilder.append(chunk)

                    if (chunk.contains(">")) {
                        break // Fin de la lectura correcta
                    }
                } else {
                    Thread.sleep(10) // Evita saturar el procesador mientras espera
                }
            }

            val rawResponse = responseBuilder.toString()
            Log.d(TAG, "RX (Respuesta cruda): $rawResponse")

            // Si ha saltado el tiempo y no hay flecha, el coche no ha respondido
            if (!rawResponse.contains(">")) {
                Log.w(TAG, "¡TIMEOUT! El escáner no devolvió '>' a tiempo.")
                return "TIMEOUT"
            }

            // Limpieza: caracteres de control + mensajes asíncronos del ELM327
            // que pueden ir pegados delante/detrás de la trama hexadecimal real
            // (típicos mientras ATSP0 aún está negociando protocolo).
            val cleanResponse = rawResponse
                .replace(">", "")
                .replace("\r", "")
                .replace("\n", "")
                .replace(" ", "")
                .replace("SEARCHING...", "")
                .replace("SEARCHING", "")
                .replace("STOPPED", "")
                .replace("BUSINIT", "")
                .replace("BUS INIT", "")
                .trim()

            Log.d(TAG, "RX (Respuesta limpia): $cleanResponse")

            return cleanResponse

        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo: ${e.message}")
            closeConnection()
            return "ERROR"
        }
    }
}