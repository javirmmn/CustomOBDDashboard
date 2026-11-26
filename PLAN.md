# Prompt: reencaminar CustomOBDDashboard (Ibiza Custom Map)

## Rol y objetivo

Actúa como ingeniero Android senior especializado en Android Auto (Car App Library), Kotlin, diagnóstico OBD2/ELM327 y desarrollo seguro. Vas a trabajar sobre mi proyecto **CustomOBDDashboard** (repo `javirmmn/CustomOBDDashboard`, `applicationId` `com.javirmmn.customobddashboard`). Es un dashboard de telemetría estilo GT3/MoTeC que se muestra en la pantalla de Android Auto de mi SEAT Ibiza. Lee los datos de un escáner OBD2 Bluetooth **Vgate iCar Pro (ELM327)**.

El objetivo es **reencaminar el proyecto**: corregir los bugs que quedan, rehacer la capa de datos a partir de mediciones reales, pulir la UI, mejorar la arquitectura y la seguridad, y montar un flujo de pruebas que no dependa de subir cada build a Google Play. Trabaja por fases, en el orden indicado. Al terminar cada fase, explícame qué has cambiado y por qué antes de pasar a la siguiente.

**Coche:** SEAT Ibiza KJ 1.0 Eco TSI 95 CV, manual de 5 velocidades. Relaciones según la ficha técnica oficial: 3.769 / 1.955 / 1.281 / 0.927 / 0.740, grupo 3.625. Potencia máxima entre 5000 y 5500 rpm.

---

## Estado actual del proyecto (ya hecho, no rehacer)

Ficheros: `MainActivity.kt`, `IbizaCarService.kt`, `IbizaSession.kt`, `MainScreen.kt` (conexión, polling y simulación), `ObdManager.kt`, `ObdDecoder.kt`, `DashboardRenderer.kt`, `DashboardSimulator.kt`, `DashboardPreviewActivity.kt`, `GearEstimator.kt`, `GForceSensor.kt` y `ObdDiagnostics.kt`, con tests unitarios para decoder, marchas, G-force, simulador y diagnóstico.

**Ya resuelto en el código:**
- **Datos sin fuente:** los valores arrancan en `NaN` y se pintan como `--` en gris. Ya no hay números inventados (antes, por ejemplo, MAF = 10 o AFR = 14.7).
- **Etiqueta del aceite:** si se usa el fallback al PID `05` (refrigerante), la celda dice `WATER TEMP` y no `OIL TEMP`.
- **G-FORCE real:** `GForceSensor` usa la aceleración lineal del móvil menos la componente vertical (proyectada sobre la gravedad), suavizada. Si no hay sensores, devuelve `NaN`.
- **Marcha:** `GearEstimator` usa las relaciones reales de la caja (rpm por km/h: 121.0 / 62.8 / 41.1 / 29.8 / 23.8), con distancia logarítmica, tolerancia, confirmación de N muestras y conservación de la marcha anterior cuando la lectura no encaja (embrague pisado, en pleno cambio). El overlay de debug muestra `R:` para afinar.
- **LEDs:** `RPM_PER_LED = 250`, así que los 24 LEDs cubren hasta 6000 rpm. Naranja desde 4500 y rojo desde 5500.
- **Umbrales** de admisión y carga adaptados al TSI.
- **AFR:** primero el PID `34` (lambda medida), luego el `44` (lambda pedida). Si lambda ≥ 1.5, se muestra `CUT` (corte de inyección).
- **MAF** con decimales.
- **Render separado:** `DashboardRenderer` es un objeto puro, compartido por Android Auto y por la vista previa del móvil.
- **Vista previa en el móvil:** `DashboardPreviewActivity` (no exportada), lanzada desde un botón en `MainActivity`, pinta el dashboard con datos de `DashboardSimulator` sin tocar el Bluetooth. Existe en todas las compilaciones, release incluida.
- **`seat_logo.png`** ya está versionado: el repo compila desde un clon limpio.
- **`ObdDiagnostics`:** sonda que informa del modelo y la tensión del adaptador, los PIDs soportados (mapas `0100`, `0120`…), el protocolo real, la latencia por PID, si funciona el sufijo `010C1` y si funciona el multi-PID (`010C0D`, `010C0D0B`). El informe va a logcat (`OBD_DIAG`) y a `Android/data/<applicationId>/files/diagnostics/`. **Pero no se llama desde ningún sitio** (ver Paso previo).

