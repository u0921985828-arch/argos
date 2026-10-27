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
