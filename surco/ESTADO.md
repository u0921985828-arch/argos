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

# Surco 2.3 — Android Auto

Encargo: «¿Android Auto?».

## Criterios
- C1: un navegador de Media3 (el protocolo de Android Auto) conectado desde FUERA de la app, en el emulador, ve 4 pestañas (Para ti, Listas, Álbumes, Artistas), entra en «Disco de Prueba» (2 canciones reproducibles), encuentra «Prueba Dos» buscando y, al tocar la 2.ª del disco, suena el disco entero desde ella (`androidTest/AutoTest.kt` + `.github/scripts/auto-surco.sh`). Base: el servicio era `MediaSessionService` (sin árbol: Auto no lista Surco).
- C2: árbol, contexto de reproducción y búsqueda probados en JVM (`BrowseTest`).
- C3: CI verde (compilación sin avisos, lint 0, humo, diseño estricto) con el servicio nuevo.
- Caso reservado: la búsqueda por voz («pon … en Surco») por el camino de Media3 sin canción (solo la frase) y por el intent del asistente en el móvil.

## Decisiones
- `MediaLibraryService` con el árbol armado de la caché que deja la app (no relee etiquetas en el coche), con los mismos filtros (carpetas excluidas, audios cortos) y lo analizado; se rehace como mucho cada 30 s.
- Tocar una canción en el coche hace sonar su carpeta desde ella (`onSetMediaItems`), como en la app; «Mezclar todo» la biblioteca en aleatorio.
- Álbumes en cuadrícula y lo demás en lista (indicaciones de estilo de contenido de Android Auto).
- La prueba del coche corre contra el APK de depuración (la prueba comparte Media3 con la app; con R8 los nombres no casan), instalado encima del de release con la misma firma para conservar la biblioteca ya leída.
- Sin probar en un coche ni en el Desktop Head Unit (no hay en el CI). Para verlo en un coche con un APK que no viene de Play: Android Auto → Ajustes → pulsar 10 veces en «Versión» → Desarrollador → «Fuentes desconocidas».
- Revisión por ataque (3 GRAVES, 5 MENORES), aplicada:
  - La voz con «de», «by», «the»… no encontraba nada y una lista vacía paraba lo que sonaba y vaciaba la cola → palabras de relleno fuera, «casi todo» si nada casa con todo, título/artista/álbum del asistente si los da, y error (no lista vacía) sin coincidencia.
  - La frase del asistente se repetía al recrearse la actividad y se perdía sin controlador → solo en la primera creación y esperando al controlador.
  - La prueba solo recorría el protocolo de Media3, no el de Android Auto → segunda prueba con el MediaBrowser del sistema (`MediaBrowserService`), tocar por id y pedir por voz, y que una frase sin coincidencia no vacíe la cola. Corre dentro del proceso de la app (es una prueba instrumentada), pero por el mismo enlace que el coche.
  - El aleatorio se heredaba al tocar un disco en el coche → se quita, como en la app.
  - El coche no puede leer portadas de MediaStore → `ArtProvider` (`content://com.eddie.surco.arte/<id>`, JPEG de 512 px en caché, solo lectura).
  - Lectura del árbol con cerrojo (el coche pide cuatro pestañas a la vez); futuros que fallan en vez de quedarse colgados con el servicio cerrándose; logcat de la prueba guardado.
- Prueba de humo: el buscador del emulador volvió a perder lo escrito (run 40326764164, ahora con el análisis de BPM en marcha al arrancar) → se espera a que el campo tenga el foco, se comprueba que lo escrito llega y hay 5 intentos con más calma cada vez. De paso: «1 canción encontrada» (decía «encontradas»).
- CI verde (run 40328610266): «OK (2 tests)» en el emulador — navegador de Media3: 4 pestañas, 2 álbumes, búsqueda 1, suena «Prueba Uno» en su disco; camino de Android Auto (MediaBrowser del sistema): 4 pestañas, disco de 2, toque y voz bien, y una frase sin coincidencia no vacía la cola. Diseño estricto 0 y 0.

# Surco 2.4 — widgets

Encargo: «Quiero widgets también».

## Criterios
- C1: un anfitrión de widgets (como un lanzador) pone el widget de Surco en el emulador, el widget enseña una canción, al pulsar «reproducir» suena y el botón pasa a «Pausar», y al pulsarlo otra vez vuelve a «Reproducir» (`androidTest/WidgetTest.kt`). Base: Surco no tenía widget.
- C2: tres tamaños (2×1, 4×1 y 4×2 con favorita) con imagen de cada uno en la rama `capturas-surco` (`widget_*.png`), revisada a ojo.
- C3: CI verde (compilación sin avisos, lint 0, humo, diseño estricto, Android Auto).
- Caso reservado: con el servicio parado, «reproducir» en el widget reanuda la última cola (el toque va por el receptor de teclas multimedia de Media3 y su reanudación).