**Historial útil:**
- El conflicto entre CAN de 29 y 11 bits se resolvió quitando `ATSH7E0` y dejando `ATSP0` en autodetección.
- Antes se creía que la ECU no soportaba AFR ni MAF; el diagnóstico lo confirmará.
- Instalo vía **Google Play Console, prueba interna** (Android Auto no me permitía el sideload en el coche). Al cambiar el `CarAppService` hay que desinstalar y reinstalar con `adb`.

**Comportamiento observado en el coche:** con la última versión instalada y **sin escáner conectado**, la pantalla del coche se quedó "todo a 0" (en el código actual, `--`, marcha `N` y LEDs apagados), y la única simulación que vi fue la vista previa del móvil. Explicación según el código: `MainScreen` sigue haciendo 5 intentos de conexión. Cada `connect()` contra un dispositivo emparejado pero ausente bloquea varios segundos, y tras los 5 intentos **entra sola en simulación sin ningún aviso en release**. Si me quedé poco rato, vi solo la fase de intentos. **Verifícalo** (esperando lo suficiente en el DHU o con el emulador) y trátalo como bug de la Fase 0.

**Incoherencia a corregir:** los comentarios del código hablan de "Fase 2" y "Fase 8 del plan", que no corresponden con este documento. Actualízalos a la numeración de aquí.

---

## Paso previo — Medir antes de optimizar

1. Conecta `ObdDiagnostics` en `MainScreen`: **solo en builds de debug**, una vez tras la inicialización del ELM327 y antes del bucle de polling, en el hilo OBD. Debe respetar la interrupción y no tocar el estado del dashboard.
2. Pensando en que solo instalo por Play, añade también una forma de lanzarlo **bajo demanda en release** (por ejemplo, un botón "Diagnóstico OBD" en la app del móvil que use la conexión existente o una propia). Así puedo ejecutarlo en el coche. El informe debe poder compartirse desde la app (Intent de compartir o copiar al portapapeles), porque en el coche no tengo `adb`.
3. Cuando te pase el informe, úsalo para decidir los detalles de la Fase 1: qué PIDs pedir, si usar `…1`, si usar multi-PID y con cuántos PIDs, qué latencias son realistas, y qué celdas del dashboard rellenar con qué datos.

---

## Fase 0 — Bugs que siguen abiertos (prioridad máxima)

### 🔴 Simulación automática e invisible
Tras 5 intentos fallidos, `modoSimulacion = true` y la pantalla del coche muestra ondas senoidales. El único aviso (`SIM_OK`) está dentro de `if (BuildConfig.DEBUG)`.
- **Quita la simulación automática.** Sin escáner, el estado es BUSCANDO ADAPTADOR y luego SIN CONEXIÓN, y se sigue reintentando con backoff.
- La simulación pasa a ser una **fuente elegida explícitamente** desde la app del móvil, con un indicador visible en la pantalla del coche también en release.
- Unifica la simulación: `MainScreen` debe usar `DashboardSimulator` en vez de su copia de las fórmulas.

### 🔴 La conexión no se recupera nunca
`sendCommand` y `readResponse` capturan la excepción y llaman a `closeConnection()` sin relanzarla, así que el `catch` del bucle principal no se ejecuta jamás. Si el Bluetooth se corta en marcha, `readResponse()` devuelve `""`, los parsers devuelven `-1`, el bucle sigue girando sin reconectar y el dashboard se queda congelado en los últimos valores sin aviso.
- **Solución:** propagar los errores de transporte, detectar la desconexión, reconectar con backoff y mostrar DESCONECTADO o RECONECTANDO. Al perder la conexión, los valores vuelven a `NaN` (`--`); no se quedan congelados.

