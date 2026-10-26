# Surco — reproductor de música Android que organiza por etiquetas

App nativa Android (Kotlin + Jetpack Compose + Media3). Lee todas las etiquetas de los archivos de audio del móvil y permite navegar la biblioteca por cualquiera de ellas.

## Estado
- **Surco 2.3**: Android Auto (`player/CarLibrary.kt`, `data/PureBrowse.kt`, `PlaybackService` como `MediaLibraryService`): cuatro pestañas (Para ti, Listas, Álbumes, Artistas), tocar una canción suena su carpeta desde ella, búsqueda en pantalla y por voz, portadas por `player/ArtProvider.kt`; probado en el emulador con `androidTest/AutoTest.kt` por los dos caminos (navegador de Media3 y MediaBrowser del sistema, el de Android Auto).
- **Surco 2.2**: interfaz limpia (en «Sonando» solo lo de cada día) y todo lo demás en Ajustes por secciones; acento de color (fijo o «Del disco»), fundido entre canciones que respeta los discos, índice de letras, carpetas excluidas, Inicio a medida y «Tus escuchas». Antes, 2.1: salida sin recortar a 16 bits, auditoría independiente aplicada (`AUDITORIA.md`) y app en pestañas. Pruebas JVM (motor, PCM, letras, ReplayGain/R128/Opus, cola, búsqueda) + debug/release + prueba de humo en emulador con el APK de release.
- Se compila en GitHub Actions (`.github/workflows/build-surco.yml` del repo `argos`, donde vive en `surco/`): el contenedor de desarrollo no llega a Google Maven ni al Android SDK. Cada push que toque `surco/` deja los APK en el artifact `surco-apk` y en la rama `apk-surco` (un solo commit, se reescribe).
- Las partes puras se prueban también sin Android con `kotlinc` + JUnit (ver ESTADO.md).
- **Sin errores**: Kotlin con `allWarningsAsErrors`; Android Lint con 0 errores y 0 avisos (`.github/scripts/lint-surco.py` tumba el CI); versiones fijadas como informativas en lint, con su motivo.
- **Diseño medido**: tras la prueba de humo, `.github/scripts/diseno-surco.sh` recorre 29 pantallas con la letra normal y ×1,3, deja las capturas en la rama `capturas-surco` y `diseno-surco.py --estricto` tumba el CI con un toque < 40 dp, contraste < 4,5:1 (3:1 en grande), un solape real o algo fuera de pantalla.
- El release va firmado con la clave de depuración del ejecutor, que cambia en cada compilación: para instalar uno nuevo encima hay que desinstalar el anterior. Una clave propia lo arregla.

## Sonido (lo importante)
- **Dos caminos** (`audio/SurcoAudioSink.kt`): Media3 recorta a 16 bits todo lo de más resolución antes de los procesadores y, con salida float, se salta los procesadores (también la 1.11.1). Por eso:
  - FLAC/ALAC se piden en coma flotante al decodificador (`SurcoAudioRenderer`) y, como los WAV de 24/32 bits o float, `SurcoAudioSink` los convierte a float sin pérdida, los procesa con `DspEngine` y los entrega en float a AudioTrack;
  - MP3/AAC/Opus/Vorbis se piden en 16 bits: el recorte de huecos del codificador («sin cortes») solo existe en el camino de 16 bits. Ahí actúa `SurcoAudioProcessor` (bit a bit en neutro; con efectos, float interno y dither TPDF).
  - Velocidad y tono por AudioTrack (valen en ambos caminos); saltar silencios solo en 16 bits.
- Motor `audio/Dsp.kt`: estructura fija de filtros por hueco y **fundido cruzado de ~20 ms** al cambiar parámetros (sin eso chasqueaba ×828). **Preamplificación automática** (por defecto) que deja la curva ≤ 0 dB; el **limitador solo actúa si la curva pasa de 0 dB** (mono y balance nunca lo activan) y mantiene 60 ms (sin «bombear» en graves). Sin anticipación a propósito: un retardo solo cuando limita cambiaría la latencia y el fundido entre configuraciones chasquearía (medido ×8,7).
- ReplayGain por `player.volume` al cambiar de pista (volumen = ReplayGain × fundido de pausa × temporizador × fundido entre canciones); con RG todo baja 6 dB (`RG_HEADROOM_DB`). Opus: R128 → escala RG (+5 dB).
- La línea «Salida» de «Sonando» dice lo que hay: profundidad del archivo, si va en float y qué cambia la señal. El mezclador de Android remuestrea después (no es de Surco); «bit-perfect» por USB pendiente.

