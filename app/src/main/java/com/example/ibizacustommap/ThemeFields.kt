package com.example.ibizacustommap

import java.util.Locale

/** Tipo de un parámetro del tema: decide el control de la pantalla de ajuste y cómo se escribe. */
enum class FieldKind { FLOAT, INT, COLOR }

/** Grupo de parámetros: una propiedad de [DashTheme] y su data class. */
class ThemeGroup(val property: String, val className: String, val title: String)

/**
 * Un parámetro del tema. [static] indica que afecta a la capa estática del
 * renderer (al cambiar hay que regenerar el bitmap de fondo). Los valores
 * viajan como Double para que quepan Float, Int y colores ARGB sin perder nada.
 */
class ThemeField(
    val group: ThemeGroup,
    val name: String,
    val kind: FieldKind,
    val min: Float,
    val max: Float,
    val static: Boolean,
    val get: (DashTheme) -> Double,
    val set: (DashTheme, Double) -> DashTheme,
) {
    val key: String = "${group.property}.$name"
}

/**
 * Registro de TODOS los parámetros visuales: única fuente de verdad para la
 * pantalla de ajuste (sliders y colores), los presets (JSON), el código
 * exportado y la separación estático/dinámico del renderer.
 */
object ThemeFields {

    val BLOOM = ThemeGroup("bloom", "BloomTheme", "Bloom")
    val NEON = ThemeGroup("neon", "NeonTheme", "Neón rojo")
    val PANEL = ThemeGroup("panel", "PanelTheme", "Paneles")
    val BACKGROUND = ThemeGroup("background", "BackgroundTheme", "Fondo")
    val ARC = ThemeGroup("arc", "ArcTheme", "Arco RPM")
    val GEAR = ThemeGroup("gear", "GearTheme", "Marcha")
    val TYPOGRAPHY = ThemeGroup("typography", "TypographyTheme", "Tipografía")
    val LAYOUT = ThemeGroup("layout", "LayoutTheme", "Composición")

    val groups = listOf(BLOOM, NEON, PANEL, BACKGROUND, ARC, GEAR, TYPOGRAPHY, LAYOUT)