### 🔴 El hilo OBD no muere bien (fuga y posible crash)
```kotlin
while (!Thread.currentThread().isInterrupted) {
    try { ... Thread.sleep(40) ... }
    catch (e: Exception) { ... modoSimulacion = true }   // ← también captura InterruptedException
}
```
- La `InterruptedException` cae en el `catch` genérico, que borra el flag de interrupción, y el hilo sigue vivo con un `sleep(1000)` infinito. Cada vez que reconecto Android Auto se crea otro hilo zombi.
- El `Thread.sleep(1000)` del bucle de conexión está **fuera** de cualquier `try`. Una interrupción en esa fase no se captura y **tumba el proceso**.
- `modoSimulacion` se escribe desde el hilo OBD y se lee desde el de render sin `@Volatile`.
- `ObdDiagnostics` ya gestiona bien la interrupción; aplica el mismo criterio al bucle principal (o resuélvelo de raíz con coroutines en la Fase 3).

### 🟠 Desincronización de respuestas
Si un comando hace timeout, la respuesta tardía se lee como la del siguiente comando. `ObdDiagnostics.exec()` ya vacía la respuesta tardía tras un `TIMEOUT`, pero **el bucle principal no**. Lleva esa lógica a `ObdManager` para que valga en todos los sitios, y trata explícitamente `NO DATA`, `?`, `CAN ERROR` y las respuestas negativas `7F`.

### 🟠 Higiene del repo
- `app/release/` (AAB y baseline profiles) sigue commiteado e hincha el historial con cada versión. Sácalo del control de versiones y añádelo al `.gitignore`.
- Revisa por qué `ic_launcher_background.xml` y `ic_launcher_foreground.xml` aparecen en `.gitignore`.

### 🟢 Estados visibles en la pantalla del coche
Cuatro estados claramente distinguibles, en la pantalla del coche y no solo en el móvil:
- **BUSCANDO ADAPTADOR / CONECTANDO**
- **EN VIVO**
- **SIMULACIÓN**, con franja o indicador visible también en release
- **SIN CONEXIÓN / DESCONECTADO**, con valores en `--` e indicador

Si faltan permisos, la pantalla del coche debe pedir que abra la app en el móvil. Ojo: la marcha muestra `N` y los LEDs se apagan aunque no haya datos. Sin conexión, la marcha debe mostrar `-` o `--`, no `N`.

---

## Fase 1 — Capa de datos (basada en el informe de `ObdDiagnostics`)

### Velocidad de refresco
Ahora mismo, cada ciclo rápido hace 3 PIDs, cada uno con su ida y vuelta más `sleep(40)`, y otro `sleep(50)` al final: unos **2–3 Hz de RPM**. Cada 10 ciclos entra el carril lento, con 6–8 PIDs más, incluidos posibles no soportados con 500 ms de timeout cada uno. Ese tirón periódico se nota en los LEDs.

1. **Quita todos los `sleep`.** El prompt `>` ya es el control de flujo.
2. **Multi-PID**, si el diagnóstico confirma que funciona: `01 0C 0D 0B` trae RPM, velocidad y MAP en una sola ida y vuelta (sobre CAN se admiten hasta 6 PIDs). Hace falta un parser que recorra la respuesta byte a byte (`41 0C AA BB 0D CC 0B DD`) y soporte multi-frame (formato `0:` / `1:` con `ATCAF1`). Con 3 PIDs la respuesta ya va en varias tramas.
3. **Sufijo de número de respuestas** (`010C1`), si el diagnóstico confirma que ahorra tiempo.
4. **`ATAT2`** y un **`ATST`** bajo.
5. **PIDs soportados en producción:** reutiliza `ObdDiagnostics.parseSupportedPids` para descubrir el mapa al conectar y pedir solo lo soportado. Se acaban los timeouts de MAF y AFR y los fallbacks a ciegas.
6. **Scheduler por prioridad** en lugar de `loopCounter % 10`:

| Grupo | Frecuencia objetivo | PIDs |
|---|---|---|
| Alta | máxima posible | `0C` RPM, `0D` velocidad, `0B` MAP |
| Media | ~2 Hz | pedal, carga |
| Baja | ~0,2 Hz | aceite/refrigerante, admisión, lambda |
| Una vez | al conectar | `33` presión barométrica, mapa de PIDs |

Objetivo: pasar de unos 3 Hz a **10–15 Hz** en el grupo alto, si las latencias del informe lo permiten. Mídelo e infórmame.

