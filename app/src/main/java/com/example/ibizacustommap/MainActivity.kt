package com.example.ibizacustommap

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult

class MainActivity : ComponentActivity() {

    // 1. DICCIONARIO DE PERMISOS: Según la edad del móvil, pide unos u otros.
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            // Variable que vigila si ya nos han dado el "Sí"
            var permissionsGranted by remember { mutableStateOf(checkPermissions()) }

            // Lanzador del Pop-up de Android
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { permissionsMap ->
                // Comprobamos si el usuario ha aceptado TODOS los permisos que pedimos
                permissionsGranted = permissionsMap.values.all { it == true }
            }

            // Llamamos a la pantalla visual pasándole el estado y el botón de pedir permisos
            MainAppScreen(
                permissionsGranted = permissionsGranted,
                onRequestPermissions = { permissionLauncher.launch(requiredPermissions) }
            )
        }
    }

    // Función que comprueba silenciosamente cómo están los permisos
    private fun checkPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

// ====================================================================
// EL DISEÑO DE LA PANTALLA (Minimalista Blanco/Negro)
// ====================================================================
@Composable
fun MainAppScreen(permissionsGranted: Boolean, onRequestPermissions: () -> Unit) {

    val backgroundColor = Color(0xFF040404) // Negro casi absoluto
    val textColor = Color(0xFFF0F0F0)       // Blanco roto
    val mutedText = Color(0xFF7A7A7A)       // Gris sutil para estado
    val seatRed = Color(0xFFB40000)         // Conservado solo para el botón de permisos

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // LOGO DE SEAT NUEVO (Sin textos adicionales)
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(id = R.drawable.seat_logo),
            contentDescription = "Logo de Seat",
            modifier = Modifier.fillMaxWidth(0.7f), // Ocupará el 70% del ancho de la pantalla
            // Si quieres que el logo (S plateada y letras rojas) se vuelva 100% BLANCO PURO
            // para mantener la estética estricta, descomenta la siguiente línea quitando las dos barras (//):
            // colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White)
        )

        Spacer(modifier = Modifier.height(80.dp))

        // LÓGICA DE LA INTERFAZ
        if (permissionsGranted) {
            Text(
                text = "ESPERANDO CONEXIÓN OBD2...",
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Permisos de sistema: OK",
                color = mutedText,
                fontSize = 12.sp
            )
        } else {
            Text(
                text = "ACCESO REQUERIDO",
                color = textColor, // Cambiado a blanco para no ser hortera
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Para interceptar la centralita y conectar con el adaptador OBD2, es vital habilitar el acceso Bluetooth.",
                color = mutedText,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(containerColor = seatRed),
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(50.dp)
            ) {
                Text(
                    text = "VINCULAR SISTEMAS",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}