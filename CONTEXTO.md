# Contexto del trabajo — CustomOBDDashboard (Ibiza Custom Map)

Resumen de la conversación con Claude para retomar el trabajo en otra sesión.
Última actualización: 2026-11-26.

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
- Commits solo cuando el usuario lo pide. Rama de trabajo: **`reencaminar`** (sale de `main`).
- Todo diagnóstico es **solo lectura** (lista blanca en `ObdDiagnostics.isReadOnly`).
- Las celdas actuales son las elegidas "por ahora"; el usuario puede cambiarlas en el futuro, pero la app no debe decir que son temporales.

## 3. Commits en la rama `reencaminar`

| Commit | Contenido | Versión |
|---|---|---|
| `a428d9f` | Paso previo: diagnóstico OBD bajo demanda en release | 11 / 1.4.0 |
| `fd81e5c` | Fase 0: estados visibles, reconexión, fin de la simulación automática, `app/release/` fuera de git | 12 / 1.4.0 (subida a Play y probada) |
| `3e51c63` | Fase 1: lectura rápida basada en el diagnóstico real | 13 / 1.5.0 (**pendiente de subir y probar**) |

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

## 7. Pendiente inmediato

1. Generar AAB firmado (rama `reencaminar`) y subir **13 (1.5.0)** a prueba interna. Notas de versión:
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

## 9. Próximas fases (según PLAN.md, aún no empezadas)

- **Fase 2 (UI):** reutilizar `Paint`/`Typeface`/`RectF` en vez de crearlos cada frame; interpolación de RPM/LEDs; usar `stableArea`; medir texto para que quepa; `Locale.ROOT` para el punto decimal (ahora sale coma en español); modo noche; modos Track/Cruise; G-FORCE (pico de sesión, arreglar `--`).
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

1. Android Studio en rama `reencaminar` → Build → Generate Signed App Bundle → keystore de siempre → release.
2. Play Console → Prueba interna → Crear nueva versión → subir `app/release/app-release.aab` → notas dentro de `<es-ES>…</es-ES>` → no "Incluir" la versión anterior → Siguiente → Guardar y publicar.
3. Actualizar desde Play Store en el móvil (sin desinstalar, salvo que cambie el `CarAppService`).