### Precisión de cada dato
- **Boost:** sigue restando 100 kPa fijos al MAP. `parseBarometricPressure` (PID `33`) existe pero no se usa. Calcula boost = MAP − presión barométrica (leída al conectar y cada cierto tiempo). Revisa los umbrales de color de boost (1.2 y 1.4 bar) con datos reales de este motor.
- **THROTTLE:** el PID `11` es la mariposa, no el pedal. Si el diagnóstico confirma `49` o `4A` (pedal), úsalos; si no, `45` o `4C`. Ajusta la etiqueta.
- **Celdas:** decide con el mapa real de PIDs qué va en cada celda. Si MAF o lambda no están soportados, sustitúyelos por datos que sí existan (avance de encendido `0E`, refrigerante `05`, tiempo de motor `1F`, temperatura ambiente `46`…), o haz que el layout se adapte.
- **Marcha:** valida la tabla de relaciones con el `R:` del overlay en conducción real. Opcional: calibración automática con k-means (k = 5) sobre pares (rpm, km/h) para ajustar a los neumáticos reales.
- **LEDs:** haz configurables régimen máximo y punto de cambio (los valores actuales son buenos por defecto), y añade parpadeo al llegar al corte.
- **Valores centinela** (`-1`, `-999`, `-999.0`) en los parsers: sustitúyelos por tipos nulos o por `sealed class PidResult { Value, NoData, Unsupported, Timeout }`. El `NaN` del snapshot se queda para el render.

---

## Fase 2 — UI de Android Auto

### Rendimiento del render
- `DashboardRenderer.draw()` sigue creando en cada frame (30 por segundo) **todos los `Paint`, los `Typeface` y los `RectF`**, incluidos 24 `Paint` para los LEDs y un `Paint` nuevo por cada fila. Créalos una vez (por tamaño de superficie) y reutilízalos.
- **Interpolación:** se pinta a 30 fps pero los datos llegan más despacio. Guarda el valor objetivo y avanza hacia él en cada frame (por ejemplo, `display += (target - display) * 0.25f`), sobre todo en RPM y LEDs.

### Layout
- `visibleArea` y `stableArea` se guardan en `MainScreen` pero **no se pasan al renderer**. Todo se calcula sobre la superficie completa. Pasa `stableArea` a `DashboardRenderer.draw()` y úsala como lienzo. En la vista previa del móvil, usa los insets equivalentes.
- Los tamaños de texto son proporcionales a la altura y no se comprueba que quepan. Mide con `measureText` y reduce el tamaño si no cabe (`"INTAKE TEMP"`, `"WATER TEMP"` en pantallas panorámicas).
- `String.format("%.1f")` usa el locale del sistema, así que en español sale `0,8`. Usa `Locale.ROOT` para el punto decimal estilo MoTeC.

### Diseño
- Los **cuatro estados** de la Fase 0, bien diseñados.
- **Modo noche:** la marcha ocupa el 54 % de la altura en blanco 240. Usa `carContext.isDarkMode` para bajar el blanco a ~170 y atenuar los LEDs.
- **Jerarquía:** la velocidad (`0.068 × h`, alfa 200) queda muy por debajo de la marcha. Añade dos modos: **Track** (marcha y LEDs) y **Cruise** (velocidad grande y temperaturas).
- **G-FORCE:** valora mostrar el pico de la sesión además del valor actual. Si hay que calibrar la orientación del móvil (separar lateral de longitudinal), hazlo al arrancar con el coche parado.

---

## Fase 3 — Arquitectura

Parte del trabajo ya está hecho (`DashboardRenderer`, `GearEstimator`, `GForceSensor` y `DashboardSimulator` están separados). Queda sacar de `MainScreen.kt` la conexión, el polling y la simulación:

