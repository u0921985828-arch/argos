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

# Surco 2.1 — diseño

Encargo: «¿Y de diseño?».

## Criterios
- C1: recorrido de 29 pantallas en el emulador con captura y volcado (`.github/scripts/diseno-surco.sh`), con la letra normal y ×1,3; las capturas quedan en la rama `capturas-surco`.
- C2: comprobador medible (`.github/scripts/diseno-surco.py`): tamaño de lo tocable (< 40 dp falla; 40–47 aviso), contraste WCAG (4,5:1; 3:1 en grande), solapes reales entre tocables, nodos fuera de pantalla. Línea base: 82 fallos con letra normal, 16 con ×1,3 (antes de calibrar: casi todo eran solapes de los objetivos de 48 dp que Compose declara).
- C3: revisión visual independiente (un revisor que no construyó la interfaz) con FATAL/GRAVE arreglados.
- Caso reservado: letra del sistema ×1,3.

## Hecho
- Visto en las capturas (no en el código): el título de «Sonando» y el de la pantalla de permiso salían en NEGRO sobre fondo oscuro (contraste 1,1:1). Causa: fuera del `Scaffold` el color de contenido de Compose es negro. `SurcoTheme` lo fija a `Paper`.
- Chips de etiquetas y de acciones de 34 dp → 40 dp mínimo y 8 dp de hueco; chips de «Explorar por etiquetas» a 40 dp.
- «Más escuchadas» se cortaba con letra ×1,3 → dos líneas.
- Filas de opción de Ajustes sin señal de que se pulsan → flecha a la derecha.
- Calibración del comprobador: piezas de deslizador (< 10 dp) no cuentan; botón desactivado sin contraste; el botón de borrar dentro del campo de texto no solapa; «ajustado» (40–47 dp) solo avisa.
- Revisión visual independiente (58 capturas): 1 FATAL, 8 GRAVES, 3 MENORES. Aplicado: deslizador propio (`SurcoSlider`: pulgar redondo, pista continua, sin marcas de paso) en «Sonando», ecualizador y velocidad; «Sonando» sin la ficha por triplicado (línea de salida solo si recorta o cambia la señal; chips sin el grupo Archivo); «Seguir escuchando» solo sin minirreproductor; «Otra Banda, Banda Surco y 2 más» en vez de «Varios artistas» (una sola función); mismo color y 40 dp en los 9 chips (`chipColors`, `ChipHeight`); Ajustes con una frase por opción y `OptionRow` alineado a 20 dp; letra ×1,3: chips de Biblioteca sin recuento (total en la cabecera), tarjetas y etiquetas a dos líneas, crossfeed desliza, placeholder de una línea; `TabHeader` en las cuatro pestañas; Inicio vacío solo con el aviso; icono en la pantalla de permiso; listas automáticas vacías en gris y con iconos que no dicen «oculto»; reinicio de deslizador con icono solo si hace falta; «EQ» fijo y «Más» arriba en «Sonando».
- No aplicado (DECISIONES): unificar radios en dos constantes — cosmético, sin efecto medible.
- Trampa: la rama se compartió con otro trabajo (tests del servidor) y el push rebotó; rebase antes de subir. Un push de solo ESTADO.md cancela el run anterior (concurrency): subir documentación junto con el código.
- Medido en las capturas nuevas (commit 3109293): 0 fallos con letra normal y 0 con ×1,3 (con el comprobador final; la base, con el mismo comprobador sobre las capturas de partida, era 6 y 5: título negro en «Sonando», título negro en permiso y chips de 34 dp). El comprobador ya no cuenta lo recortado por el borde de una lista que se desplaza.
- El CI pasa a estricto: un fallo medible de diseño tumba la compilación. Las capturas se publican antes de comprobar, para verlas si falla.
- Deslizadores con el cero en medio (graves, agudos, balance) sin relleno: a 0 dB la pista medio cobre parecía un valor.
- Prueba de humo: el toque sobre el texto de ayuda del buscador a veces no daba el foco y lo escrito se perdía (run 40295895769, el mismo código había pasado antes). → Se pulsa el propio campo (EditText) y hay 3 intentos.

# Surco 2.2 — reproductor de calidad, interfaz limpia, todo lo demás en Ajustes

Encargo: «Quiero que mejores el reproductor a niveles exagerados, la UI debe ser limpia y simple, pero en ajustes está todo lo demás, un reproductor y interfaz de calidad».

## Criterios
- C1: «Sonando» limpia: sin líneas técnicas por defecto y ≤ 12 elementos tocables (base medida: 16 tocables, 17 textos con formato, salida y chips de etiquetas). Lo comprueba la prueba de humo.
- C2: todo lo demás en Ajustes, por secciones (Sonido, Reproducción, Biblioteca, Interfaz, Tus escuchas, Acerca de), y funcionando: la prueba de humo activa «Detalles técnicos» en Ajustes → Interfaz y comprueba que aparecen en «Sonando»; el ecualizador se abre desde «⋮» y su preajuste sobrevive al reinicio desde Ajustes → Sonido.
- C3: funciones nuevas con pruebas JVM (fundido entre canciones, discos sin pausas, índice de letras, acentos legibles, tus escuchas) y CI verde: compilación sin avisos, lint 0, humo, diseño estricto con letra normal y ×1,3.
- Caso reservado: la pasada con letra ×1,3 corre con el acento Violeta elegido en la pasada anterior (tiene que sobrevivir al reinicio) y el diseño estricto tiene que dar 0 con él.