## Decisiones
- RemoteViews sin dependencias nuevas (Glance sería otra biblioteca y otra versión que fijar). En Android 12+ el lanzador elige el diseño por tamaño (RemoteViews por tamaños); antes, se elige con el tamaño que da el lanzador.
- Los mandos son teclas multimedia al receptor de Media3: funcionan con la app cerrada.
- La favorita va por un receptor sin exportar: el del widget tiene que estar abierto al sistema.
- El acento del widget es el de Ajustes; «Del disco», de la portada (legible sobre el fondo del widget).
- Revisión por ataque (3 FATALES, 2 GRAVES, 5 MENORES), aplicada:
  - El receptor de teclas de Media3 solo deja pasar PLAY y PLAY_PAUSE: pausar, anterior y siguiente no hacían nada → el botón central es PLAY_PAUSE por el receptor (arranca y reanuda con la app cerrada) y anterior/siguiente van directos al servicio, como en la notificación de Media3.
  - Lint: OptIn de UnstableApi en las teclas; sin pesos anidados (fila de títulos y favorita en RelativeLayout) ni alineación por línea base; diseños que caben en su celda (2×1 desde 56 dp, 4×2 desde 124 dp).
  - La prueba podía pulsar el diseño inicial (sin acción) → espera a la descripción que solo pone el proveedor.
  - «Reproducir» sin nada que reanudar abría un servicio sin cola y Android lo cerraba → sin canción, el botón abre Surco.
  - Actualizaciones desordenadas (una corrutina por evento en paralelo) → un solo hilo y se pinta el último estado; un fallo del widget no tira el servicio de música; sin widgets en el aparato no pasa nada.
  - Favorita: color y acción se fijan siempre (el lanzador reaplica sobre lo anterior). Portada y acento, una vez por canción; la caché de la biblioteca solo se lee si cambia la canción guardada. Textos en recursos.
- CI verde (run 40339727282): «OK (4 tests)» — widget: enseña «Prueba Dos», reproducir y pausar desde el widget; Android Auto por los dos caminos; diseño estricto 0 y 0. Capturas de los tres tamaños revisadas a ojo.

# Surco 2.5 — pop-ups: notificación, hojas y avisos

Encargo: «Los pop ups que se usan en el móvil se podrían modificar también?» → las tres cosas: diálogos de la app, avisos breves y notificación de música.

## Criterios
- C1: la notificación de música lleva dos botones propios, «Marcar favorita» y «Activar aleatorio» (`dumpsys notification`), y pulsar el de aleatorio en la persiana del emulador lo activa (el botón pasa a «Aleatorio activado») y lo vuelve a quitar. Base: solo anterior / reproducir / siguiente de Media3.
- C2: ningún `AlertDialog` en la app: temporizador, velocidad, nombres, añadir a lista y confirmaciones de borrado son hojas de Surco (`SurcoSheet`), capturadas en el recorrido de diseño con `--estricto` (0 fallos).
- C3: ningún Toast con la app a la vista: marcar favorita saca la franja «Añadida a favoritas» con «Deshacer»; «Deshacer» la quita (marcarla otra vez vuelve a decir «Añadida») y la franja se va sola.
- Caso reservado: la hoja de nombre con el teclado abierto no queda tapada (captura `lista-nueva` del recorrido).

## Decisiones
- Botones de la notificación con comandos propios de Media3 (`SessionCommand` + `setMediaButtonPreferences`): los mismos salen en la pantalla de bloqueo, los mandos multimedia del sistema y el coche. Iconos de Media3 (corazón y aleatorio, lleno o vacío según el estado).
- Icono propio en la barra de estado (la nota de Surco) y canal «Música» en español.
- `SurcoSheet` sobre `ModalBottomSheet`: fondo de la app, título grande, botones de 48 dp abajo (principal en el acento; lo que borra, en rojo). Se cierra deslizando o tocando fuera.
- Avisos (`ui/Notice.kt`): uno a la vez, 3,5 s (6 s con acción), encima del minirreproductor; en «Sonando», arriba, para no tapar los mandos. El servicio usa la franja si la app se ve y Toast si no (una canción rota con la app cerrada sigue avisando).
- Borrar una lista sigue pidiendo confirmación y además se puede deshacer.
- Revisión por ataque (0 bloqueantes, 3 importantes, 6 menores), aplicada:
  - «Deshacer»/«Ver» quedaban debajo de la cola y del menú de canción (cada hoja es su propia ventana) → cada hoja lleva su franja.
  - Deshacer «quitar de la cola» en aleatorio la devolvía a otro sitio del orden → en aleatorio, aviso sin «Deshacer».
  - La captura del aviso podía cogerlo saliendo (falso contraste) → se lanza desde el corazón del minirreproductor y dura 8 s con acción; caducidad propia del aviso (cambiar de pantalla no reinicia la cuenta) y 6 s para los largos.
  - El canal «Música» no se aplicaba al actualizar (el de Media3 ya existía) → canal `musica` nuevo y se borra el viejo.
  - La persiana plegada no enseña los botones propios → el humo despliega los ajustes rápidos.
  - «Aleatorio activado» describía el estado, no la acción → «Desactivar aleatorio».
  - Foco del campo de nombre dentro de la hoja; sin franja huérfana con «Sonando» abierta y sin canción; sombra y borde en la franja.
- CI: un push cambió la lambda final de `Notice.show` al parámetro nuevo `long` (no compilaba) → la acción vuelve a ser el último parámetro. El humo con música tardaba tanto en la persiana que «Prueba Dos» (20 s) se acababa → se prueba en pausa. Desde octubre de 2026 `ubuntu-latest` es 26.04: instalar ffmpeg tardó 30 min y la descarga del emulador se cortó → el humo va fijado en `ubuntu-24.04`.
- CI verde (run 40376253523): notificación con «Marcar favorita» y «Activar aleatorio»; pulsado en la persiana → «Desactivar aleatorio» y vuelta; aviso «Añadida a favoritas» → Deshacer → otra vez «Añadida»; «Sonando» con 11 tocables; recorrido de 38 pantallas ×2 con diseño estricto 0 (también `aviso`); «OK (4 tests)». Caso reservado: la hoja «Lista nueva» queda encima del teclado (captura `18-lista-nueva`), revisado a ojo.

