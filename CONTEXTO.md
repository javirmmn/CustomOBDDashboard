# Contexto del trabajo — CustomOBDDashboard (Ibiza Custom Map)

Resumen de la conversación con Claude para retomar el trabajo en otra sesión.
Última actualización: 2026-11-27.

---

## 1. Proyecto

- App Android Auto (Car App Library) que pinta un dashboard estilo GT3/MoTeC en la pantalla del coche.
- `applicationId` `com.javirmmn.customobddashboard` (el `namespace` sigue siendo `com.example.ibizacustommap`).
- Coche: SEAT Ibiza KJ 1.0 Eco TSI 95 CV, manual 5 vel. (relaciones 3.769 / 1.955 / 1.281 / 0.927 / 0.740, grupo 3.625).
- Adaptador: Vgate iCar Pro (ELM327 v2.3, Bluetooth clásico SPP, solo admite UNA conexión a la vez).
- Plan maestro: `PLAN.md` (sin versionar). Orden: Paso previo → Fase 0 → 1 → 2 → 3 → 4 → 5.

## 2. Reglas de trabajo acordadas

- **Solo release.** El usuario solo instala builds release desde Google Play (prueba interna, él como tester); Android Auto no permite sideload en su coche. Nada puede depender de `BuildConfig.DEBUG` (el overlay de debug con `R:` y `SIM_OK` no lo ve nunca).
- **Subir `versionCode`** en cada build que vaya a Play.
- **Claude no puede probar en el coche.** Cambios en la conexión: mínimos, con tests en PC, y avisando de que solo se validan en el coche. La secuencia de inicialización AT (ATZ, ATE0, ATH0, ATS0, ATSP0, ATDPN con sus esperas) no se ha tocado.
- Commits solo cuando el usuario lo pide. Todo se trabaja directamente en **`main`** (no hay más ramas).
- Todo diagnóstico es **solo lectura** (lista blanca en `ObdDiagnostics.isReadOnly`).
- Las celdas actuales son las elegidas "por ahora"; el usuario puede cambiarlas en el futuro, pero la app no debe decir que son temporales.

## 3. Commits en `main`

| Commit | Contenido | Versión |
|---|---|---|
| `a428d9f` | Paso previo: diagnóstico OBD bajo demanda en release | 11 / 1.4.0 |
| `fd81e5c` | Fase 0: estados visibles, reconexión, fin de la simulación automática, `app/release/` fuera de git | 12 / 1.4.0 (subida a Play y probada) |
| `3e51c63` | Fase 1: lectura rápida basada en el diagnóstico real | 13 / 1.5.0 (subida a Play y probada) |

Tests unitarios: 56, todos pasan (`testDebugUnitTest`; con AGP 9 no existe `testReleaseUnitTest`, mismo código fuente).

## 4. Qué está hecho

### Paso previo (diagnóstico)
- Botón **DIAGNÓSTICO OBD** en la app del móvil → `DiagnosticsActivity` (+ `DiagnosticsViewModel`).
- `ObdDiagnosticsRunner`: conexión PROPIA al adaptador (no comparte la del salpicadero), misma inicialización, informe compartible (Intent `ACTION_SEND` de texto) y copiable; se guardan los 10 últimos en `filesDir/diagnostics`.
- `ObdDiagnostics`: adaptador, mapa de PIDs, protocolo, latencias, sufijo `010C1`, multi-PID (incluido multi-trama y sufijo con nº de tramas), ATAT/ATST (restaura al terminar).

### Fase 0
- `ObdPoller` (conexión + lectura en su hilo) sacado de `MainScreen`.
- Sin escáner: BUSCANDO ADAPTADOR → SIN CONEXIÓN · REINTENTANDO, reintentos 2/4/8/16/30 s. **Nunca simula solo.**
- Simulación solo desde el interruptor **SIMULACIÓN EN EL COCHE** (SharedPreferences, `AppSettings`), con franja naranja en el coche; usa `DashboardSimulator`.
- Errores de transporte propagados (`ElmLinkException`), reconexión automática con valores a `--`; adaptador colgado (3 TIMEOUT seguidos) → reconexión; ECU callada → SIN DATOS DE LA ECU.
- Hilo OBD termina siempre (`stop()`), sin zombis ni crash por `InterruptedException`; falta de permiso Bluetooth ya no tumba la app.
- `ElmIo.query` recoge la respuesta tardía tras TIMEOUT y clasifica respuestas (`ElmReply`: Data, NoData, Negative 7F, BusError, NotUnderstood, Timeout, Other).
- Franja de estado en el coche (también release): BUSCANDO, CONECTANDO, ● EN VIVO, RECONECTANDO, SIN CONEXIÓN, SIN DATOS DE LA ECU, BLUETOOTH APAGADO, FALTAN PERMISOS, SIMULACIÓN. Marcha `--` en gris sin datos.
- App del móvil muestra el estado real: "SALPICADERO: …".

