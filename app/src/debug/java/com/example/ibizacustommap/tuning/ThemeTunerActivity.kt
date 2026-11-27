package com.example.ibizacustommap.tuning

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ibizacustommap.DashDebug
import com.example.ibizacustommap.DashTheme
import com.example.ibizacustommap.DashboardRenderer
import com.example.ibizacustommap.DashboardSimulator
import com.example.ibizacustommap.DebugFrameExporter
import com.example.ibizacustommap.FieldKind
import com.example.ibizacustommap.MetricsSummary
import com.example.ibizacustommap.ThemeField
import com.example.ibizacustommap.ThemeFields
import com.example.ibizacustommap.ThemeGroup
import com.example.ibizacustommap.ThemeStore
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Presets del tema, guardados como JSON (uno por clave). */
private val Context.presetStore by preferencesDataStore(name = "theme_presets")
private const val PRESET_PREFIX = "preset:"

private val TEXT = Color(0xFFE6E6EA)
private const val PREVIEW_W = 2400
private const val PREVIEW_H = 1080

/**
 * Solo en debug: ajuste en vivo del diseño del salpicadero. Cambia
 * [ThemeStore.theme], que lee el renderer en cada frame, así que los cambios
 * se ven a la vez en la vista previa de arriba y en Android Auto (mismo proceso).
 */
class ThemeTunerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B0B0D), contentColor = TEXT) {
                    TunerScreen()
                }
            }
        }
    }
}

private class PreviewState {
    var frame by mutableStateOf<ImageBitmap?>(null)
    var metrics by mutableStateOf<MetricsSummary?>(null)
}

/**
 * Vista previa: el mismo renderer dibujando en un Bitmap de 2400×1080 en un
 * hilo de fondo (~12 fps), con dos bitmaps alternos para no pintar encima del
 * que se está mostrando.
 */
@Composable
private fun rememberPreview(animate: Boolean): PreviewState {
    val context = LocalContext.current
    val state = remember { PreviewState() }
    val animateNow by rememberUpdatedState(animate)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            val renderer = DashboardRenderer(DashboardRenderer.loadLogo(context.resources), debugOverlay = false)
            val bitmaps = Array(2) { Bitmap.createBitmap(PREVIEW_W, PREVIEW_H, Bitmap.Config.ARGB_8888) }
            val canvases = Array(2) { Canvas(bitmaps[it]) }
            val reference = DebugFrameExporter.referenceSnapshot()
            var index = 0
            var tick = 0f
            while (isActive) {
                val snapshot = if (animateNow) DashboardSimulator.at(tick).also { tick += 0.08f } else reference
                renderer.draw(canvases[index], PREVIEW_W, PREVIEW_H, snapshot)
                val image = bitmaps[index].asImageBitmap()
                val summary = renderer.metrics.summary()
                withContext(Dispatchers.Main) {
                    state.frame = image
                    state.metrics = summary
                }
                index = 1 - index
                delay(80)
            }
        }
    }
    return state
}

@Composable
private fun TunerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by ThemeStore.theme.collectAsState()
    val carMetrics by DashDebug.carMetrics.collectAsState()
    val overlay by DashDebug.showMetricsOverlay.collectAsState()

    var animate by remember { mutableStateOf(false) }
    var onion by remember { mutableStateOf<ImageBitmap?>(null) }
    var onionAlpha by remember { mutableFloatStateOf(0.5f) }
    val preview = rememberPreview(animate)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { loadScaled(context, uri, PREVIEW_W) }
                onion = bmp?.asImageBitmap()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        // Vista previa con la referencia superpuesta (onion skin).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PREVIEW_W.toFloat() / PREVIEW_H)
                .background(Color.Black),
        ) {
            preview.frame?.let {
                Image(it, contentDescription = "Vista previa", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
            onion?.let {
                Image(
                    it, contentDescription = "Referencia", alpha = onionAlpha,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Section("Vista previa y referencia", initiallyOpen = true) {
                ToggleRow("Animar (datos simulados)", animate) { animate = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("Cargar referencia") }
                    if (onion != null) TextButton(onClick = { onion = null }) { Text("Quitar") }
                }
                if (onion != null) {
                    ValueLabel("Opacidad de la referencia", String.format(Locale.ROOT, "%.2f", onionAlpha))
                    Slider(value = onionAlpha, onValueChange = { onionAlpha = it })
                }
            }

            Section("Rendimiento", initiallyOpen = true) {
                ToggleRow("Overlay de métricas en Android Auto", overlay) { DashDebug.showMetricsOverlay.value = it }
                MetricsText("Android Auto", carMetrics)
                MetricsText("Vista previa (Canvas de software)", preview.metrics)
            }

            PresetsSection(theme)

            for (group in ThemeFields.groups) {
                Section(group.title) {
                    for (field in ThemeFields.fieldsOf(group)) FieldControl(field, theme)
                    TextButton(onClick = { resetGroup(group) }) { Text("Restablecer ${group.title.lowercase()}") }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportCode(context, theme) }) { Text("Exportar código") }
                OutlinedButton(onClick = { ThemeStore.reset() }) { Text("Reset") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PresetsSection(theme: DashTheme) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val names by remember {
        context.presetStore.data.map { prefs ->
            prefs.asMap().keys.map { it.name }.filter { it.startsWith(PRESET_PREFIX) }.map { it.removePrefix(PRESET_PREFIX) }.sorted()
        }
    }.collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }

    Section("Presets") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Nombre") }, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(enabled = name.isNotBlank(), onClick = {
                val key = stringPreferencesKey(PRESET_PREFIX + name.trim())
                val json = ThemeFields.toJson(theme)
                scope.launch { context.presetStore.edit { it[key] = json } }
                Toast.makeText(context, "Preset guardado", Toast.LENGTH_SHORT).show()
            }) { Text("Guardar") }
        }
        for (preset in names) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(preset, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch {
                        val json = context.presetStore.data.first()[stringPreferencesKey(PRESET_PREFIX + preset)]
                        if (json != null) ThemeStore.theme.value = ThemeFields.fromJson(json)
                    }
                }) { Text("Cargar") }
                TextButton(onClick = {
                    scope.launch { context.presetStore.edit { it.remove(stringPreferencesKey(PRESET_PREFIX + preset)) } }
                }) { Text("Borrar") }
            }
        }
    }
}