    val all: List<ThemeField> = listOf(
        float(BLOOM, "threshold", 0f, 1f, false, { it.bloom.threshold }) { t, v -> t.copy(bloom = t.bloom.copy(threshold = v)) },
        float(BLOOM, "knee", 0f, 1f, false, { it.bloom.knee }) { t, v -> t.copy(bloom = t.bloom.copy(knee = v)) },
        float(BLOOM, "intensity", 0f, 2f, false, { it.bloom.intensity }) { t, v -> t.copy(bloom = t.bloom.copy(intensity = v)) },
        int(BLOOM, "radius", 1, 12, false, { it.bloom.radius }) { t, v -> t.copy(bloom = t.bloom.copy(radius = v)) },
        int(BLOOM, "passes", 1, 5, false, { it.bloom.passes }) { t, v -> t.copy(bloom = t.bloom.copy(passes = v)) },

        float(NEON, "haloWidthPx", 0f, 30f, true, { it.neon.haloWidthPx }) { t, v -> t.copy(neon = t.neon.copy(haloWidthPx = v)) },
        float(NEON, "bodyWidthPx", 0f, 10f, true, { it.neon.bodyWidthPx }) { t, v -> t.copy(neon = t.neon.copy(bodyWidthPx = v)) },
        float(NEON, "coreWidthPx", 0f, 5f, true, { it.neon.coreWidthPx }) { t, v -> t.copy(neon = t.neon.copy(coreWidthPx = v)) },
        color(NEON, "haloColor", true, { it.neon.haloColor }) { t, v -> t.copy(neon = t.neon.copy(haloColor = v)) },
        color(NEON, "bodyColor", true, { it.neon.bodyColor }) { t, v -> t.copy(neon = t.neon.copy(bodyColor = v)) },
        color(NEON, "coreColor", true, { it.neon.coreColor }) { t, v -> t.copy(neon = t.neon.copy(coreColor = v)) },
        float(NEON, "fadeShare", 0f, 1f, true, { it.neon.fadeShare }) { t, v -> t.copy(neon = t.neon.copy(fadeShare = v)) },

        color(PANEL, "topColor", true, { it.panel.topColor }) { t, v -> t.copy(panel = t.panel.copy(topColor = v)) },
        color(PANEL, "bottomColor", true, { it.panel.bottomColor }) { t, v -> t.copy(panel = t.panel.copy(bottomColor = v)) },
        color(PANEL, "edgeColor", true, { it.panel.edgeColor }) { t, v -> t.copy(panel = t.panel.copy(edgeColor = v)) },
        float(PANEL, "bevelAlpha", 0f, 0.5f, true, { it.panel.bevelAlpha }) { t, v -> t.copy(panel = t.panel.copy(bevelAlpha = v)) },
        color(PANEL, "plateColor", true, { it.panel.plateColor }) { t, v -> t.copy(panel = t.panel.copy(plateColor = v)) },
        float(PANEL, "plateBevelAlpha", 0f, 0.5f, true, { it.panel.plateBevelAlpha }) { t, v -> t.copy(panel = t.panel.copy(plateBevelAlpha = v)) },

        color(BACKGROUND, "baseColor", true, { it.background.baseColor }) { t, v -> t.copy(background = t.background.copy(baseColor = v)) },
        color(BACKGROUND, "centerColor", true, { it.background.centerColor }) { t, v -> t.copy(background = t.background.copy(centerColor = v)) },
        float(BACKGROUND, "vignette", 0f, 1f, true, { it.background.vignette }) { t, v -> t.copy(background = t.background.copy(vignette = v)) },
        float(BACKGROUND, "textureAlpha", 0f, 0.3f, true, { it.background.textureAlpha }) { t, v -> t.copy(background = t.background.copy(textureAlpha = v)) },

        float(ARC, "bandWidth", 6f, 40f, true, { it.arc.bandWidth }) { t, v -> t.copy(arc = t.arc.copy(bandWidth = v)) },
        color(ARC, "litStartColor", false, { it.arc.litStartColor }) { t, v -> t.copy(arc = t.arc.copy(litStartColor = v)) },
        color(ARC, "litEndColor", false, { it.arc.litEndColor }) { t, v -> t.copy(arc = t.arc.copy(litEndColor = v)) },
        color(ARC, "dimStartColor", true, { it.arc.dimStartColor }) { t, v -> t.copy(arc = t.arc.copy(dimStartColor = v)) },
        color(ARC, "dimEndColor", true, { it.arc.dimEndColor }) { t, v -> t.copy(arc = t.arc.copy(dimEndColor = v)) },
        float(ARC, "specularAlpha", 0f, 1f, false, { it.arc.specularAlpha }) { t, v -> t.copy(arc = t.arc.copy(specularAlpha = v)) },
        float(ARC, "needleWidthPx", 1f, 12f, false, { it.arc.needleWidthPx }) { t, v -> t.copy(arc = t.arc.copy(needleWidthPx = v)) },
        color(ARC, "redInnerColor", false, { it.arc.redInnerColor }) { t, v -> t.copy(arc = t.arc.copy(redInnerColor = v)) },
        color(ARC, "redOuterColor", false, { it.arc.redOuterColor }) { t, v -> t.copy(arc = t.arc.copy(redOuterColor = v)) },
        color(ARC, "redIdleInnerColor", true, { it.arc.redIdleInnerColor }) { t, v -> t.copy(arc = t.arc.copy(redIdleInnerColor = v)) },
        color(ARC, "redIdleOuterColor", true, { it.arc.redIdleOuterColor }) { t, v -> t.copy(arc = t.arc.copy(redIdleOuterColor = v)) },

        float(GEAR, "size", 80f, 200f, false, { it.gear.size }) { t, v -> t.copy(gear = t.gear.copy(size = v)) },
        float(GEAR, "haloRadius", 0f, 30f, false, { it.gear.haloRadius }) { t, v -> t.copy(gear = t.gear.copy(haloRadius = v)) },
        float(GEAR, "haloIntensity", 0f, 1f, false, { it.gear.haloIntensity }) { t, v -> t.copy(gear = t.gear.copy(haloIntensity = v)) },
        color(GEAR, "haloColor", false, { it.gear.haloColor }) { t, v -> t.copy(gear = t.gear.copy(haloColor = v)) },

        float(TYPOGRAPHY, "valueRatio", 0.2f, 0.6f, true, { it.typography.valueRatio }) { t, v -> t.copy(typography = t.typography.copy(valueRatio = v)) },
        float(TYPOGRAPHY, "labelRatio", 0.08f, 0.3f, true, { it.typography.labelRatio }) { t, v -> t.copy(typography = t.typography.copy(labelRatio = v)) },
        float(TYPOGRAPHY, "unitRatio", 0.08f, 0.3f, true, { it.typography.unitRatio }) { t, v -> t.copy(typography = t.typography.copy(unitRatio = v)) },
        color(TYPOGRAPHY, "labelColor", true, { it.typography.labelColor }) { t, v -> t.copy(typography = t.typography.copy(labelColor = v)) },

        float(LAYOUT, "columnMin", 120f, 340f, true, { it.layout.columnMin }) { t, v -> t.copy(layout = t.layout.copy(columnMin = v)) },
        float(LAYOUT, "columnMax", 160f, 400f, true, { it.layout.columnMax }) { t, v -> t.copy(layout = t.layout.copy(columnMax = v)) },
        float(LAYOUT, "gaugeHeightShare", 0.6f, 1f, true, { it.layout.gaugeHeightShare }) { t, v -> t.copy(layout = t.layout.copy(gaugeHeightShare = v)) },
    )