### Fase 1
- Al conectar lee el mapa de PIDs soportados y **solo pide lo que existe**.
- Sin esperas fijas en el polling.
- Grupos por prioridad, cada uno en una petición multi-PID:
  - rápido: RPM 0C, velocidad 0D, MAP 0B — cada ciclo;
  - medio: pedal 49 (o 4A, o mariposa 11), carga 04, avance 0E, lambda pedida 44 — cada 400 ms;
  - lento: refrigerante 05 (o aceite 5C si existiera), admisión 0F — cada 5 s;
  - baro 33 — cada 60 s.
  En cada ciclo va el grupo rápido y como mucho otro grupo pendiente.
- Grupo rápido: calibra 5 lecturas con sufijo de nº de tramas y 5 sin él, se queda con el más rápido completo; si la ECU no responde bien en grupo → PID a PID.
- Boost = MAP − presión barométrica (PID 33).
- Celdas: izquierda WATER TEMP / BOOST BAR / INTAKE TEMP / PEDAL; derecha LAMBDA OBJ / ENG LOAD / G-FORCE / IGN TIMING.
- PEDAL escalado 0–100 % con `PedalScaler` (por defecto 14 % → 0, 80 % → 100, se ajusta con lo observado).
- La app del móvil muestra las lecturas por segundo: "SALPICADERO: EN VIVO · XX Hz".

## 5. Diagnóstico real del coche (2026-11-19, versión 12)

- ELM327 v2.3, 12.4 V. Protocolo A6 = CAN 11 bit 500 kbaud. Una sola ECU.
- PIDs soportados: 01 03 04 05 06 07 0B 0C 0D 0E 0F 11 13 14 15 1C 1F 20 21 23 2E 2F 30 31 33 3C 40 41 42 43 44 45 46 47 48 49 4A 4C 4F 50 51 56 60 70.
- **No soportados:** 5C (aceite), 10 (MAF), 34 (lambda medida). El "AFR" antiguo era la lambda **pedida** (44).
- Latencia PID soportado ~60–130 ms; PID no soportado ~235 ms (causa de los tirones antiguos).
- `010C` 68 ms vs `010C1` 44 ms. `010C0D` 75 ms, `010C0D1` 48 ms. `010C0D0B` 69 ms completo (2 tramas).
- `010C0D0B1` → 0/5 completas: el sufijo cuenta tramas; con 3 PIDs hace falta "2" (lo usa la Fase 1; **sin verificar en el coche**).
- Reposo: baro 101 kPa, MAP 69 kPa, pedal 49 ≈ 13 %, avance −21° con motor a 57 °C (probable calentamiento del catalizador).
- La sección ATAT/ATST del informe llegó **cortada** al pegarla.
- Candidatos interesantes para el futuro: 70 (boost pedido vs real), 3C (temp. catalizador), 42 (tensión centralita), 46 (temp. ambiente), 2F (combustible), 06/07 (correcciones de mezcla).

## 6. Prueba en el coche de la versión 12 (Fase 0)

- Todo correcto según el usuario: BUSCANDO, SIN CONEXIÓN, SIMULACIÓN y EN VIVO se ven bien; conexiones en 5–10 s.
- Al cambiar a otra app de Android Auto, el móvil sigue mostrando el salpicadero activo: **es correcto** (la sesión sigue viva en segundo plano y conectada). Posible mejora: soltar el adaptador en segundo plano a cambio de 5–10 s al volver.
- **G-FORCE sale `--` en vivo.** Probable causa: Android no entrega sensores de movimiento a una app que no está en primer plano en el móvil (sin confirmar). Pendiente (posible servicio en primer plano).

## 7. Prueba en el coche de la versión 13 (Fase 1)