@Composable
private fun FieldControl(field: ThemeField, theme: DashTheme) {
    when (field.kind) {
        FieldKind.FLOAT, FieldKind.INT -> {
            val value = field.get(theme).toFloat()
            val shown = if (field.kind == FieldKind.INT) value.toInt().toString() else String.format(Locale.ROOT, "%.3f", value)
            ValueLabel(field.name, shown)
            Slider(
                value = value.coerceIn(field.min, field.max),
                onValueChange = { v -> ThemeStore.theme.update { field.set(it, v.toDouble()) } },
                valueRange = field.min..field.max,
                steps = if (field.kind == FieldKind.INT) (field.max - field.min).toInt() - 1 else 0,
            )
        }
        FieldKind.COLOR -> {
            val argb = field.get(theme).toLong().toInt()
            var text by remember(field.key, argb) { mutableStateOf(toHex(argb)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(argb))
                        .border(1.dp, Color(0xFF555560)),
                )
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input
                        parseHex(input)?.let { c ->
                            ThemeStore.theme.update { field.set(it, (c.toLong() and 0xFFFFFFFFL).toDouble()) }
                        }
                    },
                    label = { Text(field.name) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, initiallyOpen: Boolean = false, content: @Composable () -> Unit) {
    var isOpen by remember { mutableStateOf(initiallyOpen) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16161A), contentColor = TEXT),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isOpen = !isOpen }
                .padding(14.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (isOpen) "▾" else "▸", fontSize = 18.sp)
        }
        if (isOpen) {
            Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) { content() }
        }
    }
}

@Composable
private fun ValueLabel(name: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun MetricsText(title: String, m: MetricsSummary?) {
    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    val text = if (m == null) {
        "Sin datos (¿está abierto?)"
    } else {
        String.format(
            Locale.ROOT,
            "FPS %.1f · total %.1f ms (p95 %.1f)\nestático %.2f / %.2f · dinámico %.2f / %.2f · bloom %.2f / %.2f ms",
            m.fps, m.totalAvgMs, m.totalP95Ms, m.staticAvgMs, m.staticP95Ms,
            m.dynamicAvgMs, m.dynamicP95Ms, m.bloomAvgMs, m.bloomP95Ms,
        )
    }
    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
}

private fun resetGroup(group: ThemeGroup) {
    val defaults = DashTheme()
    ThemeStore.theme.update { t ->
        ThemeFields.fieldsOf(group).fold(t) { acc, f -> f.set(acc, f.get(defaults)) }
    }
}

private fun exportCode(context: Context, theme: DashTheme) {
    val code = ThemeFields.kotlinSource(theme)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("DashTheme.kt", code))
    Toast.makeText(context, "Código copiado al portapapeles", Toast.LENGTH_SHORT).show()
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "DashTheme.kt")
        .putExtra(Intent.EXTRA_TEXT, code)
    context.startActivity(Intent.createChooser(send, "Compartir DashTheme.kt"))
}

private fun toHex(argb: Int) = String.format(Locale.ROOT, "#%08X", argb.toLong() and 0xFFFFFFFFL)

/** "#RRGGBB" o "#AARRGGBB" (con o sin #). */
private fun parseHex(text: String): Int? {
    val hex = text.trim().removePrefix("#")
    if (hex.length != 6 && hex.length != 8) return null
    val v = hex.toLongOrNull(16) ?: return null
    return if (hex.length == 6) (0xFF000000L or v).toInt() else v.toInt()
}

/** Carga la imagen de referencia reducida a como mucho [maxWidth] de ancho. */
private fun loadScaled(context: Context, uri: Uri, maxWidth: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
} catch (_: Exception) {
    null
}