```
obd/
  ElmTransport        ← interfaz: envío/lectura, resync, reconexión con backoff
    BluetoothElmTransport  ← RFCOMM al Vgate
    TcpElmTransport        ← TCP, para un emulador ELM327 en el PC
    FakeElmTransport       ← reproduce logs reales grabados (tests)
  PidRegistry         ← tabla declarativa: pid, bytes, fórmula, prioridad, unidad, etiqueta
  ObdPoller           ← coroutine en Dispatchers.IO, scheduler por prioridad
  ObdDiagnostics      ← (existente) adaptado a ElmTransport
  TelemetryRepository ← StateFlow<Telemetry> con el estado de conexión incluido
sim/
  DashboardSimulator  ← (existente) como fuente que implementa la misma interfaz que ObdPoller
car/
  DashboardRenderer   ← (existente) solo dibuja
  MainScreen          ← solo ciclo de vida y superficie
sensors/
  GForceSensor        ← (existente)
  GearEstimator       ← (existente)
```

- **Coroutines y `StateFlow`:** eliminan los `synchronized`, el problema de visibilidad de `modoSimulacion` y el lío de interrupciones.
- **Tests:** amplía los existentes con respuestas reales capturadas del coche (multi-PID, multi-frame, `NO DATA`, timeouts y desincronización), y añade tests del `ObdPoller` con `FakeElmTransport`.
- Renombra el `namespace` (`com.example.ibizacustommap`) para que coincida con el `applicationId`.
- Actualiza `androidx.car.app:app` (ahora en 1.4.0) a la última versión estable.

---

## Fase 4 — Seguridad

- **El dongle es el punto débil:** un ELM327 por Bluetooth clásico (SPP) suele tener un PIN fijo y conocido. Con el coche en contacto, cualquiera dentro del alcance que lo empareje puede mandar comandos de diagnóstico, incluido el modo `04` (borrar DTCs), y en algunos adaptadores tramas CAN crudas. Documéntalo en el README con su mitigación (desenchufarlo cuando no se use, o un adaptador con mejor autenticación).
- **Mínimo privilegio:** en API 31+ se piden `BLUETOOTH_SCAN` y `ACCESS_FINE_LOCATION`, pero nunca se escanea; solo se usan dispositivos emparejados. Basta con `BLUETOOTH_CONNECT`. Añade `android:maxSdkVersion="30"` a `BLUETOOTH` y `BLUETOOTH_ADMIN`. La localización solo tendrá sentido con GPS para grabación de datos o tiempos por vuelta.
- **Selección del dispositivo:** ahora se coge el primer emparejado cuyo nombre contiene "OBD", "V-LINK", "ELM327" o "Vgate". Sustitúyelo por una elección explícita en la app del móvil que guarde la MAC del adaptador, con fallback a socket insecure para clones.
- **Logs:** hay `Log.d` y `Log.i` en release con respuestas crudas, y `isMinifyEnabled = false`. Elimínalos en release con un wrapper o reglas de R8 (salvo los del diagnóstico bajo demanda, que van al informe), y valora activar la minificación.
- **Informe de diagnóstico:** no debe incluir datos identificativos (el VIN no se lee hoy; que siga así) y se guarda en el almacenamiento privado de la app.
- **Vista previa del móvil en release:** es aceptable (no exportada, sin Bluetooth), pero etiquétala como "Vista previa" y no como "(debug)".
- **Mantén** el `HostValidator` que separa debug y release.
- **Categoría NAVIGATION:** vale para prueba interna, pero en producción Google la rechazaría por no navegar de verdad, y el logo y el nombre de SEAT añadirían un problema de marca registrada. Déjalo documentado.
- **Restricción absoluta:** todo diagnóstico es **solo lectura**. Nada de modo `04`, ni servicios UDS de escritura, codificación, adaptación o rutinas, ni inyección de tramas en el coche real.

---

## Flujo de pruebas (que Play deje de ser el ciclo de desarrollo)

Monta y documenta estos niveles:

1. **Vista previa en el móvil (ya existe):** `DashboardPreviewActivity` con `DashboardSimulator`. Sirve para cambios visuales rápidos. Mejórala con un selector de escenarios: valores normales, sin datos (`--`), sin conexión, AFR en `CUT`, alarmas de temperatura y cada uno de los estados de conexión.
2. **Desktop Head Unit (DHU):** el emulador oficial de Android Auto, desde el SDK Manager (*Android Auto Desktop Head Unit Emulator*).
   - En el móvil: Android Auto → ajustes → tocar varias veces la versión para activar el modo desarrollador. Allí, activar *Fuentes desconocidas* e *Iniciar servidor de la unidad principal*.
   - Móvil por USB, `adb forward tcp:5277 tcp:5277` y lanzar `desktop-head-unit`.
   - Valida el `MainScreen` real (superficie, `stableArea`, ActionStrip, ciclo de vida, reconexiones de Android Auto) con builds debug instaladas por `adb`. Al cambiar el `CarAppService`, desinstalar y reinstalar.
   - Prepara scripts o tareas Gradle que automaticen build, desinstalación, instalación y arranque del DHU.