- Subida a prueba interna y probada en el coche: según el usuario, todo lo que había que probar funciona.
- **No se han apuntado datos concretos:** Hz en vivo, si `010C0D0B2` llega completo ni la sección ATAT/ATST del diagnóstico. Hacen falta para los ajustes finos de la Fase 1 (ATAT2/ATST) y para calibrar la marcha.
- Siguiente: **Fase 2 (UI)**.

### Plan de prueba que se usó (referencia)

1. Generar AAB firmado (rama `main`) y subir **13 (1.5.0)** a prueba interna. Notas de versión:
   ```
   <es-ES>
   1.5.0 (13)
   - Lectura de datos mucho más rápida y sin tirones.
   - Solo se piden los datos que la centralita tiene.
   - Boost calculado con la presión atmosférica real.
   - Nuevas celdas: LAMBDA OBJ, IGN TIMING y PEDAL.
   - La app del móvil muestra las lecturas por segundo.
   - Diagnóstico OBD ampliado.
   </es-ES>
   ```
2. Prueba en el coche (escáner enchufado desde el principio, motor caliente):
   - A. Diagnóstico con el salpicadero cerrado → enviar informe COMPLETO (sobre todo Multi-PID: `010C0D0B2`, `0149040E44`, y ATAT/ATST).
   - B. Tiempo hasta EN VIVO y **Hz** que marca el móvil (esperado 10–15).
   - C. Ralentí: boost ≈ −0,3/−0,4, PEDAL 0 %, LAMBDA OBJ 1.00, IGN TIMING; pisar a fondo en punto muerto → PEDAL 100 %, LEDs fluidos; soltar desde ~3000 rpm → CUT.
   - D. Conduciendo: LEDs y velocidad fluidos, marchas 1–5, boost máximo, CUT al soltar.
   - E. Quitar contacto → SIN DATOS DE LA ECU; volver → EN VIVO.

## 8. Discrepancias encontradas entre PLAN.md y el código

- Los `ic_launcher_*.xml` nunca estuvieron en `.gitignore` (punto inexistente).
- El hilo zombi se formaba porque `ObdManager.readResponse` se tragaba la `InterruptedException` (resuelto en Fase 0).
- El `sleep(1000)` del modo simulación también estaba fuera de `try` → crash al cerrar Android Auto sin escáner (resuelto).
- La falta de permiso era un crash (`bondedDevices` fuera del `try`), no solo un mensaje (resuelto vía `BluetoothElmConnector`).
- Las latencias medidas incluyen hasta ~10 ms del sondeo de `readResponse` (`sleep(10)`).
- El plan asume builds debug (overlay `R:`, DHU con debug por adb): hay que replantearlo para release.

## 9. Próximas fases (según PLAN.md)