## Surco 2.5 — «Sonando» opaca y adaptable
Encargo (con captura del móvil real): «No es responsive no? Y el degradado a opaco no me mola mucho» y «el huecazo de aire que hay debajo del menú».
- Causa: el fondo era un degradado que empezaba con alfa 0,55 y dejaba ver Inicio por debajo (el «Buenas noches» y una portada asomando); la portada era fija (380 dp) y lo que sobraba quedaba abajo.
- Fondo liso y opaco: un 18 % del color de la portada sobre el de la app (empieza con el de la caché: al girar no pasa por el gris).
- Vertical: la portada toma lo que dejan la barra y los mandos (que crecen con la letra del sistema); los mandos van abajo y lo que sobra se reparte arriba y abajo de la portada. Horizontal o tableta: portada a la izquierda y mandos a la derecha.
- Revisión por ataque (1 importante, 2 menores), aplicada: en horizontal los huecos dejaban pasar toques a Inicio (`pointerInput` vacío en el fondo); la letra abierta se cerraba al girar (`rememberSaveable`); parpadeo del fondo al girar.
- El diseño estricto cazó además que, en horizontal, TalkBack leía lo de Inicio que asomaba por los huecos → un nodo de accesibilidad (`paneTitle = "Sonando"`) que cubre toda la pantalla.
- Prueba nueva FONDO en `diseno-surco.py` (bordes de un solo color en «Sonando», en horizontal y con letra); con la captura del móvil real y con la de antes falla. Captura en horizontal girando el emulador.
- CI verde (run 40398712414): 39 pantallas ×2 con diseño estricto 0, incluido FONDO; humo y «OK (4 tests)».
- Pendiente de la CI: instalar ffmpeg en el ejecutor de GitHub tarda ahora ~30 min (también en 24.04); habría que cachearlo o generar la música de prueba sin apt.

# Surco 2.6 — listo para marcas (ronda 1 de 4)

Encargo: «Quiero que sea el mejor reproductor de música de Android, que lo quieran las marcas de móviles incluso». Rondas elegidas, en orden: listo para marcas → sonido de audiófilo → funciones que piden → conectado. Y, a mitad: «en la biblioteca molaría hacer zoom para que las cuadrículas vayan de ×2 carátulas de ancho hasta ×5, respetando las cuadrículas y las distancias; que toda la app sea agradable de usar».

## Criterios
- C1: ningún texto de la interfaz fijo en el código: inglés por defecto (`values/`) y español (`values-es/`) con las mismas claves; el recorrido en inglés (`idioma-surco.sh`) falla si queda una palabra de la interfaz en español. Base: ~500 textos en español en el código, 0 en inglés.
- C2: idioma por app (Android 13+): en Ajustes del sistema y en Ajustes → Interfaz → Idioma; las pruebas ponen español con `cmd locale set-app-locales`.
- C3: 10.000 canciones más en el emulador: sin caídas ni ANR, Inicio llega a enseñarlas todas; arranque en frío, lectura de etiquetas, fotogramas al desplazar y memoria apuntados en `rendimiento.txt`. Base: nunca medido con más de 4.
- C4: la cuadrícula de álbumes se pellizca con dos dedos de 2 a 5 portadas por fila y vuelta (`GridZoomTest`, UiAutomator), con capturas de 2 y 5.
- C5: diseño estricto también en inglés y con la regla nueva SIN NOMBRE (nada tocable sin nombre para TalkBack; cazó el interruptor del ecualizador).
- Caso reservado: la pantalla «Sonando» y Ajustes en inglés, sin textos cortados (capturas `capturas-en/`).

