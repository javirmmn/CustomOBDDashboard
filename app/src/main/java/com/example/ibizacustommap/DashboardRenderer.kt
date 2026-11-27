package com.example.ibizacustommap

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import com.example.ibizacustommap.DashboardIcons.Icon
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Dibuja el salpicadero sobre un Canvas de Android. Es un componente puro (no
 * sabe nada de Bluetooth, OBD ni Android Auto): recibe un [TelemetrySnapshot]
 * ya calculado y lo pinta. Diseño de referencia: `docs/design/referencia.webp`.
 * Todos los parámetros visuales salen de un [DashTheme] ([ThemeStore]).
 *
 * Lo usan MainScreen (Android Auto), DashboardPreviewActivity, DebugFrameExporter
 * y la pantalla de ajuste del diseño (debug); cada uno tiene su propia
 * instancia, porque guarda cachés y estado de animación.
 *
 * Capas:
 * 1. **Estática** (fondo, placas, marcos, neón, escala, iconos, haces, logo):
 *    se pinta UNA vez en un bitmap por tamaño de superficie y por valores de
 *    los campos "estáticos" del tema. Si estos cambian (p. ej. moviendo un
 *    slider) se regenera con un debounce de [STATIC_DEBOUNCE_MS].
 * 2. **Dinámica**: valores, barras, arco de RPM y marcha, cada frame.
 * 3. **Bloom global por umbral**: el frame a 1/4 (copia estática reducida más
 *    la parte dinámica redibujada a 1/4) pasa por un umbral de luminancia con
 *    rodilla suave, se le suma la capa emisiva (neón, halo de la marcha,
 *    aguja, zona roja), se desenfoca ([BoxBlur]) y se suma al frame (PLUS).
 *
 * Sin reservas de memoria por frame: Paint, rectángulos, búferes de texto
 * ([TextBuf]), métricas y bitmaps se crean una vez. Solo se reserva al cambiar
 * el tamaño o el tema, una vez por minuto (la hora) y ~1 vez por segundo si
 * hay [metricsSink].
 *
 * Coordenadas: lienzo de diseño de [DESIGN_W] × [DESIGN_H] unidades escalado
 * al área disponible (stableArea de Android Auto).
 */