## Compilar
Requisitos: JDK 17 o superior y Android SDK (platform 35, build-tools 35). Crear `local.properties` con `sdk.dir=<ruta al SDK>` si no existe (en Windows: `sdk.dir=C\:\\Users\\<usuario>\\AppData\\Local\\Android\\Sdk`).

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # minificado, firmado con la clave debug: app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Si el release falla en R8 por clases que faltan de jaudiotagger (java.awt, javax.imageio…), añadir `-dontwarn` en `app/proguard-rules.pro`.

## Versiones
AGP 8.7.3 · Kotlin 2.0.21 · Gradle 8.14.3 · Compose BOM 2024.12.01 · Media3 1.5.1 · jaudiotagger 3.0.1 · palette 1.0.0 · minSdk 26 · targetSdk 35.

## Estructura (`app/src/main/java/com/eddie/surco/`)
- `MainActivity.kt` — permisos (con salida a Ajustes y recomprobación al volver), barra inferior, minirreproductor, «Sonando», hojas y diálogos.
- `LibraryViewModel.kt` — pestañas (`Tab`: Inicio · Biblioteca · Etiquetas · Listas) con su pila de pantallas; escaneo (4 lecturas a la vez, interfaz 1 vez/s, caché cada 20 s, una vez por proceso) y `fresh` (la cola solo se restaura tras consultar MediaStore).
- `audio/` — `Dsp.kt` (motor), `PurePcm.kt` (conversión exacta a float), `SurcoAudioSink.kt` (sink + renderizador sin pérdida, `AudioOutput`), `SurcoAudioProcessor.kt` (camino de 16 bits), `SurcoSettings.kt` (ajustes).
- `data/` — `LibraryScanner.kt` (MediaStore + jaudiotagger + Opus propio, `cleanText` quita nulos de WAV), `Disk.kt` (AtomicFile, un hilo de escritura, JSON roto apartado), `UserStore.kt` (favoritas, listas, escuchas, cola con aleatorio/repetición, `reconcile` por claves estables), `PureQueue.kt` (reanudar, claves, órdenes aleatorios), `PureText.kt` (búsqueda sin tildes), `PureOpus.kt`, `PureReplayGain.kt`, `PurePlayer.kt` (fundido entre canciones, índice de letras, acentos legibles, tus escuchas), `Lyrics.kt`, `TagField.kt`, `Track.kt`.
- `player/PlaybackService.kt` — ExoPlayer con un solo renderizador de audio; salta archivos rotos con aviso; ReplayGain; fundido solo al reanudar; aleatorio que empieza por la actual y «a continuación» que respeta su nombre; temporizador; escuchas; reanudar (fuera del hilo principal).
- `player/PlayerConnection.kt` — controlador, cola en orden de reproducción, «Seguir escuchando».
- `ui/` — `SettingsScreens.kt` (Ajustes por secciones: Sonido, Reproducción, Biblioteca, Interfaz, Tus escuchas, Acerca de), `Home.kt` (Inicio, Biblioteca con índice de letras `WithLetters`, Buscar, barra inferior), `Screens.kt` (Etiquetas, grupos, listas de canciones, `rememberOffMain`), `PlayerUi.kt` («Sonando», letra, cola, temporizador, velocidad, menú de canción, línea de salida), `EqualizerScreen.kt`, `LibraryScreens.kt` (listas), `Controls.kt` (deslizadores accesibles, `SurcoSlider`: pulgar redondo y pista continua; el de Material 1.3 se veía en tres piezas), `Theme.kt` (portadas por bytes y tamaños; `chipColors`/`ChipHeight` para todos los chips; color de contenido `Paper`: fuera del Scaffold Compose pinta en negro).

## Pendiente
- «Bit-perfect» por USB (Android 14+), cruce con solape entre canciones, edición de etiquetas; Android Auto sin probar en un coche ni en el Desktop Head Unit.
- Probado en JVM y en emulador; falta un móvil real (sonido real, Bluetooth con la app cerrada).

## Idioma
La interfaz y los comentarios están en español.
