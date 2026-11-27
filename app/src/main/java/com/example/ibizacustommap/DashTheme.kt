package com.example.ibizacustommap

// Parámetros visuales del salpicadero. Este fichero lo genera ThemeFields.kotlinSource
// (botón Exportar de la pantalla de ajuste del diseño, solo en debug): para fijar
// un diseño afinado, sustituye este fichero entero por el código exportado.

data class DashTheme(
    val bloom: BloomTheme = BloomTheme(),
    val neon: NeonTheme = NeonTheme(),
    val panel: PanelTheme = PanelTheme(),
    val background: BackgroundTheme = BackgroundTheme(),
    val arc: ArcTheme = ArcTheme(),
    val gear: GearTheme = GearTheme(),
    val typography: TypographyTheme = TypographyTheme(),
    val layout: LayoutTheme = LayoutTheme(),
)

data class BloomTheme(
    val threshold: Float = 1.0f,
    val knee: Float = 0.0f,
    val intensity: Float = 0.0f,
    val radius: Int = 5,
    val passes: Int = 2,
)

data class NeonTheme(
    val haloWidthPx: Float = 10.471429f,
    val bodyWidthPx: Float = 10.0f,
    val coreWidthPx: Float = 0.0f,
    val haloColor: Int = 0xFFFF1020.toInt(),
    val bodyColor: Int = 0xFFFF1A2A.toInt(),
    val coreColor: Int = 0xFFFFB0B8.toInt(),
    val fadeShare: Float = 0.79931587f,
)

data class PanelTheme(
    val topColor: Int = 0xFF0E0E11.toInt(),
    val bottomColor: Int = 0xFF060608.toInt(),
    val edgeColor: Int = 0xFF26262C.toInt(),
    val bevelAlpha: Float = 0.5f,
    val plateColor: Int = 0xFF121216.toInt(),
    val plateBevelAlpha: Float = 0.5f,
)

data class BackgroundTheme(
    val baseColor: Int = 0xFF050506.toInt(),
    val centerColor: Int = 0xFF0F0F11.toInt(),
    val vignette: Float = 0.0f,
    val textureAlpha: Float = 0.21514413f,
)

data class ArcTheme(
    val bandWidth: Float = 19.091732f,
    val litStartColor: Int = 0xFF4A4A50.toInt(),
    val litEndColor: Int = 0xFFB8B8BE.toInt(),
    val dimStartColor: Int = 0xFF222226.toInt(),
    val dimEndColor: Int = 0xFF3E3E44.toInt(),
    val specularAlpha: Float = 1.0f,
    val needleWidthPx: Float = 4.9500413f,
    val redInnerColor: Int = 0xFF5A0008.toInt(),
    val redOuterColor: Int = 0xFFFF1A2A.toInt(),
    val redIdleInnerColor: Int = 0xFF380006.toInt(),
    val redIdleOuterColor: Int = 0xFFB4101E.toInt(),
)

data class GearTheme(
    val size: Float = 142.74934f,
    val haloRadius: Float = 9.424248f,
    val haloIntensity: Float = 0.7907686f,
    val haloColor: Int = 0xFFFF111B.toInt(),
)

data class TypographyTheme(
    val valueRatio: Float = 0.39614254f,
    val labelRatio: Float = 0.3f,
    val unitRatio: Float = 0.23495026f,
    val labelColor: Int = 0xFFA0A0A8.toInt(),
)

data class LayoutTheme(
    val columnMin: Float = 230.1135f,
    val columnMax: Float = 334.03607f,
    val gaugeHeightShare: Float = 0.9082585f,
)