- **Fase 2 (UI), EN CURSO (2026-11-27, commiteada en `main`, sin probar en el coche):** el diseño GT3 de LEDs se ha sustituido por completo por el diseño nuevo del usuario (`docs/design/referencia.webp`, notas en `docs/design/README.md`).
  - `DashboardRenderer` es ahora una clase (una instancia por pantalla) con tres capas: estática (fondo con viñeta, marcos, glow de los marcos, escala, iconos, logo) cacheada en bitmap por tamaño; dinámica por frame; y **bloom** selectivo (arco y línea de RPM, zona roja encendida, marcha, barras) pintado sobre negro a 1/4 de resolución, desenfocado con `BoxBlur` (CPU, separable, 3 pasadas) y sumado con `BlendMode.PLUS` (ADD antes de API 29). Sin BlurMaskFilter por frame.
  - Paneles: relleno `#1C1C20`→`#0A0A0C`, borde `#2A2A2E`, bisel superior blanco al 8 %, sombra inferior, rojo solo en las esquinas exteriores. Etiquetas al 55 % del valor en `#9A9AA0`, cifras tabulares. El cuentavueltas busca el 85 % del alto (en 20:9 lo consigue; en pantallas más estrechas se reduce para no pisar las columnas).
  - Afinado posterior: las barras ya no van en el bloom (un solo radio de desenfoque no sirve a la vez para el halo ancho de la marcha y un brillo ceñido a una barra de 7 unidades); llevan un brillo analítico (capas redondeadas con alfa decreciente, ~2× su grosor). Acentos rojos `#E01020` solo en los chaflanes exteriores, desvaneciéndose al 30 % de cada lado. Arco plateado `#6E6E74`→`#D6D6DA`, separadores de 3 px, aguja de 4 px con bloom, zona roja con degradado radial `#5A0008`→`#FF1A2A`. Marcha blanca pura con halo rojo al 60 %. Tipografía en proporción al alto del panel (valor 38 %, etiqueta 16 % a la derecha sobre el valor, unidad 14 %). Placas de fondo `#121216` y haces rojos en V hacia el logo. La zona roja sigue empezando en 5500 rpm.
  - Bloom global por umbral (`BloomMath`, `BLOOM_THRESHOLD` 0.6 con rodilla suave, `BLOOM_INTENSITY` 0.35): el frame a 1/4 se reconstruye (copia estática reducida una vez + parte dinámica redibujada a 1/4, porque el Canvas de hardware no se puede leer), se le suma la capa emisiva (halos de neón estáticos, halo de la marcha, aguja, zona roja) y se desenfoca una sola vez. minSdk 26 → sin AGSL. Neón rojo en 3 pasadas (halo 10 px a la capa emisiva, cuerpo 3 px `#FF1A2A`, núcleo 1 px `#FFB0B8`), solo en el chaflán exterior superior de cada panel y en los marcos. Paneles `#0E0E11`→`#060608`, borde `#26262C`, bisel 10 %, fondo `#050506`. Banda del arco un 35 % más estrecha, relleno `#4A4A50`→`#B8B8BE`, reflejo especular, aguja de 5 px. Columnas un 20 % más estrechas y cuentavueltas al 95 % del alto; icono pegado al bloque de texto y barras a todo el ancho.
  - `DebugFrameExporter` (solo debug, al abrir la vista previa): guarda `debug_frame.png` (2400×1080, con los valores de la referencia), `debug_frame_sim.png` (modo simulación) y `debug_bloom.png` en `getExternalFilesDir`. Sacarlo con `adb pull /sdcard/Android/data/com.javirmmn.customobddashboard/files/debug_frame.png`. Las copias locales van a `docs/design/capturas/` (fuera de git).
  - **Ajuste en vivo (2026-11-27):** todos los parámetros visuales están en `DashTheme.kt` (data classes por grupo; valores por defecto = diseño actual, comprobado píxel a píxel). `ThemeFields` es el registro único (sliders, JSON de presets, exportación de código, campos estáticos vs dinámicos). `ThemeStore.theme` (StateFlow) lo lee el renderer una vez por frame; si cambian campos estáticos, la capa estática se regenera con 150 ms de debounce. Sin reservas por frame en el renderer (`TextBuf` para los números). `RenderMetrics`: media y p95 de estático/dinámico/bloom y FPS; overlay en Android Auto activable (`DashDebug.showMetricsOverlay`).
  - Pantalla **Ajuste del diseño** solo en debug (`app/src/debug`, botón en la app del móvil): vista previa 2400×1080, referencia superpuesta con opacidad, sliders y colores hex por secciones, presets en DataStore, Reset, exportar código (portapapeles + compartir) y métricas. Para fijar un diseño: exportar y sustituir `DashTheme.kt` entero (un test garantiza que el formato coincide).
  - **Tema afinado por el usuario aplicado** (exportado desde la pantalla de ajuste y pegado en `DashTheme.kt`). Notas: `bloom.intensity = 0` apaga el bloom por umbral (threshold y knee no influyen; radius y passes sí, en los halos), y el renderer ya no redibuja el frame a 1/4 cuando la intensidad es 0 (mismo resultado, menos coste). `neon.coreWidthPx = 0` sigue pintando una línea de 1 px (en Android, grosor 0 = línea fina). Varios valores están en el máximo del slider (bodyWidthPx, bevelAlpha, plateBevelAlpha, specularAlpha, labelRatio): si hiciera falta ir más allá, ampliar el rango en `ThemeFields`.
  - Dibuja dentro del `stableArea`; punto decimal con `Locale.ROOT`; textos medidos para que quepan; RPM suavizadas; parpadeo del arco desde 6000 rpm.
  - `TelemetrySnapshot` (fichero propio): `activeLeds` → `rpm`; nuevos `gForcePeak`, `ambientTemp`, `fuelConsumption`, `rangeKm` (los tres últimos aún sin fuente real: salen `--` en el coche).
  - Nuevos `DashboardGeometry.kt` (`RpmScale`, `DashFormat`) y `DashboardIcons.kt` (iconos dibujados con trazos). `GForceSensor.peakG`.
  - Fuente: Roboto Condensed cursiva del sistema (pendiente de decidir si se añade una fuente propia).
  - Pendiente: probar en Android Auto real (DHU o coche), pedir PID 46 y estimar consumo/autonomía (PID 2F + MAP/IAT/RPM/lambda), interpolación de barras, modo noche, arreglar G-FORCE `--` en vivo.