## Decisiones
- Inglés por defecto: es lo que ve todo el mundo que no tenga el móvil en español; el español queda en `values-es/` idéntico al de antes (las pruebas lo buscan letra por letra).
- La parte pura (etiquetas, Android Auto) no puede usar R: `data/Texts.kt` con el español por defecto (pruebas JVM) y `data/TextsAndroid.kt` con un `when` explícito a los recursos (sin `getIdentifier`, que lint marca).
- `SurcoApp` (Application) conecta los textos antes que cualquier pantalla, servicio o widget; `Res.str` fuera de Compose.
- Los identificadores guardados (preajustes, acentos, claves de etiquetas) no cambian: la etiqueta visible sale de recursos.
- Material You como acento «Del sistema» (Android 12+), aclarado hasta que se lea sobre el fondo, como «Del disco».
- Cuadrícula: hueco y margen en proporción a la portada (12/16 dp con 2 por fila → 6/10 con 5); con 4–5 por fila la portada pierde el botón de reproducir (la taparía) y la letra baja. Se recuerda en Ajustes.
- Hecho con cinco agentes en paralelo (reproductor, ajustes, biblioteca y ecualizador, datos y coche, comunes y servicio), cada uno con sus archivos y su XML.
- Revisión por ataque (2 bloqueantes, 1 importante, 2 menores), aplicada: la prueba del pellizco buscaba algo «desplazable» que con dos álbumes no lo es (ahora pellizca la tarjeta); en horizontal o tableta la cuadrícula se quedaba en 2 por fila (el ajuste es para el ancho de un móvil y se escala con el ancho); el acento «Del sistema» no llegaba al widget; la lista de datos de la prueba en inglés tapaba «Surco»; un «Sistema» restaurado en Android < 12.
- CI: las pruebas instrumentadas comparaban con «Reproducir»/«Pausar» literales y contaban también las capturas de la cuadrícula → leen los textos de la app y las capturas van aparte. El arreglo del índice de man dejó la instalación de ffmpeg en segundos.
- Medido en el emulador (run 40427734203), con 10.004 canciones: MediaStore las ve en 36 s; arranque en frío 2.045 ms con la biblioteca sin leer y 1.296 ms con caché; lectura de etiquetas de todas 258 s (en segundo plano, con la app usable); 140 MB de memoria (PSS); sin caídas ni ANR. Al desplazar, 84 % de fotogramas lentos: el emulador del CI pinta por software (swiftshader), así que la cifra solo sirve para comparar versiones; hay que medirlo en un móvil.
- Inglés: 9 pantallas con 0 textos en español y diseño estricto 0; «OK (5 tests)» con la cuadrícula pellizcada de 2 a 5 y vuelta (capturas `rejilla_2.png` y `rejilla_5.png`).
- Pendiente de esta ronda: medir en un móvil real; leer las etiquetas de 10.000 canciones en 258 s es mejorable (lectura en paralelo limitada a 4).
- CI verde (run 40431069488, segundo intento: el primero se cayó antes de empezar, con el buscador del emulador perdiendo lo escrito, el fallo intermitente conocido; el mismo código de la app había pasado entero en 40427734203): humo, 39 pantallas ×2 y 9 en inglés con diseño estricto 0, «OK (5 tests)», 10.004 canciones sin caídas.

# Surco 2.7 — lo pendiente (rondas 2, 3 y 4)

Encargo: «Cumple lo pendiente» (las tres rondas que quedaban y lo pendiente de la 2.6) y, a mitad, «la visión de carátulas en todos los apartados de biblioteca, por carátulas y en lista» y «en vez de 5 carátulas incluso a 8, siguiendo la secuencia de Fibonacci».

