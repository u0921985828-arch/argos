# Surco — reproductor de música Android que organiza por etiquetas

App nativa Android (Kotlin + Jetpack Compose + Media3). Lee todas las etiquetas de los archivos de audio del móvil y permite navegar la biblioteca por cualquiera de ellas.

## Estado
- **Surco 2** (reproductor completo). CI en verde: 25 pruebas JVM (motor, letras, ReplayGain, cola), debug + release, y prueba de humo en emulador con el APK de release: biblioteca, etiquetas profundas, favorita, «Sonando» con la calidad del archivo, cola, ecualizador con preajuste mientras suena, persistencia tras cerrar la app y un FLAC de 96 kHz/24 bits mono con EQ.
- Se compila en GitHub Actions (`.github/workflows/build-surco.yml` del repo `argos`, donde vive en `surco/`): el contenedor de desarrollo no llega a Google Maven ni al Android SDK. Cada push que toque `surco/` deja los APK en el artifact `surco-apk` y en la rama `apk-surco` (un solo commit, se reescribe).
- Las partes puras (motor, letras, ReplayGain, cola) se prueban también sin Android con `kotlinc` + JUnit (ver ESTADO.md).
- El release va firmado con la clave de depuración del ejecutor, que cambia en cada compilación: para instalar uno nuevo encima hay que desinstalar el anterior. Una clave propia lo arregla.

## Sonido (lo importante)
- Motor propio (`audio/Dsp.kt`) en un `AudioProcessor` de Media3, no `android.media.audiofx`: mismas 10 bandas en todos los móviles, curva dibujable y probado en JVM. Recibe PCM 16 bits: **no activar la salida float**, con ella Media3 se salta los procesadores.
- Estructura fija de filtros por hueco y **fundido cruzado de ~20 ms** al cambiar parámetros: sin eso, mover una banda por 0 dB o cambiar de preajuste chasqueaba (×828 la segunda diferencia; prueba `moverUnaBandaPorCeroNoChasquea`).
- Con todo en neutro, bytes intactos (bit a bit).
- ReplayGain va por `player.volume` al cambiar de pista (sin el desfase del búfer). Con RG activo todo baja 6 dB (`RG_HEADROOM_DB`) para poder subir las flojas.

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
- `MainActivity.kt` — permisos, navegación por pila, minirreproductor, «Sonando», hojas y diálogos.
- `LibraryViewModel.kt` — estado, pantallas (`Home / Groups / Songs / Smart / PlaylistView / Playlists / Settings / Equalizer`) y escaneo con caché `filesDir/library2.json`.
- `audio/Dsp.kt` — ecualizador 10 bandas (RBJ), preamplificación, graves/agudos (estantes), crossfeed bs2b, mono, balance, limitador, curva de respuesta, preajustes. Kotlin puro.
- `audio/SurcoAudioProcessor.kt` — el motor dentro de Media3 (16 bits ↔ float con dither TPDF).
- `audio/SurcoSettings.kt` — ajustes (`ajustes.json`): sonido, ReplayGain, velocidad/tono, saltar silencios, fundidos, duración mínima.
- `data/UserStore.kt` — favoritas, listas, escuchas, última escucha y cola (`usuario.json`); listas automáticas (`SmartList`).
- `data/Lyrics.kt` — letras LRC sincronizadas (varias marcas, offset) o planas. `PureReplayGain.kt` — RG de ID3/Vorbis/MP4. `PureQueue.kt` — reanudar la cola aunque falten canciones.
- `data/LibraryScanner.kt`, `TagField.kt`, `Track.kt` — como en la 1.0, más RG y letras.
- `player/PlaybackService.kt` — ExoPlayer con el procesador, ReplayGain, fundido al pausar (ForwardingPlayer), temporizador, escuchas (mitad o 4 min), guardar y reanudar cola (`onPlaybackResumption` + `MediaButtonReceiver`).
- `player/PlayerConnection.kt` — controlador y cola en orden de reproducción (también en aleatorio); a continuación, a la cola, mover, quitar.
- `ui/` — `EqualizerScreen` (curva, 10 deslizadores verticales, preajustes propios), `PlayerUi` («Sonando» con color de la portada, letra, cola arrastrable, temporizador, velocidad, menú de canción), `Screens` (inicio, grupos, listas de canciones), `LibraryScreens` (listas y ajustes), `Controls`, `Theme`.

## Pendiente
- Fundido cruzado entre canciones (necesita dos reproductores), Android Auto, edición de etiquetas, salida float/hi-res con EQ (habría que procesar en float).
- Probado en emulador; falta un móvil real (sonido real del EQ, Bluetooth con la app cerrada).

## Idioma
La interfaz y los comentarios están en español.
