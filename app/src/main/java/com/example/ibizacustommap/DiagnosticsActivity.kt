package com.example.ibizacustommap

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla "Diagnóstico OBD" de la app del móvil (también en release). Lanza
 * [ObdDiagnosticsRunner] con su propia conexión al adaptador y permite
 * compartir o copiar el informe, porque en el coche no hay adb.
 */
class DiagnosticsActivity : ComponentActivity() {

    private val viewModel: DiagnosticsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            DiagnosticsScreen(
                state = state,
                onRun = viewModel::start,
                onCancel = viewModel::cancel,
                onShare = { share(state.report) },
                onCopy = { copy(state.report) },
            )
        }
    }

    private fun share(report: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Diagnóstico OBD")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(send, "Compartir informe"))
    }

    private fun copy(report: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico OBD", report))
        // Desde Android 13 el sistema ya muestra su propio aviso al copiar.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Informe copiado", Toast.LENGTH_SHORT).show()
        }
    }
}

data class DiagnosticsUiState(
    val running: Boolean = false,
    val status: String = "",
    val report: String = "",
)

class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {

    private val runner = ObdDiagnosticsRunner(app)
    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        // Muestra el último informe guardado, para poder compartirlo más tarde
        // (por ejemplo, ya en casa) aunque se haya cerrado la app.
        viewModelScope.launch {
            val last = withContext(Dispatchers.IO) {
                runCatching {
                    ObdDiagnostics.latestReport(ObdDiagnosticsRunner.reportsDir(getApplication()))
                        ?.let { it.lastModified() to it.readText() }
                }.getOrNull()
            }
            if (last != null && !_state.value.running && _state.value.report.isEmpty()) {
                val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(last.first))
                _state.value = DiagnosticsUiState(status = "Último informe guardado: $date", report = last.second)
            }
        }
    }

    fun start() {
        if (job?.isActive == true) return
        _state.value = DiagnosticsUiState(running = true, status = "Conectando con el adaptador…")
        job = viewModelScope.launch {
            val outcome = try {
                runInterruptible(Dispatchers.IO) {
                    runner.run(
                        onStatus = { s -> _state.update { it.copy(status = s) } },
                        onLine = { l -> _state.update { it.copy(report = it.report + l + "\n") } },
                    )
                }
            } catch (e: CancellationException) {
                runner.cancel()
                throw e
            }
            _state.update {
                when (outcome) {
                    is ObdDiagnosticsRunner.Outcome.Done -> it.copy(
                        running = false,
                        report = outcome.report,
                        status = if (outcome.report.contains("ABORTADO")) {
                            "Diagnóstico incompleto (motivo al final del informe). Puedes compartirlo igualmente."
                        } else {
                            "Informe listo. Compártelo o cópialo."
                        },
                    )
                    is ObdDiagnosticsRunner.Outcome.Failed -> it.copy(running = false, status = outcome.message)
                }
            }
        }
    }

    fun cancel() {
        runner.cancel()
        job?.cancel()
        _state.update { it.copy(running = false, status = "Diagnóstico cancelado.") }
    }

    override fun onCleared() {
        // viewModelScope ya cancela el job; cerrar el socket desbloquea un connect() en curso.
        runner.cancel()
    }
}

@Composable
private fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    val backgroundColor = Color(0xFF040404)
    val textColor = Color(0xFFF0F0F0)
    val mutedText = Color(0xFF7A7A7A)
    val seatRed = Color(0xFFB40000)

    // Mantiene la pantalla encendida mientras mide (el diagnóstico tarda 1–2 min).
    val view = LocalView.current
    SideEffect { view.keepScreenOn = state.running }

    val scroll = rememberScrollState()
    LaunchedEffect(state.report.length, state.running) {
        if (state.running) scroll.scrollTo(scroll.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text("DIAGNÓSTICO OBD", color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Contacto puesto y adaptador enchufado. El adaptador solo admite una conexión: " +
                "si el salpicadero está abierto en Android Auto, desconecta Android Auto antes " +
                "y no lo abras mientras dura. Tarda 1–2 minutos.\n" +
                "Solo lectura: no borra ni modifica nada del coche.",
            color = mutedText,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.running) {
                OutlinedButton(onClick = onCancel) { Text("CANCELAR", color = textColor, letterSpacing = 1.sp) }
            } else {
                Button(onClick = onRun, colors = ButtonDefaults.buttonColors(containerColor = seatRed)) {
                    Text("EJECUTAR", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
            val canExport = !state.running && state.report.isNotEmpty()
            OutlinedButton(onClick = onShare, enabled = canExport) {
                Text("COMPARTIR", color = if (canExport) textColor else mutedText, letterSpacing = 1.sp)
            }
            OutlinedButton(onClick = onCopy, enabled = canExport) {
                Text("COPIAR", color = if (canExport) textColor else mutedText, letterSpacing = 1.sp)
            }
        }

        if (state.status.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(state.status, color = textColor, fontSize = 13.sp)
        }

        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, Color(0xFF2D2D2D))
                .padding(8.dp)
                .verticalScroll(scroll),
        ) {
            SelectionContainer {
                Text(
                    text = state.report.ifEmpty { "Todavía no hay ningún informe." },
                    color = if (state.report.isEmpty()) mutedText else textColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}