class DashboardRenderer(
    private val logo: Bitmap? = null,
    /** Chivato de calibración (R:, RAW, SIM_OK). Solo en builds de debug. */
    private val debugOverlay: Boolean = BuildConfig.DEBUG,
    private val themeSource: StateFlow<DashTheme> = ThemeStore.theme,
    /** Si no es null, aquí se publica el resumen de métricas (~1 vez por segundo). */
    private val metricsSink: MutableStateFlow<MetricsSummary?>? = null,
    private val metricsOverlay: StateFlow<Boolean> = DashDebug.showMetricsOverlay,
) {

    /** Tiempos de los últimos frames. */
    val metrics = RenderMetrics()

    // ------------------------------------------------------------------
    // Pinceles y objetos reutilizados en cada frame
    // ------------------------------------------------------------------

    // Roboto Black cursiva (ancha y gruesa) para los números y Roboto cursiva
    // para las etiquetas; ambas vienen con Android.
    private val tfBold: Typeface = Typeface.create("sans-serif-black", Typeface.ITALIC)
    private val tfLabel: Typeface = Typeface.create("sans-serif", Typeface.ITALIC)

    private val numberPaint = textPaint(tfBold, WHITE)
    private val labelPaint = textPaint(tfLabel, WHITE)
    private val unitPaint = textPaint(tfLabel, UNIT)
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val glowText = textPaint(tfBold, WHITE).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        textAlign = Paint.Align.CENTER
    }
    private val composite = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        if (Build.VERSION.SDK_INT >= 29) blendMode = BlendMode.PLUS
        else xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val overlayPaint = textPaint(tfLabel, Color.WHITE)

    private val arcRect = RectF()
    private val tmpRect = RectF()
    private val barTmp = RectF()
    private val dstRect = RectF()
    private val fullArea = Rect()
    private val fm = Paint.FontMetrics()
    private val buf = TextBuf()
    private val buf2 = TextBuf()

    // ------------------------------------------------------------------
    // Caché y estado
    // ------------------------------------------------------------------

    private var geo: Geometry? = null
    private var background: Bitmap? = null
    private var bloom: Bloom? = null

    /** Tema con el que se construyó la capa estática (y la geometría). */
    private var sTheme = DashTheme()
    private var builtKey: DoubleArray? = null

    /** Tema de este frame (para todo lo dinámico). */
    private var dTheme = DashTheme()
    private var lastTheme: DashTheme? = null

    /** Tema estático pendiente de aplicar tras el debounce. */
    private var pendingTheme: DashTheme? = null
    private var pendingSince = 0L

    // Shaders dinámicos: se rehacen solo si cambian el tema o la geometría.
    private var shaderTheme: DashTheme? = null
    private var shaderGeo: Geometry? = null
    private var silver: Shader? = null
    private var redActive: Shader? = null
    private var gearHaloColor = 0

    /** Lienzo de la capa emisiva estática (1/4); solo mientras se construye la capa estática. */
    private var staticGlow: Canvas? = null

    /** 1 píxel real en unidades de diseño. */
    private var onePx = 1f

    /** 1 píxel real en unidades locales del cuentavueltas. */
    private var gaugePx = 1f

    /** RPM que se pintan: se acercan a la lectura real en cada frame para que el arco no vaya a saltos. */
    private var shownRpm = Float.NaN

    private var clockMinute = -1L
    private var clockText = DashFormat.NO_DATA

    /** Última capa de bloom ya procesada (para depuración). */
    val lastBloom: Bitmap? get() = bloom?.current

    /**
     * @param area zona donde dibujar (stableArea de Android Auto, en
     *   coordenadas de la superficie). Null o inválida → superficie completa.
     */
    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        s: TelemetrySnapshot,
        area: Rect? = null,
        nowMs: Long = SystemClock.uptimeMillis(),
    ) {
        val t0 = System.nanoTime()
        canvas.drawColor(Color.BLACK)
        if (width <= 0 || height <= 0) return
        fullArea.set(0, 0, width, height)
        val a = if (area != null && !area.isEmpty && fullArea.contains(area)) area else fullArea

        val t = themeSource.value
        if (t !== lastTheme) onThemeChanged(t, nowMs)
        dTheme = t
        val g = ensureStatic(a.width(), a.height(), t, nowMs)
        val b = bloom ?: return
        ensureDynamicShaders(t, g)

        shownRpm = when {
            s.rpm.isNaN() -> Float.NaN
            shownRpm.isNaN() -> s.rpm
            else -> shownRpm + (s.rpm - shownRpm) * RPM_SMOOTHING
        }

        // 1. Capa estática.
        background?.let { canvas.drawBitmap(it, a.left.toFloat(), a.top.toFloat(), null) }
        val t1 = System.nanoTime()

        // Con intensidad 0 el umbral no aporta nada: no hace falta el frame a 1/4.
        val threshold = t.bloom.intensity > 0f

        // 2. Capa dinámica en pantalla, con lo emisivo a la capa de bloom.
        b.begin(threshold)
        canvas.save()
        canvas.translate(a.left.toFloat(), a.top.toFloat())
        designSpace(canvas, g)
        b.emissiveCanvas.save()
        b.emissiveCanvas.scale(b.sx, b.sy)
        designSpace(b.emissiveCanvas, g)
        drawDynamic(canvas, b.emissiveCanvas, g, s, nowMs)
        b.emissiveCanvas.restore()
        canvas.restore()
        val t2 = System.nanoTime()

        // 3. Bloom: la parte dinámica otra vez a 1/4 sobre la copia estática,
        //    umbral + emisivo, desenfoque y suma sobre la misma área.
        if (threshold) {
            b.frameCanvas.save()
            b.frameCanvas.scale(b.sx, b.sy)
            designSpace(b.frameCanvas, g)
            drawDynamic(b.frameCanvas, null, g, s, nowMs)
            b.frameCanvas.restore()
        }
        b.finish(t.bloom, threshold)
        dstRect.set(a)
        canvas.drawBitmap(b.current, null, dstRect, composite)
        val t3 = System.nanoTime()

        if (metrics.record(t0, t1 - t0, t2 - t1, t3 - t2)) metricsSink?.value = metrics.summary()
        if (metricsOverlay.value) drawMetricsOverlay(canvas, a, g)
    }

    private fun designSpace(c: Canvas, g: Geometry) {
        c.scale(g.u, g.u)
        c.translate(0f, g.oy)
    }

    private fun drawDynamic(c: Canvas, glow: Canvas?, g: Geometry, s: TelemetrySnapshot, nowMs: Long) {
        drawGaugeDynamic(c, glow, g, s, nowMs)
        drawCells(c, g, s)
        drawBottom(c, g, s)
        drawTop(c, g, s)
        if (s.link != LinkState.LIVE) drawStatusBanner(c, g, s.link)
        if (debugOverlay) drawDebug(c, s)
    }

    // ------------------------------------------------------------------
    // Tema
    // ------------------------------------------------------------------

    /** Solo al cambiar de tema (no por frame): ¿cambia algo que obligue a regenerar la capa estática? */
    private fun onThemeChanged(t: DashTheme, nowMs: Long) {
        lastTheme = t
        val key = ThemeFields.staticKey(t)
        if (builtKey?.contentEquals(key) == true) {
            pendingTheme = null
        } else {
            pendingTheme = t
            pendingSince = nowMs
        }
    }

    private fun ensureStatic(w: Int, h: Int, t: DashTheme, nowMs: Long): Geometry {
        val current = geo
        val pending = pendingTheme
        val theme = when {
            current == null -> t
            current.w != w || current.h != h -> pending ?: sTheme
            pending != null && nowMs - pendingSince >= STATIC_DEBOUNCE_MS -> pending
            else -> return current
        }
        pendingTheme = null
        return buildStatic(w, h, theme)
    }

    private fun buildStatic(w: Int, h: Int, theme: DashTheme): Geometry {
        sTheme = theme
        builtKey = ThemeFields.staticKey(theme)
        val g = Geometry(w, h, theme)
        onePx = 1f / g.u
        gaugePx = 1f / (g.u * g.k)

        val bw = max(1, w / BLOOM_DOWNSCALE)
        val bh = max(1, h / BLOOM_DOWNSCALE)
        val glowBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val glowCanvas = Canvas(glowBitmap).apply {
            drawColor(Color.BLACK)
            scale(bw.toFloat() / w, bh.toFloat() / h)
            designSpace(this, g)
        }
        staticGlow = glowCanvas
        // No se reciclan los bitmaps anteriores: un frame en vuelo podría estar usándolos.
        val bg = renderBackground(g)
        staticGlow = null
        background = bg
        bloom = Bloom(w, h, bw, bh, bg, glowBitmap)
        geo = g
        return g
    }

    private fun ensureDynamicShaders(t: DashTheme, g: Geometry) {
        if (t === shaderTheme && g === shaderGeo) return
        shaderTheme = t
        shaderGeo = g
        silver = SweepGradient(
            0f, 0f,
            intArrayOf(t.arc.litStartColor, t.arc.litEndColor, t.arc.litEndColor),
            floatArrayOf(0f, SCALE_END, 1f),
        ).apply { setLocalMatrix(fromZero) }
        redActive = RadialGradient(
            0f, 0f, g.gOuter,
            intArrayOf(t.arc.redInnerColor, t.arc.redInnerColor, t.arc.redOuterColor),
            floatArrayOf(0f, g.gInner / g.gOuter, 1f), Shader.TileMode.CLAMP,
        )
        val c = t.gear.haloColor
        val k = t.gear.haloIntensity
        gearHaloColor = Color.rgb((Color.red(c) * k).toInt(), (Color.green(c) * k).toInt(), (Color.blue(c) * k).toInt())
    }

    // ------------------------------------------------------------------
    // Geometría
    // ------------------------------------------------------------------

    private class Geometry(val w: Int, val h: Int, theme: DashTheme) {
        val u = min(w / DESIGN_W, h / DESIGN_H)
        val vw = w / u
        val vh = h / u
        val oy = (vh - DESIGN_H) / 2f
        val cx = vw / 2f

        // Cuentavueltas en coordenadas locales (centro en 0,0, tamaño base).
        val band = theme.arc.bandWidth
        val gInner = G_R - band / 2f
        val gOuter = G_R + band / 2f
        val gExtent = gOuter + 23f

        // Cuentavueltas lo más grande posible (hasta gaugeHeightShare del
        // alto); las columnas se quedan con el ancho que sobra.
        private val targetExtent = DESIGN_H * theme.layout.gaugeHeightShare / 2f
        val colW = ((vw - 2f * M - 2f * GAUGE_SIDE_GAP - 2f * targetExtent) / 2f)
            .coerceIn(theme.layout.columnMin, max(theme.layout.columnMin, theme.layout.columnMax))
        val extent = min(targetExtent, (vw - 2f * M - 2f * GAUGE_SIDE_GAP - 2f * colW) / 2f)

        /** Escala del cuentavueltas respecto a su tamaño base. */
        val k = extent / gExtent
        val cy = GAUGE_TOP + extent

        val speedTopY = cy + SPEED_TOP * k
        val speedBottomY = cy + SPEED_BOTTOM * k

        /**
         * Semiancho de la muesca central de la franja inferior en su borde
         * superior: lo justo para que la franja no pise el bisel del
         * cuentavueltas ni el panel de la velocidad.
         */
        val notchTop: Float = run {
            var need = 118f
            var y = BOTTOM_TOP
            while (y <= BOTTOM_BOTTOM) {
                val t = (y - BOTTOM_TOP) / (BOTTOM_BOTTOM - BOTTOM_TOP)
                if (t < 0.95f) {
                    var clear = 0f
                    // El bisel solo existe por encima de la abertura inferior (±30° de la vertical).
                    if (y <= cy + extent * COS_30 && y >= cy - extent) {
                        clear = sqrt(extent * extent - (y - cy) * (y - cy))
                    }
                    if (y in speedTopY..speedBottomY) {
                        val f = (y - speedTopY) / (speedBottomY - speedTopY)
                        clear = max(clear, (SPEED_TOP_HALF + (SPEED_BOTTOM_HALF - SPEED_TOP_HALF) * f) * k)
                    }
                    if (clear > 0f) need = max(need, (clear + 8f - NOTCH_BOTTOM * t) / (1f - t))
                }
                y += 2f
            }
            need
        }
        val logoH = min(LOGO_H, BOTTOM_BOTTOM - speedBottomY - 10f).coerceAtLeast(24f)
        val logoCy = (speedBottomY + BOTTOM_BOTTOM) / 2f

        val leftCells = List(3) { i ->
            val t = PANEL_TOP + i * (PANEL_H + PANEL_GAP)
            RectF(M, t, M + colW, t + PANEL_H)
        }
        val rightCells = List(3) { i ->
            val t = PANEL_TOP + i * (PANEL_H + PANEL_GAP)
            RectF(vw - M - colW, t, vw - M, t + PANEL_H)
        }
        val clock = RectF(M, TOP_Y, M + TOP_W, TOP_Y + TOP_H)
        val temp = RectF(vw - M - TOP_W, TOP_Y, vw - M, TOP_Y + TOP_H)
        val bottomLeft = RectF(M, BOTTOM_TOP, cx - notchTop, BOTTOM_BOTTOM)
        val bottomRight = RectF(cx + notchTop, BOTTOM_TOP, vw - M, BOTTOM_BOTTOM)

        /** Barras a todo el ancho del panel (solo respetan los chaflanes). */
        val bars: List<RectF> = List(6) { i ->
            val r = cell(i)
            val right = i >= 3
            val left = r.left + if (right) 18f else 14f
            val end = r.right - if (right) 14f else 18f
            RectF(left, r.top + 74f, end, r.top + 74f + BAR_H)
        }

        /** Degradados de las barras: refrigerante, admisión, carga, boost, pedal. */
        val barShaders: List<Shader> = BAR_COLORS.mapIndexed { i, (from, to) ->
            LinearGradient(bars[i].left, 0f, bars[i].right, 0f, from, to, Shader.TileMode.CLAMP)
        }

        // Posiciones de la franja inferior.
        val bottomLeftX: FloatArray = run {
            val w0 = cx - notchTop - M
            // Ign Timing, Lambda, separador, icono.
            floatArrayOf(M + w0 * 0.2f, M + w0 * 0.53f, M + w0 * 0.36f, M + w0 * 0.8f)
        }
        val bottomRightX: FloatArray = run {
            val x0 = cx + notchTop
            val w0 = vw - M - x0
            // Consumo, autonomía, separador, icono gota, icono surtidor.
            floatArrayOf(x0 + w0 * 0.34f, x0 + w0 * 0.84f, x0 + w0 * 0.565f, x0 + w0 * 0.08f, x0 + w0 * 0.645f)
        }

        fun cell(i: Int) = if (i < 3) leftCells[i] else rightCells[i - 3]
    }

    /**
     * Bitmaps del bloom a 1/4 de resolución, todos creados una vez por capa
     * estática: copia reducida del fondo, capa emisiva estática, lienzos de
     * trabajo, búferes de píxeles y dos salidas alternas (por si un frame aún
     * usa la anterior).
     */
    private class Bloom(w: Int, h: Int, private val bw: Int, private val bh: Int, staticFrame: Bitmap, private val staticEmissive: Bitmap) {
        val sx = bw.toFloat() / w
        val sy = bh.toFloat() / h
        private val staticSmall = Bitmap.createScaledBitmap(staticFrame, bw, bh, true)
        private val frameBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        private val emissiveBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val frameCanvas = Canvas(frameBitmap)
        val emissiveCanvas = Canvas(emissiveBitmap)
        private val outputs = Array(2) { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) }
        private val framePx = IntArray(bw * bh)
        private val emissivePx = IntArray(bw * bh)
        private val tmp = IntArray(bw * bh)
        private var index = 0
        var current: Bitmap = outputs[0]
            private set

        /** [threshold]: si hay que preparar también el frame a 1/4 para el umbral. */
        fun begin(threshold: Boolean) {
            if (threshold) frameCanvas.drawBitmap(staticSmall, 0f, 0f, null)
            emissiveCanvas.drawBitmap(staticEmissive, 0f, 0f, null)
        }

        fun finish(b: BloomTheme, threshold: Boolean) {
            if (threshold) {
                frameBitmap.getPixels(framePx, 0, bw, 0, 0, bw, bh)
                emissiveBitmap.getPixels(emissivePx, 0, bw, 0, 0, bw, bh)
                BloomMath.brightPass(framePx, emissivePx, bw * bh, b.threshold, b.knee, b.intensity)
            } else {
                // Sin umbral, el bloom es solo la capa emisiva (opaca: base negra).
                emissiveBitmap.getPixels(framePx, 0, bw, 0, 0, bw, bh)
            }
            BoxBlur.blurRgb(framePx, tmp, bw, bh, b.radius, b.passes)
            index = 1 - index
            current = outputs[index]
            current.setPixels(framePx, 0, bw, 0, 0, bw, bh)
        }
    }

    // ------------------------------------------------------------------
    // Capa estática (una vez por tamaño y por tema estático)
    // ------------------------------------------------------------------

    private fun renderBackground(g: Geometry): Bitmap {
        val bg = sTheme.background
        val bmp = Bitmap.createBitmap(g.w, g.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        designSpace(c, g)
        val top = -g.oy
        val bottom = DESIGN_H + g.oy

        // Fondo con viñeta (algo más claro en el centro, oscuro en los bordes)
        // y una textura de carbono casi invisible.
        val edge = lerpColor(bg.baseColor, Color.BLACK, bg.vignette)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = RadialGradient(
            g.cx, g.cy, max(g.vw, g.vh) * 0.62f,
            intArrayOf(bg.centerColor, bg.baseColor, edge),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, top, g.vw, bottom, p)
        c.drawRect(0f, top, g.vw, bottom, carbonPaint(alpha255(bg.textureAlpha)))

        drawPlates(c, g)
        drawTopStatic(c, g)

        val sg = staticGlow
        c.save()
        c.translate(g.cx, g.cy)
        c.scale(g.k, g.k)
        sg?.save()
        sg?.translate(g.cx, g.cy)
        sg?.scale(g.k, g.k)
        drawGaugeStatic(c, sg, g)
        drawSpeedPanelStatic(c)
        sg?.restore()
        c.restore()

        drawCellsStatic(c, g)
        drawBottomStatic(c, g)
        return bmp
    }

    /** Fondo estructural: placas chaflanadas detrás y entre los paneles. */
    private fun drawPlates(c: Canvas, g: Geometry) {
        val colBottom = PANEL_TOP + 3f * PANEL_H + 2f * PANEL_GAP
        val left = RectF(M - 8f, PANEL_TOP - 12f, M + g.colW + 20f, colBottom + 12f)
        val right = RectF(g.vw - M - g.colW - 20f, PANEL_TOP - 12f, g.vw - M + 8f, colBottom + 12f)
        plate(c, chamfer(left, 14f, 36f), left, 14f, 36f)
        plate(c, chamfer(right, 36f, 14f), right, 36f, 14f)

        val topL = RectF(M - 8f, 2f, g.clock.right + 56f, TOP_Y + TOP_H + 12f)
        val topR = RectF(g.temp.left - 56f, 2f, g.vw - M + 8f, TOP_Y + TOP_H + 12f)
        plate(c, chamfer(topL, 8f, 30f), topL, 8f, 30f)
        plate(c, chamfer(topR, 30f, 8f), topR, 30f, 8f)

        // Placas pequeñas entre paneles, en el lado que da al cuentavueltas.
        for (i in 0 until 2) {
            val gy = PANEL_TOP + (i + 1) * PANEL_H + i * PANEL_GAP + PANEL_GAP / 2f
            plate(c, hexBar(g.leftCells[0].right - 46f, g.leftCells[0].right + 30f, gy, 7f), RectF(), 0f, 0f, bevel = false)
            plate(c, hexBar(g.rightCells[0].left - 30f, g.rightCells[0].left + 46f, gy, 7f), RectF(), 0f, 0f, bevel = false)
        }

        val bottom = RectF(M - 8f, BOTTOM_TOP - 16f, g.vw - M + 8f, BOTTOM_BOTTOM + 8f)
        plate(c, chamfer(bottom, 20f, 20f), bottom, 20f, 20f)
    }

    private fun plate(c: Canvas, path: Path, bounds: RectF, cutLeft: Float, cutRight: Float, bevel: Boolean = true) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sTheme.panel.plateColor }
        c.drawPath(path, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = onePx
            color = Color.rgb(28, 28, 33)
        }
        c.drawPath(path, stroke)
        if (bevel) {
            stroke.color = Color.argb(alpha255(sTheme.panel.plateBevelAlpha), 255, 255, 255)
            c.drawLine(bounds.left + cutLeft, bounds.top + onePx, bounds.right - cutRight, bounds.top + onePx, stroke)
        }
    }

    private fun drawTopStatic(c: Canvas, g: Geometry) {
        drawPanel(c, chamfer(g.clock, 8f, 22f), g.clock, outerLeft = true, outerCut = 8f, leftTopCut = 8f, rightTopCut = 22f)
        drawPanel(c, chamfer(g.temp, 22f, 8f), g.temp, outerLeft = false, outerCut = 8f, leftTopCut = 22f, rightTopCut = 8f)

        // Acentos de los marcos: línea bajo cada caja que sube en diagonal.
        val y = g.clock.bottom + 7f
        val diagTop = g.clock.top + 34f
        neonPath(c, Path().apply {
            moveTo(g.clock.left + 6f, y)
            lineTo(g.clock.right - 18f, y)
            lineTo(g.clock.right + 10f, diagTop)
            lineTo(g.clock.right + 46f, diagTop)
        })
        neonPath(c, Path().apply {
            moveTo(g.temp.right - 6f, y)
            lineTo(g.temp.left + 18f, y)
            lineTo(g.temp.left - 10f, diagTop)
            lineTo(g.temp.left - 46f, diagTop)
        })
    }

    /** Cuentavueltas en coordenadas locales: centro en (0,0), tamaño base. */
    private fun drawGaugeStatic(c: Canvas, sg: Canvas?, g: Geometry) {
        val arcTheme = sTheme.arc
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

        // Esfera con profundidad: más clara en el centro, negra en el borde.
        p.shader = RadialGradient(
            0f, 0f, g.gOuter + 26f,
            intArrayOf(Color.rgb(22, 22, 25), Color.rgb(10, 10, 12), Color.rgb(3, 3, 4)),
            floatArrayOf(0f, 0.68f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawCircle(0f, 0f, g.gOuter + 26f, p)
        p.shader = null

        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        // Bisel exterior grueso con degradado metálico, abierto por abajo
        // (ahí va el panel de la velocidad).
        ring.shader = SweepGradient(
            0f, 0f,
            intArrayOf(
                Color.rgb(70, 70, 74), Color.rgb(16, 16, 18), Color.rgb(96, 96, 100),
                Color.rgb(16, 16, 18), Color.rgb(70, 70, 74),
            ),
            null,
        )
        ring.strokeWidth = 14f
        arc(c, g.gOuter + 16f, BEZEL_START, BEZEL_SWEEP, ring)
        ring.shader = null
        ring.strokeWidth = 1.2f
        ring.color = Color.rgb(118, 118, 122)
        arc(c, g.gOuter + 23f, BEZEL_START, BEZEL_SWEEP, ring)
        ring.strokeWidth = 3f
        ring.color = Color.rgb(3, 3, 4)
        arc(c, g.gOuter + 8f, BEZEL_START, BEZEL_SWEEP, ring)

        // Anillo interior alrededor de la marcha.
        ring.strokeWidth = 1.5f
        ring.color = sTheme.panel.edgeColor
        c.drawCircle(0f, 0f, G_R - 78f, ring)

        // Neón del bisel en el lado de la zona roja.
        tmpRect.set(-(g.gOuter + 16f), -(g.gOuter + 16f), g.gOuter + 16f, g.gOuter + 16f)
        neonPath(c, Path().apply { addArc(tmpRect, -60f, 105f) }, gaugePx, sg)

        // Banda apagada y zona roja en reposo (degradado radial) con su halo.
        val redStart = RpmScale.angleFor(RpmScale.RED_RPM)
        val redSweep = RpmScale.SWEEP_DEG - RpmScale.sweepFor(RpmScale.RED_RPM)
        val band = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = g.band
        }
        band.shader = SweepGradient(
            0f, 0f,
            intArrayOf(arcTheme.dimStartColor, arcTheme.dimEndColor, arcTheme.dimEndColor),
            floatArrayOf(0f, SCALE_END, 1f),
        ).apply { setLocalMatrix(fromZero) }
        arc(c, G_R, RpmScale.START_DEG, RpmScale.sweepFor(RpmScale.RED_RPM), band)
        band.shader = RadialGradient(
            0f, 0f, g.gOuter,
            intArrayOf(arcTheme.redIdleInnerColor, arcTheme.redIdleInnerColor, arcTheme.redIdleOuterColor),
            floatArrayOf(0f, g.gInner / g.gOuter, 1f), Shader.TileMode.CLAMP,
        )
        arc(c, G_R, redStart, redSweep, band)
        band.shader = null
        sg?.let {
            band.color = Color.rgb(120, 8, 16)
            arc(it, G_R, redStart, redSweep, band)
        }

        // Relieve de la banda: filo exterior claro, filo interior oscuro.
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * gaugePx
        }
        edge.color = Color.argb(90, 255, 255, 255)
        arc(c, g.gOuter - 0.7f, RpmScale.START_DEG, RpmScale.SWEEP_DEG, edge)
        edge.color = Color.argb(220, 0, 0, 0)
        arc(c, g.gInner + 0.7f, RpmScale.START_DEG, RpmScale.SWEEP_DEG, edge)
        edge.color = Color.rgb(58, 58, 62)
        edge.strokeWidth = 1.2f * gaugePx
        arc(c, g.gInner - 3f, RpmScale.START_DEG, RpmScale.SWEEP_DEG, edge)

        // Separadores negros de 3 px cada 1000 rpm y marcas menores grises finas.
        val tick = Paint(Paint.ANTI_ALIAS_FLAG)
        var rpm = 0f
        while (rpm <= RpmScale.MAX_RPM + 1f) {
            if (rpm % 1000f == 0f) {
                if (rpm > 0f && rpm < RpmScale.MAX_RPM) {
                    tick.color = Color.BLACK
                    tick.strokeWidth = 3f * gaugePx
                    radialLine(c, rpm, g.gInner, g.gOuter, tick)
                }
                tick.color = Color.rgb(170, 170, 176)
                tick.strokeWidth = 2f * gaugePx
                radialLine(c, rpm, g.gInner - 3f, g.gInner - 13f, tick)
            } else {
                tick.color = Color.rgb(96, 96, 102)
                tick.strokeWidth = 1.5f * gaugePx
                radialLine(c, rpm, g.gInner - 3f, g.gInner - 8f, tick)
            }
            rpm += 250f
        }

        // Números 0 a 7: blancos y, en la zona roja, rojos.
        val num = textPaint(tfBold, WHITE).apply {
            textAlign = Paint.Align.CENTER
            textSize = 32f
        }
        for (k in RpmScale.labels) {
            val a = Math.toRadians(RpmScale.angleFor(k * 1000f).toDouble())
            val rr = g.gInner - 34f
            val red = k * 1000f >= RpmScale.RED_RPM
            num.color = if (red) Color.rgb(0xFF, 0x1A, 0x2A) else WHITE
            drawCentered(c, k.toString(), rr * cos(a).toFloat(), rr * sin(a).toFloat(), num)
            if (red && sg != null) {
                num.color = Color.rgb(150, 10, 20)
                drawCentered(sg, k.toString(), rr * cos(a).toFloat(), rr * sin(a).toFloat(), num)
            }
        }

        val small = textPaint(tfLabel, sTheme.typography.labelColor).apply { textAlign = Paint.Align.CENTER }
        small.textSize = 16f
        drawCentered(c, "RPM x1000", 0f, -86f, small)
        small.textSize = 24f
        small.color = Color.rgb(178, 184, 196)
        drawCentered(c, "GEAR", 0f, 74f, small)
    }

    private fun drawSpeedPanelStatic(c: Canvas) {
        val path = Path().apply {
            moveTo(-SPEED_TOP_HALF, SPEED_TOP)
            lineTo(SPEED_TOP_HALF, SPEED_TOP)
            lineTo(SPEED_BOTTOM_HALF, SPEED_BOTTOM)
            lineTo(-SPEED_BOTTOM_HALF, SPEED_BOTTOM)
            close()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, SPEED_TOP, 0f, SPEED_BOTTOM, Color.rgb(24, 24, 28), sTheme.panel.bottomColor, Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        c.drawPath(path, carbonPaint(40))
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * gaugePx
            color = sTheme.panel.edgeColor
        }
        c.drawPath(path, stroke)
        stroke.color = Color.argb(alpha255(sTheme.panel.bevelAlpha), 255, 255, 255)
        stroke.strokeWidth = gaugePx
        c.drawLine(-SPEED_TOP_HALF + 3f, SPEED_TOP + gaugePx, SPEED_TOP_HALF - 3f, SPEED_TOP + gaugePx, stroke)

        val unit = textPaint(tfLabel, sTheme.typography.labelColor).apply {
            textAlign = Paint.Align.CENTER
            textSize = 17f
        }
        drawCentered(c, "km/h", 0f, SPEED_BOTTOM - 13f, unit)
    }

    private fun drawCellsStatic(c: Canvas, g: Geometry) {
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 34) }
        for (i in 0 until 6) {
            val r = g.cell(i)
            val right = i >= 3
            drawPanel(
                c, if (right) chamfer(r, 26f, 10f) else chamfer(r, 10f, 26f), r,
                outerLeft = !right, outerCut = 10f,
                leftTopCut = if (right) 26f else 10f, rightTopCut = if (right) 10f else 26f,
            )
            val x = contentLeft(r, right)
            val icon = CELL_ICONS[i]
            if (icon == Icon.TARGET) {
                DashboardIcons.draw(c, icon, x, r.top + 22f, TARGET_SIZE, Color.rgb(140, 140, 146), glow = false, weight = ICON_WEIGHT)
            } else {
                DashboardIcons.draw(c, icon, x, r.top + 24f, ICON_SIZE, CELL_ICON_COLORS[i], glow = false, weight = ICON_WEIGHT)
            }
            if (i != GFORCE_CELL) c.drawRoundRect(g.bars[i], BAR_H / 2f, BAR_H / 2f, track)
        }
    }

    private fun drawBottomStatic(c: Canvas, g: Geometry) {
        val cx = g.cx
        val left = Path().apply {
            moveTo(M, BOTTOM_TOP + 20f)
            lineTo(M + 20f, BOTTOM_TOP)
            lineTo(cx - g.notchTop, BOTTOM_TOP)
            lineTo(cx - NOTCH_BOTTOM, BOTTOM_BOTTOM)
            lineTo(M, BOTTOM_BOTTOM)
            close()
        }
        val right = Path().apply {
            moveTo(g.vw - M, BOTTOM_TOP + 20f)
            lineTo(g.vw - M - 20f, BOTTOM_TOP)
            lineTo(cx + g.notchTop, BOTTOM_TOP)
            lineTo(cx + NOTCH_BOTTOM, BOTTOM_BOTTOM)
            lineTo(g.vw - M, BOTTOM_BOTTOM)
            close()
        }
        drawPanel(c, left, g.bottomLeft, outerLeft = true, outerCut = 20f, leftTopCut = 20f, rightTopCut = 0f)
        drawPanel(c, right, g.bottomRight, outerLeft = false, outerCut = 20f, leftTopCut = 0f, rightTopCut = 20f)

        // Acentos de los marcos: neón que baja hacia el logo.
        neonPath(c, Path().apply {
            moveTo(M + 24f, BOTTOM_TOP - 6f)
            lineTo(cx - g.notchTop - 4f, BOTTOM_TOP - 6f)
            lineTo(cx - NOTCH_BOTTOM - 4f, BOTTOM_BOTTOM + 6f)
        })
        neonPath(c, Path().apply {
            moveTo(g.vw - M - 24f, BOTTOM_TOP - 6f)
            lineTo(cx + g.notchTop + 4f, BOTTOM_TOP - 6f)
            lineTo(cx + NOTCH_BOTTOM + 4f, BOTTOM_BOTTOM + 6f)
        })

        drawLogoBeams(c, g)

        // Etiquetas, separadores e iconos.
        val label = textPaint(tfLabel, sTheme.typography.labelColor).apply {
            textAlign = Paint.Align.CENTER
            textSize = PANEL_H * sTheme.typography.labelRatio
        }
        val lx = g.bottomLeftX
        drawCentered(c, "Ign Timing", lx[0], BOTTOM_LABEL_Y, label)
        drawCentered(c, "Lambda", lx[1], BOTTOM_LABEL_Y, label)
        slantedDivider(c, lx[2])
        val half = BOTTOM_ICON_SIZE / 2f
        DashboardIcons.draw(c, Icon.SPARK_PLUG, lx[3] - half, BOTTOM_ICON_Y - half, BOTTOM_ICON_SIZE, Color.rgb(200, 200, 206), glow = false, weight = ICON_WEIGHT)

        val rx = g.bottomRightX
        drawCentered(c, "Est. Cons.", rx[0], BOTTOM_LABEL_Y, label)
        drawCentered(c, "Range", rx[1], BOTTOM_LABEL_Y, label)
        slantedDivider(c, rx[2])
        DashboardIcons.draw(c, Icon.FUEL_DROP, rx[3] - half, BOTTOM_ICON_Y - half, BOTTOM_ICON_SIZE, Color.rgb(226, 226, 230), glow = false, weight = ICON_WEIGHT)
        DashboardIcons.draw(c, Icon.FUEL_PUMP, rx[4] - half, BOTTOM_ICON_Y - half, BOTTOM_ICON_SIZE, Color.rgb(226, 226, 230), glow = false, weight = ICON_WEIGHT)

        // Logo (solo el emblema, sin el texto de debajo).
        logo?.let { bmp ->
            val src = Rect(
                (bmp.width * 0.2f).toInt(), 0,
                (bmp.width * 0.8f).toInt(), (bmp.height * 0.66f).toInt(),
            )
            val wLogo = g.logoH * src.width() / src.height()
            val dst = RectF(cx - wLogo / 2f, g.logoCy - g.logoH / 2f, cx + wLogo / 2f, g.logoCy + g.logoH / 2f)
            c.drawBitmap(bmp, src, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
    }

    /**
     * Dos haces rojos en V que bajan hacia el logo por los lados de la muesca:
     * trapecios con degradado transparente (arriba) → rojo (junto al logo), y
     * su halo en la capa emisiva.
     */
    private fun drawLogoBeams(c: Canvas, g: Geometry) {
        val cx = g.cx
        val halo = sTheme.neon.haloColor
        for (side in listOf(-1f, 1f)) {
            val beam = Path().apply {
                moveTo(cx + side * (g.notchTop - 4f), BOTTOM_TOP + 4f)
                lineTo(cx + side * (g.notchTop - 22f), BOTTOM_TOP + 4f)
                lineTo(cx + side * (NOTCH_BOTTOM - 14f), BOTTOM_BOTTOM - 4f)
                lineTo(cx + side * (NOTCH_BOTTOM - 4f), BOTTOM_BOTTOM - 4f)
                close()
            }
            val fade = LinearGradient(
                0f, BOTTOM_TOP, 0f, BOTTOM_BOTTOM,
                intArrayOf(withAlpha(halo, 0), withAlpha(halo, 150), halo),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = fade }
            c.drawPath(beam, p)
            staticGlow?.drawPath(beam, p)
        }
    }

    // ------------------------------------------------------------------
    // Capa dinámica (frame a frame, sin reservar memoria)
    // ------------------------------------------------------------------

    private fun drawGaugeDynamic(c: Canvas, glow: Canvas?, g: Geometry, s: TelemetrySnapshot, nowMs: Long) {
        c.save()
        c.translate(g.cx, g.cy)
        c.scale(g.k, g.k)
        glow?.save()
        glow?.translate(g.cx, g.cy)
        glow?.scale(g.k, g.k)

        drawRpm(c, glow, g, nowMs)

        // Marcha: relleno blanco puro; el rojo va solo en el halo (capa
        // emisiva). Sin datos, "--" en gris y sin halo.
        val gear = s.gearDisplay
        val noGear = gear == DashFormat.NO_DATA
        numberPaint.textAlign = Paint.Align.CENTER
        numberPaint.color = if (noGear) NO_DATA_GRAY else Color.WHITE
        fit(numberPaint, gear, dTheme.gear.size, 230f)
        if (!noGear && glow != null) {
            glowText.textSize = numberPaint.textSize
            glowText.strokeWidth = dTheme.gear.haloRadius
            glowText.color = gearHaloColor
            drawCentered(glow, gear, 0f, GEAR_Y, glowText)
        }
        drawCentered(c, gear, 0f, GEAR_Y, numberPaint)

        // Velocidad, en su panel bajo el arco.
        DashFormat.int(s.speedKmh, buf)
        numberPaint.color = if (s.speedKmh.isNaN()) NO_DATA_GRAY else WHITE
        fit(numberPaint, buf, 52f, SPEED_TOP_HALF * 2f + 10f)
        drawCentered(c, buf, 0f, SPEED_TOP + 28f, numberPaint)

        glow?.restore()
        c.restore()
    }

    private fun drawRpm(c: Canvas, glow: Canvas?, g: Geometry, nowMs: Long) {
        val v = shownRpm
        if (v.isNaN() || v <= 0f) return
        val arcTheme = dTheme.arc

        arcRect.set(-G_R, -G_R, G_R, G_R)
        val silverSweep = RpmScale.sweepFor(min(v, RpmScale.RED_RPM))

        // Tramo encendido: degradado plateado.
        arcPaint.color = Color.WHITE // opaco: el alfa del color se aplica también al degradado
        arcPaint.shader = silver
        arcPaint.strokeWidth = g.band
        c.drawArc(arcRect, RpmScale.START_DEG, silverSweep, false, arcPaint)
        arcPaint.shader = null

        // Zona roja encendida: degradado radial y halo.
        if (RpmScale.isRed(v)) {
            val start = RpmScale.angleFor(RpmScale.RED_RPM)
            val sweep = RpmScale.sweepFor(v) - RpmScale.sweepFor(RpmScale.RED_RPM)
            arcPaint.shader = redActive
            c.drawArc(arcRect, start, sweep, false, arcPaint)
            arcPaint.shader = null
            glow?.let {
                glowStroke.color = arcTheme.redOuterColor
                glowStroke.strokeWidth = g.band + 6f
                it.drawArc(arcRect, start, sweep, false, glowStroke)
            }
        }

        // Corte: todo el arco parpadea en rojo.
        if (RpmScale.isAtLimit(v) && (nowMs / BLINK_MS) % 2L == 0L) {
            arcPaint.shader = redActive
            c.drawArc(arcRect, RpmScale.START_DEG, RpmScale.SWEEP_DEG, false, arcPaint)
            arcPaint.shader = null
            glow?.let {
                glowStroke.color = arcTheme.redOuterColor
                glowStroke.strokeWidth = g.band + 8f
                it.drawArc(arcRect, RpmScale.START_DEG, RpmScale.SWEEP_DEG, false, glowStroke)
            }
        }

        // Reflejo especular de 2 px en el borde exterior del tramo encendido.
        val litSweep = RpmScale.sweepFor(v)
        arcPaint.color = Color.argb(alpha255(arcTheme.specularAlpha), 255, 255, 255)
        arcPaint.strokeWidth = 2f * gaugePx
        tmpRect.set(-(g.gOuter - gaugePx), -(g.gOuter - gaugePx), g.gOuter - gaugePx, g.gOuter - gaugePx)
        c.drawArc(tmpRect, RpmScale.START_DEG, litSweep, false, arcPaint)

        // Separadores negros de 3 px por encima del relleno.
        linePaint.strokeCap = Paint.Cap.BUTT
        linePaint.color = Color.BLACK
        linePaint.strokeWidth = 3f * gaugePx
        var k = 1000f
        while (k < v && k < RpmScale.MAX_RPM) {
            radialLine(c, k, g.gInner, g.gOuter, linePaint)
            k += 1000f
        }

        // Posición actual: línea radial blanca, lo más brillante del arco.
        linePaint.strokeCap = Paint.Cap.ROUND
        linePaint.color = Color.WHITE
        linePaint.strokeWidth = arcTheme.needleWidthPx * gaugePx
        radialLine(c, v, g.gInner - 4f, g.gOuter + 4f, linePaint)
        glow?.let {
            glowStroke.color = Color.WHITE
            glowStroke.strokeWidth = arcTheme.needleWidthPx * 2.4f * gaugePx
            radialLine(it, v, g.gInner - 6f, g.gOuter + 6f, glowStroke)
        }
    }

    private fun drawCells(c: Canvas, g: Geometry, s: TelemetrySnapshot) {
        DashFormat.int(s.oilTemp, buf)
        drawCell(
            c, g, 0, if (s.oilTempFromCoolant) "Coolant Temp" else "Oil Temp", buf, "°C",
            when {
                s.oilTemp >= 118f -> ALERT
                s.oilTemp >= 110f -> ORANGE
                else -> WHITE
            },
            DashFormat.fraction(s.oilTemp, 40f, 130f), s.oilTemp.isNaN(),
        )
        DashFormat.int(s.intakeTemp, buf)
        drawCell(
            c, g, 1, "Intake Temp", buf, "°C",
            when {
                // 50-55 °C es normal en un TSI con calor/tráfico (aire tras el intercooler).
                s.intakeTemp >= 70f -> ALERT
                s.intakeTemp >= 60f -> ORANGE
                else -> WHITE
            },
            DashFormat.fraction(s.intakeTemp, -10f, 80f), s.intakeTemp.isNaN(),
        )
        // En un turbo la carga llega al 100 % en cada aceleración a fondo: no es una alarma.
        DashFormat.int(s.engineLoad, buf)
        drawCell(
            c, g, 2, "Engine Load", buf, "%",
            if (s.engineLoad >= 95f) ORANGE else WHITE,
            DashFormat.fraction(s.engineLoad, 0f, 100f), s.engineLoad.isNaN(),
        )
        DashFormat.oneDecimal(s.turboPressure, buf)
        drawCell(
            c, g, 3, "Boost", buf, "bar",
            when {
                s.turboPressure >= 1.4f -> ALERT
                s.turboPressure >= 1.2f -> ORANGE
                else -> WHITE
            },
            DashFormat.fraction(s.turboPressure, -1f, 1.5f), s.turboPressure.isNaN(),
        )
        DashFormat.int(s.throttlePercent, buf)
        drawCell(
            c, g, 4, if (s.throttleIsPedal) "Pedal" else "Throttle", buf, "%",
            WHITE, DashFormat.fraction(s.throttlePercent, 0f, 100f), s.throttlePercent.isNaN(),
        )
        drawGForce(c, g, s)
    }

    /**
     * Celda: icono a la izquierda y, pegado a él, el bloque etiqueta + valor
     * (etiqueta alineada a la derecha sobre el valor). Barra a todo el ancho,
     * con un brillo ceñido (capas redondeadas con alfa decreciente).
     */
    private fun drawCell(
        c: Canvas, g: Geometry, i: Int, label: String, text: TextBuf, unit: String,
        color: Int, fraction: Float, noData: Boolean,
    ) {
        val r = g.cell(i)
        val right = i >= 3
        val x0 = contentLeft(r, right) + ICON_SIZE + ICON_GAP
        val maxRight = r.right - if (right) 16f else 30f
        drawBlock(c, x0, maxRight, r.top, label, text, unit, if (noData) NO_DATA_GRAY else color, noData)

        val bar = g.bars[i]
        if (fraction > 0f) {
            barTmp.set(bar)
            barTmp.right = bar.left + max(bar.height(), bar.width() * fraction)
            val glowColor = BAR_COLORS[i].second
            shapePaint.shader = null
            for (j in BAR_GLOW_GROW.indices) {
                val grow = BAR_GLOW_GROW[j]
                shapePaint.color = withAlpha(glowColor, BAR_GLOW_ALPHA[j])
                tmpRect.set(barTmp.left - grow, barTmp.top - grow, barTmp.right + grow, barTmp.bottom + grow)
                c.drawRoundRect(tmpRect, tmpRect.height() / 2f, tmpRect.height() / 2f, shapePaint)
            }
            shapePaint.color = Color.WHITE // opaco: el alfa del color se aplica también al degradado
            shapePaint.shader = g.barShaders[i]
            c.drawRoundRect(barTmp, BAR_H / 2f, BAR_H / 2f, shapePaint)
            shapePaint.shader = null
        }
    }

    /**
     * Bloque etiqueta + valor + unidad que empieza en [x0]: el valor y su
     * unidad, y encima la etiqueta, alineados a la derecha del bloque. Devuelve
     * el borde derecho del bloque.
     */
    private fun drawBlock(
        c: Canvas, x0: Float, maxRight: Float, top: Float,
        label: String, text: TextBuf, unit: String, color: Int, noData: Boolean,
    ): Float {
        val ty = dTheme.typography
        val avail = maxRight - x0
        unitPaint.textAlign = Paint.Align.LEFT
        unitPaint.textSize = PANEL_H * ty.unitRatio
        val unitW = if (noData) 0f else unitPaint.measureText(unit) + 4f
        numberPaint.textAlign = Paint.Align.RIGHT
        numberPaint.color = color
        fit(numberPaint, text, PANEL_H * ty.valueRatio, avail - unitW)
        labelPaint.textAlign = Paint.Align.RIGHT
        labelPaint.color = ty.labelColor
        fit(labelPaint, label, PANEL_H * ty.labelRatio, avail)

        val blockW = max(labelPaint.measureText(label), numberPaint.measureText(text.chars, 0, text.length) + unitW)
        val blockRight = x0 + blockW
        val valueRight = blockRight - unitW
        c.drawText(label, blockRight, top + LABEL_BASELINE, labelPaint)
        c.drawText(text.chars, 0, text.length, valueRight, top + VALUE_BASELINE, numberPaint)
        if (!noData) c.drawText(unit, valueRight + 4f, top + VALUE_BASELINE, unitPaint)
        return blockRight
    }

    private fun drawGForce(c: Canvas, g: Geometry, s: TelemetrySnapshot) {
        val r = g.cell(GFORCE_CELL)
        val x = contentLeft(r, true)
        val noData = s.gForce.isNaN()

        // Punto del centro de la diana.
        val dotX = x + TARGET_SIZE / 2f
        val dotY = r.top + 22f + TARGET_SIZE / 2f
        shapePaint.shader = null
        shapePaint.color = Color.argb(60, 255, 255, 255)
        c.drawCircle(dotX, dotY, 7f, shapePaint)
        shapePaint.color = if (noData) NO_DATA_GRAY else WHITE
        c.drawCircle(dotX, dotY, 4f, shapePaint)

        val color = when {
            noData -> NO_DATA_GRAY
            s.gForce >= 1.0f -> ORANGE
            else -> WHITE
        }
        DashFormat.oneDecimal(s.gForce, buf)
        val blockRight = drawBlock(c, x + TARGET_SIZE + ICON_GAP, r.right - 16f, r.top, "G-Force", buf, "G", color, noData)
        if (!s.gForcePeak.isNaN()) {
            buf.clear().append("peak ")
            buf2.clear()
            DashFormat.oneDecimal(s.gForcePeak, buf2)
            buf.append(buf2)
            unitPaint.textAlign = Paint.Align.RIGHT
            unitPaint.textSize = PANEL_H * dTheme.typography.unitRatio
            c.drawText(buf.chars, 0, buf.length, blockRight, r.top + 82f, unitPaint)
        }
    }

    private fun drawBottom(c: Canvas, g: Geometry, s: TelemetrySnapshot) {
        // Cada valor se centra en su columna y no puede pasar del separador ni del icono vecino.
        val half = BOTTOM_ICON_SIZE / 2f
        val lx = g.bottomLeftX
        val ignW = 2f * min(lx[0] - M - 8f, lx[2] - lx[0] - 12f)
        val lambdaW = 2f * min(lx[1] - lx[2] - 12f, lx[3] - half - lx[1] - 6f)
        DashFormat.int(s.ignitionTiming, buf)
        valueWithUnit(c, buf, "°", lx[0], ignW, s.ignitionTiming.isNaN(), unitInline = true)
        // Lambda ≥ 1.5 no se da en combustión de gasolina: es corte de inyección.
        val cut = !s.lambda.isNaN() && s.lambda >= LAMBDA_FUEL_CUT
        if (cut) buf.clear().append("CUT") else DashFormat.twoDecimals(s.lambda, buf)
        valueWithUnit(c, buf, "", lx[1], lambdaW, s.lambda.isNaN() || cut)

        val rx = g.bottomRightX
        val consW = 2f * min(rx[2] - rx[0] - 12f, rx[0] - (rx[3] + half) - 6f)
        val rangeW = 2f * min(rx[1] - (rx[4] + half) - 6f, g.vw - M - rx[1] - 8f)
        DashFormat.oneDecimal(s.fuelConsumption, buf)
        valueWithUnit(c, buf, "L/100km", rx[0], consW, s.fuelConsumption.isNaN())
        DashFormat.int(s.rangeKm, buf)
        valueWithUnit(c, buf, "km", rx[1], rangeW, s.rangeKm.isNaN())
    }

    private fun valueWithUnit(
        c: Canvas, text: TextBuf, unit: String, cx: Float, maxW: Float, grey: Boolean, unitInline: Boolean = false,
    ) {
        val ty = dTheme.typography
        numberPaint.textAlign = Paint.Align.LEFT
        numberPaint.color = if (grey) NO_DATA_GRAY else WHITE
        unitPaint.textAlign = Paint.Align.LEFT
        val showUnit = !text.contentEquals(DashFormat.NO_DATA) && unit.isNotEmpty()
        // El símbolo de grados va pegado y al tamaño del número.
        val unitP = if (unitInline) numberPaint else unitPaint
        numberPaint.textSize = PANEL_H * ty.valueRatio
        unitPaint.textSize = PANEL_H * ty.unitRatio
        val gap = if (unitInline) 0f else 4f
        val textW = numberPaint.measureText(text.chars, 0, text.length)
        var total = textW + if (showUnit) gap + unitP.measureText(unit) else 0f
        if (total > maxW) {
            val k = maxW / total
            numberPaint.textSize *= k
            unitPaint.textSize *= k
            total = maxW
        }
        val x0 = cx - total / 2f
        c.drawText(text.chars, 0, text.length, x0, BOTTOM_VALUE_Y, numberPaint)
        if (showUnit) c.drawText(unit, x0 + numberPaint.measureText(text.chars, 0, text.length) + gap, BOTTOM_VALUE_Y, unitP)
    }

    private fun drawTop(c: Canvas, g: Geometry, s: TelemetrySnapshot) {
        val minute = System.currentTimeMillis() / 60_000L
        if (minute != clockMinute) {
            // Una vez por minuto, no por frame.
            clockMinute = minute
            clockText = LocalTime.now().format(CLOCK_FORMAT)
        }
        numberPaint.textAlign = Paint.Align.CENTER
        numberPaint.color = WHITE
        // Brillo blanco muy suave, centrado (sin desplazamiento).
        numberPaint.setShadowLayer(6f, 0f, 0f, Color.argb(60, 255, 255, 255))
        fit(numberPaint, clockText, TOP_TEXT_SIZE, TOP_W - 50f)
        drawCentered(c, clockText, g.clock.centerX() - 6f, g.clock.centerY(), numberPaint)

        if (s.ambientTemp.isNaN()) {
            buf.clear().append(DashFormat.NO_DATA)
        } else {
            DashFormat.oneDecimal(s.ambientTemp, buf).append(" °C")
        }
        numberPaint.color = if (s.ambientTemp.isNaN()) NO_DATA_GRAY else WHITE
        fit(numberPaint, buf, TOP_TEXT_SIZE, TOP_W - 50f)
        drawCentered(c, buf, g.temp.centerX() + 6f, g.temp.centerY(), numberPaint)
        numberPaint.clearShadowLayer()
    }

    /** Solo cuando NO hay datos en vivo: dice por qué (buscando, sin conexión, simulación...). */
    private fun drawStatusBanner(c: Canvas, g: Geometry, link: LinkState) {
        val bg = when (link) {
            LinkState.LIVE -> return
            LinkState.SIMULATION -> ORANGE
            LinkState.SEARCHING, LinkState.CONNECTING, LinkState.RECONNECTING, LinkState.NO_ECU_DATA -> Color.rgb(34, 34, 38)
            LinkState.NO_CONNECTION, LinkState.BLUETOOTH_OFF, LinkState.NO_PERMISSION -> ALERT
        }
        val fg = when (link) {
            LinkState.SIMULATION -> Color.BLACK
            LinkState.RECONNECTING, LinkState.NO_ECU_DATA -> ORANGE
            LinkState.NO_CONNECTION, LinkState.BLUETOOTH_OFF, LinkState.NO_PERMISSION -> Color.WHITE
            else -> WHITE
        }
        numberPaint.textAlign = Paint.Align.CENTER
        numberPaint.color = fg
        fit(numberPaint, link.label, 18f, g.cx - g.clock.right - 40f)
        val half = numberPaint.measureText(link.label) / 2f + 18f
        tmpRect.set(g.cx - half, BANNER_CY - 15f, g.cx + half, BANNER_CY + 15f)
        shapePaint.shader = null
        shapePaint.color = bg
        c.drawRoundRect(tmpRect, 15f, 15f, shapePaint)
        drawCentered(c, link.label, g.cx, BANNER_CY, numberPaint)
    }

    /** Solo en builds de debug: relación rpm/km/h para calibrar GearEstimator. */
    private fun drawDebug(c: Canvas, s: TelemetrySnapshot) {
        unitPaint.textAlign = Paint.Align.LEFT
        unitPaint.textSize = 12f
        unitPaint.color = Color.YELLOW
        if (s.link == LinkState.SIMULATION) {
            buf.clear().append("SIM_OK")
        } else {
            buf.clear().append("R:")
            DashFormat.oneDecimal(s.debugGearRatio, buf2)
            buf.append(buf2).append(" RAW:").append(s.debugRawResponse)
        }
        c.drawText(buf.chars, 0, buf.length, M, DESIGN_H - 2f, unitPaint)
        unitPaint.color = UNIT
    }

    /** Overlay de métricas (activable desde la pantalla de ajuste): FPS, medias y p95. */
    private fun drawMetricsOverlay(canvas: Canvas, a: Rect, g: Geometry) {
        canvas.save()
        canvas.translate(a.left.toFloat(), a.top.toFloat())
        designSpace(canvas, g)
        tmpRect.set(g.cx - 230f, 40f, g.cx + 230f, 98f)
        shapePaint.shader = null
        shapePaint.color = Color.argb(200, 0, 0, 0)
        canvas.drawRoundRect(tmpRect, 8f, 8f, shapePaint)
        overlayPaint.textSize = 16f
        overlayPaint.textAlign = Paint.Align.LEFT
        overlayPaint.color = Color.rgb(120, 255, 140)
        val m = metrics
        buf.clear().append("FPS ").appendFixed(m.fps, 1)
            .append("   total ").appendFixed(m.totalAvg, 1).append(" ms  p95 ").appendFixed(m.totalP95, 1)
        canvas.drawText(buf.chars, 0, buf.length, tmpRect.left + 12f, tmpRect.top + 23f, overlayPaint)
        buf.clear().append("est ").appendFixed(m.staticAvg, 1).append('/').appendFixed(m.staticP95, 1)
            .append("  din ").appendFixed(m.dynamicAvg, 1).append('/').appendFixed(m.dynamicP95, 1)
            .append("  bloom ").appendFixed(m.bloomAvg, 1).append('/').appendFixed(m.bloomP95, 1)
        canvas.drawText(buf.chars, 0, buf.length, tmpRect.left + 12f, tmpRect.top + 47f, overlayPaint)
        canvas.restore()
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private fun slantedDivider(c: Canvas, x: Float) {
        neonPath(c, Path().apply {
            moveTo(x + 9f, BOTTOM_TOP + 18f)
            lineTo(x - 9f, BOTTOM_BOTTOM - 18f)
        })
    }

    private fun arc(c: Canvas, radius: Float, start: Float, sweep: Float, p: Paint) {
        tmpRect.set(-radius, -radius, radius, radius)
        c.drawArc(tmpRect, start, sweep, false, p)
    }

    /** Línea radial del cuentavueltas (coordenadas locales, centro en 0,0). */
    private fun radialLine(c: Canvas, rpm: Float, r1: Float, r2: Float, p: Paint) {
        val a = Math.toRadians(RpmScale.angleFor(rpm).toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        c.drawLine(r1 * ca, r1 * sa, r2 * ca, r2 * sa, p)
    }

    /**
     * Panel: sombra inferior, relleno con degradado vertical, borde fino y
     * bisel de 1 px arriba. El neón rojo va SOLO en el chaflán exterior
     * superior y se desvanece a lo largo del borde superior.
     */
    private fun drawPanel(
        c: Canvas, path: Path, bounds: RectF, outerLeft: Boolean,
        outerCut: Float, leftTopCut: Float, rightTopCut: Float,
    ) {
        val panel = sTheme.panel
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 0, 0, 0)
            maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
        }
        c.save()
        c.translate(0f, 5f)
        c.drawPath(path, shadow)
        c.restore()

        val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        fill.shader = LinearGradient(0f, bounds.top, 0f, bounds.bottom, panel.topColor, panel.bottomColor, Shader.TileMode.CLAMP)
        c.drawPath(path, fill)

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.MITER
            strokeWidth = 1.5f * onePx
            color = panel.edgeColor
        }
        c.drawPath(path, stroke)
        stroke.strokeWidth = onePx
        stroke.color = Color.argb(alpha255(panel.bevelAlpha), 255, 255, 255)
        c.drawLine(bounds.left + leftTopCut, bounds.top + onePx, bounds.right - rightTopCut, bounds.top + onePx, stroke)

        val x = if (outerLeft) bounds.left else bounds.right
        val dir = if (outerLeft) 1f else -1f
        neonLine(c, x, bounds.top + outerCut, x + dir * outerCut, bounds.top, fade = false)
        neonLine(c, x + dir * outerCut, bounds.top, x + dir * (outerCut + bounds.width() * sTheme.neon.fadeShare), bounds.top, fade = true)
    }

    /**
     * Neón rojo en 3 pasadas: halo a la capa emisiva (se desenfoca con el
     * bloom), cuerpo y núcleo. [px] es el tamaño de un píxel real en las
     * coordenadas actuales.
     */
    private fun neonPath(c: Canvas, path: Path, px: Float = onePx, glow: Canvas? = staticGlow) {
        val neon = sTheme.neon
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        glow?.let {
            p.color = neon.haloColor
            p.strokeWidth = neon.haloWidthPx * px
            it.drawPath(path, p)
        }
        p.color = neon.bodyColor
        p.strokeWidth = neon.bodyWidthPx * px
        c.drawPath(path, p)
        p.color = neon.coreColor
        p.strokeWidth = neon.coreWidthPx * px
        c.drawPath(path, p)
    }

    /** Tramo de neón; si [fade], cada pasada se desvanece de 100 % a 0 % a lo largo del trazo. */
    private fun neonLine(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, fade: Boolean) {
        val neon = sTheme.neon
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        fun pass(canvas: Canvas, color: Int, width: Float) {
            p.strokeWidth = width
            if (fade) {
                p.shader = LinearGradient(x0, y0, x1, y1, color, withAlpha(color, 0), Shader.TileMode.CLAMP)
            } else {
                p.shader = null
                p.color = color
            }
            canvas.drawLine(x0, y0, x1, y1, p)
        }
        staticGlow?.let { pass(it, neon.haloColor, neon.haloWidthPx * onePx) }
        pass(c, neon.bodyColor, neon.bodyWidthPx * onePx)
        pass(c, neon.coreColor, neon.coreWidthPx * onePx)
    }

    private val carbonTile: Bitmap by lazy { makeCarbonTile() }

    private fun carbonPaint(alpha: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(carbonTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        this.alpha = alpha
    }

    /** Tejido de fibra de carbono (sarga 2×2) en una baldosa de 16×16 que se repite. */
    private fun makeCarbonTile(): Bitmap {
        val size = 16
        val half = size / 2f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        for (i in 0..1) for (j in 0..1) {
            val l = i * half
            val t = j * half
            val horizontal = (i + j) % 2 == 0
            p.shader = if (horizontal) {
                LinearGradient(l, t, l, t + half, Color.rgb(60, 60, 64), Color.rgb(8, 8, 8), Shader.TileMode.CLAMP)
            } else {
                LinearGradient(l, t, l + half, t, Color.rgb(50, 50, 54), Color.rgb(6, 6, 6), Shader.TileMode.CLAMP)
            }
            c.drawRect(l, t, l + half, t + half, p)
        }
        return bmp
    }

    private fun fit(p: Paint, text: String, size: Float, maxW: Float) {
        p.textSize = size
        val w = p.measureText(text)
        if (w > maxW && w > 0f) p.textSize = size * maxW / w
    }

    private fun fit(p: Paint, text: TextBuf, size: Float, maxW: Float) {
        p.textSize = size
        val w = p.measureText(text.chars, 0, text.length)
        if (w > maxW && w > 0f) p.textSize = size * maxW / w
    }

    private fun drawCentered(c: Canvas, text: String, x: Float, cy: Float, p: Paint) {
        p.getFontMetrics(fm)
        c.drawText(text, x, cy - (fm.ascent + fm.descent) / 2f, p)
    }

    private fun drawCentered(c: Canvas, text: TextBuf, x: Float, cy: Float, p: Paint) {
        p.getFontMetrics(fm)
        c.drawText(text.chars, 0, text.length, x, cy - (fm.ascent + fm.descent) / 2f, p)
    }

    private fun textPaint(tf: Typeface, color: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = tf
            this.color = color
            fontFeatureSettings = "tnum" // cifras tabulares: los números no bailan al cambiar
        }

    companion object {
        const val DESIGN_W = 1000f
        const val DESIGN_H = 560f

        /** Debounce de la regeneración de la capa estática al cambiar el tema. */
        const val STATIC_DEBOUNCE_MS = 150L

        /** Logo de la marca para el panel inferior, sin escalar por densidad (es grande). */
        fun loadLogo(res: Resources): Bitmap? = try {
            BitmapFactory.decodeResource(res, R.drawable.seat_logo, BitmapFactory.Options().apply { inScaled = false })
        } catch (_: Exception) {
            null
        }

        private const val BLOOM_DOWNSCALE = 4

        // Degradados del cuentavueltas: posición 0 = el "0" de la escala; 0.75 = el "7".
        private val fromZero = Matrix().apply { setRotate(RpmScale.START_DEG) }
        private const val SCALE_END = RpmScale.SWEEP_DEG / 360f

        // Disposición general (unidades de diseño).
        private const val M = 18f
        private const val PANEL_TOP = 88f
        private const val PANEL_H = 92f
        private const val PANEL_GAP = 14f
        private const val TOP_Y = 10f
        private const val TOP_H = 56f
        private const val TOP_W = 232f
        private const val BANNER_CY = 24f

        private const val GAUGE_TOP = 12f
        private const val GAUGE_SIDE_GAP = 12f
        private const val COS_30 = 0.8660254f

        // Cuentavueltas en coordenadas locales (centro en 0,0, tamaño base).
        private const val G_R = 176f
        private const val BEZEL_START = 120f
        private const val BEZEL_SWEEP = 300f
        private const val GEAR_Y = -4f
        private const val SPEED_TOP = 92f
        private const val SPEED_BOTTOM = 170f
        private const val SPEED_TOP_HALF = 62f
        private const val SPEED_BOTTOM_HALF = 100f

        // Franja inferior.
        private const val BOTTOM_TOP = 432f
        private const val BOTTOM_BOTTOM = 548f
        private const val NOTCH_BOTTOM = 100f
        private const val BOTTOM_LABEL_Y = 468f
        private const val BOTTOM_VALUE_Y = 516f
        private const val BOTTOM_ICON_Y = 494f
        private const val LOGO_H = 48f

        private const val LABEL_BASELINE = 26f
        private const val VALUE_BASELINE = 62f
        /** Hora y temperatura exterior. */
        private const val TOP_TEXT_SIZE = 25f

        // Iconos con trazo fino, pegados al bloque de texto.
        private const val ICON_SIZE = 34f
        private const val TARGET_SIZE = 42f
        private const val ICON_GAP = 12f
        private const val BOTTOM_ICON_SIZE = 33f
        private const val ICON_WEIGHT = 0.055f

        private const val BAR_H = 7f
        private const val GFORCE_CELL = 5

        /** Brillo de las barras: crecimiento en unidades y alfa, de fuera adentro; ~2 veces el grosor. */
        private val BAR_GLOW_GROW = floatArrayOf(7f, 4.5f, 2f)
        private val BAR_GLOW_ALPHA = intArrayOf(14, 30, 60)

        private const val RPM_SMOOTHING = 0.35f
        private const val BLINK_MS = 90L
        private const val LAMBDA_FUEL_CUT = 1.5f

        private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

        private val WHITE = Color.rgb(244, 244, 246)
        private val UNIT = Color.rgb(0x8A, 0x8A, 0x90)
        private val NO_DATA_GRAY = Color.rgb(104, 104, 110)
        private val ORANGE = Color.rgb(255, 138, 26)
        private val ALERT = Color.rgb(255, 59, 59)

        // Refrigerante, admisión, carga | boost, pedal, G-force.
        private val CELL_ICONS = listOf(Icon.COOLANT, Icon.INTAKE, Icon.LOAD, Icon.BOOST, Icon.PEDAL, Icon.TARGET)
        private val CELL_ICON_COLORS = listOf(
            Color.rgb(57, 166, 255), Color.rgb(226, 226, 230), Color.rgb(255, 154, 46),
            Color.rgb(220, 220, 224), Color.rgb(226, 226, 230), Color.rgb(140, 140, 146),
        )
        private val BAR_COLORS = listOf(
            Color.rgb(21, 101, 255) to Color.rgb(42, 212, 255),
            Color.rgb(15, 179, 155) to Color.rgb(59, 242, 214),
            Color.rgb(255, 106, 0) to Color.rgb(255, 179, 33),
            Color.rgb(123, 31, 224) to Color.rgb(197, 75, 255),
            Color.rgb(196, 16, 28) to Color.rgb(255, 58, 58),
        )

        private fun contentLeft(r: RectF, right: Boolean) = r.left + if (right) 22f else 14f

        private fun alpha255(fraction: Float) = (fraction * 255f).roundToInt().coerceIn(0, 255)

        private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

        private fun lerpColor(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            return Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * k).roundToInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * k).roundToInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).roundToInt(),
            )
        }

        /** Rectángulo con esquinas cortadas: [cutLeft] en el lado izquierdo y [cutRight] en el derecho. */
        private fun chamfer(r: RectF, cutLeft: Float, cutRight: Float) = Path().apply {
            moveTo(r.left + cutLeft, r.top)
            lineTo(r.right - cutRight, r.top)
            lineTo(r.right, r.top + cutRight)
            lineTo(r.right, r.bottom - cutRight)
            lineTo(r.right - cutRight, r.bottom)
            lineTo(r.left + cutLeft, r.bottom)
            lineTo(r.left, r.bottom - cutLeft)
            lineTo(r.left, r.top + cutLeft)
            close()
        }

        /** Barra hexagonal horizontal (puntas a izquierda y derecha) de semialto [half]. */
        private fun hexBar(x0: Float, x1: Float, cy: Float, half: Float) = Path().apply {
            moveTo(x0, cy)
            lineTo(x0 + half, cy - half)
            lineTo(x1 - half, cy - half)
            lineTo(x1, cy)
            lineTo(x1 - half, cy + half)
            lineTo(x0 + half, cy + half)
            close()
        }
    }
}
