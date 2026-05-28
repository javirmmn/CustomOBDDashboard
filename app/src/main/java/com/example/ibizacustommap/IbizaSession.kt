package com.example.ibizacustommap

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class IbizaSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        // Aquí le decimos que arranque nuestro nuevo lienzo GT3
        return MainScreen(carContext)
    }
}