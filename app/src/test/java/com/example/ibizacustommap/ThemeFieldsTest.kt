package com.example.ibizacustommap

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeFieldsTest {

    @Test
    fun elCodigoExportadoConLosValoresPorDefectoEsDashThemeKt() {
        // Así lo exportado desde la pantalla de ajuste se puede pegar tal cual.
        val file = File("src/main/java/com/example/ibizacustommap/DashTheme.kt")
        assertTrue("No encuentro ${file.absolutePath}", file.exists())
        assertEquals(file.readText().replace("\r\n", "\n"), ThemeFields.kotlinSource(DashTheme()))
    }

    @Test
    fun todosLosParametrosEstanEnElRegistroUnaVez() {
        val keys = ThemeFields.all.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        // 5 bloom + 7 neón + 6 paneles + 4 fondo + 11 arco + 4 marcha + 4 tipografía + 3 composición.
        assertEquals(44, keys.size)
    }

    @Test
    fun cadaCampoSeLeeYSeEscribe() {
        val base = DashTheme()
        for (f in ThemeFields.all) {
            val v = when (f.kind) {
                FieldKind.COLOR -> 0xFF123456L.toDouble()
                FieldKind.INT -> f.max.toDouble()
                FieldKind.FLOAT -> ((f.min + f.max) / 2f).toDouble()
            }
            val changed = f.set(base, v)
            assertEquals(f.key, v.toFloat(), f.get(changed).toFloat(), 1e-4f)
            // Los demás campos no se tocan.
            for (other in ThemeFields.all) if (other !== f) assertEquals(other.key, other.get(base), other.get(changed), 0.0)
        }
    }

    @Test
    fun jsonIdaYVuelta() {
        var t = DashTheme()
        t = ThemeFields.all.first { it.key == "bloom.intensity" }.set(t, 0.8)
        t = ThemeFields.all.first { it.key == "neon.bodyColor" }.set(t, 0xFF00FF00L.toDouble())
        t = ThemeFields.all.first { it.key == "bloom.radius" }.set(t, 7.0)
        assertEquals(t, ThemeFields.fromJson(ThemeFields.toJson(t)))
        assertEquals(DashTheme(), ThemeFields.fromJson(ThemeFields.toJson(DashTheme())))
    }

    @Test
    fun jsonIncompletoOConBasuraUsaLosValoresPorDefecto() {
        val t = ThemeFields.fromJson("""{"bloom.threshold":0.7,"no.existe":3,"gear.size":"x"}""")
        assertEquals(0.7f, t.bloom.threshold, 1e-6f)
        assertEquals(DashTheme().gear, t.gear)
        // Fuera de rango → se acota.
        assertEquals(1f, ThemeFields.fromJson("""{"bloom.threshold":5}""").bloom.threshold, 1e-6f)
    }

    @Test
    fun soloLosCamposEstaticosCambianLaClaveEstatica() {
        val base = ThemeFields.staticKey(DashTheme())
        for (f in ThemeFields.all) {
            val v = if (f.kind == FieldKind.COLOR) 0xFF010203L.toDouble() else f.max.toDouble()
            val t = f.set(DashTheme(), v)
            if (f.get(t) == f.get(DashTheme())) continue
            val key = ThemeFields.staticKey(t)
            if (f.static) assertFalse(f.key, base.contentEquals(key)) else assertTrue(f.key, base.contentEquals(key))
        }
    }

    @Test
    fun elCodigoExportadoLlevaLosValoresActuales() {
        val t = ThemeFields.all.first { it.key == "arc.bandWidth" }.set(DashTheme(), 24.5)
        val code = ThemeFields.kotlinSource(t)
        assertTrue(code.contains("val bandWidth: Float = 24.5f,"))
        assertNotEquals(ThemeFields.kotlinSource(DashTheme()), code)
    }
}