    fun fieldsOf(group: ThemeGroup) = all.filter { it.group === group }

    /** Valores de los campos que afectan a la capa estática: si cambian, hay que regenerarla. */
    fun staticKey(t: DashTheme): DoubleArray {
        val statics = all.filter { it.static }
        return DoubleArray(statics.size) { statics[it].get(t) }
    }

    // ------------------------------------------------------------------
    // JSON plano para los presets: {"bloom.threshold":0.6,...}
    // ------------------------------------------------------------------

    fun toJson(t: DashTheme): String = buildString {
        append('{')
        all.forEachIndexed { i, f ->
            if (i > 0) append(',')
            append('"').append(f.key).append("\":").append(jsonValue(f, f.get(t)))
        }
        append('}')
    }

    /** Lee un preset. Lo que falte o no se entienda se queda con el valor de [base]. */
    fun fromJson(json: String, base: DashTheme = DashTheme()): DashTheme {
        val values = HashMap<String, Double>()
        for (m in JSON_ENTRY.findAll(json)) {
            m.groupValues[2].toDoubleOrNull()?.let { values[m.groupValues[1]] = it }
        }
        var t = base
        for (f in all) {
            val v = values[f.key] ?: continue
            t = f.set(t, v.coerceIn(f.min.toDouble(), f.max.toDouble()).takeIf { f.kind != FieldKind.COLOR } ?: v)
        }
        return t
    }

    private val JSON_ENTRY = Regex("\"([A-Za-z.]+)\"\\s*:\\s*(-?[0-9][0-9.eE+-]*)")

    private fun jsonValue(f: ThemeField, v: Double): String = when (f.kind) {
        FieldKind.FLOAT -> v.toFloat().toString()
        FieldKind.INT -> v.toInt().toString()
        FieldKind.COLOR -> (v.toLong() and 0xFFFFFFFFL).toString()
    }

    // ------------------------------------------------------------------
    // Código Kotlin: el mismo formato que DashTheme.kt, con los valores
    // actuales como valores por defecto.
    // ------------------------------------------------------------------

    fun kotlinSource(t: DashTheme): String = buildString {
        appendLine("package com.example.ibizacustommap")
        appendLine()
        appendLine("// Parámetros visuales del salpicadero. Este fichero lo genera ThemeFields.kotlinSource")
        appendLine("// (botón Exportar de la pantalla de ajuste del diseño, solo en debug): para fijar")
        appendLine("// un diseño afinado, sustituye este fichero entero por el código exportado.")
        appendLine()
        appendLine("data class DashTheme(")
        for (g in groups) appendLine("    val ${g.property}: ${g.className} = ${g.className}(),")
        appendLine(")")
        for (g in groups) {
            appendLine()
            appendLine("data class ${g.className}(")
            for (f in fieldsOf(g)) appendLine("    val ${f.name}: ${kotlinType(f)} = ${kotlinLiteral(f, f.get(t))},")
            appendLine(")")
        }
    }

    private fun kotlinType(f: ThemeField) = when (f.kind) {
        FieldKind.FLOAT -> "Float"
        FieldKind.INT, FieldKind.COLOR -> "Int"
    }

    private fun kotlinLiteral(f: ThemeField, v: Double): String = when (f.kind) {
        FieldKind.FLOAT -> "${v.toFloat()}f"
        FieldKind.INT -> v.toInt().toString()
        FieldKind.COLOR -> String.format(Locale.ROOT, "0x%08X.toInt()", v.toLong() and 0xFFFFFFFFL)
    }

    // ------------------------------------------------------------------

    private fun float(
        g: ThemeGroup, name: String, min: Float, max: Float, static: Boolean,
        get: (DashTheme) -> Float, set: (DashTheme, Float) -> DashTheme,
    ) = ThemeField(g, name, FieldKind.FLOAT, min, max, static, { get(it).toDouble() }, { t, v -> set(t, v.toFloat()) })

    private fun int(
        g: ThemeGroup, name: String, min: Int, max: Int, static: Boolean,
        get: (DashTheme) -> Int, set: (DashTheme, Int) -> DashTheme,
    ) = ThemeField(g, name, FieldKind.INT, min.toFloat(), max.toFloat(), static, { get(it).toDouble() }, { t, v -> set(t, Math.round(v).toInt()) })

    private fun color(
        g: ThemeGroup, name: String, static: Boolean,
        get: (DashTheme) -> Int, set: (DashTheme, Int) -> DashTheme,
    ) = ThemeField(
        g, name, FieldKind.COLOR, 0f, 0f, static,
        { (get(it).toLong() and 0xFFFFFFFFL).toDouble() }, { t, v -> set(t, v.toLong().toInt()) },
    )
}