- **Fase 2 (UI), lista original:** reutilizar `Paint`/`Typeface`/`RectF` en vez de crearlos cada frame; interpolación de RPM/LEDs; usar `stableArea`; medir texto para que quepa; `Locale.ROOT` para el punto decimal (ahora sale coma en español); modo noche; modos Track/Cruise; G-FORCE (pico de sesión, arreglar `--`).
- **Fase 3 (arquitectura):** `ElmTransport` (Bluetooth/TCP/Fake), `PidRegistry`, `ObdPoller` con coroutines, `TelemetryRepository`; renombrar `namespace`; actualizar `androidx.car.app` (1.4.0).
- **Fase 4 (seguridad):** permisos mínimos (solo `BLUETOOTH_CONNECT`, `maxSdkVersion=30` en BLUETOOTH/ADMIN), elegir adaptador por MAC, quitar logs en release/R8, documentar riesgo del dongle y categoría NAVIGATION.
- **Fase 5:** grabación de datos, k-means de marchas, DTCs (modo 03), PIDs VAG por UDS (aceite real, boost pedido), app móvil definitiva, ESP32.
- Fase 1 pendiente de afinar con datos: marcha (`R:` no visible en release), LEDs configurables + parpadeo en corte, `sealed class PidResult` en vez de centinelas, ATAT2/ATST.

## 10. Mapa del código (paquete `com.example.ibizacustommap`)

| Fichero | Qué hace |
|---|---|
| `MainActivity.kt` | App del móvil: permisos, estado del salpicadero + Hz, interruptor de simulación, botones Vista previa y Diagnóstico |
| `DiagnosticsActivity.kt` | Pantalla de diagnóstico (ViewModel, compartir, copiar) |
| `DashboardPreviewActivity.kt` | Vista previa del dashboard con `DashboardSimulator` |
| `IbizaCarService.kt` / `IbizaSession.kt` | Entrada de Android Auto (HostValidator debug/release) |
| `MainScreen.kt` | Pantalla del coche: ciclo de vida, superficie, elige poller o simulación |
| `ObdPoller.kt` | Conexión, reconexión, estados, PIDs soportados, grupos multi-PID, valores |
| `ObdManager.kt` | Socket RFCOMM con el ELM327 (connect, send, read) |
| `ElmIo.kt` | Interfaz `ElmIo`, `ElmLinkException`, `ElmReply`, `query()` con drenaje |
| `ElmConnector.kt` | `BluetoothElmConnector` (permiso, Bluetooth encendido) |
| `LinkState.kt` | Estados de conexión y `ObdLinkState` (estado + Hz compartidos con el móvil) |
| `PidSpec.kt` | Tabla de PIDs, cálculo de tramas, comandos, `PedalScaler` |
| `ObdDecoder.kt` | Fórmulas de cada PID |
| `ObdDiagnostics.kt` / `ObdDiagnosticsRunner.kt` | Sonda de diagnóstico y su ejecución con conexión propia |
| `DashboardRenderer.kt` | `TelemetrySnapshot` y dibujo del dashboard + franja de estado |
| `DashboardSimulator.kt` | Datos simulados (única fuente de simulación) |
| `GearEstimator.kt` / `GForceSensor.kt` | Marcha por relación rpm/km/h y fuerza G con sensores del móvil |
| `AppSettings.kt` | Preferencias (simulación) |

## 11. Cómo compilar y probar en PC

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew --offline assembleRelease testDebugUnitTest
```
- Emulador disponible: AVD `Pixel_7` (sin Bluetooth; sirve para la app del móvil y la vista previa).
- Tras arrancar el emulador en frío puede salir "la app no responde" por saturación del propio emulador; esperar y repetir.

## 12. Cómo subir a Play (resumen)

1. Android Studio en rama `main` → Build → Generate Signed App Bundle → keystore de siempre → release.
2. Play Console → Prueba interna → Crear nueva versión → subir `app/release/app-release.aab` → notas dentro de `<es-ES>…</es-ES>` → no "Incluir" la versión anterior → Siguiente → Guardar y publicar.
3. Actualizar desde Play Store en el móvil (sin desinstalar, salvo que cambie el `CarAppService`).
