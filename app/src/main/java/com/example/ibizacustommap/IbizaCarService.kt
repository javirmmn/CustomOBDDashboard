package com.example.ibizacustommap

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class IbizaCarService : CarAppService() {

    // Esto le dice a tu app que se fie de cualquier coche (ideal para desarrollo)
    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    // Aquí le decimos qué sesión arrancar cuando nos conectamos al coche
    override fun onCreateSession(): Session {
        return IbizaSession()
    }
}