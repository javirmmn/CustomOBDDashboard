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
        // UUID estándar universal (SPP) para aparatos OBD2
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

        // 1. Buscar entre los dispositivos Bluetooth ya vinculados al móvil
        val pairedDevices = bluetoothAdapter!!.bondedDevices
        val obdDevice = pairedDevices.find { device ->
            val name = device.name ?: ""
            name.contains("OBD", ignoreCase = true) ||
                    name.contains("V-LINK", ignoreCase = true) ||
                    name.contains("ELM327", ignoreCase = true) ||
                    name.contains("Vgate", ignoreCase = true)
        }

        if (obdDevice == null) {
            Log.e(TAG, "No se encontró ningún escáner OBD2 emparejado en el móvil.")
            return false
        }

        // 2. Intentar abrir el túnel de datos RFCOMM
        return try {
            Log.d(TAG, "Conectando a: ${obdDevice.name}...")
            socket = obdDevice.createRfcommSocketToServiceRecord(OBD_UUID)
            bluetoothAdapter?.cancelDiscovery()
            socket?.connect()

            inputStream = socket?.inputStream
            outputStream = socket?.outputStream
            isConnected = true

            Log.d(TAG, "¡Túnel Bluetooth abierto con éxito!")
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
            Log.d(TAG, "Conexión cerrada de forma segura.")
        } catch (e: Exception) {
            Log.e(TAG, "Error cerrando la conexión: ${e.message}")
        }
    }

    // =========================================================
    // COMUNICACIÓN OBD2 (LEER Y ESCRIBIR)
    // =========================================================

    fun sendCommand(command: String) {
        if (!isConnected || outputStream == null) return
        try {
            // Añadimos el retorno de carro (\r) que exige el protocolo ELM327
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
            var bytesRead: Int
            val responseBuilder = java.lang.StringBuilder()

            // Nos quedamos escuchando hasta que el aparato devuelva un '>'
            while (true) {
                bytesRead = inputStream!!.read(buffer)
                if (bytesRead == -1) break

                val chunk = String(buffer, 0, bytesRead)
                responseBuilder.append(chunk)

                if (chunk.contains(">")) {
                    break
                }
            }

            // Limpiamos los espacios, saltos de línea y la flecha '>' para dejar solo el hexadecimal puro
            return responseBuilder.toString()
                .replace(">", "")
                .replace("\r", "")
                .replace("\n", "")
                .replace(" ", "")
                .trim()

        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo respuesta: ${e.message}")
            closeConnection()
            return ""
        }
    }
}