## Hecho
- **Lectura de 10.000 canciones**: los lectores de etiquetas ya no esperan en tandas de 32 a la más lenta, hay de 4 a 8 (2 por núcleo) y, con miles de canciones, la interfaz se rehace cada 4 s y no cada segundo (rehacer grupos de 10.000 cada segundo le quitaba CPU a la lectura). El tiempo sale en el log (`SurcoScan`) y `rendimiento-surco.sh` espera a esa línea (antes podía medir antes de que empezara).
- **Perfiles de arranque**: `profileinstaller` + `baseline-prof.txt` (el código de la app y los perfiles de Compose y Media3 se compilan al instalar, también fuera de Play).
- **Bit-perfect por USB** (Android 14+, Ajustes → Sonido): `PureMixer.pickMixer` elige el mezclador del DAC (bit-perfect a la frecuencia y codificación de la canción o, si no hay, uno normal a su frecuencia: sin remuestrear); `OutputRoute` lo pide con `setPreferredMixerAttributes` en cada canción. Probado en la JVM; sin DAC en el emulador.
- **Salida a la vista**: altavoz, cable, Bluetooth o USB con su nombre y, con DAC, «bit-perfect a 44,1 kHz» o «sin remuestrear». El códec Bluetooth no lo da Android a las apps.
- **Cruce con solape**: la cola de la que se va suena en un segundo reproductor (preparado unos segundos antes, sin foco ni sesión) mientras entra la siguiente; potencia constante (`OverlapTest`); `CrossfadeTest` cuenta en el emulador dos reproductores activos a la vez y que la cola se apaga.
- **Editor de etiquetas** (menú de la canción → «Editar etiquetas»): 13 campos, escribe en el archivo con jaudiotagger sobre una copia y la vuelca con el permiso del sistema por archivo (`createWriteRequest`); Opus no. Prueba de humo: cambia el título de «Prueba Tres» con «Allow», lo comprueba en MediaStore y lo deja como estaba.
- **Listas M3U/M3U8**: importar (rutas absolutas, relativas, de Windows, file://, nombres; Windows-1252 y BOM) y exportar.
- **ListenBrainz** (opcional, apagado): «sonando ahora» y escuchas con su regla (mitad o 4 min), cola sin conexión, token fuera de la copia de seguridad.
- **Copia de seguridad**: un JSON con favoritas, listas, escuchas, ajustes y lo analizado; al restaurar en otro móvil traduce los ids por las claves estables. De paso, la reconciliación reconoce ids que en el móvil nuevo son OTRA canción (lo que traía la copia automática de Android).
- **Mosaico de ajustes rápidos** y **accesos directos** del icono (mezclar todo, favoritas, buscar), probados en la prueba de humo.
- **«Enviar a»** teles y altavoces: DLNA/UPnP (descubrimiento SSDP, servidor HTTP propio con rangos, SOAP por socket, un `SimpleBasePlayer` remoto) y Chromecast (CastPlayer con el receptor por defecto; sin servicios de Google, solo DLNA). La misma sesión cambia de reproductor. Probado de extremo a extremo en la JVM con un renderizador falso; sin aparatos en el emulador.
- **Carátulas o lista en todos los apartados** de Biblioteca (Canciones, Álbumes, Artistas, Carpetas), cada uno lo recuerda; la cuadrícula se pellizca en **2-3-5-8** portadas por fila (Fibonacci); a 8 solo quedan las portadas, con el nombre para TalkBack. `GridZoomTest` llega a 8 y vuelve a 2.
- **Reloj**: sin app propia para Wear OS. El reloj ya enseña la sesión de Surco del móvil con sus mandos y los botones propios (favorita y aleatorio); una app de reloj con biblioteca propia sería otro proyecto (otro módulo, otra música en el reloj).
- Hecho con cinco agentes en paralelo (M3U, ListenBrainz, copia de seguridad, editor de etiquetas, «Enviar a»), cada uno con sus archivos; el coordinador cableó servicio, manifiesto y dependencias.
- Revisión por ataque: 2 bloqueantes de la prueba de humo («Sonando» abierta tapaba el editor; el mosaico dependía de que siguiera sonando una canción de 20 s), 4 importantes (el cruce no se cortaba con una pausa que no fuera la del botón; hueco al empezar el cruce; widget y mosaico desfasados con «Enviar a»; editar la que suena), 4 menores. Aplicada.

## 2.7, segunda parte (pedidos durante la ronda)
- **Un ecualizador para cada salida** (`audio/PureDeviceEq.kt`, `DeviceEqTest`): altavoz, cable, cada Bluetooth y cada USB por su nombre; al conectar una salida vuelve su curva y la anterior se guarda; aviso «Ecualizador de …» solo si cambia lo que se oye; interruptor en Ajustes → Sonido; «Para: …» en el ecualizador. `SurcoSettings.arriveAt` lee y escribe dentro de `update` (la copia se restaura en otro hilo) y también al restaurar una copia hecha con otros cascos.
- **Efectos de sonido del móvil** (`player/SystemEffects.kt`): se anuncia la sesión de audio (Xiaomi Sound, Dolby, Wavelet la tratan como música; la cola del cruce va en la misma sesión) y Ajustes → Sonido abre su panel si el móvil lo trae. Van después de Surco, se suman a su ecualizador y no suben bits; el códec Bluetooth no lo elige una app.
- **Inicio**: los atajos de etiqueta solo salen si alguna canción tiene ese valor (sin estado de ánimo en ninguna, no sale).
- **Cambio de canción animado**: portada y título entran deslizando desde el lado de la que llega («Sonando» y minirreproductor); con colas de dos se usa el último paso, no la posición.
- **Barra de progreso**: lisa (cada fotograma, solo se repinta la barra y solo si avanza un píxel), se queda en el destino tras soltarla aunque el aparato de «Enviar a» tarde, usa la duración de la biblioteca si el reproductor aún no la sabe, no se arrastra si la canción no deja saltar; MP3 sin tabla, AAC sueltos y AMR ya dejan saltar (`setConstantBitrateSeekingEnabled`). `ProgressTest`.
- **A 8 por fila, el nombre debajo** de cada portada (un disco sin portada no se sabía cuál era).
- Dos revisiones por ataque, aplicadas. CI: la prueba del pellizco pellizca por coordenadas (el nodo quedaba viejo al recomponerse) y el recorrido de diseño abre el menú de canción con la pantalla de ahora (a 1,3 se salía de Biblioteca y lo que fallaba era el recorrido, no la app).
- CI verde en 89c86d8: humo, diseño ×2 e inglés, «OK (6 tests)», 10.004 canciones sin caídas.
- Medido: lectura de etiquetas de 10.004 canciones 355 s (antes 258 s en otra máquina del CI): en el emulador el disco manda (135 ms por canción) y los lectores nuevos no se notan; hay que medirlo en un móvil. Arranque con caché 1,8 s, 148 MB.
- **Medida de consumo en la CI** (`bateria-surco.sh` + `androidTest/BatteryTest.kt`, resultado en `bateria.txt` de `capturas-surco`): procesador del proceso por segundo de reloj, sin la biblioteca de 10.000. En 68f54ae: en pausa 0,2 % de un núcleo; MP3 320 sonando con la pantalla apagada 4,8 %; FLAC 24/96 3,9 %; con ecualizador 5,7 %; con Surco abierto y pantalla encendida 5,5 %; de nuevo en pausa en segundo plano 0,2 % (sin bucles que sigan girando). La primera lectura de una biblioteca grande sí gasta: analizar el BPM de 10.000 canciones tuvo un núcleo al 70 % (una sola vez; se puede apagar en Ajustes → Biblioteca). Emulador x86 y build de depuración: para comparar versiones, no son mAh.

## Calma, letra y todos los botones
- **Calma y orden**: lo elegido con un velo del acento y letra clara (chips, barra inferior); Biblioteca con cuatro pestañas de texto a lo ancho y el cambio carátulas/lista arriba; reproducir sobre las portadas oscuro y translúcido; menús de canción y «Más» en dos columnas iguales; cifras en claro y barras tenues; ayudas de una línea.
- **Letra**: Manrope (SIL OFL, `res/font`, licencia en `assets/licencias`) en cuatro pesos fijos, nada en Black; cifras tabulares (`Tnum`) para tiempos y datos técnicos; iconos redondeados en toda la app.
- **Todos los botones** (`botones-surco.py`, trabajo «medidas», rama `medidas-surco`): 211 botones de 23 pantallas pulsados uno a uno con el APK de release, **0 caídas**. Lo que sale «sin efecto visible» está explicado: chips ya elegidos, el asa de mover de la cola (se arrastra), los mandos de «Sonando» (cambian icono o estado; los comprueba `androidTest/PlayerButtonsTest.kt` en el reproductor) y el panel de efectos (el emulador no tiene). Encontró uno de verdad: «Siguiente» con una sola canción no hacía nada; ahora se ve apagado.
- **Búsqueda desde fuera**: el acceso directo «Buscar» admite `SearchManager.QUERY` y abre los resultados sin teclado.
- **CI**: el humo y lo largo (10.000 canciones, consumo, botones) en paralelo; el classpath de compilación se publica en `classpath-surco` y el Kotlin entero (app y pruebas del emulador, Compose incluido) se compila aquí antes de subir. Inestabilidad del emulador resuelta en las pruebas: el diálogo «Pixel Launcher isn't responding» se quedaba con el foco (no se va con Atrás: se cierra esa app) y se tragaba lo escrito y los toques.
- Consumo (30298aa): en pausa 0,1 %; MP3 4,4 %; FLAC 24/96 3,5 %; con ecualizador 5,4 %; con Surco abierto 10,0 % (de un núcleo).
- Visto una vez y sin repetir: la búsqueda por voz del coche no cambió de canción en 20 s en una pasada (en las demás, bien).

# Surco 2.8 — lo que hacen mejor otros, hecho mejor (feria)

Encargo: «Lleva la app a una feria… analizar diferentes proyectos y presentarlos mejorados» y, tras la presentación, «quiero que apliques las mejoras».

## Análisis (qué tienen los demás que Surco no)
- Plexamp / Spotify: radio de una canción. Surco lo hace sin red ni cuenta, con lo que ya lee y analiza (género, artista, año, tonalidad Camelot, tempo, carpeta).
- Spotify (2014) / Apple: aleatorio que no amontona al mismo artista. Surco lo garantiza: sin dos seguidas siempre que sea posible (probado en 2.000 casos).
- Neutron / foobar2000: ReplayGain medido en el propio aparato. Surco mide EBU R128 (la escala de ReplayGain 2.0) en el análisis de fondo, para las que no traen la etiqueta.
- Poweramp / SoundCloud: barra con la forma de onda. Surco la mide en ~1 s saltando a 64 puntos (no decodifica la canción entera) y la guarda en caché.

## Criterios
- C1: pruebas JVM `RadioTest` (aleatorio sin choques en 2.000 casos y con la primera fijada; radio que se parece, ≤4 por artista, sin repetir; R128 con las señales de EBU Tech 3341 a 44,1/48/96 kHz ±0,1 LU; puertas; forma de onda). Base: no existían.
- C2: en el emulador, `PlayerButtonsTest`: el aleatorio por el servicio real no pone seguidas dos del mismo artista (8 rondas, al activarlo y con lista nueva); «Radio de la canción» desde el menú de «Sonando» deja la cola empezando por ella y sin repetidas. Captura `sonando_onda.png`.
- C3: humo: sonoridad medida de los tonos de ffmpeg dentro de ±0,6 LU de lo calculado a mano (Uno MP3 mono −21,75; Cuatro WAV float estéreo 192 kHz −18,62).
- Caso reservado: Cuatro (WAV float 192 kHz) en la medida de sonoridad: otro decodificador (PCM float) y otra frecuencia.

## Decisiones
- La sonoridad sale de los mismos 30 s centrales que el BPM (lo más fuerte casi siempre: la ganancia peca de prudente). Ganancia = −18 − LUFS. Lo de la etiqueta manda. Por álbum, sin etiqueta de álbum, se usa la de pista (como antes).
- Lo analizado antes de esta versión se vuelve a analizar solo si a la canción le falta ReplayGain (`ld` en analisis.json). La copia de seguridad no lleva la sonoridad: se vuelve a medir.
- Radio: puntuación (género 3, artista 2, año hasta 1,5, tonalidad vecina 1, tempo 1, carpeta 0,5) + azar 0,75; si casi nada se parece, se completa al azar (la radio suena siempre). Si se pide de la que suena, sigue por donde iba.
- Aleatorio: en cada paso un artista distinto del anterior, con peso según lo que le queda, salvo que uno tenga más de la mitad de lo que falta (entonces va él). Canciones sin artista no chocan.
- Forma de onda: 64 barras, dB relativos al tramo más fuerte con 30 dB de recorrido; caché en `cacheDir/ondas` (SHA-1 de ruta|fecha) y en memoria (64). Se puede apagar en Ajustes → Interfaz.
- `compilar.sh`: el R de mentira va primero en el classpath (el jar de clases de la app del CI traía un R viejo y escondía los textos nuevos).

## Hecho
- JVM: 183/183 (RadioTest nuevo). Compilación local de app y pruebas del emulador sin errores ni avisos.
- Revisión por ataque (1 bloqueante, 3 importantes, 5 menores), aplicada: la prueba del aleatorio miraba el orden provisional del mando (sin mezclar) → espera al del servicio; «por álbum» igualaba cada pista → ganancia de álbum medida por energía de sus pistas (`withAlbumGains`, `albumReplayGain`); aleatorio O(canciones × artistas) en el hilo principal (12 s interpretado con 10.000) → Fenwick + montículo, 20.000 en < 600 ms; mono medido 3 dB bajo → cuenta como dos canales (dual mono); radio en el hilo principal → `Dispatchers.Default` y regex compiladas una vez; forma de onda: primer salto antes de `start()` (OMX), fallos recordados, cancelable y una a la vez; 5.1 sin LFE y con 1,41 detrás; re-análisis solo de sonoridad si ya hay BPM y tonalidad, y la copia de seguridad lleva la sonoridad; el registro con títulos solo con `setprop log.tag.SurcoAnalisis DEBUG`.
- CI de 57d2171: compila; humo rojo en la medida de sonoridad de «Prueba Cuatro» (WAV float estéreo 192 kHz): −21,63 LUFS frente a −18,62, justo 3 dB = un canal. El registro ahora dice frecuencia, canales y formato que da el decodificador, y el humo exige «2 canales».
- CI de 3a4e955: la sonoridad de Cuatro sigue en −21,63 con «192000 Hz · 2 canales» (el decodificador del sistema da el WAV float en 16 bits). No es un canal perdido: ffmpeg pasa el mono a estéreo con −3 dB en cada canal (`center_mix_level`), así que la energía total es la del mono. Lo esperado era lo que estaba mal: −21,63. Uno −19,01 (esperado −18,74), Dos −18,81, Tres −18,70.
- CI de f1f9fbf: humo con la sonoridad bien (Uno, Dos, Tres, Cuatro), «OK (9 tests)» con «aleatorio repartido: 8 rondas sin dos del mismo artista seguidos» y «radio de «Prueba Uno»: 4 canciones, empieza por ella y sin repetir», captura `sonando_onda.png`, inglés con 0 textos en español, diseño ×1,0 0 fallos. Rojo solo por el diseño ×1,3 de `25-ecualizador-abajo` (dos toques de 37 dp en la cabecera, que no se ha tocado y pasó en 30298aa): se relanza una vez.
- Medidas (f1f9fbf): 209 botones, 0 caídas («Radio de la canción» cambia la cola). Consumo más alto que en 30298aa (MP3 6,4 %, FLAC 4,9 %, ecualizador 7,3 %, abierta 17,3 %), pero esa máquina era más lenta en todo (MediaStore 54 s frente a 36, arranque en frío 2,6 s frente a 2,0, etiquetas 419 s frente a 355). Aun así, la onda se pinta ahora como un solo trazado en caché (dos pinturas por avance en vez de 64 rectángulos).
- **CI verde completa en 8cfca7c** (run 40811651415): build, humo (sonoridad de las cuatro), diseño ×1,0 y ×1,3 con 0 fallos (lo del ecualizador fue de una pasada), inglés 0, «OK (9 tests)» con aleatorio repartido y radio, y medidas: 212 botones, 0 caídas; consumo en pausa 0,1 %, MP3 4,4 %, FLAC 3,5 %, con ecualizador 5,4 %, con Surco abierto 8,9 % (antes 10,0); 10.004 canciones sin caídas ni ANR, arranque con caché 1,7 s. APK 2.8 = `Surco.apk` de `apk-surco` (sha256 22c1c705…f026).
- Pendiente: memoria con 10.004 canciones 204 MB (en 89c86d8 eran 148): sin aislar si es de la 2.8 o de lo de después; candidato barato, rehacer las ganancias de álbum solo al empezar y al acabar el análisis (no cada 10 canciones). Los «sin efecto» nuevos del recorrido son «Reproducir»/«Aleatorio» de listas que ya sonaban (la pantalla no cambia); sin comprobar uno a uno.

# Surco 2.8.1 — lo pendiente de la 2.8

Encargo: «Cumple lo pendiente».

## Criterios
- C1: memoria con 10.004 canciones medida contra la 2.7 (APK de depuración de 30298aa, rama `referencia-surco`) en el mismo emulador y la misma pasada (`referencia-surco.sh` → `rendimiento-referencia.txt`), con desglose (Java, nativa, código, gráficos) y otra vez tras pedir que suelte memoria. Base: 204 MB (2.8) frente a 148 MB de 89c86d8 medidos en pasadas y máquinas distintas.
- C2: el recorrido de botones mira también la sesión multimedia (canción, estado y su última actualización): un «Reproducir» o «Aleatorio» que vuelve a poner lo que ya sonaba sale como «suena (…)» y no como «sin efecto». Base: 17 sin efecto.
- C3: CI verde entero.
- Caso reservado: la medida de referencia con el APK viejo (otra firma: se desinstala y se vuelve a leer todo).

## Hecho
- Ganancias de álbum solo al empezar y al acabar el análisis; con más de 500 canciones por analizar, la biblioteca se rehace cada 100 (no cada 10). Compilado sin avisos, JVM 185/185.
- **CI verde completa en 7424959** (run 40827441880).
- **Memoria, misma pasada y mismo emulador**: 2.8.1 146 MB de PSS (Java 27,8 · nativa 23,7 · código 5,5 · otros 74,7 MB) frente a 142 MB de la 2.7 (30298aa): +3 %. Los 204 MB de la 2.8 no se repiten (otra máquina y la biblioteca rehecha cada 10 canciones durante el análisis). Pedirle que suelte memoria no la baja (152/148): no es basura, es lo que usa.
- Tiempos de la referencia no comparables: va segunda, con los 10.000 archivos ya en la caché del disco (etiquetas 210 s frente a 358 s; arranque con caché al revés, 3,4 s frente a 1,3 s).
- **Botones**: 206 pulsados, 0 caídas, 7 sin efecto (antes 17), todos chips u opciones ya elegidas, el orden ya puesto o el panel de efectos que el emulador no tiene. Los «Reproducir» y «Aleatorio» salen ahora como «suena (vuelve a empezar / estado 2 → 3 / la reproducción cambió)». «Mover» de la cola sale con «estado 2 → 3»: es el arranque de lo pulsado justo antes, que llega tarde (el asa no reproduce).
- Consumo: pausa 0,1 %, MP3 4,7 %, FLAC 3,5–3,7 %, ecualizador 5,4 %, abierta 11,9 % (en la línea de las pasadas anteriores).
- Pendiente fuera de mi alcance: probarlo en un móvil real (no hay aparato conectado a esta sesión).

# Surco 2.8.2 — sonido por el altavoz con cascos USB-C

Encargo: «Pues pasa, hay errores por ahí»: con cascos USB-C conectados se oye el altavoz del móvil, a ratos y al cambiar de canción.

## Diagnóstico (sin cascos USB-C aquí: el emulador no tiene)
- Surco nunca ataba la música a una salida: se fiaba de la elección de Android en cada AudioTrack. Con DAC USB-C hay tres momentos en que puede no ser los cascos: cada cambio de mezclador del DAC (bit-perfect, al cambiar de canción con otra frecuencia), el AudioTrack nuevo de una canción con otro formato y el segundo reproductor del cruce, que abre su propia salida. Con algunos móviles (Xiaomi) un USB-C pasa además por «altavoz» un instante al reconfigurarse.
- Media3 pausa con el aviso de Android de «ruido» al quitar los cascos, pero no si Android no lo manda (USB-C, según el fabricante).

## Arreglo
- `OutputRoute.pinned`: con cascos conectados (USB, cable o Bluetooth), la música se ata a ellos con `ExoPlayer.setPreferredAudioDevice`, también el reproductor del cruce. Si Android dice «altavoz» con unos cascos conectados, se atan los cascos de más rango (`headsetToPin`, JVM). La salida que se enseña, su ecualizador y el mezclador del DAC siguen a los cascos atados.
- Si se van los últimos cascos (`onAudioDevicesRemoved`), Surco pausa en ese momento, además del aviso de Android.
- JVM 187/187 (`MixerTest.conCascosLaMusicaVaAtadaAEllos`, `sinCascosDecideElSistema`); compilación sin avisos.
- CI de 2ccd697: build verde; humo rojo solo en `AutoTest.caminoDeAndroidAuto`: «pon prueba dos de otra banda» no cambió de canción en 20 s (segunda vez que se ve; la primera en la 2.7, código del coche sin tocar). La búsqueda pura devuelve solo «Prueba Dos» (palabras vacías fuera); no se sabe aún si falla la petición o la sesión no la aplica: el servicio registra «Surco voz: N canciones» y los fallos del coche, y `auto-surco.sh` los enseña al fallar sin que los avisos de portadas los tapen.
- **CI verde completa en 6658daf** (run 40948728671): build, humo (la voz del coche pasó), diseño ×2, inglés, «OK (9 tests)», medidas: 207 botones, 0 caídas, 7 sin efecto (los mismos chips de siempre); 10.004 canciones sin caídas, 148 MB.
- Consumo más alto en esta pasada (MP3 6,8 %, FLAC 6,5 %, ecualizador 10,0 %, abierta 20,4 %), pero la máquina era más lenta en todo (MediaStore 40 s frente a 25; etiquetas 461 s frente a 358) y el cambio de la 2.8.2 no toca el camino del sonido (con el emulador sin cascos solo llama una vez a `setPreferredAudioDevice(null)`, sin coste por bloque de audio). Para salir de dudas haría falta medir el consumo de la referencia en la misma pasada (la de rendimiento ya lo hace con la memoria); la medida de referencia de esta pasada no llegó al final (aviso, no tumba el CI).
- APK 2.8.2 = `Surco.apk` de `apk-surco` (6658daf2). Falta lo principal: que Eddie lo pruebe con sus cascos USB-C.

# Surco 2.8.3 — firma fija

Encargo: «¿Con la misma firma?» → secreto de GitHub (elegido por Eddie frente a guardar la clave en el repositorio, que es público).
- Clave EC P-256 en PKCS12, generada aquí (no está en el repositorio). Certificado SHA-256 en `surco/firma.sha256` (637a687e…898d), que es público y sirve para comprobar.
- CI: el secreto `SURCO_FIRMA` (el .p12 en base64) se escribe en `$RUNNER_TEMP` y Gradle firma con él release, debug y la prueba (en el CI se instalan unos encima de otros). Sin el secreto, la de depuración del ejecutor y un aviso. Con el secreto, `apksigner` comprueba los tres APK contra `firma.sha256` y tumba el CI si no casan.
- Hace falta desinstalar una última vez (de la clave de depuración a la fija); a partir de ahí, cada versión se instala encima.
- Secreto `SURCO_FIRMA` puesto por Eddie en GitHub (29-10, 23:06). Primera compilación con la clave fija: esta.