3. **Emulador ELM327 en el PC:** el emulador en Python *ELM327-emulator* (GitHub/PyPI) responde como un escáner real. Con el `TcpElmTransport`, la app se conecta a él por red local y se prueba el pipeline completo: timeouts, reconexiones, PIDs no soportados y desincronización. Configúralo con el mapa de PIDs y las latencias reales del informe de `ObdDiagnostics`, y añade escenarios de fallos forzados.
4. **Selector de fuente en la app del móvil:** Bluetooth real, TCP (emulador), simulación o reproducción de log.

| Nivel | Herramienta | Qué se valida |
|---|---|---|
| Visual rápido | Vista previa en el móvil | Diseño, colores, `--`, estados |
| Desarrollo diario | DHU + simulación o emulador ELM | `MainScreen` real, layout, ciclo de vida, lógica |
| Antes del coche | DHU + emulador ELM con fallos forzados | Reconexión, timeouts, desincronización |
| Validación final | Play prueba interna + coche real | Datos reales de la ECU, pantalla real, informe de diagnóstico |

Cuando se use Play, sube el `versionCode` en cada build.

---

## Fase 5 — Escalado (cuando lo anterior esté sólido)

1. **Grabación de datos:** CSV o SQLite por sesión, más el GPS del móvil. En el escritorio, un notebook o una app Streamlit con gráficas superpuestas por vuelta o tramo. Después, detección de anomalías (aceite que sube más rápido de lo normal, boost por debajo de lo esperado para cierta carga y RPM). Los logs grabados alimentan también el `FakeElmTransport` y el emulador.
2. **Calibración de marchas con k-means**, si la tabla fija resulta insuficiente.
3. **Lectura de DTCs** (modo `03`) y freeze frame. Solo lectura.
4. **PIDs específicos de VAG** por UDS (servicio `22` ReadDataByIdentifier), dirigidos a la ECU de motor con `ATSH7E0` en 11 bits, sin romper la autodetección que resolvió el conflicto de 29 y 11 bits: temperatura real de aceite, boost pedido frente al real, retardo por picado, etc. Siempre en solo lectura.
5. **App del móvil definitiva**, que sustituye la vista previa temporal: ajustes (régimen máximo, punto de cambio, umbrales, unidades, PIDs por celda, adaptador), selector de fuente, diagnóstico bajo demanda, dashboard en vivo y elección de layout.
6. **Puente con un proyecto de CAN propio:** el Ibiza KJ (plataforma MQB A0) tiene **gateway**. Por el conector OBD no se ve el tráfico broadcast del CAN de motor, solo lo que el gateway deja pasar en diagnóstico. Lo viable es un ESP32 como adaptador propio por BLE que haga polling UDS más rápido que un ELM327, detrás de la interfaz `ElmTransport`. El sniffing pasivo queda para un banco de pruebas aparte.

---

## Forma de trabajar

- Antes de escribir código, explora el repo y confirma qué puntos de este documento siguen vigentes. Si algo ya está hecho, sáltalo y dímelo.
- Orden: Paso previo → Fase 0 → 1 → 2 → 3 → 4 → 5. El flujo de pruebas (DHU y emulador) puede montarse en paralelo desde el principio si acelera el resto.
- No rompas lo que ya funciona en el coche (RPM, velocidad, MAP, temperaturas, carga, `GearEstimator`, `GForceSensor`).
- Cambios pequeños y revisables, con commits descriptivos.
- Cada cambio en el pipeline de datos viene con su test.
- Revisa el código nuevo en busca de vulnerabilidades y explícame las mitigaciones.
- Si algo de este documento resulta incorrecto al verlo en el código real, dímelo y propón la alternativa en vez de aplicarlo a ciegas.
