# ESTADO — Surco 2.0 (reproductor completo)

Encargo de Eddie: «Es muy mediocre el reproductor además de no tener EQ y diferentes opciones necesarias que debes investigar».

## Criterios
- C1: pruebas JVM del motor de sonido y del parser de letras en verde (local con kotlinc y en CI). Base: 0 pruebas.
- C2: CI compila debug+release y la prueba de humo en emulador pasa con las funciones nuevas: ecualizador abierto y preajuste aplicado mientras suena, cola visible, favorita marcada que sobrevive a reiniciar la app. Base: no hay EQ, cola, favoritas ni listas.
- C3: cadena de sonido medida: banda a +6 dB sube 6,0 ±0,15 dB un seno en su centro; limitador ≤ 0,98 con +12 dB.
- Caso reservado: FLAC 96 kHz 24 bits mono con EQ activo en el emulador (procesador con 1 canal y frecuencia alta) — no mirar hasta el final.

## Investigación (qué tiene un reproductor serio)
Poweramp / Neutron / Musicolet: EQ gráfico + preajustes + preamp, tono (graves/agudos), balance, mono, crossfeed, limitador, ReplayGain, sin cortes (gapless), fundidos, velocidad y tono, saltar silencios, temporizador, cola editable, listas y favoritas, listas inteligentes, letras sincronizadas, reanudar donde se quedó.

## Decisiones
- DSP propio (AudioProcessor de Media3) en vez de `android.media.audiofx.Equalizer`: 10 bandas exactas en todos los móviles, curva dibujable, probado en JVM. Recibe PCM 16 bits (sin salida float: con ella Media3 se salta los procesadores).
- ReplayGain con `player.volume` al cambiar de pista (sin desfase por el búfer); ganancias positivas topadas a 0 dB.
- `ma.py` no existía: escrito en `.modo-absoluto/ma.py` con la interfaz de v4.3.

## Caminos cerrados
- Compilar Android aquí: dl.google.com bloqueado (ni AGP ni androidx). Se compila en Actions.
- Gradle local con plugin Kotlin: Maven Central da 404 intermitentes vía proxy. Se usa kotlinc suelto (`scratchpad/pruebas.sh`).

## Hecho
- Motor, procesador, ajustes, ReplayGain, datos de usuario, servicio, cola, interfaz completa.
- Pruebas JVM 25/25 (local con kotlinc: `scratchpad/pruebas.sh`; en CI `testDebugUnitTest`).
- Revisión por ataque: 6 (4 graves) → todos arreglados: chasquidos (estructura fija + fundido cruzado, pruebas ×828→<3), humo que aceptaba falsos («Vaciar», 3× «Graves»), RG sin releer biblioteca (caché library2.json + margen 6 dB), claves de preajuste repetidas, índices tras filtrar (keptQueue + índice real en listas), MediaButtonReceiver + URI por mediaId.
- Compilación 1 de Surco 2 en CI: verde a la primera (tests unitarios incluidos).
- Humo 1: falló al abrir «Sonando»: uiautomator no vuelca con la música sonando (la barra se mueve) y deja el volcado anterior.
  → REGLA: volcar la interfaz solo en pausa (KEYCODE_MEDIA_PAUSE) y borrar /sdcard/ui.xml antes de cada volcado.
- Humo 2: los toques al minirreproductor caían en el teclado (el buscador lo dejaba abierto: «sellonorte » con espacio). → la app esconde el teclado al reproducir desde la búsqueda.

# Surco 2.1 — auditoría, salida sin pérdida y nueva estructura

Encargo: «Auditoría de diseño y lógicas, reproducción y motor de sonido sin pérdida de calidad ninguna, orden del uso de la app para una mejor experiencia visual principal de la app nose si es lo mejor esa pantalla».