## Decisiones
- Fundido entre canciones «baja y sube» (curva de potencia constante), no un cruce con solape: ExoPlayer toca una pista cada vez y un segundo reproductor rompería la sesión, el sin cortes y el camino sin pérdida. Por defecto respeta los discos (mismo álbum y misma carpeta): los directos siguen sin pausas.
- El acento es estado de Compose detrás de `Copper` (un getter): cambiarlo repinta todo sin tocar los cien sitios que lo usan. «Del disco» se aclara (manteniendo el tono) hasta 4,5:1 sobre el fondo.
- `SwitchRow`: la fila entera es el interruptor (antes solo el interruptor, un blanco pequeño).
- Pendiente a propósito: Android Auto, edición de etiquetas, salida bit-perfect por USB (sin aparato para medirla), cruce con solape.
- Letras de LRCLIB (pedidas por Eddie a mitad de tarea): opcionales y apagadas por defecto (Ajustes → Biblioteca), con caché en disco (lo encontrado para siempre, «no está» 7 días), búsqueda exacta y después por artista y título filtrada por duración (± 3 s). Cliente y JSON sin Android, probados en JVM y contra el servicio de verdad en el CI (`SURCO_RED=1`). Permiso INTERNET solo para esto; la privacidad de Acerca de lo dice.
- Revisión por ataque (1 FATAL, 3 GRAVES, 6 MENORES), todo aplicado salvo lo marcado:
  - FATAL: el recorrido miraba `selected` en la muestra de color; con rol de botón de opción Compose publica `checked`. → se miran los dos.
  - El índice de letras tapaba el «⋮» de cada fila → la lista deja libre su franja; letras en orden de alfabeto (# primero, Ñ tras N), tamaño fijo (con letra ×1,3 se pisaban) y la clave del gesto es el índice entero (al releer la biblioteca saltaba a sitios viejos).
  - «Del disco» solo se medía contra el fondo; contra Panel2 (pestaña activa, píldoras) bajaba a 3,8:1 → se mide contra Panel2; las portadas grises o en blanco y negro dan el acento de siempre (antes, un salmón inventado).
  - Fundido con «Repetir una» dependía de la canción vecina → sin fundido al repetir una; saltar a mano durante un fundido entra entera; volver al principio a mano no funde; duración desconocida o corta (MP3 VBR) ya no apaga la cola; tick a 50 ms solo cerca de los bordes (500 ms en medio) y sin mandar cambios de volumen de menos de medio punto.
  - Arrastrar la portada hacia abajo para cerrar se comía el desplazamiento de «Sonando» con letra grande → vuelve a ser solo horizontal.
  - La prueba de humo dejaba puestos los detalles técnicos y el recorrido capturaba «Sonando» con ellos → el recorrido los quita antes.
  - No aplicado: carpeta con ruta vacía no se puede excluir (no existe en MediaStore); «Tus escuchas» cuenta también las carpetas excluidas (a propósito: son escuchas reales).
- Catálogo por BPM y tonalidad (pedido a mitad de tarea): análisis en el móvil de las canciones sin esas etiquetas (`data/PureAnalysis.kt`: tempo por autocorrelación de ataques con peine y desempate de doble tiempo; tonalidad por cromagrama con Goertzel y perfiles de Krumhansl–Kessler; `data/AudioAnalyzer.kt` decodifica 30 s desde el 30 % a ~11 kHz mono; `data/AnalysisStore.kt` guarda por ruta y fecha). La etiqueta manda: solo se rellena lo que falta. Tonalidades normalizadas («Am», «A minor», «8A», «1m», «La menor» son la misma) y ordenadas por la rueda Camelot; sección «Ritmo y tono» en Etiquetas, BPM y Tonalidad en Inicio y como orden de cualquier lista; «Para mezclar» en el menú de una canción (tonalidad vecina en la rueda y ±6 % de tempo, también a mitad o al doble).
  - Medido en JVM: batería sintética a 90, 100, 120, 128, 140 y 174 BPM → ±1 BPM; Do mayor, La menor armónica y Re mayor por acordes → acierto; 18 formas de escribir una tonalidad.
  - Dos desvíos: con logaritmo puro de la energía, el charles sobre silencio pesaba como el bombo y 90 BPM salía 180,5 → compresión suave respecto a la media. Con ventanas de 11 ms, 128 BPM salía 64 (el pico caía entre dos ventanas) → ventanas de 5 ms y pico buscado a ±1.