## Criterios
- C1: sin pérdida: FLAC/ALAC/WAV de >16 bits llegan a AudioTrack en coma flotante (log «Surco salida: … coma flotante»), MP3 sigue en 16 bits (sin cortes); en JVM, 24 bits → float exacto y −138 dBFS sobrevive al motor neutro.
- C2: CI verde: JVM + debug/release + humo con barra inferior (Inicio con «Seguir escuchando» tras reiniciar, Biblioteca con álbumes, búsqueda, EQ, favorita).
- C3: auditoría independiente: FATAL/GRAVE arreglados o rebatidos con prueba (AUDITORIA.md).
- Caso reservado: WAV float 32 bits 192 kHz estéreo con EQ.
- Línea base: por código, todo salía en 16 bits (DefaultAudioSink sin float: ToInt16 antes de los procesadores; el FLAC se decodificaba en 16). La medición en emulador cayó por un ANR del lanzador («Pixel Launcher isn't responding»), no de Surco → REGLA: ui() pulsa «Wait» si sale ese diálogo.

## Decisiones 2.1
- Media3 (incluida la 1.11.1) se salta los procesadores con salida float y su recorte de huecos solo existe en 16 bits. Solución: `SurcoAudioSink` (ForwardingAudioSink) procesa en float lo de alta resolución y deja lo de 16 bits por el camino normal; `SurcoAudioRenderer` pide float a los decodificadores sin pérdida y 16 bits a los con pérdida.
- Velocidad y tono por AudioTrack (vale para ambos caminos). Saltar silencios solo actúa en 16 bits.
- Navegación inferior Inicio · Biblioteca · Etiquetas · Listas; búsqueda como pantalla propia.
- WAV: el bloque INFO trae el nulo final dentro del texto («Prueba Cuatro\u0000»): `cleanText` lo quita (visto con jaudiotagger aquí).
- Compilación de los arreglos de la auditoría: falló por un import (`DefaultShuffleOrder` es `ShuffleOrder.DefaultShuffleOrder`) y dos llaves sueltas al reescribir Screens.kt.
  → REGLA: antes de cada push, `scratchpad/sintaxis.sh` (kotlinc sobre todo el código, solo errores de sintaxis) y comprobar en las fuentes de Media3 dónde vive cada clase nueva.
- Humo de 2a560f6: el toque al buscador de Inicio se perdió una vez: la barra «Leyendo etiquetas» aparecía encima y lo empujaba. → la barra va debajo del buscador, y la prueba comprueba que la búsqueda se abrió (hasta 3 toques).
- Humo de cd4f355: todo verde salvo el caso reservado: el WAV float de 192 kHz salía como «32 bits» enteros. Causa: Media3 1.5.1 (WavUtil) trata WAVE_FORMAT_EXTENSIBLE por profundidad y no mira el subformato 0x0003 (float): esos WAV sonarían como ruido. ffmpeg (y muchos programas) escriben así cualquier WAV de más de 16 bits o float. → `SurcoExtractorsFactory` envuelve el WavExtractor y corrige el formato a float; prueba `wavEnComaFlotanteExtensible`.
- Humo de b1f0ecd: VERDE entero (pestañas, seguir escuchando, búsqueda, favorita, cola, EQ, persistencia, FLAC 96 kHz y WAV float 192 kHz en coma flotante, MP3 en 16 bits). APK 2.1 entregado (versionCode 21).

# Surco 2.1 — sin errores

Encargo: «MODO ABSOLUTO / Sin errores».

## Criterios
- C1: Kotlin compila sin un solo aviso (`allWarningsAsErrors = true`). Base: avisos de iconos obsoletos (`Icons.Default.QueueMusic/PlaylistAdd`).
- C2: Android Lint con 0 errores en CI (`lintDebug` + `.github/scripts/lint-surco.py`, que falla con cualquier error). Base medida: 12 errores, 11 avisos.
- C3: CI verde: JVM + debug/release + lint + humo en emulador.
- Caso reservado: las pruebas unitarias también compilan sin avisos.

## Decisiones
- `produceState` → `remember` + `LaunchedEffect` en los cinco sitios (fondo de «Sonando», temporizador, letra, portadas, `rememberOffMain`): lint no veía la asignación a `value`. Las anotaciones solo enseñaron tres: GitHub corta en 10 por paso, y los otros dos quedaron ocultos.
- `@OptIn(UnstableApi::class)` solo donde se usa `MediaItemsWithStartPosition` (restoreQueue, restoreIfEmpty), no en el archivo entero.
- Versiones fijadas (Media3 1.5.1, targetSdk 35…): `GradleDependency` y `OldTargetApi` pasan a informativos con su motivo. El sink y la corrección de WAV dependen del comportamiento medido de 1.5.1; subirlas es otro encargo con su propia prueba.
- Servicio exportado a propósito (`tools:ignore="ExportedService"`): es lo que pide MediaSessionService.

## Hecho
- C1 y caso reservado: run 40164847353, compilación de Kotlin y de pruebas sin avisos.
- Lint medido: 12 errores (3 ProduceStateDoesNotAssignValue, 9 UnsafeOptInUsageError) y 11 avisos (7 GradleDependency, OldTargetApi, ExportedService, 2 ObsoleteSdkInt) → corregidos o anotados.
- Trampa: `::error::` se convierte en anotación y no sale en el log; el script imprime cada error también en claro.
- Revisión por ataque: 1 FATAL (dos produceState ocultos por el tope de 10 anotaciones; ya corregido) y 2 MENORES (rutas cortadas en lint-surco.py; runs en paralelo que se pisaban `apk-surco`) → corregidos: rutas desde la raíz con `file=`/`line=`, y `concurrency` que cancela el run anterior de la misma rama.
